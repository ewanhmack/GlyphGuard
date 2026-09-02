package com.ewan.glyphguard.engine

/** Nothing Phone (4a) Pro's Glyph Matrix is 13x13 = 169 addressable LEDs. */
object MatrixSize {
    const val SIZE = 13
    const val FRAME_LENGTH = SIZE * SIZE

    /**
     * Per-row count of physically-real LEDs in the circular cutout of the
     * 13x13 square grid (the hardware has no LED behind the corner cells),
     * per Glyph Museum's documented layout (glyphmuseum.com/developers,
     * "ROWS_13"). Each row's real LEDs are centered: colStart = (SIZE -
     * count) / 2. Sums to 137, matching Museum's compact per-frame pixel
     * count. Used both to expand Museum's compact frames into our 169-square
     * representation (GlyphMuseumFormat) and, by row/column symmetry, to
     * bound per-column height in the audio visualizer's bar-graph mapping
     * (AudioVisualizerEngine).
     */
    val ROW_LED_COUNTS = intArrayOf(5, 9, 11, 11, 13, 13, 13, 13, 13, 11, 11, 9, 5)
}
