package com.ewan.glyphguard.glyph

/**
 * In-memory "which mapped app currently has a pattern-worthy notification
 * pending" state — written by GlyphNotificationListenerService, read by
 * GuardToyEngine.resolveFrameSource() during AOD rendering only.
 *
 * [pendingPostTimeMs] is when that notification actually posted (its own
 * postTime, not when we noticed it) — GuardToyEngine treats pendingPackage
 * as active only for a few seconds after that, rather than for as long as
 * the notification sits in the tray, so a per-app pattern reads as "you got
 * a notification" rather than parking there until manually swiped away.
 *
 * Deliberately NOT persisted to SharedPreferences: this is inherently
 * transient (a stale flag surviving a process restart or reboot would just
 * be wrong), and onListenerConnected() already re-seeds it from
 * getActiveNotifications() on every reconnect, including after a process
 * restart. Relies on GlyphNotificationListenerService and GuardToyService
 * running in the same process — neither declares android:process, so this
 * holds by default; don't add one to either.
 */
object NotificationPatternState {
    @Volatile
    var pendingPackage: String? = null
    @Volatile
    var pendingPostTimeMs: Long = 0L
}
