package com.ewan.glyphguard.glyph

import android.content.Context

/**
 * KeyActionPrefs — what each Essential Key press-type should do.
 *
 * There's still no SDK hook for the key itself (see ToggleReceiver's class
 * doc for why), but Key Mapper can distinguish single/double/long press and
 * fire a distinct broadcast per [KeyTrigger] — this is what stores which
 * [KeyAction] each of those three is currently mapped to, read by
 * [KeyActionReceiver] when one of those broadcasts arrives.
 */
object KeyActionPrefs {

    private const val PREFS_NAME = "glyph_guard_key_actions"

    enum class KeyTrigger(val broadcastAction: String) {
        SINGLE("com.ewan.glyphguard.ACTION_KEY_SINGLE"),
        DOUBLE("com.ewan.glyphguard.ACTION_KEY_DOUBLE"),
        LONG("com.ewan.glyphguard.ACTION_KEY_LONG"),
    }

    enum class KeyAction {
        NONE, TOGGLE_ALWAYS_ON, TOGGLE_FLASHLIGHT, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, OPEN_APP
    }

    fun getAction(context: Context, trigger: KeyTrigger): KeyAction {
        val stored = prefs(context).getString(actionKey(trigger), null) ?: return KeyAction.NONE
        return try {
            KeyAction.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            KeyAction.NONE
        }
    }

    fun setAction(context: Context, trigger: KeyTrigger, action: KeyAction) {
        prefs(context).edit().putString(actionKey(trigger), action.name).apply()
    }

    /** Only meaningful while [getAction] for this trigger is OPEN_APP. */
    fun getTargetPackage(context: Context, trigger: KeyTrigger): String? =
        prefs(context).getString(packageKey(trigger), null)

    fun setTargetPackage(context: Context, trigger: KeyTrigger, packageName: String?) {
        prefs(context).edit().putString(packageKey(trigger), packageName).apply()
    }

    private fun actionKey(trigger: KeyTrigger) = "action_${trigger.name}"
    private fun packageKey(trigger: KeyTrigger) = "package_${trigger.name}"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
