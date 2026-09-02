package com.ewan.glyphguard.glyph

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.ewan.glyphguard.engine.AudioVisualizerEngine
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The actual Always-on Glyph Toy logic — living at process scope rather
 * than tied to GuardToyService's own Android Service instance.
 *
 * Confirmed via on-device logcat: Nothing's OS destroys and recreates
 * GuardToyService's Service instance periodically WHILE STILL IN AOD, not
 * only on wake — an onUnbind()/onDestroy() pair was observed ~9.5s after a
 * put-down with the screen still off, followed by a fresh
 * onCreate()/onBind() ~2s later, all within the same process (same PID
 * throughout). Anchoring state to the Service instance's own
 * onCreate()/onDestroy() (an earlier fix, still correct for surviving
 * flaky onBind()/onUnbind() churn on its own) wasn't enough: during that
 * ~2s gap between the old instance's onDestroy() and the new one's
 * onCreate(), the dynamically-registered screen receiver didn't exist at
 * all, silently dropping any real pickup landing in that window — which is
 * exactly why the pickup-timeout appeared to work "sometimes" rather than
 * consistently either working or failing.
 *
 * The fix: hoist all of this to a plain singleton object and register the
 * screen receiver against the stable applicationContext (not `this` on a
 * churning Service instance) exactly once for the process's whole
 * lifetime. GuardToyService becomes a thin shim: ensureStarted() on
 * onCreate(), hand back [messenger] on onBind(), otherwise do nothing on
 * onUnbind()/onDestroy() — tearing this down there would just reintroduce
 * the same gap.
 *
 * Accepted trade-off: if the user later deselects Glyph Guard as the AOD
 * toy entirely, this keeps running in the background (a cheap screen-on/off
 * listener, effectively inert once resolveFrameSource() has nothing to
 * render) rather than fully stopping, since there's no reliable way to
 * distinguish that from the OS's own transient churn.
 */
object GuardToyEngine {

    private const val TAG = "GuardToyEngine"
    const val ACTION_REFRESH = "com.ewan.glyphguard.REFRESH"

    private var appContext: Context? = null
    private var manager: GlyphMatrixManager? = null
    private var audioManager: AudioManager? = null
    private val engineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var clearJob: Job? = null
    private var animationJob: Job? = null
    private var audioEngine: AudioVisualizerEngine? = null
    private var receiverRegistered = false
    private var started = false

    // ---- Glyph Toy messenger protocol ----

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                super.handleMessage(msg)
                return
            }
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
            Log.d(TAG, "Toy event: $event")
            when (event) {
                GlyphToy.EVENT_AOD -> startAnimation()
                GlyphToy.EVENT_CHANGE -> { /* long-press on Glyph Button — unused */ }
            }
        }
    }

    val messenger = Messenger(handler)

    private val gmmCallback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            try {
                manager?.register(Glyph.DEVICE_25111p)
                startAnimation()
            } catch (e: Exception) {
                Log.e(TAG, "register() failed: ${e.message}", e)
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            // no-op — SDK will redeliver onServiceConnected if it reconnects
        }
    }

    // ---- Screen on/off receiver: this is what actually implements the timeout ----

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onPickedUp()
                Intent.ACTION_SCREEN_OFF -> onPutDown()
                ACTION_REFRESH -> startAnimation()
            }
        }
    }

    /** Idempotent — safe to call from every GuardToyService instance's onCreate(). */
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        Log.d(TAG, "ensureStarted")
        val app = context.applicationContext
        appContext = app
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        manager = GlyphMatrixManager.getInstance(app)?.also { it.init(gmmCallback) }
        registerScreenReceiver(app)
    }

    private fun onPickedUp() {
        val context = appContext ?: return
        clearJob?.cancel()
        val timeout = GuardPrefs.getTimeoutSeconds(context)
        Log.d(TAG, "onPickedUp: timeout=${timeout}s")
        if (timeout <= 0) {
            turnOff()
            return
        }
        clearJob = engineScope.launch {
            delay(timeout * 1000L)
            if (isActive) {
                Log.d(TAG, "timeout elapsed, clearing")
                turnOff()
            }
        }
    }

    private fun onPutDown() {
        // Back into AOD territory — cancel any pending clear and restart
        // playback (re-resolving the frame source), ready for the next wake cycle.
        Log.d(TAG, "onPutDown")
        clearJob?.cancel()
        startAnimation()
    }

    // ---- Frame source selection ----

    private sealed class FrameSource {
        data class Static(val frames: List<IntArray>, val intervalMs: Int) : FrameSource()
        object AudioReactive : FrameSource()
    }

    /**
     * Priority: a pending mapped-app notification beats the music-reactive
     * visualizer beats the default pattern. A mapped music app's own
     * now-playing notification doesn't get to permanently claim the top
     * slot — [GlyphNotificationListenerService] excludes
     * Notification.CATEGORY_TRANSPORT notifications when computing
     * [NotificationPatternState.pendingPackage], so the visualizer plays
     * through such notifications instead of being blocked by them; a
     * different (non-transport) notification from that same app still wins
     * normally.
     */
    private fun resolveFrameSource(context: Context): FrameSource {
        NotificationPatternState.pendingPackage?.let { pkg ->
            AppPatternPrefs.getPattern(context, pkg)?.let {
                return FrameSource.Static(it.frames, it.intervalMs)
            }
        }
        if (GuardPrefs.isMusicReactiveEnabled(context) &&
            hasRecordAudioPermission(context) &&
            audioManager?.isMusicActive == true
        ) {
            return FrameSource.AudioReactive
        }
        return FrameSource.Static(GuardPrefs.getFrames(context), GuardPrefs.getFrameIntervalMs(context))
    }

    private fun hasRecordAudioPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---- Rendering ----

    /**
     * (Re)starts playback from scratch: stops whatever's currently playing,
     * re-resolves the frame source, and starts the appropriate one. A
     * single stored frame just renders once (no point looping a delay on a
     * static image); 2+ frames loop at their configured interval until
     * [stopAllRendering] is called (pickup timeout, disabled, or teardown).
     */
    private fun startAnimation() {
        val context = appContext ?: return
        stopAllRendering()
        try {
            if (!GuardPrefs.isEnabled(context)) {
                manager?.turnOff()
                return
            }
            when (val source = resolveFrameSource(context)) {
                is FrameSource.Static -> playStatic(source.frames, source.intervalMs)
                FrameSource.AudioReactive -> playAudioReactive(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startAnimation failed: ${e.message}", e)
        }
    }

    private fun playStatic(frames: List<IntArray>, intervalMs: Int) {
        val context = appContext ?: return
        val brightness = GuardPrefs.getBrightness(context)
        val scaledFrames = frames.map { base -> IntArray(base.size) { i -> base[i] * brightness / 255 } }

        if (scaledFrames.size <= 1) {
            manager?.setMatrixFrame(scaledFrames.first())
            return
        }

        animationJob = engineScope.launch {
            var index = 0
            while (isActive) {
                manager?.setMatrixFrame(scaledFrames[index])
                index = (index + 1) % scaledFrames.size
                delay(intervalMs.toLong())
            }
        }
    }

    private fun playAudioReactive(context: Context) {
        val engine = AudioVisualizerEngine()
        val started = engine.start { rawFrame ->
            val brightness = GuardPrefs.getBrightness(context)
            val scaled = IntArray(rawFrame.size) { i -> rawFrame[i] * brightness / 255 }
            manager?.setMatrixFrame(scaled)
        }
        if (started) {
            audioEngine = engine
        } else {
            Log.w(TAG, "AudioVisualizerEngine failed to start, falling back to default pattern")
            playStatic(GuardPrefs.getFrames(context), GuardPrefs.getFrameIntervalMs(context))
        }
    }

    private fun stopAllRendering() {
        animationJob?.cancel()
        animationJob = null
        audioEngine?.stop()
        audioEngine = null
    }

    private fun turnOff() {
        stopAllRendering()
        try {
            manager?.turnOff()
        } catch (e: Exception) {
            Log.e(TAG, "turnOff failed: ${e.message}", e)
        }
    }

    // ---- Helpers ----

    private fun registerScreenReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_REFRESH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }
}
