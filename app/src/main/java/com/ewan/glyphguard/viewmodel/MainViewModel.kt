package com.ewan.glyphguard.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewan.glyphguard.engine.AudioVisualizerEngine
import com.ewan.glyphguard.engine.DefaultFrames
import com.ewan.glyphguard.engine.FrameScaling
import com.ewan.glyphguard.engine.GlyphMuseumFormat
import com.ewan.glyphguard.engine.ImageToFrame
import com.ewan.glyphguard.engine.MatrixSize
import com.ewan.glyphguard.glyph.AppPatternPrefs
import com.ewan.glyphguard.glyph.GalleryPrefs
import com.ewan.glyphguard.glyph.GlyphController
import com.ewan.glyphguard.glyph.GuardPrefs
import com.ewan.glyphguard.glyph.IconNotifyPrefs
import com.ewan.glyphguard.glyph.KeyActionPrefs
import com.ewan.glyphguard.glyph.MusicPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val glyph = GlyphController()

    private val _enabled = MutableStateFlow(GuardPrefs.isEnabled(application))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _timeoutSeconds = MutableStateFlow(GuardPrefs.getTimeoutSeconds(application))
    val timeoutSeconds: StateFlow<Int> = _timeoutSeconds.asStateFlow()

    private val _brightness = MutableStateFlow(GuardPrefs.getBrightness(application))
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _frames = MutableStateFlow(GuardPrefs.getFrames(application))
    val frames: StateFlow<List<IntArray>> = _frames.asStateFlow()

    private val _frameIntervalMs = MutableStateFlow(GuardPrefs.getFrameIntervalMs(application))
    val frameIntervalMs: StateFlow<Int> = _frameIntervalMs.asStateFlow()

    private val _musicMode = MutableStateFlow(MusicPrefs.getMode(application))
    val musicMode: StateFlow<MusicPrefs.Mode> = _musicMode.asStateFlow()

    /**
     * What [frames]/[frameIntervalMs] and every pattern-editing method below
     * (useBuiltIn/importImages/importGlyphMuseumFile/setFrameIntervalMs/
     * applyGalleryEntry) currently read/write. Default = the global default
     * pattern (GuardPrefs) — i.e. everything behaves exactly as before this
     * concept existed. Set via [startEditingApp]/[startEditingMusic],
     * cleared via [stopEditingTarget].
     */
    sealed class EditTarget {
        object Default : EditTarget()
        data class App(val packageName: String) : EditTarget()
        object Music : EditTarget()
    }

    private val _editTarget = MutableStateFlow<EditTarget>(EditTarget.Default)
    val editTarget: StateFlow<EditTarget> = _editTarget.asStateFlow()

    data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val hasCustomPattern: Boolean,
        val iconNotifyEnabled: Boolean,
        val frames: List<IntArray>,
        val intervalMs: Int,
    ) {
        /** In the per-app list at all — either a full custom pattern or just the simple icon-flash opt-in. */
        val isAdded: Boolean get() = hasCustomPattern || iconNotifyEnabled
    }

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()
    private var installedAppsLoaded = false

    private val _galleryEntries = MutableStateFlow<List<GalleryPrefs.Entry>>(emptyList())
    val galleryEntries: StateFlow<List<GalleryPrefs.Entry>> = _galleryEntries.asStateFlow()

    /** What one Essential Key trigger is currently mapped to — see KeyActionReceiver. */
    data class KeyMapping(
        val action: KeyActionPrefs.KeyAction,
        val targetPackage: String?,
        val targetLabel: String?,
    )

    private val _keyMappings = MutableStateFlow(loadKeyMappings(application))
    val keyMappings: StateFlow<Map<KeyActionPrefs.KeyTrigger, KeyMapping>> = _keyMappings.asStateFlow()

    /**
     * What the simulator/preview should actually show right now. Cycles
     * through [frames] at [frameIntervalMs] so an imported sequence previews
     * as an animation, same as it'll play on the real matrix.
     */
    val previewFrame: StateFlow<IntArray> = MutableStateFlow(scaledPreviewFrame(0)).also { flow ->
        viewModelScope.launch {
            var index = 0
            while (true) {
                delay(_frameIntervalMs.value.toLong().coerceAtLeast(GuardPrefs.MIN_FRAME_INTERVAL_MS.toLong()))
                index = (index + 1) % _frames.value.size
                flow.value = scaledPreviewFrame(index)
            }
        }
    }

    val isConnected: StateFlow<Boolean> get() = glyph.isConnected

    /**
     * Live preview of the music visualizer for the Music tab — a separate
     * AudioVisualizerEngine instance from GuardToyEngine's own AOD one, since
     * this runs foreground/screen-on and that one only runs face-down/AOD;
     * the two states are mutually exclusive in practice, so there's no real
     * contention, just no reason to couple this screen to the AOD engine.
     */
    private var previewAudioEngine: AudioVisualizerEngine? = null
    private val _visualizerPreviewFrame = MutableStateFlow(IntArray(MatrixSize.FRAME_LENGTH))
    val visualizerPreviewFrame: StateFlow<IntArray> = _visualizerPreviewFrame.asStateFlow()

    fun startVisualizerPreview(context: Context) {
        if (previewAudioEngine != null) return
        val engine = AudioVisualizerEngine()
        val started = engine.start { rawFrame ->
            val brightness = GuardPrefs.getBrightness(context)
            _visualizerPreviewFrame.value = IntArray(rawFrame.size) { i -> rawFrame[i] * brightness / 255 }
        }
        if (started) previewAudioEngine = engine
    }

    fun stopVisualizerPreview() {
        previewAudioEngine?.stop()
        previewAudioEngine = null
        _visualizerPreviewFrame.value = IntArray(MatrixSize.FRAME_LENGTH)
    }

    init {
        glyph.init(application)
    }

    fun setEnabled(context: Context, value: Boolean) {
        _enabled.value = value
        GuardPrefs.setEnabled(context, value)
        if (!value) glyph.clear() else pushPreview()
    }

    fun setTimeoutSeconds(context: Context, seconds: Int) {
        _timeoutSeconds.value = seconds
        GuardPrefs.setTimeoutSeconds(context, seconds)
    }

    fun setBrightness(context: Context, value: Int) {
        _brightness.value = value
        GuardPrefs.setBrightness(context, value)
        pushPreview()
    }

    fun setMusicMode(context: Context, mode: MusicPrefs.Mode) {
        _musicMode.value = mode
        MusicPrefs.setMode(context, mode)
    }

    fun setFrameIntervalMs(context: Context, ms: Int) {
        _frameIntervalMs.value = ms
        persistFrameIntervalMs(context, ms)
    }

    fun useBuiltIn(context: Context, pattern: BuiltInPattern) {
        val f = when (pattern) {
            BuiltInPattern.DOT -> DefaultFrames.dot()
            BuiltInPattern.RING -> DefaultFrames.ring()
            BuiltInPattern.NONE -> DefaultFrames.blank()
        }
        _frames.value = listOf(f)
        persistFrames(context, listOf(f))
        pushPreview()
    }

    /** Downsamples an installed app's own launcher icon into a single static frame. */
    fun useAppIcon(context: Context, packageName: String) {
        val frame = ImageToFrame.fromInstalledApp(context, packageName) ?: return
        _frames.value = listOf(frame)
        persistFrames(context, listOf(frame))
        pushPreview()
    }

    /** Import one or more frames, in the order picked, as an animation sequence. */
    fun importImages(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val imported = uris.mapNotNull { ImageToFrame.fromUri(context.contentResolver, it) }
        if (imported.isEmpty()) return
        _frames.value = imported
        persistFrames(context, imported)
        pushPreview()
    }

    /**
     * Import a design exported from Glyph Museum (glyphmuseum.com/developers format).
     * Its first frame's duration (if present) becomes the shared frame speed — Museum
     * supports a different duration per frame, but GlyphGuard only has one shared speed
     * today, so per-frame timing beyond that isn't preserved.
     */
    fun importGlyphMuseumFile(context: Context, uri: Uri): Result<Unit> {
        return try {
            val json = context.contentResolver.openInputStream(uri)
                ?.use { it.bufferedReader().readText() }
                ?: return Result.failure(Exception("Couldn't open that file."))
            val parsed = GlyphMuseumFormat.parse(json)
            _frames.value = parsed.map { it.pixels }
            persistFrames(context, parsed.map { it.pixels })
            parsed.first().durationMs?.let { setFrameIntervalMs(context, it) }
            pushPreview()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Saves the pattern currently shown (whatever [frames]/[frameIntervalMs] hold right now) under [name]. */
    fun saveCurrentToGallery(context: Context, name: String) {
        if (name.isBlank()) return
        GalleryPrefs.save(context, name.trim(), _frames.value, _frameIntervalMs.value)
        loadGallery(context)
    }

    /** Re-reads the gallery — cheap (one SharedPreferences blob), safe to call every time the screen opens. */
    fun loadGallery(context: Context) {
        _galleryEntries.value = GalleryPrefs.getAll(context)
    }

    /** Loads a saved design into whatever [editTarget] currently is (default, an app, or music). */
    fun applyGalleryEntry(context: Context, entry: GalleryPrefs.Entry) {
        _frames.value = entry.frames
        _frameIntervalMs.value = entry.intervalMs
        persistFrames(context, entry.frames)
        persistFrameIntervalMs(context, entry.intervalMs)
        pushPreview()
    }

    /** Gallery is a standalone tab with no "current editing session" — always targets Default. */
    fun applyGalleryEntryToDefault(context: Context, entry: GalleryPrefs.Entry) {
        _editTarget.value = EditTarget.Default
        _frames.value = entry.frames
        _frameIntervalMs.value = entry.intervalMs
        GuardPrefs.setFrames(context, entry.frames)
        GuardPrefs.setFrameIntervalMs(context, entry.intervalMs)
        pushPreview()
    }

    fun deleteGalleryEntry(context: Context, id: String) {
        GalleryPrefs.remove(context, id)
        loadGallery(context)
    }

    /**
     * Switches [frames]/[frameIntervalMs] and every editing method to this app's mapping.
     * An app with no custom pattern yet starts from its own icon rather than a plain dot —
     * still nothing but a live preview until something's actually changed/saved.
     */
    fun startEditingApp(context: Context, packageName: String) {
        _editTarget.value = EditTarget.App(packageName)
        val existing = AppPatternPrefs.getPattern(context, packageName)
        _frames.value = existing?.frames
            ?: listOf(ImageToFrame.fromInstalledApp(context, packageName) ?: DefaultFrames.dot())
        _frameIntervalMs.value = existing?.intervalMs ?: GuardPrefs.DEFAULT_FRAME_INTERVAL_MS
        pushPreview()
    }

    /** Switches [frames]/[frameIntervalMs] and every editing method to the Spotify-linked custom design. */
    fun startEditingMusic(context: Context) {
        _editTarget.value = EditTarget.Music
        _frames.value = MusicPrefs.getFrames(context)
        _frameIntervalMs.value = MusicPrefs.getFrameIntervalMs(context)
        pushPreview()
    }

    /** Lightweight opt-in — no editor, just "flash this app's own icon on its notifications" (IconNotifyPrefs). */
    fun addAppToNotifyList(context: Context, packageName: String) {
        IconNotifyPrefs.add(context, packageName)
        installedAppsLoaded = false
        loadInstalledApps(context)
    }

    /** Removes an app from the per-app list entirely -- both the simple icon opt-in and any full custom pattern. */
    fun removeAppFromNotifyList(context: Context, packageName: String) {
        IconNotifyPrefs.remove(context, packageName)
        AppPatternPrefs.removePattern(context, packageName)
        installedAppsLoaded = false
        loadInstalledApps(context)
    }

    /** Reverts [frames]/[frameIntervalMs] to the global default. Must be called when leaving the app/music editor. */
    fun stopEditingTarget(context: Context) {
        _editTarget.value = EditTarget.Default
        _frames.value = GuardPrefs.getFrames(context)
        _frameIntervalMs.value = GuardPrefs.getFrameIntervalMs(context)
        installedAppsLoaded = false // a mapping may have just changed — refresh "Custom"/"Default" labels
        pushPreview()
    }

    /** Loads once per app-list visit (cheap enough to re-run, but no need to repeat while unchanged). */
    fun loadInstalledApps(context: Context) {
        if (installedAppsLoaded) return
        installedAppsLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val mapped = AppPatternPrefs.getMappedPackages(context)
            val iconEnabled = IconNotifyPrefs.getEnabledPackages(context)
            val defaultFrames = GuardPrefs.getFrames(context)
            val defaultIntervalMs = GuardPrefs.getFrameIntervalMs(context)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { resolveInfo ->
                    try {
                        val pkg = resolveInfo.activityInfo.packageName
                        val custom = AppPatternPrefs.getPattern(context, pkg)
                        // Same fallback GuardToyEngine uses at runtime for a pending
                        // notification with no custom pattern -- the app's own icon,
                        // not the shared default -- so this preview matches what
                        // adding it (or a real notification once added) would show.
                        val fallbackFrames = ImageToFrame.fromInstalledApp(context, pkg)
                            ?.let { listOf(it) } ?: defaultFrames
                        AppInfo(
                            packageName = pkg,
                            label = resolveInfo.loadLabel(pm).toString(),
                            icon = resolveInfo.loadIcon(pm),
                            hasCustomPattern = mapped.contains(pkg),
                            iconNotifyEnabled = iconEnabled.contains(pkg),
                            frames = custom?.frames ?: fallbackFrames,
                            intervalMs = custom?.intervalMs ?: defaultIntervalMs,
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
            _installedApps.value = apps
        }
    }

    fun setKeyAction(context: Context, trigger: KeyActionPrefs.KeyTrigger, action: KeyActionPrefs.KeyAction) {
        KeyActionPrefs.setAction(context, trigger, action)
        if (action != KeyActionPrefs.KeyAction.OPEN_APP) KeyActionPrefs.setTargetPackage(context, trigger, null)
        _keyMappings.value = loadKeyMappings(context)
    }

    fun setKeyActionApp(context: Context, trigger: KeyActionPrefs.KeyTrigger, packageName: String) {
        KeyActionPrefs.setTargetPackage(context, trigger, packageName)
        _keyMappings.value = loadKeyMappings(context)
    }

    private fun loadKeyMappings(context: Context): Map<KeyActionPrefs.KeyTrigger, KeyMapping> {
        val pm = context.packageManager
        return KeyActionPrefs.KeyTrigger.entries.associateWith { trigger ->
            val pkg = KeyActionPrefs.getTargetPackage(context, trigger)
            val label = pkg?.let {
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull()
            }
            KeyMapping(KeyActionPrefs.getAction(context, trigger), pkg, label)
        }
    }

    /** Push the current pattern's first frame to the matrix for a quick live check (app-mode). */
    fun pushPreview() {
        if (!_enabled.value) return
        // scaledPreviewFrame() is 0-255 -- shared with the on-screen simulator, which needs
        // that range. The real matrix wants ~0-4095 (see FrameScaling.toHardwareRange), so
        // that conversion happens here, right at the hardware boundary, not upstream.
        glyph.displayFrame(FrameScaling.toHardwareRange(scaledPreviewFrame(0)))
    }

    fun stopPreview() {
        glyph.clear()
    }

    /** Pushes an arbitrary live-drawn frame straight to the matrix (app-mode) -- doesn't touch [frames]/persisted state. */
    fun pushLiveFrame(frame: IntArray) {
        val peak = FrameScaling.peakOf(listOf(frame)).coerceAtLeast(1)
        val scaled = FrameScaling.scaleFrame(frame, _brightness.value, peak)
        glyph.displayFrame(FrameScaling.toHardwareRange(scaled))
    }

    fun clearLiveDraw() {
        glyph.clear()
    }

    fun saveLiveFrameToGallery(context: Context, name: String, frame: IntArray) {
        if (name.isBlank()) return
        GalleryPrefs.save(context, name.trim(), listOf(frame), GuardPrefs.DEFAULT_FRAME_INTERVAL_MS)
        loadGallery(context)
    }

    private fun persistFrames(context: Context, frames: List<IntArray>) {
        when (val target = _editTarget.value) {
            is EditTarget.App -> AppPatternPrefs.setPattern(context, target.packageName, frames, _frameIntervalMs.value)
            EditTarget.Music -> MusicPrefs.setFrames(context, frames)
            EditTarget.Default -> GuardPrefs.setFrames(context, frames)
        }
    }

    private fun persistFrameIntervalMs(context: Context, ms: Int) {
        when (val target = _editTarget.value) {
            is EditTarget.App -> AppPatternPrefs.setPattern(context, target.packageName, _frames.value, ms)
            EditTarget.Music -> MusicPrefs.setFrameIntervalMs(context, ms)
            EditTarget.Default -> GuardPrefs.setFrameIntervalMs(context, ms)
        }
    }

    private fun scaledPreviewFrame(index: Int): IntArray {
        val list = _frames.value
        val peak = FrameScaling.peakOf(list)
        return FrameScaling.scaleFrame(list[index % list.size], _brightness.value, peak)
    }

    override fun onCleared() {
        glyph.close()
        previewAudioEngine?.stop()
        super.onCleared()
    }

    enum class BuiltInPattern { DOT, RING, NONE }
}
