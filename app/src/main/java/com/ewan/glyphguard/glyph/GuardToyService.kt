package com.ewan.glyphguard.glyph

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * GuardToyService — the Always-on Glyph Toy Android component itself.
 *
 * Deliberately thin: all actual state and logic (the matrix connection, the
 * screen receiver, rendering, the pickup-timeout countdown) lives in
 * [GuardToyEngine], a process-scoped singleton — see its class doc for why.
 * This class exists only because Nothing's toy framework needs a bound
 * Service to talk to; onUnbind()/onDestroy() intentionally do nothing
 * beyond logging, since tearing down GuardToyEngine's state here would
 * defeat the whole point of hoisting it out of this instance's lifecycle.
 */
class GuardToyService : Service() {

    companion object {
        private const val TAG = "GuardToyService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        GuardToyEngine.ensureStarted(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return GuardToyEngine.messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        return false
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }
}
