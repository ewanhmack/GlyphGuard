package com.ewan.glyphguard.glyph

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.ewan.glyphguard.glyph.KeyActionPrefs.KeyAction
import com.ewan.glyphguard.glyph.KeyActionPrefs.KeyTrigger

/**
 * KeyActionReceiver — the target for Key Mapper's three Essential Key
 * triggers (single/double/long press → [KeyTrigger.broadcastAction]).
 * Wired up the same way as [ToggleReceiver] (see its class doc for why a
 * broadcast receiver is the only option at all) but dispatches to whatever
 * [KeyAction] the user configured per trigger in [KeyActionPrefs], instead
 * of doing one fixed thing.
 */
class KeyActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeyActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = KeyTrigger.entries.firstOrNull { it.broadcastAction == intent.action } ?: return
        when (KeyActionPrefs.getAction(context, trigger)) {
            KeyAction.NONE -> Unit
            KeyAction.TOGGLE_ALWAYS_ON -> toggleAlwaysOn(context)
            KeyAction.TOGGLE_FLASHLIGHT -> toggleFlashlight(context)
            KeyAction.MEDIA_PLAY_PAUSE -> withActiveMediaController(context) {
                if (it.playbackState?.state == PlaybackState.STATE_PLAYING) it.transportControls.pause()
                else it.transportControls.play()
            }
            KeyAction.MEDIA_NEXT -> withActiveMediaController(context) { it.transportControls.skipToNext() }
            KeyAction.MEDIA_PREVIOUS -> withActiveMediaController(context) { it.transportControls.skipToPrevious() }
            KeyAction.OPEN_APP -> openApp(context, trigger)
        }
    }

    private fun toggleAlwaysOn(context: Context) {
        GuardPrefs.setEnabled(context, !GuardPrefs.isEnabled(context))
        context.sendBroadcast(Intent(GuardToyEngine.ACTION_REFRESH).setPackage(context.packageName))
    }

    /**
     * Torch state isn't ours to track — the Quick Settings tile or another
     * app can flip it independently — so the current state is read from the
     * system callback (which reports it immediately on registration) rather
     * than a locally-remembered guess that could drift out of sync.
     */
    private fun toggleFlashlight(context: Context) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            null
        } ?: return

        val pending = goAsync()
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(camId: String, enabled: Boolean) {
                if (camId != cameraId) return
                cameraManager.unregisterTorchCallback(this)
                try {
                    cameraManager.setTorchMode(cameraId, !enabled)
                } catch (e: Exception) {
                    Log.e(TAG, "setTorchMode failed: ${e.message}", e)
                }
                pending.finish()
            }

            override fun onTorchModeUnavailable(camId: String) {
                if (camId != cameraId) return
                cameraManager.unregisterTorchCallback(this)
                pending.finish()
            }
        }
        cameraManager.registerTorchCallback(callback, null)
    }

    /**
     * First active, controllable media session — reuses the notification
     * listener access [GlyphNotificationListenerService] already needs for
     * per-app patterns, rather than requiring a second permission grant
     * just for this.
     */
    private fun withActiveMediaController(context: Context, action: (MediaController) -> Unit) {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        try {
            val listener = ComponentName(context, GlyphNotificationListenerService::class.java)
            msm.getActiveSessions(listener).firstOrNull()?.let(action)
        } catch (e: Exception) {
            Log.w(TAG, "No active media session (notification access not granted?): ${e.message}")
        }
    }

    private fun openApp(context: Context, trigger: KeyTrigger) {
        val pkg = KeyActionPrefs.getTargetPackage(context, trigger) ?: return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't launch $pkg: ${e.message}", e)
        }
    }
}
