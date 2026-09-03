package com.ewan.glyphguard.engine

/**
 * Applies the user's brightness setting relative to a frame set's OWN peak
 * pixel value, not a flat 0-255 assumption — so brightness=255 always
 * reaches this design's true maximum, even if it was authored (or
 * downsampled from a larger device's grid, e.g. GlyphMuseumFormat) well
 * under 255. A design that's already full-range (DefaultFrames.dot()/
 * ring(), most PNG imports) is unaffected, since peak is already 255 there.
 *
 * Peak is computed once across every frame in the set, not per-frame, so a
 * multi-frame animation keeps its own relative contrast between frames
 * instead of each frame being independently (and inconsistently) stretched.
 *
 * Not used for the audio visualizer — its per-tick peak varies with the
 * music itself, and normalizing against that would flatten quiet and loud
 * moments to the same apparent brightness, defeating the point of a live EQ.
 */
object FrameScaling {

    /**
     * Nothing's Glyph Matrix HAL takes each pixel on a ~12-bit (0-4095) scale, not
     * 0-255 — confirmed by decompiling glyph-matrix-sdk-2.0.aar: its own
     * GlyphMatrixUtils.rgbaToGrayscale() (the reference bitmap-to-frame conversion)
     * computes an 8-bit luma value and multiplies by its private BRIGHTNESS_MULTIPLIER
     * (16) before returning, capped at its MAX_BRIGHTNESS (4095). GlyphMatrixManager
     * .setMatrixFrame(int[]) forwards whatever array it's given straight to the AIDL
     * service (IGlyphService.setMatrixColors) with no rescaling of its own — so a frame
     * that peaks at 255 only reaches ~6% of the hardware's true maximum. Everywhere else
     * in this codebase (GuardPrefs, DefaultFrames, ImageToFrame, GlyphMuseumFormat,
     * FrameCodec) works in the more portable 0-255 range, so this is the one place that
     * adapts to the hardware's real range, right before a frame leaves the app.
     */
    private const val HARDWARE_SCALE = 16
    private const val HARDWARE_MAX = 255 * HARDWARE_SCALE

    fun peakOf(frames: List<IntArray>): Int =
        frames.maxOfOrNull { frame -> frame.maxOrNull() ?: 0 } ?: 0

    fun scaleFrame(frame: IntArray, brightness: Int, peak: Int): IntArray {
        if (peak <= 0) return IntArray(frame.size)
        return IntArray(frame.size) { i -> (frame[i] * brightness / peak).coerceIn(0, 255) }
    }

    fun scaleFrames(frames: List<IntArray>, brightness: Int): List<IntArray> {
        val peak = peakOf(frames)
        return frames.map { scaleFrame(it, brightness, peak) }
    }

    /** Last step before any frame reaches [com.nothing.ketchum.GlyphMatrixManager] — see class doc. */
    fun toHardwareRange(frame: IntArray): IntArray =
        IntArray(frame.size) { i -> (frame[i] * HARDWARE_SCALE).coerceIn(0, HARDWARE_MAX) }
}
