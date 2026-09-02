package com.ewan.glyphguard.glyph

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * ToggleReceiver — the hook external tools use to flip "Always-on enabled"
 * from outside the app entirely.
 *
 * There's no SDK/API for third-party apps to intercept the Essential Key
 * directly — Nothing doesn't expose it. The standard community workaround
 * is: disable Essential Space via ADB (frees the physical key), then use
 * an Accessibility-Service-based remapper like Key Mapper to detect the
 * key press and fire an action. This receiver is that action's target:
 * point Key Mapper's (or Tasker's) "long press → send broadcast/intent"
 * action at ACTION_TOGGLE, and it'll flip the always-on state and nudge
 * the running toy service to redraw immediately.
 *
 * This is a manifest-declared (not runtime-registered) receiver so it
 * works even if Glyph Guard's process isn't currently alive — Key Mapper
 * sending an explicit, package-targeted broadcast is exempt from Android's
 * implicit-broadcast background restrictions.
 */
class ToggleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE = "com.ewan.glyphguard.ACTION_TOGGLE_ALWAYS_ON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) return

        val newValue = !GuardPrefs.isEnabled(context)
        GuardPrefs.setEnabled(context, newValue)

        // Nudge the toy service (if it's currently bound/active) to redraw
        // right away rather than waiting for the next screen on/off event.
        val refresh = Intent(GuardToyEngine.ACTION_REFRESH).setPackage(context.packageName)
        context.sendBroadcast(refresh)
    }
}
