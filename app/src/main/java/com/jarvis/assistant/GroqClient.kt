package com.jarvis.assistant

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---- Groq-specific exception types (mapped to spoken sentences by ActionExecutor) ----

/** Missing or blank API key or model ID. */
class GroqConfigException(message: String) : Exception(message)

/** HTTP 401 / 403 — invalid or expired API key. */
class GroqAuthException(message: String) : Exception(message)

/** HTTP 429 — rate limit or quota exceeded. */
class GroqRateLimitException(message: String) : Exception(message)

/** Any other non-2xx HTTP status. */
class GroqHttpException(message: String) : Exception(message)

/** Response body was not the expected OpenAI-compatible JSON shape. */
class GroqParseException(message: String) : Exception(message)

/** Response succeeded but the answer text was blank. */
class GroqEmptyResponseException(message: String) : Exception(message)

/** Socket timeout or call-timeout fired. */
class GroqTimeoutException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Any other I/O failure (DNS, TLS, connection reset, etc.). */
class GroqNetworkException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Minimal async client for Groq's Chat Completions endpoint.
 *
 * [askOnce] is a suspend function that uses OkHttp's [Call.enqueue] so it
 * never blocks the calling thread. When the surrounding coroutine is
 * cancelled, [Call.cancel] is invoked via `invokeOnCancellation`, and any
 * in-flight HTTP request is torn down. No streaming.
 *
 * Contract:
 *   - [question], [apiKey] and [model] must be non-blank.
 *   - Returns a short plain-text answer suitable for later TTS.
 *   - Throws one of the typed exceptions above on every failure path.
 *   - Never logs the API key or the Authorization header.
 *   - Never resumes a cancelled continuation twice.
 */
class GroqClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String
) {

    private companion object {
        const val ENDPOINT =
            "https://api.groq.com/openai/v1/chat/completions"

        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        const val SYSTEM_PROMPT =
            "You are JARVIS, a concise voice assistant. " +
                "Answer in one to three short, plain sentences. " +
                "Do not use Markdown, bullet points, numbered lists, or code blocks. " +
                "Do not invent facts. If unsure, say so briefly."

        const val TEMPERATURE = 0.4
        const val MAX_COMPLETION_TOKENS = 256
    }

    /**
     * Send one question to Groq and return the answer text.
     *
     * Suspends the calling coroutine; network work runs on OkHttp's
     * dispatcher. Cancellation of the calling coroutine cancels the
     * active HTTP call.
     */
    suspend fun askOnce(question: String): String {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            throw GroqConfigException("Groq question input is blank")
        }
        if (apiKey.isBlank()) {
            throw GroqConfigException("Groq API key is not configured")
        }
        if (model.isBlank()) {
            // Defense-in-depth. ActionExecutor validates this before calling.
            throw GroqConfigException("Groq model ID is not configured")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", trimmed))
            })
            put("temperature", TEMPERATURE)
            put("max_completion_tokens", MAX_COMPLETION_TOKENS)
        }.toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            val done = AtomicBoolean(false)

            fun complete(result: Result<String>) {
                // First caller owns the completion. Subsequent callbacks
                // (racing onFailure after cancellation, etc.) are no-ops.
                if (!done.compareAndSet(false, true)) return
                if (!cont.isActive) return
                try {
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) }
                    )
                } catch (_: IllegalStateException) {
                    // Continuation was cancelled in the tiny window between
                    // the isActive check above and the resume call. The
                    // cancellation path has already run; ignore the race.
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
                    val result: Result<String> = try {
                        response.use { r ->
                            val code = r.code
                            when {
                                code == 401 || code == 403 ->
                                    Result.failure(
                                        GroqAuthException(
                                            "Groq authentication failed (HTTP $code)"
                                        )
                                    )
                                code == 429 ->
                                    Result.failure(
                                        GroqRateLimitException(
                                            "Groq rate limit exceeded (HTTP 429)"
                                        )
                                    )
                                code !in 200..299 ->
                                    Result.failure(
                                        GroqHttpException(
                                            "Groq returned HTTP $code"
                                        )
                                    )
                                else -> {
                                    val raw = r.body?.string().orEmpty()
                                    val parsed = parseAnswer(raw).trim()
                                    if (parsed.isEmpty()) {
                                        Result.failure(
                                            GroqEmptyResponseException(
                                                "Groq returned an empty answer"
                                            )
                                        )
                                    } else {
                                        Result.success(parsed)
                                    }
                                }
                            }
                        }
                    } catch (e: GroqParseException) {
                        Result.failure(e)
                    } catch (e: IOException) {
                        Result.failure(
                            GroqNetworkException(
                                "Failed to read Groq response",
                                e
                            )
                        )
                    } catch (e: Exception) {
                        Result.failure(
                            GroqParseException("Malformed Groq response")
                        )
                    }
                    complete(result)
                }
            })
        }
    }

    /**
     * Map an OkHttp `onFailure` [IOException] to one of our typed
     * exceptions. `InterruptedIOException` (including its subclass
     * `SocketTimeoutException`) indicates a timeout; anything else is
     * treated as a network failure.
     */
    private fun mapFailure(e: IOException): Throwable =
        if (e is InterruptedIOException) {
            GroqTimeoutException("Groq request timed out", e)
        } else {
            GroqNetworkException("Groq network error", e)
        }

    /**
     * Extract `choices[0].message.content` from an OpenAI-compatible
     * Chat Completions response. Throws GroqParseException on any
     * structural deviation.
     */
    private fun parseAnswer(raw: String): String {
        if (raw.isBlank()) {
            throw GroqParseException("Empty response body")
        }
        val root = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw GroqParseException("Response body is not valid JSON")
        }
        val choices = root.optJSONArray("choices")
            ?: throw GroqParseException("Missing choices array")
        if (choices.length() == 0) {
            throw GroqParseException("Empty choices array")
        }
        val first = choices.optJSONObject(0)
            ?: throw GroqParseException("First choice is not an object")
        val message = first.optJSONObject("message")
            ?: throw GroqParseException("Missing message object")
        return message.optString("content", "")
    }
}
