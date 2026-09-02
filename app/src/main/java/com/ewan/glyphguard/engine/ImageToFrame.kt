package com.ewan.glyphguard.engine

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.scale

/**
 * Converts an arbitrary image (e.g. a PNG exported from Glyph Museum) into a
 * 13x13 brightness frame (0-255 per pixel, row-major) suitable for the matrix.
 */
object ImageToFrame {

    private const val SIZE = 13

    fun fromUri(resolver: ContentResolver, uri: Uri): IntArray? {
        val input = resolver.openInputStream(uri) ?: return null
        val original = input.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
        return fromBitmap(original)
    }

    fun fromBitmap(bitmap: Bitmap): IntArray {
        val scaled = bitmap.scale(SIZE, SIZE)
        val frame = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val pixel = scaled.getPixel(x, y)
                // Luminance-weighted greyscale, scaled by the pixel's own alpha
                // so transparent PNG backgrounds come through as "off".
                val alpha = Color.alpha(pixel) / 255f
                val luminance = (0.299f * Color.red(pixel) +
                        0.587f * Color.green(pixel) +
                        0.114f * Color.blue(pixel))
                frame[y * SIZE + x] = (luminance * alpha).toInt().coerceIn(0, 255)
            }
        }
        return frame
    }
}
