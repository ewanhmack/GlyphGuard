package com.ewan.glyphguard.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ewan.glyphguard.engine.MatrixSize
import com.ewan.glyphguard.glyph.GuardPrefs
import com.ewan.glyphguard.viewmodel.MainViewModel
import kotlinx.coroutines.delay

private enum class DrawMode { LITE_BRITE, WATER }

/**
 * Interactive live-draw canvas — pushes to the real matrix via app-mode
 * preview (MainViewModel.pushLiveFrame), same path the pattern editor's
 * "quick live check" uses. Two modes:
 * - Lite-Brite: drag paints LEDs on (or off, dragging from an already-lit
 *   one) — a direct pixel editor, no fading.
 * - Water: every touch lights up (as a small blob, not a single pixel — see
 *   [paintBlob]) and continuously fades (see the decay loop below), so
 *   trailing your finger leaves a dissipating ripple rather than a fixed
 *   line.
 *
 * The on-screen [frame] state updates instantly on every touch/decay tick,
 * but the real matrix is pushed on its own throttled loop instead of once
 * per update — same throughput ceiling documented at GuardPrefs
 * .MIN_FRAME_INTERVAL_MS (the hardware/binder pipeline can't keep up with a
 * push per gesture event, which was previously the on-screen preview
 * visibly racing ahead of the real matrix as pushes queued up behind it).
 */
@Composable
fun LiveDrawSection(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(DrawMode.LITE_BRITE) }
    var frame by remember { mutableStateOf(IntArray(MatrixSize.FRAME_LENGTH)) }
    var showSaveDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { viewModel.clearLiveDraw() } }

    LaunchedEffect(Unit) {
        var lastPushed: IntArray? = null
        while (true) {
            delay(GuardPrefs.MIN_FRAME_INTERVAL_MS.toLong())
            val current = frame
            if (!current.contentEquals(lastPushed)) {
                viewModel.pushLiveFrame(current)
                lastPushed = current
            }
        }
    }

    LaunchedEffect(mode) {
        if (mode != DrawMode.WATER) return@LaunchedEffect
        while (true) {
            delay(80)
            // ~1s to fully fade (was ~2.7s) -- also helps mask the real matrix's
            // inherent hardware lag behind the on-screen preview, since a trail
            // that clears faster leaves less time for the two to visibly diverge.
            frame = IntArray(frame.size) { i -> (frame[i] * 0.65f).toInt() }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live draw", style = MaterialTheme.typography.titleMedium)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                selected = mode == DrawMode.LITE_BRITE,
                onClick = { mode = DrawMode.LITE_BRITE },
                label = { Text("Lite-Brite") }
            )
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                selected = mode == DrawMode.WATER,
                onClick = { mode = DrawMode.WATER },
                label = { Text("Water") }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .aspectRatio(1f)
                .pointerInput(mode) {
                    // Whether the stroke currently in progress paints on or off --
                    // set once per gesture in onDragStart, read by every onDrag
                    // call that follows within that same stroke. Plain var, not
                    // Compose state: pointerInput's own coroutine (kept alive
                    // across recompositions as long as `mode` doesn't change)
                    // is what needs to see this, not the UI.
                    var paintOn = true
                    detectDragGestures(
                        onDragStart = { offset ->
                            cellAt(offset, size.width.toFloat())?.let { cell ->
                                paintOn = frame[cell] == 0
                                frame = if (mode == DrawMode.WATER) {
                                    paintBlob(frame, cell)
                                } else {
                                    frame.copyOf().also { it[cell] = if (paintOn) 255 else 0 }
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            cellAt(change.position, size.width.toFloat())?.let { cell ->
                                frame = if (mode == DrawMode.WATER) {
                                    paintBlob(frame, cell)
                                } else {
                                    val value = if (paintOn) 255 else 0
                                    if (frame[cell] == value) frame else frame.copyOf().also { it[cell] = value }
                                }
                            }
                        }
                    )
                }
        ) {
            GlyphGrid(frame = frame, modifier = Modifier.fillMaxSize())
        }

        Text(
            if (mode == DrawMode.LITE_BRITE) {
                "Drag to paint LEDs on; start a stroke on an already-lit one to erase instead."
            } else {
                "Touch and drag — each point fades out on its own like a ripple."
            },
            style = MaterialTheme.typography.bodySmall
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                selected = false,
                onClick = { frame = IntArray(frame.size) },
                label = { Text("Clear") }
            )
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                selected = false,
                onClick = { showSaveDialog = true },
                label = { Text("Save to gallery…") }
            )
        }
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save to gallery") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.saveLiveFrameToGallery(context, name, frame)
                        showSaveDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/** Maps a touch offset to a frame index, or null outside the grid or off the circular cutout ([isValidCell]). */
private fun cellAt(offset: Offset, canvasSizePx: Float): Int? {
    val size = MatrixSize.SIZE
    val cellSize = canvasSizePx / size
    val col = (offset.x / cellSize).toInt()
    val row = (offset.y / cellSize).toInt()
    if (!isValidCell(row, col)) return null
    return row * size + col
}

private fun isValidCell(row: Int, col: Int): Boolean {
    val size = MatrixSize.SIZE
    if (row !in 0 until size || col !in 0 until size) return false
    val count = MatrixSize.ROW_LED_COUNTS[row]
    val colStart = (size - count) / 2
    return col in colStart until colStart + count
}

/**
 * Water mode's brush — a center LED at full brightness, dimmer orthogonal
 * neighbours, dimmer still diagonal ones, rather than a single pixel, so a
 * stroke reads as a soft trail rather than a thin dotted line. Three levels
 * (not two) for a smoother falloff than a hard center/edge cutoff; overall
 * a touch smaller and gentler than the first pass. Neighbours use max(), not
 * a flat assignment, so painting over an already-brighter spot (e.g. two
 * strokes crossing) doesn't dim it back down.
 */
private fun paintBlob(frame: IntArray, centerCell: Int): IntArray {
    val size = MatrixSize.SIZE
    val row = centerCell / size
    val col = centerCell % size
    val updated = frame.copyOf()
    updated[centerCell] = 255
    for ((r, c) in listOf(row - 1 to col, row + 1 to col, row to col - 1, row to col + 1)) {
        if (isValidCell(r, c)) {
            val idx = r * size + c
            updated[idx] = maxOf(updated[idx], 110)
        }
    }
    for ((r, c) in listOf(row - 1 to col - 1, row - 1 to col + 1, row + 1 to col - 1, row + 1 to col + 1)) {
        if (isValidCell(r, c)) {
            val idx = r * size + c
            updated[idx] = maxOf(updated[idx], 40)
        }
    }
    return updated
}
