package com.ewan.glyphguard.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewan.glyphguard.engine.DefaultFrames
import com.ewan.glyphguard.engine.GlyphMuseumFormat
import com.ewan.glyphguard.engine.ImageToFrame
import com.ewan.glyphguard.glyph.AppPatternPrefs
import com.ewan.glyphguard.glyph.GlyphController
import com.ewan.glyphguard.glyph.GuardPrefs
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

    private val _musicReactiveEnabled = MutableStateFlow(GuardPrefs.isMusicReactiveEnabled(application))
    val musicReactiveEnabled: StateFlow<Boolean> = _musicReactiveEnabled.asStateFlow()

    /**
     * null = the global default pattern (GuardPrefs) is being edited/shown —
     * i.e. everything on MainScreen behaves exactly as before this feature.
     * Non-null = [frames]/[frameIntervalMs] and every pattern-editing method
     * below (useBuiltIn/importImages/importGlyphMuseumFile/setFrameIntervalMs)
     * read/write this specific app's mapping (AppPatternPrefs) instead. Set
     * via [startEditingApp], cleared via [stopEditingApp].
     */
    private val _editingPackage = MutableStateFlow<String?>(null)
    val editingPackage: StateFlow<String?> = _editingPackage.asStateFlow()

    data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val hasCustomPattern: Boolean,
    )

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()
    private var installedAppsLoaded = false

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

    init {
        glyph.init(application)
    }

    fun setEnabled(context: android.content.Context, value: Boolean) {
        _enabled.value = value
        GuardPrefs.setEnabled(context, value)
        if (!value) glyph.clear() else pushPreview()
    }

    fun setTimeoutSeconds(context: android.content.Context, seconds: Int) {
        _timeoutSeconds.value = seconds
        GuardPrefs.setTimeoutSeconds(context, seconds)
    }

    fun setBrightness(context: android.content.Context, value: Int) {
        _brightness.value = value
        GuardPrefs.setBrightness(context, value)
        pushPreview()
    }

    fun setMusicReactiveEnabled(context: android.content.Context, enabled: Boolean) {
        _musicReactiveEnabled.value = enabled
        GuardPrefs.setMusicReactiveEnabled(context, enabled)
    }

    fun setFrameIntervalMs(context: android.content.Context, ms: Int) {
        _frameIntervalMs.value = ms
        persistFrameIntervalMs(context, ms)
    }

    fun useBuiltIn(context: android.content.Context, pattern: BuiltInPattern) {
        val f = when (pattern) {
            BuiltInPattern.DOT -> DefaultFrames.dot()
            BuiltInPattern.RING -> DefaultFrames.ring()
            BuiltInPattern.NONE -> DefaultFrames.blank()
        }
        _frames.value = listOf(f)
        persistFrames(context, listOf(f))
        pushPreview()
    }

    /** Import one or more frames, in the order picked, as an animation sequence. */
    fun importImages(context: android.content.Context, uris: List<Uri>) {
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
    fun importGlyphMuseumFile(context: android.content.Context, uri: Uri): Result<Unit> {
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

    /** Switches [frames]/[frameIntervalMs] and every editing method to this app's mapping. */
    fun startEditingApp(context: android.content.Context, packageName: String) {
        _editingPackage.value = packageName
        val existing = AppPatternPrefs.getPattern(context, packageName)
        _frames.value = existing?.frames ?: listOf(DefaultFrames.dot())
        _frameIntervalMs.value = existing?.intervalMs ?: GuardPrefs.DEFAULT_FRAME_INTERVAL_MS
        pushPreview()
    }

    /** Un-maps an app back to the default pattern. If it's the one currently being edited, resets the editor too. */
    fun removeAppPattern(context: android.content.Context, packageName: String) {
        AppPatternPrefs.removePattern(context, packageName)
        if (_editingPackage.value == packageName) {
            _frames.value = listOf(DefaultFrames.dot())
            _frameIntervalMs.value = GuardPrefs.DEFAULT_FRAME_INTERVAL_MS
            pushPreview()
        }
    }

    /** Reverts [frames]/[frameIntervalMs] to the global default. Must be called when leaving the per-app editor. */
    fun stopEditingApp(context: android.content.Context) {
        _editingPackage.value = null
        _frames.value = GuardPrefs.getFrames(context)
        _frameIntervalMs.value = GuardPrefs.getFrameIntervalMs(context)
        installedAppsLoaded = false // a mapping may have just changed — refresh "Custom"/"Default" labels
        pushPreview()
    }

    /** Loads once per app-list visit (cheap enough to re-run, but no need to repeat while unchanged). */
    fun loadInstalledApps(context: android.content.Context) {
        if (installedAppsLoaded) return
        installedAppsLoaded = true
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val mapped = AppPatternPrefs.getMappedPackages(context)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { resolveInfo ->
                    try {
                        val pkg = resolveInfo.activityInfo.packageName
                        AppInfo(
                            packageName = pkg,
                            label = resolveInfo.loadLabel(pm).toString(),
                            icon = resolveInfo.loadIcon(pm),
                            hasCustomPattern = mapped.contains(pkg),
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
            _installedApps.value = apps
        }
    }

    /** Push whatever the preview is currently showing to the matrix for a quick live check (app-mode). */
    fun pushPreview() {
        if (!_enabled.value) return
        glyph.displayFrame(previewFrame.value)
    }

    fun stopPreview() {
        glyph.clear()
    }

    private fun persistFrames(context: android.content.Context, frames: List<IntArray>) {
        val pkg = _editingPackage.value
        if (pkg != null) {
            AppPatternPrefs.setPattern(context, pkg, frames, _frameIntervalMs.value)
        } else {
            GuardPrefs.setFrames(context, frames)
        }
    }

    private fun persistFrameIntervalMs(context: android.content.Context, ms: Int) {
        val pkg = _editingPackage.value
        if (pkg != null) {
            AppPatternPrefs.setPattern(context, pkg, _frames.value, ms)
        } else {
            GuardPrefs.setFrameIntervalMs(context, ms)
        }
    }

    private fun scaledPreviewFrame(index: Int): IntArray {
        val list = _frames.value
        return scale(list[index % list.size], _brightness.value)
    }

    private fun scale(f: IntArray, brightness: Int): IntArray =
        IntArray(f.size) { i -> f[i] * brightness / 255 }

    override fun onCleared() {
        glyph.close()
        super.onCleared()
    }

    enum class BuiltInPattern { DOT, RING, NONE }
}
