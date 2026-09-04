package com.ewan.glyphguard.glyph

import android.content.Context

/**
 * Simple opt-in list of packages that should flash their own icon on a
 * pending notification — no pattern to draw or import, just "show me when
 * this app notifies me." Starts empty. Separate from [AppPatternPrefs] (a
 * fully custom, hand-drawn/imported pattern per app) — a package can be in
 * either, neither, or both, with AppPatternPrefs taking priority when both
 * apply (see GuardToyEngine.resolveFrameSource).
 */
object IconNotifyPrefs {

    private const val PREFS_NAME = "glyph_guard_icon_notify"
    private const val KEY_PACKAGES = "packages"

    fun getEnabledPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()

    fun isEnabled(context: Context, packageName: String): Boolean =
        getEnabledPackages(context).contains(packageName)

    fun add(context: Context, packageName: String) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, getEnabledPackages(context) + packageName).apply()
    }

    fun remove(context: Context, packageName: String) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, getEnabledPackages(context) - packageName).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
