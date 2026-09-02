package com.ewan.glyphguard.engine

import org.json.JSONObject

/**
 * Parses Glyph Museum's documented design-interchange format
 * (https://glyphmuseum.com/developers) for the Phone (4a) Pro.
 *
 * Museum's own storage format only lists the matrix's 137 physically-real
 * LEDs per frame (a circular cutout of the 13x13 grid, format version 4).
 * GlyphGuard's frames are always the full 169-value square instead, because
 * that's what GlyphMatrixManager.setMatrixFrame() and Nothing's own SDK
 * utility (GlyphMatrixUtils.convertToGlyphMatrix, confirmed by decompiling
 * the bundled glyph-matrix-sdk-2.0.aar) actually allocate and fill on this
 * device — a fixed matrixLength*matrixLength array, no compaction.
 * MatrixSize.ROW_LED_COUNTS — Museum's own documented per-row count of real
 * LEDs, centered in each row — is used here purely to place their 137
 * compact values back into the correct positions of that 169 square; every
 * other cell is padding the hardware has no LED behind anyway.
 */
object GlyphMuseumFormat {

    private const val GRID_SIZE = MatrixSize.SIZE
    private const val PHONE_4A_PRO_VERSION = 4
    private val COMPACT_LENGTH = MatrixSize.ROW_LED_COUNTS.sum() // 137

    data class Frame(val pixels: IntArray, val durationMs: Int?)

    class UnsupportedDesignException(message: String) : Exception(message)

    /** Throws [UnsupportedDesignException] for anything that isn't a valid Phone (4a) Pro design. */
    fun parse(json: String): List<Frame> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw UnsupportedDesignException("Not a valid Glyph Museum design file.")
        }

        val version = root.optInt("v", -1)
        if (version != PHONE_4A_PRO_VERSION) {
            throw UnsupportedDesignException(
                "This design is format v$version, not v$PHONE_4A_PRO_VERSION " +
                    "(Phone (4a) Pro) — likely exported for a different Glyph device."
            )
        }

        val framesJson = root.optJSONArray("frames")
            ?: throw UnsupportedDesignException("No frames array in this design.")
        if (framesJson.length() == 0) {
            throw UnsupportedDesignException("Design has no frames.")
        }

        return (0 until framesJson.length()).map { i ->
            val frameObj = framesJson.getJSONObject(i)
            val pixelsJson = frameObj.optJSONArray("p")
                ?: throw UnsupportedDesignException("Frame $i is missing its pixel data.")
            if (pixelsJson.length() != COMPACT_LENGTH) {
                throw UnsupportedDesignException(
                    "Frame $i has ${pixelsJson.length()} pixels, expected $COMPACT_LENGTH " +
                        "for a Phone (4a) Pro design."
                )
            }
            val compact = IntArray(COMPACT_LENGTH) { j -> pixelsJson.getInt(j) }
            val durationMs = if (frameObj.has("d")) frameObj.optInt("d") else null
            Frame(expandToSquare(compact), durationMs)
        }
    }

    /** Places Museum's 137 compact, row-major values into their real positions in a 169 (13x13) square. */
    private fun expandToSquare(compact: IntArray): IntArray {
        val square = IntArray(GRID_SIZE * GRID_SIZE)
        var read = 0
        for (row in 0 until GRID_SIZE) {
            val count = MatrixSize.ROW_LED_COUNTS[row]
            val colStart = (GRID_SIZE - count) / 2
            for (col in colStart until colStart + count) {
                square[row * GRID_SIZE + col] = compact[read]
                read++
            }
        }
        return square
    }
}
