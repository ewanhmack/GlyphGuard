package com.ewan.glyphguard.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ewan.glyphguard.viewmodel.MainViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenAppPatterns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsState()
    val timeoutSeconds by viewModel.timeoutSeconds.collectAsState()
    val brightness by viewModel.brightness.collectAsState()
    val musicReactiveEnabled by viewModel.musicReactiveEnabled.collectAsState()
    val previewFrame by viewModel.previewFrame.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

    val recordAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setMusicReactiveEnabled(context, true)
        } else {
            android.widget.Toast.makeText(
                context,
                "Microphone permission denied — music-reactive AOD needs it to read current audio output, not to record anything.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessGranted = isNotificationAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Glyph Guard", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (isConnected) "Connected to Glyph Matrix" else "Glyph Matrix not connected (open on your 4a Pro)",
            style = MaterialTheme.typography.bodySmall
        )

        GlyphSimulatorView(frame = previewFrame)

        // ---- 1) Master always-on control ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Always-on enabled", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Full override — off means off, regardless of the AOD toy slot",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { viewModel.setEnabled(context, it) }
            )
        }

        // ---- 2) Timeout after unflip ----
        Column {
            Text(
                "Turn off ${timeoutSeconds}s after pickup",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "0 = clear the instant the screen wakes",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = timeoutSeconds.toFloat(),
                onValueChange = { viewModel.setTimeoutSeconds(context, it.toInt()) },
                valueRange = 0f..60f,
                steps = 59
            )
        }

        // ---- 3) Brightness ----
        Column {
            Text("Brightness: $brightness / 255", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = brightness.toFloat(),
                onValueChange = { viewModel.setBrightness(context, it.toInt()) },
                valueRange = 0f..255f,
                steps = 254
            )
        }

        // ---- 4) Default pattern ----
        PatternPickerSection(viewModel = viewModel)

        // ---- 5) Per-app patterns ----
        Text("Per-app patterns", style = MaterialTheme.typography.titleMedium)
        Text(
            "Give specific apps their own pattern, shown during AOD whenever they have " +
                "a pending notification — highest priority, above both the visualizer and " +
                "the default pattern above (except a mapped music app's own now-playing " +
                "notification, which doesn't block the visualizer below).",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onOpenAppPatterns) {
            Text("Assign patterns per app…")
        }
        Text(
            if (notificationAccessGranted) {
                "Notification access granted."
            } else {
                "Needs \"Notification access\" to detect pending notifications — reads only " +
                    "which app and when, never message content."
            },
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { openNotificationAccessSettings(context) }) {
            Text("Open notification access settings")
        }

        // ---- 6) Music-reactive visualizer ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Music-reactive AOD", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Live EQ-style animation while music plays, instead of the default " +
                        "pattern (unless a pending app notification is showing). Uses the " +
                        "microphone permission as the technical mechanism for reading " +
                        "current audio output — Glyph Guard never records or accesses " +
                        "microphone input.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = musicReactiveEnabled,
                onCheckedChange = { turnOn ->
                    if (!turnOn) {
                        viewModel.setMusicReactiveEnabled(context, false)
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.setMusicReactiveEnabled(context, true)
                    } else {
                        recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }

        // ---- System toy manager shortcut ----
        Button(onClick = { openGlyphToyManager(context) }) {
            Text("Open system Glyph Toy manager")
        }
        Text(
            "Select \"Glyph Guard\" there once, under Always-on Glyph Toy — everything " +
                "else on this screen updates live after that.",
            style = MaterialTheme.typography.bodySmall
        )

        // ---- External toggle (Essential Key via Key Mapper, Tasker, etc.) ----
        Text("External toggle", style = MaterialTheme.typography.titleMedium)
        Text(
            "Any tool that can send an explicit broadcast — Key Mapper, Tasker — can " +
                "flip Always-on by targeting this action. Useful for remapping a long " +
                "press of the Essential Key once it's freed via ADB.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { copyAdbTestCommand(context) }) {
            Text("Copy ADB test command")
        }
    }
}

/** Lets you sanity-check the toggle from a PC before wiring up Key Mapper. */
private fun copyAdbTestCommand(context: android.content.Context) {
    val command = "adb shell am broadcast -a com.ewan.glyphguard.ACTION_TOGGLE_ALWAYS_ON " +
        "-p com.ewan.glyphguard"
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Glyph Guard ADB toggle", command))
}

/**
 * Deep-links into Nothing's own "Manage Glyph Toys" screen, per the
 * documented best-practice intent (system version 20250829+). Falls back
 * silently if unavailable on older system builds.
 */
private fun openGlyphToyManager(context: android.content.Context) {
    try {
        val intent = Intent().apply {
            component = android.content.ComponentName(
                "com.nothing.thirdparty",
                "com.nothing.thirdparty.matrix.toys.manager.ToysManagerActivity"
            )
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Older system version — ask the user to navigate manually.
    }
}

private fun isNotificationAccessGranted(context: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** No runtime-permission-style prompt exists for notification access — only this Settings deep link. */
private fun openNotificationAccessSettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (e: Exception) {
        // No-op — extremely unlikely to be missing on any real device.
    }
}
