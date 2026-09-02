package com.ewan.glyphguard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ewan.glyphguard.viewmodel.MainViewModel

/**
 * Saved designs tab (MainViewModel.galleryEntries / GalleryPrefs) — tap one
 * to apply it as the default pattern, or delete it with the trailing icon.
 * A standalone tab, so it always targets the default pattern (see
 * MainViewModel.applyGalleryEntryToDefault) rather than whatever editing
 * session might otherwise be active — use "Load from gallery…" inside the
 * per-app editor or Music tab to apply a saved design there instead.
 */
@Composable
fun GalleryScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val entries by viewModel.galleryEntries.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadGallery(context) }

    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Text("Gallery", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Saved designs — tap one to use it as the default pattern. \"Save to " +
                "gallery…\" on the Main tab (or the per-app/music editors) adds the " +
                "pattern currently shown there.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        if (entries.isEmpty()) {
            Text(
                "Nothing saved yet.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.id }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.applyGalleryEntryToDefault(context, entry) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlyphPreviewThumbnail(entry.frames, entry.intervalMs)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (entry.frames.size > 1) {
                                "${entry.frames.size} frames — ${entry.intervalMs}ms/frame"
                            } else {
                                "1 frame — static"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { viewModel.deleteGalleryEntry(context, entry.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete \"${entry.name}\"")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
