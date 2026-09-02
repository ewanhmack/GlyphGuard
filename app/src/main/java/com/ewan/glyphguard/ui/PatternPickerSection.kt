package com.ewan.glyphguard.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ewan.glyphguard.glyph.GuardPrefs
import com.ewan.glyphguard.viewmodel.MainViewModel

/**
 * The Dot/Ring/None/Import-frames/Import-Museum pattern picker + frame-count
 * label + speed slider, reused by both the global default (MainScreen) and
 * the per-app editor (AppPatternEditorScreen) — both drive the same
 * MainViewModel state ([MainViewModel.frames]/[MainViewModel.frameIntervalMs]),
 * which itself decides whether edits land in GuardPrefs or AppPatternPrefs
 * based on [MainViewModel.editingPackage].
 */
@Composable
fun PatternPickerSection(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val frames by viewModel.frames.collectAsState()
    val frameIntervalMs by viewModel.frameIntervalMs.collectAsState()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.importImages(context, uris)
    }

    val museumPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.importGlyphMuseumFile(context, it).onFailure { e ->
                android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                viewModel.useBuiltIn(context, MainViewModel.BuiltInPattern.DOT)
            }) { Text("Dot") }
            OutlinedButton(onClick = {
                viewModel.useBuiltIn(context, MainViewModel.BuiltInPattern.RING)
            }) { Text("Ring") }
            OutlinedButton(onClick = {
                viewModel.useBuiltIn(context, MainViewModel.BuiltInPattern.NONE)
            }) { Text("None") }
            OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                Text("Import frames…")
            }
            OutlinedButton(onClick = { museumPicker.launch("*/*") }) {
                Text("Import from Glyph Museum…")
            }
        }
        Text(
            if (frames.size > 1) {
                "${frames.size} frames loaded — animating at ${frameIntervalMs}ms/frame"
            } else {
                "1 frame loaded — static"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "\"Import frames…\" takes one or more 13x13-friendly PNGs — each is " +
                "downsampled and converted to greyscale brightness automatically; pick " +
                "several, in order, to animate. \"Import from Glyph Museum…\" reads a design " +
                "file exported from the Glyph Museum app directly (glyphmuseum.com's " +
                "documented format) — exact brightness values and frame timing, no " +
                "screenshotting required. Only Phone (4a) Pro designs (format v4) are " +
                "accepted.",
            style = MaterialTheme.typography.bodySmall
        )

        if (frames.size > 1) {
            Column {
                Text(
                    "Frame speed: ${frameIntervalMs}ms",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = frameIntervalMs.toFloat(),
                    onValueChange = { viewModel.setFrameIntervalMs(context, it.toInt()) },
                    valueRange = GuardPrefs.MIN_FRAME_INTERVAL_MS.toFloat()..GuardPrefs.MAX_FRAME_INTERVAL_MS.toFloat()
                )
            }
        }
    }
}
