package com.shiko.pokedex.camera

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs Google ML Kit's on-device text recognizer over a cropped card image.
 * Fully local, free, no API key, no account — the model ships with Play Services
 * and downloads once on first use.
 */
object TextExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Collector number / set symbol text lives in this bottom slice on virtually
    // every Pokémon card layout, but it's small — running the whole-card OCR pass
    // alone often misses it. A dedicated, upscaled pass on just this strip reads
    // small text far more reliably.
    private const val BOTTOM_STRIP_FRACTION = 0.15f
    private const val STRIP_UPSCALE = 2

    /** Runs OCR on the full card, plus a second focused/upscaled pass on the bottom strip for the collector number. */
    suspend fun extractLines(bitmap: Bitmap): List<String> {
        val mainLines = runOcr(bitmap)
        val strip = cropAndUpscaleBottomStrip(bitmap)
        val stripLines = runOcr(strip)
        return mainLines + stripLines
    }

    private fun cropAndUpscaleBottomStrip(bitmap: Bitmap): Bitmap {
        val y = (bitmap.height * (1f - BOTTOM_STRIP_FRACTION)).toInt().coerceIn(0, bitmap.height - 1)
        val stripHeight = (bitmap.height - y).coerceAtLeast(1)
        val strip = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, stripHeight)
        return Bitmap.createScaledBitmap(strip, strip.width * STRIP_UPSCALE, strip.height * STRIP_UPSCALE, true)
    }

    private suspend fun runOcr(bitmap: Bitmap): List<String> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val lines = visionText.textBlocks.flatMap { block ->
                        block.lines.map { it.text.trim() }
                    }.filter { it.isNotBlank() }
                    cont.resume(lines)
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
