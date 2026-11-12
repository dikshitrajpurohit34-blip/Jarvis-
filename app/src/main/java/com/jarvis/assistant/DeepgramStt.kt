package com.jarvis.assistant

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when Deepgram closes the stream with no recognisable speech. */
class NoSpeechException(message: String = "No speech detected") : Exception(message)

/** Thrown when Deepgram rejects the request due to authentication. */
class DeepgramAuthException(message: String) : Exception(message)

/**
 * Streaming speech recognition against Deepgram's /v1/listen endpoint.
 *
 * Uses OkHttp's WebSocket to send raw PCM16 audio (linear16, 16 kHz, mono)
 * from [AudioCapture] and accumulates final transcript segments. Returns
 * the joined final transcript when `speech_final` is observed, or throws
 * [NoSpeechException] if nothing was recognised.
 *
 * Provider model and language identifiers are configurable. Users must
 * verify them against Deepgram's current documentation before use.
 */
class DeepgramStt(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val language: String = DEFAULT_LANGUAGE
) {

    companion object {
        private const val TAG = "JARVIS"
        private const val ENDPOINT = "wss://api.deepgram.com/v1/listen"

        /**
         * Configurable defaults. Deepgram may rename or retire models and
         * language codes at any time; verify against their docs.
         */
        const val DEFAULT_MODEL = "nova-2"
        const val DEFAULT_LANGUAGE = "en-US"
    }

    suspend fun listenOnce(): String = suspendCancellableCoroutine { cont ->
        val capture = AudioCapture()
        val finals = StringBuilder()
        val done = AtomicBoolean(false)
        val socketRef = AtomicReference<WebSocket?>(null)

        fun finish(result: Result<String>) {
            // Idempotent: only the first caller proceeds to cleanup and
            // to resume the continuation. Later calls (from racing
            // callbacks or from the cancellation handler) are no-ops.
            if (!done.compareAndSet(false, true)) return
            try { capture.stop() } catch (t: Throwable) {
                Log.w(TAG, "capture.stop() failed", t)
            }
            socketRef.get()?.let { ws ->
                try { ws.send("""{"type":"CloseStream"}""") } catch (_: Throwable) {}
                try { ws.close(1000, "done") } catch (_: Throwable) {}
            }
            // If the coroutine has been cancelled (e.g. by withTimeout in the
            // session), do not resume — the cancellation path already ran.
            if (cont.isActive) {
                result.fold(
                    onSuccess = { text -> cont.resume(text) },
                    onFailure = { e -> cont.resumeWithException(e) }
                )
            }
        }

        val url = buildString {
            append(ENDPOINT)
            append("?model=").append(model)
            append("&language=").append(language)
            append("&encoding=linear16")
            append("&sample_rate=16000")
            append("&channels=1")
            append("&interim_results=true")
            append("&endpointing=500")
            append("&smart_format=true")
            append("&punctuate=true")
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    capture.start { frame ->
                        if (!done.get()) {
                            try {
                                webSocket.send(frame.toByteString())
                            } catch (t: Throwable) {
                                Log.w(TAG, "Error sending PCM frame to Deepgram", t)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start microphone capture: ${t.javaClass.simpleName}")
                    finish(Result.failure(
                        IllegalStateException("Microphone capture failed", t)
                    ))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (done.get()) return
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    // Only Results messages contain channel data.
                    // Metadata, UtteranceEnd, SpeechStarted, etc. are ignored.
                    if (type != "Results") return

                    val channel = json.optJSONObject("channel") ?: return
                    val alternatives = channel.optJSONArray("alternatives") ?: return
                    if (alternatives.length() == 0) return
                    val alt = alternatives.optJSONObject(0) ?: return
                    val transcript = alt.optString("transcript", "")
                    val isFinal = json.optBoolean("is_final", false)
                    val speechFinal = json.optBoolean("speech_final", false)

                    if ((isFinal || speechFinal) && transcript.isNotBlank()) {
                        if (finals.isNotEmpty()) finals.append(' ')
                        finals.append(transcript.trim())
                    }

                    if (speechFinal) {
                        val out = finals.toString().trim()
                        if (out.isEmpty()) {
                            finish(Result.failure(NoSpeechException()))
                        } else {
                            finish(Result.success(out))
                        }
                    }
                } catch (t: Throwable) {
                    // Malformed JSON or unexpected shape: never let this
                    // crash the WebSocket callback thread.
                    Log.w(TAG, "Ignoring malformed Deepgram message", t)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Throwable) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (done.get()) return
                val out = finals.toString().trim()
                if (out.isEmpty()) {
                    finish(Result.failure(NoSpeechException()))
                } else {
                    finish(Result.success(out))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (done.get()) return
                val code = response?.code ?: 0
                if (code == 401 || code == 403) {
                    finish(Result.failure(
                        DeepgramAuthException(
                            "Deepgram authentication failed (HTTP $code)"
                        )
                    ))
                } else {
                    finish(Result.failure(
                        IllegalStateException("Deepgram connection failed", t)
                    ))
                }
            }
        }

        socketRef.set(client.newWebSocket(request, listener))

        cont.invokeOnCancellation {
            // Idempotent cleanup when the coroutine is cancelled, including
            // when withTimeout in the session fires. Guarded by the same
            // `done` atomic as finish() so mic and WebSocket are released
            // exactly once across all paths.
            if (done.compareAndSet(false, true)) {
                try { capture.stop() } catch (_: Throwable) {}
                try { socketRef.get()?.cancel() } catch (_: Throwable) {}
            }
        }
    }
}
