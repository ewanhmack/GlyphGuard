package com.ewan.glyphguard.glyph

import android.content.Context
import com.ewan.glyphguard.engine.FrameCodec
import org.json.JSONObject

/**
 * Per-app glyph patterns — packageName -> Pattern, shown instead of the
 * default (GuardPrefs) frames while that app has a pending notification
 * during AOD. See GuardToyEngine.resolveFrameSource() and
 * GlyphNotificationListenerService.
 *
 * Stored as one JSON blob (packageName -> {framesCsv, intervalMs}) in its
 * own SharedPreferences key — same "single blob string" shape GuardPrefs
 * uses for its own frames, JSON instead of raw CSV only because this outer
 * structure needs keys. framesCsv uses the same encoding as GuardPrefs
 * (FrameCodec), so patterns are interchangeable between the two.
 */
object AppPatternPrefs {

    private const val PREFS_NAME = "glyph_guard_app_patterns"
    private const val KEY_PATTERNS_JSON = "patterns_json"

    private const val FIELD_FRAMES_CSV = "framesCsv"
    private const val FIELD_INTERVAL_MS = "intervalMs"

    data class Pattern(val frames: List<IntArray>, val intervalMs: Int)

    fun getMappedPackages(context: Context): Set<String> =
        readAll(context).keys().asSequence().toSet()

    fun getPattern(context: Context, packageName: String): Pattern? {
        val obj = readAll(context).optJSONObject(packageName) ?: return null
        val framesCsv = obj.optString(FIELD_FRAMES_CSV, "")
        val frames = FrameCodec.decode(framesCsv) ?: return null
        val intervalMs = obj.optInt(FIELD_INTERVAL_MS, GuardPrefs.DEFAULT_FRAME_INTERVAL_MS)
        return Pattern(frames, intervalMs)
    }

    fun setPattern(context: Context, packageName: String, frames: List<IntArray>, intervalMs: Int) {
        require(frames.isNotEmpty()) { "Need at least one frame" }
        val root = readAll(context)
        val entry = JSONObject()
            .put(FIELD_FRAMES_CSV, FrameCodec.encode(frames))
            .put(FIELD_INTERVAL_MS, intervalMs.coerceIn(GuardPrefs.MIN_FRAME_INTERVAL_MS, GuardPrefs.MAX_FRAME_INTERVAL_MS))
        root.put(packageName, entry)
        writeAll(context, root)
    }

    fun removePattern(context: Context, packageName: String) {
        val root = readAll(context)
        root.remove(packageName)
        writeAll(context, root)
    }

    private fun readAll(context: Context): JSONObject {
        val json = prefs(context).getString(KEY_PATTERNS_JSON, null) ?: return JSONObject()
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeAll(context: Context, root: JSONObject) {
        prefs(context).edit().putString(KEY_PATTERNS_JSON, root.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
