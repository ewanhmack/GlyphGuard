package com.ewan.glyphguard.glyph

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.util.Log
import com.ewan.glyphguard.engine.AudioVisualizerEngine
import com.ewan.glyphguard.engine.FrameScaling
import com.ewan.glyphguard.engine.ImageToFrame
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
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    // Gravity is ~9.81 m/s^2; these leave slack for a phone that isn't lying
    // perfectly flat rather than requiring an exact +/-9.81 z reading.
    private const val FACE_DOWN_Z_THRESHOLD = -7f
    private const val FACE_UP_Z_THRESHOLD = 7f

    // A single instantaneous reading below the threshold also fires for a
    // hand that's merely tilted downward for a moment (e.g. lowering your
    // arm right after locking the screen) — require it to hold for this long
    // before treating it as an actual, deliberate face-down placement.
    private const val FACE_DOWN_CONFIRM_MS = 600L

    // How long a pending notification's pattern (custom or icon) stays on
    // screen after it posts, regardless of whether the notification itself
    // is still sitting in the tray — a brief "you got a notification" flash
    // rather than camping the display until manually swiped away.
    private const val NOTIFICATION_FLASH_MS = 5000L

    private var appContext: Context? = null
    private var manager: GlyphMatrixManager? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private val engineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var clearJob: Job? = null
    private var notificationFlashJob: Job? = null
    private var animationJob: Job? = null
    private var audioEngine: AudioVisualizerEngine? = null
    private var receiverRegistered = false
    private var sensorRegistered = false
    private var started = false

    /**
     * Only true once the accelerometer has actually confirmed the phone is
     * lying screen-down — this, not merely "screen off", is what gates
     * [startAnimation] actually rendering anything. Mirrors the system's own
     * "Flip to Glyph" naming: the display should engage on a real flip, not
     * on every unrelated screen-off (pocket, timeout while sitting face-up).
     */
    private var isFaceDown = false

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
                // Experiment: setGlyphMatrixTimeout delegates straight to the
                // system service with no visible implementation on our side —
                // untested whether "true" (unset default) applies some kind
                // of system-level dimming/standby independent of our own
                // pickup-timeout. Explicitly disabling it to see if raw
                // brightness values reach the hardware unattenuated.
                manager?.setGlyphMatrixTimeout(false)
                startAnimation()
            } catch (e: Exception) {
                Log.e(TAG, "register() failed: ${e.message}", e)
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            // no-op — SDK will redeliver onServiceConnected if it reconnects
        }
    }

    // ---- Screen on/off receiver: gates whether we're even listening for a flip ----

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                ACTION_REFRESH -> startAnimation()
            }
        }
    }

    // ---- Accelerometer: this is what actually implements "face down only" ----

    private var faceDownConfirmJob: Job? = null
    private var lastZ = 0f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val z = event.values[2]
            lastZ = z
            when {
                z < FACE_DOWN_Z_THRESHOLD && !isFaceDown && faceDownConfirmJob == null -> {
                    faceDownConfirmJob = engineScope.launch {
                        delay(FACE_DOWN_CONFIRM_MS)
                        faceDownConfirmJob = null
                        if (lastZ < FACE_DOWN_Z_THRESHOLD && !isFaceDown) {
                            isFaceDown = true
                            onFaceDown()
                        }
                    }
                }
                z > FACE_UP_Z_THRESHOLD -> {
                    faceDownConfirmJob?.cancel()
                    faceDownConfirmJob = null
                    if (isFaceDown) {
                        isFaceDown = false
                        onFaceUp()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Idempotent — safe to call from every GuardToyService instance's onCreate(). */
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        Log.d(TAG, "ensureStarted")
        val app = context.applicationContext
        appContext = app
        mediaSessionManager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        manager = GlyphMatrixManager.getInstance(app)?.also { it.init(gmmCallback) }
        registerScreenReceiver(app)
        // Covers the toy service (re)starting while the screen is already off
        // (e.g. process churn) — otherwise we'd wait forever for a SCREEN_OFF
        // broadcast that already happened.
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) registerSensor()
    }

    private fun onScreenOn() {
        unregisterSensor()
        isFaceDown = false
        onPickedUp()
    }

    private fun onScreenOff() {
        registerSensor()
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

    /** Confirmed face-down (accelerometer, screen already off) — this is the real "put down". */
    private fun onFaceDown() {
        Log.d(TAG, "onFaceDown")
        clearJob?.cancel()
        startAnimation()
    }

    /** Flipped back face-up without unlocking — treat like a pickup: same timeout, not an instant clear. */
    private fun onFaceUp() {
        Log.d(TAG, "onFaceUp")
        onPickedUp()
    }

    // ---- Frame source selection ----

    private sealed class FrameSource {
        data class Static(val frames: List<IntArray>, val intervalMs: Int) : FrameSource()
        object AudioReactive : FrameSource()
    }

    /**
     * Priority: a pending notification's custom pattern (AppPatternPrefs)
     * beats Spotify-linked behavior beats that same pending app's own icon
     * (IconNotifyPrefs) beats the default pattern. [freshPendingPackage] is
     * already gated to the last [NOTIFICATION_FLASH_MS] by [startAnimation]
     * — by the time it's stale this just behaves as if nothing were
     * pending. A mapped music app's own now-playing notification doesn't
     * get to permanently claim the top slot — [GlyphNotificationListenerService]
     * excludes Notification.CATEGORY_TRANSPORT notifications when computing
     * [NotificationPatternState.pendingPackage], so Spotify-linked frames
     * play through such notifications instead of being blocked by them; a
     * different (non-transport) notification from that same app still wins
     * normally. The icon fallback sits below Spotify-linked behavior
     * specifically so a stray non-transport Spotify notification can't
     * knock the live visualizer/custom pattern out for its icon while
     * music's actually playing.
     */
    private fun resolveFrameSource(context: Context, freshPendingPackage: String?): FrameSource {
        freshPendingPackage?.let { pkg ->
            AppPatternPrefs.getPattern(context, pkg)?.let {
                return FrameSource.Static(it.frames, it.intervalMs)
            }
        }
        when (MusicPrefs.getMode(context)) {
            MusicPrefs.Mode.OFF -> {}
            MusicPrefs.Mode.VISUALIZER ->
                if (isSpotifyPlaying(context, requireLocal = true) && hasRecordAudioPermission(context)) {
                    return FrameSource.AudioReactive
                }
            MusicPrefs.Mode.CUSTOM ->
                if (isSpotifyPlaying(context)) {
                    return FrameSource.Static(MusicPrefs.getFrames(context), MusicPrefs.getFrameIntervalMs(context))
                }
        }
        freshPendingPackage?.let { pkg ->
            if (IconNotifyPrefs.isEnabled(context, pkg)) {
                ImageToFrame.fromInstalledApp(context, pkg)?.let {
                    return FrameSource.Static(listOf(it), GuardPrefs.DEFAULT_FRAME_INTERVAL_MS)
                }
            }
        }
        return FrameSource.Static(GuardPrefs.getFrames(context), GuardPrefs.getFrameIntervalMs(context))
    }

    private fun hasRecordAudioPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Reuses the notification-listener access already granted for per-app
     * patterns — getActiveSessions() only requires that grant, not a
     * separate permission. Returns false (not an error) if Spotify isn't
     * running, isn't the active session, or access hasn't been granted.
     *
     * [requireLocal] additionally requires playback to be genuinely local
     * (MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) — the visualizer
     * specifically needs that, since Visualizer(0) only ever sees audio
     * actually mixed on this device. Spotify Connect (casting playback to a
     * separate speaker/device while this phone just remote-controls it)
     * still reports STATE_PLAYING on its MediaSession even though no audio
     * ever flows through this phone at all, which otherwise reads as
     * "playing" while the visualizer has nothing whatsoever to capture —
     * not needed for the CUSTOM-pattern tier, which doesn't capture audio.
     */
    private fun isSpotifyPlaying(context: Context, requireLocal: Boolean = false): Boolean {
        val msm = mediaSessionManager ?: return false
        return try {
            val listener = ComponentName(context, GlyphNotificationListenerService::class.java)
            msm.getActiveSessions(listener).any { controller ->
                controller.packageName == SPOTIFY_PACKAGE &&
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    (!requireLocal ||
                        controller.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)
            }
        } catch (e: Exception) {
            false
        }
    }

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
            if (!GuardPrefs.isEnabled(context) || !isFaceDown) {
                manager?.turnOff()
                return
            }
            val pending = NotificationPatternState.pendingPackage
            val ageMs = System.currentTimeMillis() - NotificationPatternState.pendingPostTimeMs
            val freshPending = pending?.takeIf { ageMs < NOTIFICATION_FLASH_MS }
            if (freshPending != null) {
                // Re-resolve right when the flash window closes, rather than
                // waiting for the next unrelated event (pickup, EVENT_AOD's
                // own ~60s tick, another notification) to notice it expired.
                notificationFlashJob = engineScope.launch {
                    delay(NOTIFICATION_FLASH_MS - ageMs)
                    startAnimation()
                }
            }
            when (val source = resolveFrameSource(context, freshPending)) {
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
        val scaledFrames = FrameScaling.scaleFrames(frames, brightness)

        if (scaledFrames.size <= 1) {
            manager?.setMatrixFrame(FrameScaling.toHardwareRange(scaledFrames.first()))
            return
        }

        animationJob = engineScope.launch {
            var index = 0
            while (isActive) {
                manager?.setMatrixFrame(FrameScaling.toHardwareRange(scaledFrames[index]))
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
            manager?.setMatrixFrame(FrameScaling.toHardwareRange(scaled))
        }
        if (started) {
            audioEngine = engine
        } else {
            Log.w(TAG, "AudioVisualizerEngine failed to start, falling back to default pattern")
            playStatic(GuardPrefs.getFrames(context), GuardPrefs.getFrameIntervalMs(context))
        }
    }

    private fun stopAllRendering() {
        notificationFlashJob?.cancel()
        notificationFlashJob = null
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

    private fun registerSensor() {
        if (sensorRegistered) return
        val sensor = accelerometer ?: return
        sensorManager?.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorRegistered = true
    }

    private fun unregisterSensor() {
        faceDownConfirmJob?.cancel()
        faceDownConfirmJob = null
        if (!sensorRegistered) return
        sensorManager?.unregisterListener(sensorListener)
        sensorRegistered = false
    }
}
