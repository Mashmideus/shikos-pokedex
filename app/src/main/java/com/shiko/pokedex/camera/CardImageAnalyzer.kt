package com.shiko.pokedex.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.shiko.pokedex.util.PHash
import java.io.ByteArrayOutputStream

/**
 * Runs on every CameraX frame. Cheap steps (YUV->Bitmap, card-rectangle detection)
 * happen on every frame. Two separate things come out of that, on separate
 * cadences:
 *  - [onCardTracked] fires whenever a confident card-shaped region is found, for
 *    drawing a tracking box that follows the card as it moves. It does NOT wait
 *    for stability — it's just "here's roughly where the card is right now".
 *  - [onStableCard] (the expensive path: OCR + lookups) only fires once the crop
 *    has been stable for a few frames AND looks like a different card than the
 *    one already identified.
 *  - [onCardLost] fires once a confident region hasn't been seen for a while,
 *    signalling the UI to clear whatever it's showing.
 */
class CardImageAnalyzer(
    private val onCardTracked: (RectF, Int, Int, Int) -> Unit,
    private val onCardLost: () -> Unit,
    private val onStableCard: (Bitmap) -> Unit
) : androidx.camera.core.ImageAnalysis.Analyzer {

    private var lastHash: Long? = null
    private var lastAttemptAtMillis: Long = 0
    private var stableFrameCount = 0
    private var missedQuadFrames = 0
    private var reportedLost = true

    companion object {
        private const val STABLE_FRAMES_REQUIRED = 3 // faster trigger; still ignores single-frame motion blur
        private const val LOST_AFTER_MISSED_FRAMES = 20 // ~1s of no confident detection before clearing
        // Keep retrying while unlocked so a card that didn't read cleanly the first
        // time gets more chances — the ViewModel ignores these once it has locked in
        // a confirmed match, so this can't cause the display to flicker.
        private const val ATTEMPT_THROTTLE_MILLIS = 700L
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap() ?: return
            val rotation = image.imageInfo.rotationDegrees
            val detection = CardDetector.findAndCropCard(bitmap)
            val cropped = detection.cropped

            if (detection.quadFound) {
                missedQuadFrames = 0
                reportedLost = false
                onCardTracked(detection.boundingRect, bitmap.width, bitmap.height, rotation)
            } else {
                missedQuadFrames++
                if (missedQuadFrames >= LOST_AFTER_MISSED_FRAMES && !reportedLost) {
                    reportedLost = true
                    lastAttemptAtMillis = 0
                    stableFrameCount = 0
                    lastHash = null
                    onCardLost()
                }
            }

            val hash = PHash.compute(cropped)
            val prev = lastHash
            if (prev != null && PHash.hammingDistance(hash, prev) <= PHash.SAME_CARD_THRESHOLD) {
                stableFrameCount++
            } else {
                stableFrameCount = 1
            }
            lastHash = hash

            val throttled = System.currentTimeMillis() - lastAttemptAtMillis < ATTEMPT_THROTTLE_MILLIS

            if (stableFrameCount >= STABLE_FRAMES_REQUIRED && !throttled) {
                lastAttemptAtMillis = System.currentTimeMillis()
                stableFrameCount = 0
                onStableCard(cropped)
            }
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        if (format != ImageFormat.YUV_420_888) return null
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
