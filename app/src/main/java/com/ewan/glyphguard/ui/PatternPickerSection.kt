package com.ewan.glyphguard.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ewan.glyphguard.glyph.GuardPrefs
import com.ewan.glyphguard.viewmodel.MainViewModel

/**
 * The Dot/Ring/None/Import-frames/Import-Museum/gallery pattern picker +
 * frame-count label + speed slider, reused by the global default
 * (MainScreen), the per-app editor (AppPatternEditorScreen), and the Music
 * tab's custom-design view — all three drive the same MainViewModel state
 * ([MainViewModel.frames]/[MainViewModel.frameIntervalMs]), which itself
 * decides where edits land based on [MainViewModel.editTarget].
 */
@Composable
fun PatternPickerSection(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val frames by viewModel.frames.collectAsState()
    val frameIntervalMs by viewModel.frameIntervalMs.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showGalleryPicker by remember { mutableStateOf(false) }

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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            OutlinedButton(onClick = { showSaveDialog = true }) {
                Text("Save to gallery…")
            }
            OutlinedButton(onClick = {
                viewModel.loadGallery(context)
                showGalleryPicker = true
            }) {
                Text("Load from gallery…")
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
                        viewModel.saveCurrentToGallery(context, name)
                        showSaveDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showGalleryPicker) {
        val galleryEntries by viewModel.galleryEntries.collectAsState()
        AlertDialog(
            onDismissRequest = { showGalleryPicker = false },
            title = { Text("Load from gallery") },
            text = {
                if (galleryEntries.isEmpty()) {
                    Text("Nothing saved yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn {
                        items(galleryEntries, key = { it.id }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.applyGalleryEntry(context, entry)
                                        showGalleryPicker = false
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                GlyphPreviewThumbnail(entry.frames, entry.intervalMs)
                                Column {
                                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (entry.frames.size > 1) {
                                            "${entry.frames.size} frames"
                                        } else {
                                            "1 frame"
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGalleryPicker = false }) { Text("Close") }
            }
        )
    }
}
