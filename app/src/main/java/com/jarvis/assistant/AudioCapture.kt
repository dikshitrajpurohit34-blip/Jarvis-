package com.jarvis.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Microphone capture using AudioRecord.
 *
 * Audio format: PCM 16-bit mono at 16 kHz, VOICE_RECOGNITION source.
 * Frames are ~100 ms each (1600 samples * 2 bytes = 3200 bytes).
 *
 * Capture runs on a dedicated daemon thread. Public methods are
 * idempotent and thread-safe. The capture never requests permissions and
 * never performs speech recognition.
 *
 * Shutdown contract:
 *   - [stop] guarantees that no new [onFrame] invocation can begin after
 *     it returns.
 *   - If a callback is already in flight, [stop] waits for it to finish
 *     (bounded by the callback's own duration).
 *   - [stop] is safe to call from any thread, including from inside the
 *     capture thread (e.g. from within the callback itself).
 */
class AudioCapture {

    companion object {
        private const val TAG = "JARVIS"
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 3_200 // 100 ms of PCM16 mono at 16 kHz

        /** Bounded join timeout for the capture thread during stop(). */
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
    }

    private val lock = Any()
    private val callbackLock = Any()

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var stopping = false

    /**
     * Start capturing. Throws [IllegalStateException] if already running,
     * if the AudioRecord cannot be initialized, or if the mic is
     * unavailable (for example, permission not granted).
     *
     * The [onFrame] callback is invoked on the capture thread. It should
     * be short and non-blocking (the intended use is a WebSocket send).
     */
    @SuppressLint("MissingPermission")
    fun start(onFrame: (ByteArray) -> Unit) {
        synchronized(lock) {
            if (running) throw IllegalStateException("AudioCapture already started")
            stopping = false

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                throw IllegalStateException(
                    "Invalid minimum AudioRecord buffer size: $minBuf"
                )
            }

            val bufferBytes = maxOf(minBuf, FRAME_BYTES * 2)

            val rec: AudioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "Failed to construct AudioRecord: ${t.javaClass.simpleName}",
                    t
                )
            }

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                try { rec.release() } catch (_: Throwable) {}
                throw IllegalStateException(
                    "AudioRecord not initialized (microphone unavailable or permission not granted)"
                )
            }

            try {
                rec.startRecording()
            } catch (t: IllegalStateException) {
                try { rec.release() } catch (_: Throwable) {}
                throw IllegalStateException("Failed to start AudioRecord", t)
            }

            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try { rec.release() } catch (_: Throwable) {}
                throw IllegalStateException("AudioRecord did not enter recording state")
            }

            recorder = rec
            running = true

            val thread = Thread({
                val buf = ByteArray(FRAME_BYTES)
                try {
                    while (running && !stopping) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            // Dispatch the frame under callbackLock so that
                            // stop() cannot return while a callback is in
                            // progress and no new callback can begin once
                            // stopping is set.
                            synchronized(callbackLock) {
                                if (stopping) return@Thread
                                try {
                                    onFrame(buf.copyOf(n))
                                } catch (t: Throwable) {
                                    Log.w(TAG, "onFrame threw", t)
                                }
                            }
                        } else if (n == AudioRecord.ERROR_INVALID_OPERATION) {
                            // Expected after stop() from another thread.
                            break
                        } else if (n < 0) {
                            Log.e(TAG, "AudioRecord.read returned error: $n")
                            break
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Audio capture thread terminated", t)
                }
            }, "jarvis-audio-capture")
            thread.isDaemon = true
            captureThread = thread
            thread.start()
        }
    }

    /**
     * Stop capturing and release the AudioRecord.
     *
     * Idempotent and safe to call from any thread. Ensures no new
     * [onFrame] invocation begins after this call returns. If a callback
     * is in progress, waits for it to complete; that wait is bounded by
     * the callback's own duration (the callback must be non-blocking).
     */
    fun stop() {
        // Phase 1 — signal shutdown and guarantee no callback begins after
        // this block completes. Holding callbackLock here means stop() either
        // acquires it before any further callback begins (the thread then
        // sees stopping == true and skips the callback), or waits for an
        // already-running callback to finish and then acquires it. Either
        // way, once this synchronized block exits, no callback can start.
        synchronized(callbackLock) {
            stopping = true
        }

        val rec: AudioRecord?
        val thread: Thread?
        synchronized(lock) {
            if (!running && recorder == null) return
            running = false
            rec = recorder
            recorder = null
            thread = captureThread
            captureThread = null
        }

        // If we were called from the capture thread (e.g. from within the
        // callback), joining ourselves would deadlock. The capture loop
        // will observe stopping == true on its next iteration and exit.
        // It is still safe to stop and release the recorder here because
        // we are between reads (inside the callback, not inside rec.read).
        if (thread != null && thread === Thread.currentThread()) {
            rec?.let {
                try { it.stop() } catch (_: Throwable) {}
                try { it.release() } catch (_: Throwable) {}
            }
            return
        }

        // Phase 2 — unblock any in-flight rec.read() on the capture thread.
        rec?.let {
            try { it.stop() } catch (_: Throwable) {}
        }

        // Phase 3 — bounded join. The thread is guaranteed to exit promptly
        // because running/stopping are already set and rec.stop() unblocks
        // read(). The timeout guards against pathological OEM behaviour.
        thread?.let {
            try {
                it.join(THREAD_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        // Phase 4 — release the recorder.
        rec?.let {
            try { it.release() } catch (_: Throwable) {}
        }
    }

    fun isRunning(): Boolean = running
}
