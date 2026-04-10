package expo.modules.backgroundremover

import android.content.Context
import android.graphics.*
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

class BackgroundRemoverProcessor(private val context: Context) {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask() // High quality edges
            .build()
    )

    suspend fun processImage(uriString: String): String {
        // 1. Efficiently load and downscale to max 2048px
        val bitmap = loadAndResizeBitmap(uriString, 2048)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // 2. Perform Segmentation
        val result = segmenter.process(inputImage).await()
        
        // 3. Retrieve global mask (Includes People + Objects automatically)
        val maskBuffer = result.foregroundConfidenceMask 
            ?: throw Exception("Could not detect subjects")
            
        // 4. Create Mask Bitmap and Blend
        val maskBitmap = createMaskFromBuffer(maskBuffer, bitmap.width, bitmap.height)
        val outputBitmap = applyMaskToBitmap(bitmap, maskBitmap)

        return saveResult(outputBitmap)
    }

    private fun loadAndResizeBitmap(uriString: String, maxDimension: Int): Bitmap {
        val uri = Uri.parse(uriString)
        
        // Stage A: Get dimensions only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
          BitmapFactory.decodeStream(stream, null, loadOptions)
         }?: throw Exception("Failed to open input stream")
        
        // Stage B: Calculate optimal inSampleSize (power of 2)
        var sampleSize = 1
        while ((options.outWidth / (sampleSize * 2)) >= maxDimension && 
               (options.outHeight / (sampleSize * 2)) >= maxDimension) {
            sampleSize *= 2
        }
        
        // Stage C: Load the subsampled bitmap
        val loadOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val subsampledBitmap = context.contentResolver.openInputStream(uri).use { 
            BitmapFactory.decodeStream(it, null, loadOptions) 
        } ?: throw Exception("Failed to decode image")

        // Stage D: Precise scaling to exactly fit within 2048px while preserving aspect ratio
        val scale = minOf(maxDimension.toFloat() / subsampledBitmap.width, maxDimension.toFloat() / subsampledBitmap.height)
        if (scale >= 1f) return subsampledBitmap

        val targetWidth = (subsampledBitmap.width * scale).toInt()
        val targetHeight = (subsampledBitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(subsampledBitmap, targetWidth, targetHeight, true)
    }

    private fun createMaskFromBuffer(buffer: java.nio.FloatBuffer, width: Int, height: Int): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val alpha = (buffer.get() * 255).toInt()
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
}
