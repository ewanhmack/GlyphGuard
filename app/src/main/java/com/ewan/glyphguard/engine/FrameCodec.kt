package com.ewan.glyphguard.engine

/**
 * Encodes/decodes a list of 13x13 (169-value) frames to/from a single string.
 * Shared by GuardPrefs (the global default pattern) and AppPatternPrefs
 * (per-app patterns) so both use identical, interchangeable storage.
 */
object FrameCodec {

    private const val FRAME_SIZE = 169
    private const val FRAME_SEPARATOR = ";"

    fun encode(frames: List<IntArray>): String =
        frames.joinToString(FRAME_SEPARATOR) { it.joinToString(",") }

    /** Returns null if the string is empty, malformed, or any frame isn't 169 values. */
    fun decode(csv: String): List<IntArray>? {
        if (csv.isBlank()) return null
        return try {
            val frames = csv.split(FRAME_SEPARATOR).map { block ->
                val values = block.split(",").map { it.trim().toInt() }
                require(values.size == FRAME_SIZE)
                values.toIntArray()
            }
            frames.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
