package expo.modules.backgroundremover

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface // Added for getRotation
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.FileInputStream
import java.util.UUID
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log

class BackgroundRemoverProcessor(private val context: Context) {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
    )

    @Volatile
    private var isWarmedUp = false

    suspend fun processImage(uriString: String): String = withContext(Dispatchers.IO) {
      var bitmap: Bitmap? = null
    //   val uri = Uri.parse(uriString)
      try{
        // bitmap = loadAndResizeBitmap(uriString, 2048)
        bitmap = ImageUtils.loadBitmap(context, uriString, 512, 512)?: throw Exception("Failed to load bitmap")
        
        val inputImage = InputImage.fromBitmap(bitmap, 0) 

        val result = withTimeout(30000) {
            segmenter.process(inputImage).await()
        }
        
        val maskBuffer = result.foregroundConfidenceMask 
            ?: throw Exception("Could not detect subjects")

        // The mask is guaranteed to match the input bitmap size.
        val maskWidth = bitmap.width
        val maskHeight = bitmap.height

        val maskBitmap = createMaskFromBuffer(maskBuffer, maskWidth, maskHeight)
        val outputBitmap = applyMaskToBitmap(bitmap, maskBitmap)

        // mask no longer needed
        maskBitmap.recycle()

        val resultPath = saveResult(outputBitmap)

        // cleanup
        outputBitmap.recycle() 
        return@withContext resultPath
      }finally {
          // ALWAYS recycle original bitmap
          bitmap?.recycle()
      }

    } 
   

   @Synchronized  
   fun warmUp() {
    if (isWarmedUp) return

    CoroutineScope(Dispatchers.IO).launch {
        try {
            Log.d("BG_REMOVER", "Warmup started")

            val dummyBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val inputImage = InputImage.fromBitmap(dummyBitmap, 0)

            withTimeout(20000) {
                segmenter.process(inputImage).await()
            }

            dummyBitmap.recycle()
            isWarmedUp = true

            Log.d("BG_REMOVER", "Warmup completed")

        } catch (e: TimeoutCancellationException) {
            Log.e("BG_REMOVER", "Warmup timeout", e)
        } catch (e: Exception) {
            Log.e("BG_REMOVER", "Warmup failed", e)
        }
      }
    }

    // Helper to handle image rotation logic
   private fun getRotation(uriString: String): Int {
    return try {
        // Use the helper and the .use block for automatic closing
        val exifInterface = openStream(uriString)?.use { ExifInterface(it) }

        when (exifInterface?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        e.printStackTrace() // Logs the specific exception (e.g., FileNotFound, SecurityException)
        0
    }
}

    private fun loadAndResizeBitmap(uriString: String, maxDimension: Int): Bitmap {
        val uri = Uri.parse(uriString)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true } 

        // Using the helper with the .use extension
        openStream(uriString)?.use { stream ->
          BitmapFactory.decodeStream(stream, null, options)
        } ?: throw Exception("Could not open stream for image")
        
        var sampleSize = 1
        while ((options.outWidth / (sampleSize * 2)) >= maxDimension && 
               (options.outHeight / (sampleSize * 2)) >= maxDimension) {
            sampleSize *= 2
        }
        
        val loadOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val subsampledBitmap = openStream(uriString)?.use { stream ->
           BitmapFactory.decodeStream(stream, null, loadOptions)
        } ?: throw Exception("Failed to decode subsampled image")

        val scale = minOf(maxDimension.toFloat() / subsampledBitmap.width, maxDimension.toFloat() / subsampledBitmap.height)
        if (scale >= 1f) return subsampledBitmap

        val scaledBitmap = Bitmap.createScaledBitmap(
        subsampledBitmap,
        (subsampledBitmap.width * scale).toInt(),
        (subsampledBitmap.height * scale).toInt(),
        true
        )

        subsampledBitmap.recycle()  

        return scaledBitmap
    }

    private fun createMaskFromBuffer(buffer: java.nio.FloatBuffer, width: Int, height: Int): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
            // Mask transparency based on confidence (0.0 to 1.0)
            val alpha = (buffer.get().coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = Color.argb(alpha, 0, 0, 0)
        }
        mask.setPixels(pixels, 0, width, 0, 0, width, height)
        return mask
    }

    private fun applyMaskToBitmap(source: Bitmap, mask: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        canvas.drawBitmap(source, 0f, 0f, null)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)
        
        return result
    }

    private fun saveResult(bitmap: Bitmap): String {
        val file = File(context.cacheDir, "${UUID.randomUUID()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file).toString()
    }

    private fun openStream(uriString: String): java.io.InputStream? {
    val uri = Uri.parse(uriString)
    return when (uri.scheme) {
        "content" -> {
            // Asks the Android System to resolve the virtual URI
            context.contentResolver.openInputStream(uri)
        }
        "file" -> {
            // Opens a direct pipe to the file on the storage
            uri.path?.let { java.io.FileInputStream(it) }
        }
        else -> {
            // Handles raw paths that might not have a scheme prefix
            java.io.FileInputStream(uriString)
        }
     }
    }
    
    fun close() {
        segmenter.close()
    }
}