package com.ewan.glyphguard.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
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

    /**
     * Same greyscale conversion as [fromBitmap], but for an app's own
     * launcher icon rather than user-authored art. Most icons without a
     * monochrome layer are a light/white circular background with a
     * coloured or dark mark on top — converted via raw luminance, that
     * background (bright) lights up instead of the mark (dimmer), the
     * opposite of a legible result on a dark-background matrix. Sampling
     * the icon's corners (reliably outside a centered mark, for both
     * adaptive and legacy icons) and inverting when they read light fixes
     * that common case without needing to know anything about the icon's
     * actual content.
     */
    fun fromIconBitmap(bitmap: Bitmap): IntArray {
        val scaled = bitmap.scale(SIZE, SIZE)
        val corners = listOf(
            scaled.getPixel(0, 0), scaled.getPixel(SIZE - 1, 0),
            scaled.getPixel(0, SIZE - 1), scaled.getPixel(SIZE - 1, SIZE - 1),
        )
        val backgroundLuminance = corners.map { luminanceOf(it) }.average()
        val invert = backgroundLuminance > 128

        val frame = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val pixel = scaled.getPixel(x, y)
                val alpha = Color.alpha(pixel) / 255f
                val luminance = luminanceOf(pixel)
                val value = if (invert) 255 - luminance else luminance
                frame[y * SIZE + x] = (value * alpha).toInt().coerceIn(0, 255)
            }
        }
        return frame
    }

    private fun luminanceOf(pixel: Int): Int =
        (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)).toInt()

    /**
     * For a monochrome adaptive-icon layer (AdaptiveIconDrawable.monochrome) —
     * an alpha-only shape meant to be tinted by whoever draws it, e.g. black
     * fill/full alpha inside the icon's silhouette, zero alpha outside. Its
     * own fill colour carries no signal (it's always the same flat colour,
     * often black — [fromBitmap]'s luminance*alpha would come out ~0
     * everywhere and produce a blank frame), so alpha alone is the shape.
     */
    fun fromMonochromeBitmap(bitmap: Bitmap): IntArray {
        val scaled = bitmap.scale(SIZE, SIZE)
        val frame = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                frame[y * SIZE + x] = Color.alpha(scaled.getPixel(x, y))
            }
        }
        return frame
    }

    /**
     * An installed app's own launcher icon, converted to a frame — the
     * shared entry point for both the per-app pattern editor (MainViewModel)
     * and GuardToyEngine's own icon fallback for a pending notification with
     * no saved custom pattern, so both go through identical logic. Prefers
     * the monochrome adaptive-icon layer (see [fromMonochromeBitmap]) when
     * the app provides one, else falls back to [fromIconBitmap]. Null if the
     * package's icon can't be read (e.g. it's been uninstalled since).
     */
    fun fromInstalledApp(context: Context, packageName: String): IntArray? {
        val icon = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            return null
        }
        val monochrome = (icon as? AdaptiveIconDrawable)?.monochrome
        return if (monochrome != null) {
            fromMonochromeBitmap(monochrome.toBitmap())
        } else {
            fromIconBitmap(icon.toBitmap())
        }
    }
}
