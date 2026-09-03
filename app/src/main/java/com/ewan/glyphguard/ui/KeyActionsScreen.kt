package com.ewan.glyphguard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.ewan.glyphguard.glyph.KeyActionPrefs.KeyAction
import com.ewan.glyphguard.glyph.KeyActionPrefs.KeyTrigger
import com.ewan.glyphguard.viewmodel.MainViewModel

private val TRIGGER_LABELS = mapOf(
    KeyTrigger.SINGLE to "Single press",
    KeyTrigger.DOUBLE to "Double press",
    KeyTrigger.LONG to "Long press",
)

private val ACTION_LABELS = mapOf(
    KeyAction.NONE to "None",
    KeyAction.TOGGLE_ALWAYS_ON to "Toggle Always-on",
    KeyAction.TOGGLE_FLASHLIGHT to "Toggle flashlight",
    KeyAction.MEDIA_PLAY_PAUSE to "Play/pause music",
    KeyAction.MEDIA_NEXT to "Next track",
    KeyAction.MEDIA_PREVIOUS to "Previous track",
    KeyAction.OPEN_APP to "Open app",
)

/**
 * Essential Key tab — what each press type does. Nothing still doesn't
 * expose the key itself (see ToggleReceiver/KeyActionReceiver), so this only
 * configures the *mapping*; wiring Key Mapper to actually fire each trigger
 * is a one-time manual step documented in the README.
 */
@Composable
fun KeyActionsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mappings by viewModel.keyMappings.collectAsState()
    val apps by viewModel.installedApps.collectAsState()
    var appPickerFor by remember { mutableStateOf<KeyTrigger?>(null) }

    LaunchedEffect(Unit) { viewModel.loadInstalledApps(context) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Essential Key", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Nothing doesn't expose the Essential Key to third-party apps directly, so each " +
                "press type below still needs its own Key Mapper rule pointed at the matching " +
                "broadcast — see the README's \"Wiring up the Essential Key\" section. This screen " +
                "just controls what happens once that broadcast arrives.",
            style = MaterialTheme.typography.bodySmall
        )

        KeyTrigger.entries.forEach { trigger ->
            val mapping = mappings[trigger]
            Column {
                Text(TRIGGER_LABELS.getValue(trigger), style = MaterialTheme.typography.titleMedium)
                ActionDropdown(
                    selected = mapping?.action ?: KeyAction.NONE,
                    onSelected = { viewModel.setKeyAction(context, trigger, it) },
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (mapping?.action == KeyAction.OPEN_APP) {
                    OutlinedButton(
                        onClick = { appPickerFor = trigger },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(mapping.targetLabel ?: "Choose app")
                    }
                }
            }
            HorizontalDivider()
        }
    }

    val pickerTrigger = appPickerFor
    if (pickerTrigger != null) {
        AppPickerDialog(
            apps = apps,
            onDismiss = { appPickerFor = null },
            onPicked = { pkg ->
                viewModel.setKeyActionApp(context, pickerTrigger, pkg)
                appPickerFor = null
            }
        )
    }
}

@Composable
private fun ActionDropdown(
    selected: KeyAction,
    onSelected: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(ACTION_LABELS.getValue(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            KeyAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(ACTION_LABELS.getValue(action)) },
                    onClick = {
                        onSelected(action)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<MainViewModel.AppInfo>,
    onDismiss: () -> Unit,
    onPicked: (packageName: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.heightIn(max = 500.dp).padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(app.packageName) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                bitmap = app.icon.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
