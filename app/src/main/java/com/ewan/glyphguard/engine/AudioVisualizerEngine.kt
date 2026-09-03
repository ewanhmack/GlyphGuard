package com.ewan.glyphguard.engine

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Wraps android.media.audiofx.Visualizer attached to audio session 0 (the
 * device's global output mix — captures whatever's currently playing across
 * all apps) to drive a live 13-column bar-graph EQ on the Glyph Matrix.
 * Requires RECORD_AUDIO + MODIFY_AUDIO_SETTINGS; no MediaProjection consent
 * flow needed, since this reads the overall output mix rather than one
 * specific app's stream.
 *
 * Visualizer delivers capture callbacks on whichever thread constructs it
 * and must have a Looper — the caller (GuardToyService, running on
 * Dispatchers.Default) has none, and the main thread would mean a binder
 * call (setMatrixFrame) on every ~15Hz tick, risking UI jank. So this runs
 * its own dedicated HandlerThread for the Visualizer's entire lifetime.
 *
 * One instance per active AOD-with-music session — see
 * GuardToyEngine.playAudioReactive(). Not shared/reused across sessions.
 */
class AudioVisualizerEngine {

    companion object {
        private const val TAG = "AudioVisualizer"
        private const val TARGET_CAPTURE_RATE_MHZ = 15_000 // ~15 Hz, plenty for a 13x13 matrix
        private const val BANDS = MatrixSize.SIZE
        private const val START_TIMEOUT_SECONDS = 2L

        /**
         * Magnitude-to-brightness scale factor, applied to sqrt(peak) rather
         * than peak directly (see fftToFrame) — raw FFT magnitude is heavily
         * skewed toward bass, so a linear scale left every other band too
         * dim to clear paintColumn's rounding and show more than its single
         * bottom-edge LED, which looked like only one row was ever lit.
         * sqrt-compression brings quieter bands up into a visible range
         * without letting loud ones dominate. Bumped up again after
         * on-device feedback that 20.0 still wasn't sensitive enough to
         * quieter passages — was 2.2, linear, before the sqrt-compression fix.
         */
        private const val MAGNITUDE_SCALE = 36.0
    }

    private var handlerThread: HandlerThread? = null
    private var visualizer: Visualizer? = null

    /**
     * Starts capturing and invokes [onFrame] (on the internal background
     * thread — the caller must not assume main-thread delivery) with a
     * fresh 169-value frame (0-255 raw brightness, unscaled by the user's
     * brightness preference — the caller applies that uniformly, same as
     * every other frame source) on every capture tick.
     *
     * Blocks briefly (bounded, up to [START_TIMEOUT_SECONDS]) while setup
     * happens on the background thread, so this has a synchronous
     * true/false result to act on. Returns false without throwing if the
     * Visualizer can't be created/enabled (permission not actually granted
     * despite the caller's own check, no audio session available, setup
     * timed out, etc.) so the caller can fall back cleanly to the default
     * pattern.
     */
    fun start(onFrame: (IntArray) -> Unit): Boolean {
        val thread = HandlerThread("GlyphAudioVisualizer").also { it.start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var success = false

        handler.post {
            try {
                val v = Visualizer(0)
                val maxCaptureSize = Visualizer.getCaptureSizeRange()[1]
                v.captureSize = maxCaptureSize.coerceAtMost(1024)
                val rate = min(TARGET_CAPTURE_RATE_MHZ, Visualizer.getMaxCaptureRate())
                v.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            // Unused — FFT drives the EQ mapping instead.
                        }

                        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            if (fft != null) onFrame(fftToFrame(fft))
                        }
                    },
                    rate,
                    false,
                    true,
                )
                v.enabled = true
                visualizer = v
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "start timed out")
            success = false
        }

        if (success) {
            handlerThread = thread
        } else {
            thread.quitSafely()
        }
        return success
    }

    fun stop() {
        val thread = handlerThread
        val v = visualizer
        val latch = CountDownLatch(1)
        if (thread != null && v != null) {
            Handler(thread.looper).post {
                try {
                    v.enabled = false
                    v.release()
                } catch (e: Exception) {
                    Log.w(TAG, "stop failed: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
            latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        thread?.quitSafely()
        handlerThread = null
        visualizer = null
    }

    /**
     * Groups FFT magnitude bins into BANDS bands, weighted toward finer
     * resolution at low frequencies and coarser at high (matches how pitch
     * is perceived, and keeps bass transients visually distinct), then maps
     * each band's magnitude to a bar mirrored outward from the center row of
     * that column's real LED span (MatrixSize.ROW_LED_COUNTS, reused here by
     * row/column symmetry) — a soundwave/spectrum look, quiet audio reading
     * as a thin center line rather than a bar hugging one edge.
     */
    private fun fftToFrame(fft: ByteArray): IntArray {
        val frame = IntArray(MatrixSize.FRAME_LENGTH)
        val bins = fft.size / 2
        if (bins < BANDS) return frame

        // fft[0] = Re(0) (DC), fft[1] = Re(N/2) (Nyquist); for k=1..bins-1: fft[2k]=Re(k), fft[2k+1]=Im(k).
        val magnitudes = DoubleArray(bins)
        for (k in 1 until bins) {
            val re = fft[2 * k].toInt()
            val im = fft[2 * k + 1].toInt()
            magnitudes[k] = sqrt((re * re + im * im).toDouble())
        }

        val maxBin = (bins - 1).coerceAtLeast(1)
        for (band in 0 until BANDS) {
            val startBin = bandEdge(band, maxBin)
            val endBin = bandEdge(band + 1, maxBin).coerceAtLeast(startBin)
            var peak = 0.0
            for (k in startBin..endBin) peak = max(peak, magnitudes[k])

            val brightness = (sqrt(peak) * MAGNITUDE_SCALE).roundToInt().coerceIn(0, 255)
            paintColumn(frame, band, brightness)
        }
        return frame
    }

    /** Power-curve band edge (exponent 1.5): compresses low bands, expands high ones. */
    private fun bandEdge(band: Int, maxBin: Int): Int =
        (1 + (maxBin - 1) * (band.toDouble() / BANDS).pow(1.5)).roundToInt().coerceIn(1, maxBin)

    private fun paintColumn(frame: IntArray, column: Int, brightness: Int) {
        if (brightness <= 0) return
        val size = MatrixSize.SIZE
        val count = MatrixSize.ROW_LED_COUNTS[column]
        if (count == 0) return
        // Every column's real LED span is centered on the grid's middle row
        // (ROW_LED_COUNTS is symmetric, and every entry is odd), so growing
        // the bar outward from there in both directions at once needs no
        // assumption about which physical direction is "up" — unlike the
        // single-edge version this replaced, which had that backwards.
        val center = size / 2
        val halfSpan = count / 2
        val lit = halfSpan * brightness / 255
        for (offset in 0..lit) {
            frame[(center - offset) * size + column] = brightness
            frame[(center + offset) * size + column] = brightness
        }
    }
}
