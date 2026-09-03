package com.shiko.pokedex.camera

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Result of one detection pass over a frame.
 * @param cropped the (possibly perspective-corrected) card image, for OCR.
 * @param quadFound true if a real card-shaped quadrilateral was found; false if
 *   this is just the plain-center-crop fallback (no perspective correction, lower
 *   confidence — used for OCR but not for drawing a tracking box).
 * @param boundingRect the region's bounding box, in the *input* bitmap's pixel
 *   coordinate space (i.e. the raw, unrotated camera buffer), for positioning an
 *   on-screen tracking overlay.
 */
data class CardDetection(
    val cropped: Bitmap,
    val quadFound: Boolean,
    val boundingRect: RectF
)

/**
 * Finds the largest card-shaped quadrilateral in a frame and returns a
 * perspective-corrected crop of just the card. Runs entirely on-device —
 * no network call happens until a stable crop is produced.
 *
 * Requires OpenCV to be initialized once at app startup
 * (OpenCVLoader.initDebug() in Application.onCreate, or the async loader).
 */
object CardDetector {

    // Standard trading card ratio (2.5in x 3.5in) used to validate candidate contours.
    private const val CARD_ASPECT = 2.5 / 3.5
    private const val ASPECT_TOLERANCE = 0.4
    private const val MIN_AREA_FRACTION = 0.05 // card must fill at least 5% of frame

    /**
     * Never returns null: if no clean quadrilateral is found (messy background,
     * reflections, imperfect lighting), falls back to a plain center-crop of the
     * frame so the identification pipeline still gets a chance to run. The
     * fallback is flagged via `quadFound = false` so callers can tell it apart
     * from a confident detection (e.g. to decide whether to draw a tracking box).
     */
    fun findAndCropCard(input: Bitmap): CardDetection {
        val src = Mat()
        Utils.bitmapToMat(input, src)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.dilate(edges, edges, Mat())

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        val frameArea = src.rows() * src.cols()
        var best: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < frameArea * MIN_AREA_FRACTION) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val rect = Imgproc.boundingRect(MatOfPoint(*approx.toArray()))
                val ratio = rect.width.toDouble() / rect.height.toDouble()
                val normalizedRatio = if (ratio > 1.0) 1.0 / ratio else ratio
                val targetRatio = CARD_ASPECT
                if (Math.abs(normalizedRatio - targetRatio) <= ASPECT_TOLERANCE && area > bestArea) {
                    best = approx
                    bestArea = area
                }
            }
        }

        val quadFound = best != null
        val boundingRect: RectF
        val resultMat: Mat

        if (best != null) {
            val points = best.toArray()
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            boundingRect = RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
            resultMat = warpToCard(src, best)
        } else {
            val marginX = (src.cols() * 0.075).toInt()
            val marginY = (src.rows() * 0.075).toInt()
            boundingRect = RectF(
                marginX.toFloat(), marginY.toFloat(),
                (src.cols() - marginX).toFloat(), (src.rows() - marginY).toFloat()
            )
            resultMat = centerCrop(src, marginX, marginY)
        }

        src.release(); gray.release(); edges.release(); hierarchy.release()

        val enhanced = enhanceContrast(resultMat)
        resultMat.release()

        val output = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhanced, output)
        enhanced.release()

        return CardDetection(cropped = output, quadFound = quadFound, boundingRect = boundingRect)
    }

    /**
     * Boosts local contrast (CLAHE on the L channel in LAB space) so OCR has a
     * better chance of reading small text — HP, collector number — on dark or
     * holographic card backgrounds where it's otherwise low-contrast and easy to
     * miss. This crop is only ever used internally for OCR/hashing, never shown
     * to the user, so there's no downside to making it look a bit harsh.
     */
    private fun enhanceContrast(rgba: Mat): Mat {
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)

        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhancedL = Mat()
        clahe.apply(channels[0], enhancedL)
        channels[0].release()
        channels[0] = enhancedL

        Core.merge(channels, lab)
        channels.forEach { it.release() }

        val enhancedBgr = Mat()
        Imgproc.cvtColor(lab, enhancedBgr, Imgproc.COLOR_Lab2BGR)
        lab.release()

        val enhancedRgba = Mat()
        Imgproc.cvtColor(enhancedBgr, enhancedRgba, Imgproc.COLOR_BGR2RGBA)
        bgr.release()
        enhancedBgr.release()

        return enhancedRgba
    }

    /** Fallback when no clean quad is found: center ~85% of the frame, no perspective correction. */
    private fun centerCrop(src: Mat, marginX: Int, marginY: Int): Mat {
        val roi = org.opencv.core.Rect(
            marginX, marginY,
            src.cols() - 2 * marginX, src.rows() - 2 * marginY
        )
        return Mat(src, roi).clone()
    }

    /** Orders the 4 corner points and applies a perspective warp so the card fills the output rectangle. */
    private fun warpToCard(src: Mat, quad: MatOfPoint2f): Mat {
        val points = quad.toArray()
        val ordered = orderCorners(points)

        val widthTop = distance(ordered[0], ordered[1])
        val widthBottom = distance(ordered[3], ordered[2])
        val heightLeft = distance(ordered[0], ordered[3])
        val heightRight = distance(ordered[1], ordered[2])

        val outWidth = max(widthTop, widthBottom)
        val outHeight = max(heightLeft, heightRight)

        val srcMat = MatOfPoint2f(*ordered)
        val dstMat = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outWidth, 0.0),
            Point(outWidth, outHeight),
            Point(0.0, outHeight)
        )

        val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(outWidth, outHeight))
        return warped
    }

    /** Returns corners ordered top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        val sumSorted = points.sortedBy { it.x + it.y }
        val topLeft = sumSorted.first()
        val bottomRight = sumSorted.last()
        val diffSorted = points.sortedBy { it.y - it.x }
        val topRight = diffSorted.first()
        val bottomLeft = diffSorted.last()
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt(dx * dx + dy * dy)
    }
}
