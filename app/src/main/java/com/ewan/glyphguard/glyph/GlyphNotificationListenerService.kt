package com.ewan.glyphguard.glyph

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Watches for notifications from apps the user has opted into a per-app
 * display — either a fully custom pattern (AppPatternPrefs) or the simpler
 * icon-flash list (IconNotifyPrefs) — and tracks which one, if any, should
 * currently be shown. Consumed only by GuardToyService during AOD
 * rendering — this service has no visible effect while the screen is on.
 *
 * Requires the user to grant "Notification access" manually in system
 * Settings (Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) — there's no
 * runtime-permission-style prompt for this. Reads only package name, post
 * time, and category/flags of active notifications — never title, text, or
 * other extras.
 */
class GlyphNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "GlyphNotifListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "onListenerConnected")
        recompute()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "onListenerDisconnected")
        // A revoked grant shouldn't leave a stale pattern showing forever.
        NotificationPatternState.pendingPackage = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isOptedIn(sbn.packageName)) recompute()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isOptedIn(sbn.packageName)) recompute()
    }

    private fun isOptedIn(packageName: String): Boolean =
        AppPatternPrefs.getMappedPackages(applicationContext).contains(packageName) ||
            IconNotifyPrefs.isEnabled(applicationContext, packageName)

    /**
     * Recomputes from getActiveNotifications() (the source of truth) rather
     * than incrementally tracking add/remove — avoids double-count/mis-clear
     * bugs. Excludes CATEGORY_TRANSPORT (media playback controls) so a mapped
     * music app's own now-playing notification doesn't permanently block the
     * audio visualizer while it plays — see GuardToyEngine.resolveFrameSource().
     */
    private fun recompute() {
        val mapped = AppPatternPrefs.getMappedPackages(applicationContext)
        val iconEnabled = IconNotifyPrefs.getEnabledPackages(applicationContext)
        val candidate = try {
            activeNotifications
                ?.filter { sbn ->
                    (mapped.contains(sbn.packageName) || iconEnabled.contains(sbn.packageName)) &&
                        sbn.notification.category != Notification.CATEGORY_TRANSPORT
                }
                ?.maxByOrNull { it.postTime }
        } catch (e: Exception) {
            Log.e(TAG, "recompute failed: ${e.message}", e)
            null
        }

        val newPending = candidate?.packageName
        val newPostTime = candidate?.postTime ?: 0L
        if (NotificationPatternState.pendingPackage != newPending ||
            NotificationPatternState.pendingPostTimeMs != newPostTime
        ) {
            NotificationPatternState.pendingPackage = newPending
            NotificationPatternState.pendingPostTimeMs = newPostTime
            Log.d(TAG, "pendingPackage -> $newPending")
            sendBroadcast(Intent(GuardToyEngine.ACTION_REFRESH).setPackage(packageName))
        }
    }
}
