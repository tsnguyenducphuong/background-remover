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
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

class BackgroundRemoverProcessor(private val context: Context) {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
    )

    suspend fun processImage(uriString: String): String = withContext(Dispatchers.IO) {
        val bitmap = loadAndResizeBitmap(uriString, 2048)
        
        // FIX 1: Defined getRotation helper
        val inputImage = InputImage.fromBitmap(bitmap, getRotation(uriString))

        val result = segmenter.process(inputImage).await()
        
        val maskBuffer = result.foregroundConfidenceMask 
            ?: throw Exception("Could not detect subjects")

        // FIX 2: Use bitmap dimensions (result does not have width/height)
        val totalPixels = maskBuffer.remaining()
        val maskWidth = bitmap.width
        val maskHeight = totalPixels / maskWidth

        val maskBitmap = createMaskFromBuffer(maskBuffer, maskWidth, maskHeight)
        val outputBitmap = applyMaskToBitmap(bitmap, maskBitmap)

        saveResult(outputBitmap)
    }

    // Helper to handle image rotation logic
    private fun getRotation(uriString: String): Int {
        return try {
            val inputStream = context.contentResolver.openInputStream(Uri.parse(uriString))
            val exifInterface = inputStream?.use { ExifInterface(it) }
            when (exifInterface?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun loadAndResizeBitmap(uriString: String, maxDimension: Int): Bitmap {
        val uri = Uri.parse(uriString)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        
        context.contentResolver.openInputStream(uri)?.use { 
            BitmapFactory.decodeStream(it, null, options) 
        } ?: throw Exception("Failed to open input stream")
        
        var sampleSize = 1
        while ((options.outWidth / (sampleSize * 2)) >= maxDimension && 
               (options.outHeight / (sampleSize * 2)) >= maxDimension) {
            sampleSize *= 2
        }
        
        val loadOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val subsampledBitmap = context.contentResolver.openInputStream(uri)?.use { 
            BitmapFactory.decodeStream(it, null, loadOptions) 
        } ?: throw Exception("Failed to decode image")

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
    
    fun close() {
        segmenter.close()
    }
}