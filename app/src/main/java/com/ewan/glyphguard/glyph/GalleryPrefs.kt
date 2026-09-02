package com.ewan.glyphguard.glyph

import android.content.Context
import com.ewan.glyphguard.engine.FrameCodec
import org.json.JSONObject
import java.util.UUID

/**
 * User-saved gallery of designs — id -> {name, framesCsv, intervalMs} — so an
 * imported (or hand-built) pattern can be named, kept, and reused later as
 * either the default pattern or a per-app one, without re-importing it.
 * Same "single JSON blob" storage shape as AppPatternPrefs.
 */
object GalleryPrefs {

    private const val PREFS_NAME = "glyph_guard_gallery"
    private const val KEY_ENTRIES_JSON = "entries_json"

    private const val FIELD_NAME = "name"
    private const val FIELD_FRAMES_CSV = "framesCsv"
    private const val FIELD_INTERVAL_MS = "intervalMs"

    data class Entry(val id: String, val name: String, val frames: List<IntArray>, val intervalMs: Int)

    fun getAll(context: Context): List<Entry> {
        val root = readAll(context)
        return root.keys().asSequence().mapNotNull { id ->
            val obj = root.optJSONObject(id) ?: return@mapNotNull null
            val frames = FrameCodec.decode(obj.optString(FIELD_FRAMES_CSV, "")) ?: return@mapNotNull null
            Entry(
                id = id,
                name = obj.optString(FIELD_NAME, "Untitled"),
                frames = frames,
                intervalMs = obj.optInt(FIELD_INTERVAL_MS, GuardPrefs.DEFAULT_FRAME_INTERVAL_MS),
            )
        }.sortedBy { it.name.lowercase() }.toList()
    }

    fun save(context: Context, name: String, frames: List<IntArray>, intervalMs: Int) {
        require(frames.isNotEmpty()) { "Need at least one frame" }
        val root = readAll(context)
        val entry = JSONObject()
            .put(FIELD_NAME, name)
            .put(FIELD_FRAMES_CSV, FrameCodec.encode(frames))
            .put(FIELD_INTERVAL_MS, intervalMs.coerceIn(GuardPrefs.MIN_FRAME_INTERVAL_MS, GuardPrefs.MAX_FRAME_INTERVAL_MS))
        root.put(UUID.randomUUID().toString(), entry)
        writeAll(context, root)
    }

    fun remove(context: Context, id: String) {
        val root = readAll(context)
        root.remove(id)
        writeAll(context, root)
    }

    private fun readAll(context: Context): JSONObject {
        val json = prefs(context).getString(KEY_ENTRIES_JSON, null) ?: return JSONObject()
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeAll(context: Context, root: JSONObject) {
        prefs(context).edit().putString(KEY_ENTRIES_JSON, root.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
