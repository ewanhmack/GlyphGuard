package com.ewan.glyphguard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ewan.glyphguard.viewmodel.MainViewModel

/**
 * Lists installed launchable apps so the user can assign each a custom glyph
 * pattern. Selecting one opens AppPatternEditorScreen. See
 * MainViewModel.installedApps/loadInstalledApps.
 */
@Composable
fun AppPatternListScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAppSelected: (packageName: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apps by viewModel.installedApps.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadInstalledApps(context) }

    val filtered = if (query.isBlank()) {
        apps
    } else {
        apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Assign patterns per app",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        Text(
            "Pick an app to give it its own pattern, shown during AOD whenever it has " +
                "a pending notification. Apps without one still show the default.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth()
        )

        if (apps.isEmpty()) {
            Text(
                "Loading installed apps…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAppSelected(app.packageName, app.label) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        bitmap = app.icon.toBitmap().asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                    Column {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (app.hasCustomPattern) "Custom pattern" else "Default",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
