package com.ewan.glyphguard.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ewan.glyphguard.glyph.MusicPrefs
import com.ewan.glyphguard.viewmodel.MainViewModel

/**
 * What to show while Spotify is actively playing — off, the audio-reactive
 * visualizer (needs RECORD_AUDIO), or a custom design (needs nothing extra,
 * see MainViewModel.startEditingMusic). The trigger itself is Spotify's own
 * media session (GuardToyEngine.isSpotifyPlaying), not generic audio output.
 */
@Composable
fun MusicScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mode by viewModel.musicMode.collectAsState()
    val previewFrame by viewModel.previewFrame.collectAsState()

    val recordAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setMusicMode(context, MusicPrefs.Mode.VISUALIZER)
        } else {
            android.widget.Toast.makeText(
                context,
                "Microphone permission denied — the visualizer needs it to read current audio output, not to record anything.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    val options = listOf(
        MusicPrefs.Mode.OFF to "Off",
        MusicPrefs.Mode.VISUALIZER to "Visualizer",
        MusicPrefs.Mode.CUSTOM to "Custom design",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Music", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Engages only while Spotify is actively playing — checked via Spotify's " +
                "own now-playing media session, using the same notification access as " +
                "per-app patterns. Pausing or closing Spotify hands control back to " +
                "per-app patterns or the default pattern.",
            style = MaterialTheme.typography.bodySmall
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (optionMode, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    selected = mode == optionMode,
                    onClick = {
                        if (optionMode == MusicPrefs.Mode.VISUALIZER) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.setMusicMode(context, MusicPrefs.Mode.VISUALIZER)
                            } else {
                                recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            viewModel.setMusicMode(context, optionMode)
                        }
                    },
                    label = { Text(label) }
                )
            }
        }

        when (mode) {
            MusicPrefs.Mode.OFF -> {}
            MusicPrefs.Mode.VISUALIZER -> {
                val visualizerFrame by viewModel.visualizerPreviewFrame.collectAsState()
                DisposableEffect(Unit) {
                    viewModel.startVisualizerPreview(context)
                    onDispose { viewModel.stopVisualizerPreview() }
                }
                Text(
                    "Live EQ-style animation while Spotify plays, instead of the default " +
                        "pattern (unless an added app has a pending notification). Glyph " +
                        "Guard never records or accesses microphone input beyond reading " +
                        "current audio output for the visualizer.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "This preview runs live off whatever's currently playing on the phone " +
                        "(not just Spotify) so you can see it react right now — on the " +
                        "matrix itself it only engages while Spotify is actively playing.",
                    style = MaterialTheme.typography.bodySmall
                )
                GlyphSimulatorView(frame = visualizerFrame)
            }
            MusicPrefs.Mode.CUSTOM -> {
                LaunchedEffect(Unit) { viewModel.startEditingMusic(context) }
                DisposableEffect(Unit) { onDispose { viewModel.stopEditingTarget(context) } }
                GlyphSimulatorView(frame = previewFrame)
                PatternPickerSection(viewModel = viewModel)
            }
        }
    }
}
