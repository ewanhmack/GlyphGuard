package com.ewan.glyphguard.glyph

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps GlyphMatrixManager for APP-MODE control (setAppMatrixFrame /
 * closeAppMatrix) — this is what powers the "Preview" button in the
 * settings screen. The actual Always-on behaviour is driven separately by
 * [GuardToyService], which uses toy-mode (setMatrixFrame) since that's what
 * the system's AOD carousel expects.
 */
class GlyphController {

    companion object {
        private const val TAG = "GlyphController"
    }

    private var manager: GlyphMatrixManager? = null
    private var initialized = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val callback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            try {
                manager?.register(Glyph.DEVICE_25111p)
                // See GuardToyEngine's identical call for why — untested
                // whether the unset default applies some system-level
                // dimming independent of the raw brightness values we send.
                manager?.setGlyphMatrixTimeout(false)
                _isConnected.value = true
            } catch (e: Exception) {
                Log.e(TAG, "register() failed: ${e.message}", e)
                _isConnected.value = false
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            _isConnected.value = false
        }
    }

    fun init(context: Context) {
        if (initialized) return
        try {
            val gmm = GlyphMatrixManager.getInstance(context.applicationContext)
            if (gmm == null) {
                Log.w(TAG, "GlyphMatrixManager unavailable — not a Nothing device?")
                return
            }
            manager = gmm
            gmm.init(callback)
            initialized = true
        } catch (e: Exception) {
            Log.w(TAG, "SDK not available: ${e.message}")
        }
    }

    /** Push a raw 13x13 (169-value) frame for live preview. */
    fun displayFrame(frame: IntArray) {
        if (!_isConnected.value) return
        try {
            manager?.setAppMatrixFrame(frame)
        } catch (e: Exception) {
            Log.e(TAG, "setAppMatrixFrame failed: ${e.message}", e)
        }
    }

    fun clear() {
        if (!_isConnected.value) return
        try {
            manager?.closeAppMatrix()
        } catch (e: Exception) {
            Log.e(TAG, "closeAppMatrix failed: ${e.message}", e)
        }
    }

    fun close() {
        try {
            if (_isConnected.value) manager?.closeAppMatrix()
            manager?.unInit()
        } catch (e: Exception) {
            Log.e(TAG, "close() failed: ${e.message}", e)
        } finally {
            manager = null
            initialized = false
            _isConnected.value = false
        }
    }
}
