package com.jarvis.assistant

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---- Azure TTS-specific exception types (surfaced by later TaskManager) ----

/** Missing or blank text, key, region, voice, or language. */
class AzureTtsConfigException(message: String) : Exception(message)

/** HTTP 401 / 403 — invalid or expired subscription key. */
class AzureTtsAuthException(message: String) : Exception(message)

/** HTTP 429 — rate limit or quota exceeded. */
class AzureTtsRateLimitException(message: String) : Exception(message)

/** Any other non-2xx HTTP status. */
class AzureTtsHttpException(message: String) : Exception(message)

/** HTTP 2xx but the response body contained no audio bytes. */
class AzureTtsEmptyResponseException(message: String) : Exception(message)

/** Socket timeout or call timeout fired. */
class AzureTtsTimeoutException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Any other I/O failure (DNS, TLS, connection reset, etc.). */
class AzureTtsNetworkException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Azure Speech text-to-speech client.
 *
 * Endpoint: https://{configured-region}.tts.speech.microsoft.com/cognitiveservices/v1
 * Output format: MP3 24 kHz 48 kbit/s mono.
 *
 * [synthesize] is a suspend function built on OkHttp's [Call.enqueue]
 * plus `suspendCancellableCoroutine`. Cancelling the calling coroutine
 * cancels the in-flight HTTP call. Response bodies are always closed via
 * `Response.use`. The subscription key is never logged, and the SSML body
 * is never logged either.
 *
 * Dynamic text is XML-escaped before being inserted into SSML so that
 * user/LLM content cannot break or inject markup.
 */
class AzureTts(
    private val settings: SettingsManager,
    private val client: OkHttpClient = DEFAULT_CLIENT
) {

    companion object {
        private const val TAG = "JARVIS"

        /**
         * Documented example defaults. Voice and region availability are
         * controlled by Azure and may change; users must verify them in
         * the Azure portal or docs and can override them in settings.
         */
        const val DEFAULT_REGION = "centralindia"
        const val DEFAULT_VOICE = "en-IN-NeerjaNeural"
        const val DEFAULT_LANGUAGE = "en-IN"

        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        private const val USER_AGENT = "jarvis-android"
        private val SSML_MEDIA = "application/ssml+xml".toMediaType()

        /**
         * Default OkHttp client with timeouts tuned to Azure Speech. One
         * shared instance per process; every synthesis call reuses it.
         */
        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Read the current settings snapshot, validate it, then perform one
     * Azure TTS request and return the MP3 bytes. Suspends; the HTTP call
     * runs on OkHttp's dispatcher and is cancelled if the calling
     * coroutine is cancelled.
     */
    suspend fun synthesize(text: String): ByteArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw AzureTtsConfigException("Text for synthesis is blank")
        }

        // EncryptedSharedPreferences may involve first-use crypto work and
        // disk I/O, so read settings on Dispatchers.IO.
        val config = withContext(Dispatchers.IO) {
            Config(
                key = settings.azureKey(),
                region = settings.azureRegion().ifBlank { DEFAULT_REGION },
                voice = settings.azureVoice().ifBlank { DEFAULT_VOICE },
                language = settings.azureLanguage().ifBlank { DEFAULT_LANGUAGE }
            )
        }

        if (config.key.isBlank()) {
            throw AzureTtsConfigException("Azure Speech key is not configured")
        }
        if (config.region.isBlank()) {
            throw AzureTtsConfigException("Azure region is not configured")
        }
        if (config.voice.isBlank()) {
            throw AzureTtsConfigException("Azure voice is not configured")
        }
        if (config.language.isBlank()) {
            throw AzureTtsConfigException("Azure language is not configured")
        }

        val ssml = buildSsml(trimmed, config.voice, config.language)
        val endpoint =
            "https://${config.region}.tts.speech.microsoft.com/cognitiveservices/v1"

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Ocp-Apim-Subscription-Key", config.key)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            .addHeader("User-Agent", USER_AGENT)
            .post(ssml.toRequestBody(SSML_MEDIA))
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            val done = AtomicBoolean(false)

            fun complete(result: Result<ByteArray>) {
                // Single-shot: any subsequent callback (e.g. onFailure
                // fired by call.cancel()) becomes a no-op.
                if (!done.compareAndSet(false, true)) return
                if (!cont.isActive) return
                try {
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) }
                    )
                } catch (_: IllegalStateException) {
                    // Narrow race: cancelled in the gap between the
                    // isActive check and resume. Cancellation cleanup
                    // has already run; ignore.
                }
            }

            cont.invokeOnCancellation {
                try { call.cancel() } catch (_: Throwable) {}
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    complete(Result.failure(mapFailure(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result: Result<ByteArray> = try {
                        response.use { r ->
                            val code = r.code
                            when {
                                code == 401 || code == 403 ->
                                    Result.failure(
                                        AzureTtsAuthException(
                                            "Azure TTS authentication failed (HTTP $code)"
                                        )
                                    )
                                code == 429 ->
                                    Result.failure(
                                        AzureTtsRateLimitException(
                                            "Azure TTS rate limit exceeded (HTTP 429)"
                                        )
                                    )
                                code !in 200..299 ->
                                    Result.failure(
                                        AzureTtsHttpException(
                                            "Azure TTS returned HTTP $code"
                                        )
                                    )
                                else -> {
                                    val bytes = r.body?.bytes()
                                    if (bytes == null || bytes.isEmpty()) {
                                        Result.failure(
                                            AzureTtsEmptyResponseException(
                                                "Azure TTS returned empty audio"
                                            )
                                        )
                                    } else {
                                        Result.success(bytes)
                                    }
                                }
                            }
                        }
                    } catch (e: IOException) {
                        Result.failure(
                            AzureTtsNetworkException(
                                "Failed to read Azure TTS response",
                                e
                            )
                        )
                    } catch (e: Exception) {
                        Result.failure(
                            AzureTtsNetworkException(
                                "Unexpected Azure TTS response error",
                                e
                            )
                        )
                    }
                    complete(result)
                }
            })
        }
    }

    /**
     * Map an OkHttp `onFailure` [IOException] to a typed exception.
     * `InterruptedIOException` (including `SocketTimeoutException` and
     * OkHttp's own call-timeout IOException) is treated as a timeout.
     */
    private fun mapFailure(e: IOException): Throwable =
        if (e is InterruptedIOException) {
            AzureTtsTimeoutException("Azure TTS request timed out", e)
        } else {
            AzureTtsNetworkException("Azure TTS network error", e)
        }

    /**
     * Build a minimal SSML document. All dynamic values (text, voice,
     * language) are XML-escaped before insertion so that the SSML
     * parser cannot be broken or injected.
     */
    private fun buildSsml(text: String, voice: String, language: String): String {
        val escText = escapeXml(text)
        val escVoice = escapeXml(voice)
        val escLang = escapeXml(language)
        return "<speak version='1.0' xml:lang='$escLang'>" +
            "<voice name='$escVoice'>$escText</voice>" +
            "</speak>"
    }

    /**
     * Escape a string for safe XML text/attribute embedding. Handles the
     * five XML predefined entities.
     */
    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private data class Config(
        val key: String,
        val region: String,
        val voice: String,
        val language: String
    )
}
