package com.ewan.glyphguard.engine

import org.json.JSONObject

/**
 * Parses Glyph Museum's documented design-interchange format
 * (https://glyphmuseum.com/developers).
 *
 * The "v" field does NOT distinguish which device a design was made for —
 * per Museum's own docs only format v1 exists, and it's shared by every
 * device. What actually varies is each frame's compact pixel count: 137 for
 * Phone (4a) Pro's 13x13 circular cutout, 489 for Phone (3)'s 25x25 one
 * (confirmed via glyphmuseum.com/developers's own per-device LED-count
 * table). We detect the source device from that count instead of "v".
 *
 * Phone (4a) Pro designs expand directly into our native 13x13 square (no
 * resize needed — this device's own hardware). Phone (3) designs expand
 * into their native 25x25 square first, then get downsampled to 13x13 by
 * max-pooling (see downsampleToOurGrid) rather than blending/averaging —
 * confirmed on-device that a bilinear/box-average resize diluted fine bright
 * detail (thin lines, small accents) against their darker surroundings down
 * to values too dim to register, leaving only the design's few solid/bold
 * elements visible. Max-pooling keeps the brightest source pixel in each
 * target cell instead, which is the right tradeoff for pixel-art-style LED
 * content (unlike a smooth photo, where averaging is usually preferred).
 */
object GlyphMuseumFormat {

    private const val PHONE_4A_PRO_GRID_SIZE = MatrixSize.SIZE
    private val PHONE_4A_PRO_ROW_LED_COUNTS = MatrixSize.ROW_LED_COUNTS
    private val PHONE_4A_PRO_COMPACT_LENGTH = PHONE_4A_PRO_ROW_LED_COUNTS.sum() // 137

    private const val PHONE_3_GRID_SIZE = 25
    private val PHONE_3_ROW_LED_COUNTS = intArrayOf(
        7, 11, 15, 17, 19, 21, 21, 23, 23, 25, 25, 25, 25, 25, 25, 25, 23, 23, 21, 21, 19, 17, 15, 11, 7
    )
    private val PHONE_3_COMPACT_LENGTH = PHONE_3_ROW_LED_COUNTS.sum() // 489

    data class Frame(val pixels: IntArray, val durationMs: Int?)

    class UnsupportedDesignException(message: String) : Exception(message)

    /** Throws [UnsupportedDesignException] for anything that isn't a recognized Glyph design file. */
    fun parse(json: String): List<Frame> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw UnsupportedDesignException("Not a valid Glyph Museum design file.")
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
            val compact = IntArray(pixelsJson.length()) { j -> pixelsJson.getInt(j) }
            val durationMs = if (frameObj.has("d")) frameObj.optInt("d") else null
            Frame(toDeviceSquare(compact, i), durationMs)
        }
    }

    /** Routes by compact pixel count to the source device's own grid, then to our 13x13 if it isn't already. */
    private fun toDeviceSquare(compact: IntArray, frameIndex: Int): IntArray {
        return when (compact.size) {
            PHONE_4A_PRO_COMPACT_LENGTH ->
                expandToSquare(compact, PHONE_4A_PRO_GRID_SIZE, PHONE_4A_PRO_ROW_LED_COUNTS)
            PHONE_3_COMPACT_LENGTH ->
                downsampleToOurGrid(expandToSquare(compact, PHONE_3_GRID_SIZE, PHONE_3_ROW_LED_COUNTS), PHONE_3_GRID_SIZE)
            else -> throw UnsupportedDesignException(
                "Frame $frameIndex has ${compact.size} pixels — not a recognized Glyph device layout " +
                    "(expected $PHONE_4A_PRO_COMPACT_LENGTH for Phone (4a) Pro or $PHONE_3_COMPACT_LENGTH for Phone (3))."
            )
        }
    }

    /** Places a device's compact, row-major values into their real positions in a gridSize² square. */
    private fun expandToSquare(compact: IntArray, gridSize: Int, rowLedCounts: IntArray): IntArray {
        val square = IntArray(gridSize * gridSize)
        var read = 0
        for (row in 0 until gridSize) {
            val count = rowLedCounts[row]
            val colStart = (gridSize - count) / 2
            for (col in colStart until colStart + count) {
                square[row * gridSize + col] = compact[read]
                read++
            }
        }
        return square
    }

    /** Max-pools a larger device's square grid down to our 13x13 — see class doc for why max, not average. */
    private fun downsampleToOurGrid(square: IntArray, sourceSize: Int): IntArray {
        val targetSize = MatrixSize.SIZE
        val result = IntArray(MatrixSize.FRAME_LENGTH)
        for (ty in 0 until targetSize) {
            val sy0 = ty * sourceSize / targetSize
            val sy1 = maxOf((ty + 1) * sourceSize / targetSize, sy0 + 1)
            for (tx in 0 until targetSize) {
                val sx0 = tx * sourceSize / targetSize
                val sx1 = maxOf((tx + 1) * sourceSize / targetSize, sx0 + 1)
                var peak = 0
                for (sy in sy0 until sy1) {
                    for (sx in sx0 until sx1) {
                        peak = maxOf(peak, square[sy * sourceSize + sx])
                    }
                }
                result[ty * targetSize + tx] = peak
            }
        }
        return result
    }
}
