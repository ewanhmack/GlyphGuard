package com.ewan.glyphguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ewan.glyphguard.glyph.GuardPrefs
import com.ewan.glyphguard.ui.theme.NothingBlack
import kotlinx.coroutines.delay

/**
 * Small self-animating preview for list rows (Gallery tab, Apps tab) — a
 * static frame if [frames] has only one, otherwise cycles at [intervalMs]
 * just like the real matrix would.
 */
@Composable
fun GlyphPreviewThumbnail(frames: List<IntArray>, intervalMs: Int, modifier: Modifier = Modifier) {
    var index by remember(frames, intervalMs) { mutableStateOf(0) }

    LaunchedEffect(frames, intervalMs) {
        if (frames.size <= 1) return@LaunchedEffect
        while (true) {
            delay(intervalMs.toLong().coerceAtLeast(GuardPrefs.MIN_FRAME_INTERVAL_MS.toLong()))
            index = (index + 1) % frames.size
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .background(NothingBlack, RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        GlyphGrid(
            frame = frames.getOrElse(index) { frames.first() },
            modifier = Modifier.fillMaxSize()
        )
    }
}
