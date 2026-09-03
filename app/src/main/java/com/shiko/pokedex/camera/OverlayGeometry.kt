package com.shiko.pokedex.camera

import android.graphics.RectF

/**
 * CameraX's ImageAnalysis buffer is in raw sensor orientation; PreviewView shows
 * it rotated upright (per `rotationDegrees`) and scaled with ScaleType.FILL_CENTER
 * (PreviewView's default — crops to fill, no letterboxing). To draw a box around
 * the detected card that visually lines up with what the user sees, both steps
 * have to be replicated here.
 */
object OverlayGeometry {

    /**
     * @param rect bounding box in raw buffer pixel coordinates (bufferWidth x bufferHeight)
     * @param rotationDegrees ImageInfo.rotationDegrees for this frame (0/90/180/270)
     * @param viewWidth/viewHeight the PreviewView's on-screen size in pixels
     */
    fun mapToView(
        rect: RectF,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        viewWidth: Float,
        viewHeight: Float
    ): RectF {
        val (rotatedRect, rotatedWidth, rotatedHeight) = rotateRect(rect, bufferWidth, bufferHeight, rotationDegrees)

        // FILL_CENTER: scale up to cover the view, crop whatever overflows, center the rest.
        val scale = maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        val displayedWidth = rotatedWidth * scale
        val displayedHeight = rotatedHeight * scale
        val offsetX = (viewWidth - displayedWidth) / 2f
        val offsetY = (viewHeight - displayedHeight) / 2f

        return RectF(
            offsetX + rotatedRect.left * scale,
            offsetY + rotatedRect.top * scale,
            offsetX + rotatedRect.right * scale,
            offsetY + rotatedRect.bottom * scale
        )
    }

    private data class Rotated(val rect: RectF, val width: Float, val height: Float)

    private fun rotateRect(rect: RectF, w: Int, h: Int, rotationDegrees: Int): Rotated {
        fun rotatePoint(x: Float, y: Float): Pair<Float, Float> = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> (h - y) to x
            180 -> (w - x) to (h - y)
            270 -> y to (w - x)
            else -> x to y
        }

        val corners = listOf(
            rotatePoint(rect.left, rect.top),
            rotatePoint(rect.right, rect.top),
            rotatePoint(rect.right, rect.bottom),
            rotatePoint(rect.left, rect.bottom)
        )
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val rotatedRect = RectF(xs.min(), ys.min(), xs.max(), ys.max())

        val (rotatedWidth, rotatedHeight) = if (((rotationDegrees % 360) + 360) % 360 == 90 ||
            ((rotationDegrees % 360) + 360) % 360 == 270
        ) {
            h.toFloat() to w.toFloat()
        } else {
            w.toFloat() to h.toFloat()
        }

        return Rotated(rotatedRect, rotatedWidth, rotatedHeight)
    }
}
