package com.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MP3 playback backed by Android's public MediaPlayer API.
 *
 * Threading contract:
 *   - The MediaPlayer instance is created on `Dispatchers.Main.immediate`.
 *   - Every MediaPlayer operation (construction, setAudioAttributes,
 *     setDataSource, setOn*Listener, prepareAsync, start, stop, release)
 *     therefore runs on the main thread.
 *   - External entry points (`stop()` and the continuation's cancellation
 *     handler) may be invoked on any thread; both marshal their work onto
 *     the main thread via [mainHandler].
 *   - Setup is aborted before any further MediaPlayer call if the
 *     coroutine has been cancelled while setup is in progress.
 *
 * Lifecycle contract:
 *   - The temporary MP3 file is created in the app cache directory.
 *   - The temp file reference is captured BEFORE any operation that can
 *     fail or be cancelled, so the outer try/finally guarantees cleanup
 *     of the file on every exit path: successful completion, playback
 *     error, setup failure, file-write failure, stop(), and coroutine
 *     cancellation.
 *   - The MediaPlayer is stopped (best effort) and released exactly once.
 *   - [play] suspends until playback completes, fails, or is cancelled.
 *
 * No Android TextToSpeech is used anywhere in this class.
 */
class AudioPlayer(private val appContext: Context) {

    private companion object {
        const val TAG = "JARVIS"
        const val TEMP_PREFIX = "jarvis_tts_"
        const val TEMP_SUFFIX = ".mp3"
    }

    /**
     * Main-thread handler used to marshal MediaPlayer work from `stop()`
     * and from the continuation's cancellation handler, both of which can
     * run on arbitrary threads.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()

    /**
     * The stop handler of the currently active [play] call, or null when
     * nothing is playing. Guarded by [lock].
     */
    @Volatile
    private var activeStop: (() -> Unit)? = null

    /**
     * Suspend until playback of [mp3] completes, fails, or is cancelled.
     * Cancelling the calling coroutine, or calling [stop], stops and
     * releases the MediaPlayer and deletes the temporary file.
     */
    suspend fun play(mp3: ByteArray) {
        if (mp3.isEmpty()) {
            throw IllegalArgumentException("Empty MP3 data")
        }

        // The File reference is captured BEFORE any operation that can
        // fail or be cancelled, so the outer finally block below can
        // always delete the (possibly partially written) file.
        var tempFile: File? = null

        // Captured by the finally block so the activeStop slot can be
        // cleared only if it still points to our handler.
        var myStopHandler: (() -> Unit)? = null

        try {
            // Resolve the cache directory and construct the File path.
            // Constructing File does not touch disk; only writeBytes does.
            // If cacheDir is unavailable we throw here — no file exists,
            // so the finally below has nothing to clean up.
            val cacheDir = appContext.cacheDir
                ?: throw IOException("App cache directory unavailable")
            val file = File(
                cacheDir,
                TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX
            )

            // Publish the reference before writing so the finally below
            // can clean up if the write fails or is cancelled midway.
            tempFile = file

            // Write the MP3 bytes on IO. If the write fails (disk full,
            // I/O error) or the coroutine is cancelled here, the outer
            // finally deletes whatever file was created.
            withContext(Dispatchers.IO) {
                file.writeBytes(mp3)
            }

            // MediaPlayer setup and playback.
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { cont ->
                    // Created on main; every method call on this instance
                    // must stay on main.
                    val player = MediaPlayer()

                    // Single-shot completion guard shared by every
                    // terminal path (success, error, cancellation).
                    val done = AtomicBoolean(false)

                    /**
                     * Release the player and delete the temp file.
                     * MUST be called on the main thread.
                     */
                    fun releaseResources() {
                        try { player.stop() } catch (_: Throwable) {}
                        try { player.release() } catch (_: Throwable) {}
                        try {
                            if (file.exists()) file.delete()
                        } catch (_: Throwable) {}
                    }

                    /**
                     * Marshal [block] onto the main thread. If already
                     * on main, run inline; otherwise post.
                     */
                    fun runOnMain(block: () -> Unit) {
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            block()
                        } else {
                            mainHandler.post { block() }
                        }
                    }

                    fun finishSuccess() {
                        if (!done.compareAndSet(false, true)) return
                        // onCompletion fires on main because the player
                        // was created on main. Safe to call directly.
                        releaseResources()
                        if (cont.isActive) {
                            try {
                                cont.resume(Unit)
                            } catch (_: IllegalStateException) {}
                        }
                    }

                    fun finishError(error: Throwable) {
                        if (!done.compareAndSet(false, true)) return
                        // Both onError and the synchronous catch on main
                        // call this from main.
                        releaseResources()
                        if (cont.isActive) {
                            try {
                                cont.resumeWithException(error)
                            } catch (_: IllegalStateException) {}
                        }
                    }

                    fun finishCancelled() {
                        if (!done.compareAndSet(false, true)) return
                        // Always invoked via runOnMain; safe to release.
                        releaseResources()
                        try {
                            cont.cancel(CancellationException("Playback stopped"))
                        } catch (_: Throwable) {}
                    }

                    // Cancellation may originate on any thread. Marshal
                    // cleanup onto main so MediaPlayer is touched from the
                    // correct thread only. `done` ensures cleanup runs once.
                    cont.invokeOnCancellation {
                        if (done.compareAndSet(false, true)) {
                            runOnMain { releaseResources() }
                        }
                    }

                    // stop() may be called on any thread; marshal onto main.
                    val stopHandler: () -> Unit = {
                        runOnMain { finishCancelled() }
                    }
                    myStopHandler = stopHandler
                    synchronized(lock) { activeStop = stopHandler }

                    // ---- Setup (main thread) ----
                    try {
                        // Bail if cancellation already occurred while we
                        // were still setting up; do not touch a player
                        // that has been scheduled for release.
                        if (done.get()) return@suspendCancellableCoroutine

                        player.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )

                        if (done.get()) return@suspendCancellableCoroutine

                        player.setDataSource(file.absolutePath)

                        player.setOnPreparedListener { mp ->
                            if (done.get()) return@setOnPreparedListener
                            try {
                                mp.start()
                            } catch (t: Throwable) {
                                finishError(t)
                            }
                        }
                        player.setOnCompletionListener { finishSuccess() }
                        player.setOnErrorListener { _, what, extra ->
                            finishError(
                                RuntimeException(
                                    "MediaPlayer error (what=$what extra=$extra)"
                                )
                            )
                            true
                        }

                        // Final check before handing off to the async
                        // preparation engine. `prepareAsync()` itself is
                        // non-blocking, and `release()` is safe even if
                        // preparation is in flight.
                        if (done.get()) return@suspendCancellableCoroutine

                        player.prepareAsync()
                    } catch (t: Throwable) {
                        // Runs on main. If `finishError` was already
                        // superseded by cancellation, it becomes a no-op
                        // and the exception is safely discarded because
                        // the coroutine is already cancelled.
                        finishError(t)
                    }
                }
            }
        } finally {
            // Delete the temp file if it was ever created. This covers
            // every exit path:
            //   - file creation / cacheDir failure (tempFile == null,
            //     nothing to delete),
            //   - file-write failure or cancellation during write,
            //   - MediaPlayer setup failure,
            //   - playback error,
            //   - normal playback completion,
            //   - stop() called externally,
            //   - coroutine cancellation.
            // File.delete() on a non-existent file is a no-op, so the
            // double delete (releaseResources + this) is harmless.
            tempFile?.let {
                try {
                    if (it.exists()) it.delete()
                } catch (_: Throwable) {}
            }

            // Clear our own stop handler if it is still installed.
            val handler = myStopHandler
            if (handler != null) {
                synchronized(lock) {
                    if (activeStop === handler) activeStop = null
                }
            }
        }
    }

    /**
     * Stop the currently active playback, if any. Safe to call from any
     * thread and safe when nothing is playing. The suspended [play] call
     * resumes with [CancellationException]. The actual MediaPlayer work
     * is performed on the main thread.
     */
    fun stop() {
        val handler = synchronized(lock) { activeStop }
        if (handler != null) {
            try {
                handler()
            } catch (t: Throwable) {
                Log.w(TAG, "stop() handler failed: ${t.javaClass.simpleName}")
            }
        }
    }
}
