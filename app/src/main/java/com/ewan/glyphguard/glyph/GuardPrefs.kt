package com.ewan.glyphguard.glyph

import android.content.Context
import com.ewan.glyphguard.engine.DefaultFrames
import com.ewan.glyphguard.engine.FrameCodec

/**
 * GuardPrefs — persisted settings shared between MainActivity/ViewModel (writer)
 * and GuardToyService (reader).
 *
 * enabled         — master on/off switch. When false the AOD toy always renders
 *                    blank, regardless of what the system's own Always-on Glyph
 *                    Toy setting thinks it's doing.
 * timeoutSeconds  — how long to keep showing frames after the screen turns
 *                    back on (i.e. after you pick the phone up / un-flip it).
 *                    0 = clear immediately on pickup.
 * brightness      — 0-255, scales every pixel in every stored frame before
 *                    it's pushed to the matrix.
 * frames          — one or more 13x13 frames (169 ints each, 0-255 raw
 *                    brightness, row-major), cycled in order at
 *                    frameIntervalMs while the toy is active. A single frame
 *                    is just a static display; 2+ frames animate.
 * frameIntervalMs      — how long each frame stays up before advancing to
 *                         the next, when there's more than one.
 */
object GuardPrefs {

    private const val PREFS_NAME = "glyph_guard_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_FRAMES = "frames_csv"
    private const val KEY_FRAME_INTERVAL_MS = "frame_interval_ms"

    private const val FRAME_SIZE = 169

    const val DEFAULT_TIMEOUT_SECONDS = 5
    const val DEFAULT_BRIGHTNESS = 255
    const val DEFAULT_FRAME_INTERVAL_MS = 200
    // ~15Hz — matches AudioVisualizerEngine's own capture-rate choice ("plenty
    // for a 13x13 matrix"). A faster requested interval (e.g. from a Glyph
    // Museum import's own frame timing) gets floored to this instead of
    // flooding setMatrixFrame() with calls the binder/hardware can't keep up
    // with — confirmed on-device: a 103-frame animation at 30-40ms/frame
    // mostly dropped, with only occasional frames actually landing.
    const val MIN_FRAME_INTERVAL_MS = 66
    const val MAX_FRAME_INTERVAL_MS = 2000

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getTimeoutSeconds(context: Context): Int =
        prefs(context).getInt(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)

    fun setTimeoutSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_TIMEOUT_SECONDS, seconds.coerceIn(0, 300)).apply()
    }

    fun getBrightness(context: Context): Int =
        prefs(context).getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS)

    fun setBrightness(context: Context, brightness: Int) {
        prefs(context).edit().putInt(KEY_BRIGHTNESS, brightness.coerceIn(0, 255)).apply()
    }

    fun getFrames(context: Context): List<IntArray> {
        val csv = prefs(context).getString(KEY_FRAMES, null) ?: return listOf(DefaultFrames.dot())
        return FrameCodec.decode(csv) ?: listOf(DefaultFrames.dot())
    }

    fun setFrames(context: Context, frames: List<IntArray>) {
        require(frames.isNotEmpty()) { "Need at least one frame" }
        frames.forEach { require(it.size == FRAME_SIZE) { "Each frame must be 13x13 (169 values)" } }
        prefs(context).edit().putString(KEY_FRAMES, FrameCodec.encode(frames)).apply()
    }

    fun getFrameIntervalMs(context: Context): Int =
        prefs(context).getInt(KEY_FRAME_INTERVAL_MS, DEFAULT_FRAME_INTERVAL_MS)

    fun setFrameIntervalMs(context: Context, ms: Int) {
        prefs(context).edit()
            .putInt(KEY_FRAME_INTERVAL_MS, ms.coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
