package com.ewan.glyphguard.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ewan.glyphguard.viewmodel.MainViewModel

/**
 * Per-app pattern list (Apps tab) — pick an app to give it its own pattern,
 * shown during AOD whenever it has a pending notification. Selecting one
 * opens AppPatternEditorScreen. See MainViewModel.installedApps/loadInstalledApps.
 */
@Composable
fun AppPatternListScreen(
    viewModel: MainViewModel,
    onAppSelected: (packageName: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apps by viewModel.installedApps.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadInstalledApps(context) }

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

    val filtered = if (query.isBlank()) {
        apps
    } else {
        apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Text("Per-app patterns", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Give specific apps their own pattern, shown during AOD whenever they have " +
                "a pending notification — highest priority, above both Spotify-linked " +
                "behavior and the default pattern.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        Text(
            if (notificationAccessGranted) {
                "Notification access granted."
            } else {
                "Needs \"Notification access\" to detect pending notifications — reads only " +
                    "which app and when, never message content."
            },
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = { openNotificationAccessSettings(context) },
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        ) {
            Text("Open notification access settings")
        }

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
                    GlyphPreviewThumbnail(app.frames, app.intervalMs)
                    Column {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (app.hasCustomPattern) "Custom pattern" else "App icon",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider()
            }
        }
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
