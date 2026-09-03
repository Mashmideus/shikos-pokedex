package com.shiko.pokedex.util

import android.graphics.Bitmap

/**
 * Difference hash (dHash): fast perceptual hash used only to decide whether the
 * current frame is "the same card as last time" so we don't burn API credits
 * re-identifying an unchanged card every frame.
 */
object PHash {

    private const val HASH_SIZE = 9 // -> 8x8 diff grid -> 64-bit hash

    fun compute(bitmap: Bitmap): Long {
        val resized = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE - 1, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_SIZE - 1) {
            for (x in 0 until HASH_SIZE - 1) {
                val left = luminance(resized.getPixel(x, y))
                val right = luminance(resized.getPixel(x + 1, y))
                if (left > right) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }

    /** Two hashes closer than this are considered "the same card". Tune empirically. */
    const val SAME_CARD_THRESHOLD = 14

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
