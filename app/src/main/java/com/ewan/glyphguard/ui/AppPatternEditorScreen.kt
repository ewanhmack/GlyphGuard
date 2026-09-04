package com.ewan.glyphguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ewan.glyphguard.viewmodel.MainViewModel

/**
 * Editor for one app's pattern — GlyphSimulatorView preview + the same
 * PatternPickerSection used for the global default, bound to this app via
 * MainViewModel.editTarget. MainViewModel.stopEditingTarget() runs
 * automatically whenever this composable leaves composition (see the
 * DisposableEffect below), regardless of which exit path was used.
 */
@Composable
fun AppPatternEditorScreen(
    viewModel: MainViewModel,
    packageName: String,
    label: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewFrame by viewModel.previewFrame.collectAsState()

    LaunchedEffect(packageName) { viewModel.startEditingApp(context, packageName) }
    // Runs on any exit path -- the back button below, the system back
    // gesture (once MainActivity's BackHandler clears the drill-in), or
    // this composable simply leaving composition for any other reason --
    // so the default pattern's simulator/labels never keep showing this
    // app's pattern after leaving.
    DisposableEffect(Unit) { onDispose { viewModel.stopEditingTarget(context) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                label,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        Text(
            "Shown during AOD whenever $label has a pending notification (and no other " +
                "mapped app's notification is more recent, and music isn't currently " +
                "playing through it) — otherwise the default pattern shows as usual.",
            style = MaterialTheme.typography.bodySmall
        )

        GlyphSimulatorView(frame = previewFrame)

        PatternPickerSection(viewModel = viewModel)

        OutlinedButton(onClick = {
            viewModel.removeAppFromNotifyList(context, packageName)
            onDone()
        }) {
            Text("Remove from list")
        }
    }
}
