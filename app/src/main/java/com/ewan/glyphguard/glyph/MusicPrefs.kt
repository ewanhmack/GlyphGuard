package com.ewan.glyphguard.glyph

import android.content.Context
import com.ewan.glyphguard.engine.DefaultFrames
import com.ewan.glyphguard.engine.FrameCodec

/**
 * What to show on the matrix while Spotify is actively playing (see
 * GuardToyEngine.resolveFrameSource() / isSpotifyPlaying()).
 *
 * mode            — OFF: no music-linked behavior. VISUALIZER: the
 *                    audio-reactive EQ (needs RECORD_AUDIO). CUSTOM: a
 *                    fixed/animated design, same frames/intervalMs shape as
 *                    GuardPrefs' default pattern.
 */
object MusicPrefs {

    private const val PREFS_NAME = "glyph_guard_music_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_FRAMES = "frames_csv"
    private const val KEY_FRAME_INTERVAL_MS = "frame_interval_ms"

    private const val FRAME_SIZE = 169

    enum class Mode { OFF, VISUALIZER, CUSTOM }

    fun getMode(context: Context): Mode {
        val raw = prefs(context).getString(KEY_MODE, null) ?: return Mode.OFF
        return try {
            Mode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            Mode.OFF
        }
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
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
        prefs(context).getInt(KEY_FRAME_INTERVAL_MS, GuardPrefs.DEFAULT_FRAME_INTERVAL_MS)

    fun setFrameIntervalMs(context: Context, ms: Int) {
        prefs(context).edit()
            .putInt(KEY_FRAME_INTERVAL_MS, ms.coerceIn(GuardPrefs.MIN_FRAME_INTERVAL_MS, GuardPrefs.MAX_FRAME_INTERVAL_MS))
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
