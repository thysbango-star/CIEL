package dev.ciel.app.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * On-device screen understanding for apps without accessible elements (games):
 *  - [readText]: ML Kit text recognition, fully offline (the bundled model ships in the APK);
 *  - [averageHash]: a 64-bit fingerprint to tell screens apart and detect change;
 *  - [jpegBase64]: the downscaled image for the vision model — only when the user allows it.
 */
object ScreenVision {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Lines of text with their bounds in the bitmap's pixel space. */
    suspend fun readText(bitmap: Bitmap): List<Pair<String, Rect>> = suspendCancellableCoroutine { cont ->
        runCatching {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line -> line.boundingBox?.let { box -> line.text.trim().takeIf { it.isNotEmpty() }?.let { it to box } } }
                    }
                    if (cont.isActive) cont.resume(lines.take(150))
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        }.onFailure { if (cont.isActive) cont.resume(emptyList()) }
    }

    /** 8×8 average hash: bit set where a cell is brighter than the mean. */
    fun averageHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val lum = IntArray(64) { i ->
            val c = small.getPixel(i % 8, i / 8)
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        val mean = lum.average()
        var h = 0L
        for (i in 0 until 64) if (lum[i] > mean) h = h or (1L shl i)
        return h
    }

    fun jpegBase64(bitmap: Bitmap, maxSide: Int = 1024, quality: Int = 70): String {
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
