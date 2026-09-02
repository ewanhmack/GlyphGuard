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
}
