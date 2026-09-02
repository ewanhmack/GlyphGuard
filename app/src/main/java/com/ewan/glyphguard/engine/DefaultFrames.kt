package com.ewan.glyphguard.engine

/**
 * A couple of built-in 13x13 patterns so the toy has something to show
 * before you've imported your own Glyph Museum export.
 */
object DefaultFrames {

    private const val SIZE = 13

    /** Filled circle roughly matching the matrix's circular LED layout. */
    fun dot(): IntArray {
        val frame = IntArray(SIZE * SIZE)
        val center = (SIZE - 1) / 2.0
        val radius = SIZE / 2.0 - 0.5
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dx = x - center
                val dy = y - center
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                frame[y * SIZE + x] = if (dist <= radius) 255 else 0
            }
        }
        return frame
    }

    /** Thin ring outline — useful for confirming brightness scaling visually. */
    fun ring(): IntArray {
        val frame = IntArray(SIZE * SIZE)
        val center = (SIZE - 1) / 2.0
        val outer = SIZE / 2.0 - 0.5
        val inner = outer - 1.5
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dx = x - center
                val dy = y - center
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                frame[y * SIZE + x] = if (dist in inner..outer) 255 else 0
            }
        }
        return frame
    }

    fun blank(): IntArray = IntArray(SIZE * SIZE)
}
