package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class TaskManager(
    private val ctx: Context,
    private val session: JarvisSession
) {
    companion object {
        private const val TAG = "JARVIS"
        private const val RECOGNITION_TIMEOUT_MS = 20_000L
        private const val TTS_TIMEOUT_MS = 20_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val settings by lazy { SettingsManager(ctx.applicationContext) }
    private val azureTts by lazy { AzureTts(settings) }
    private val player by lazy { AudioPlayer(ctx.applicationContext) }
    private val executor by lazy { ActionExecutor(ctx, session) }

    fun start() {
        job?.cancel()
        // Ensure no previous playback overlaps with the new invocation.
        // player is a lazy AudioPlayer; stop() is a no-op if nothing is
        // currently playing. Any failure is logged (class only) and does
        // not prevent the new pipeline from starting.
        try {
            player.stop()
        } catch (e: Exception) {
            Log.w(TAG, "player.stop before start failed: ${e.javaClass.simpleName}")
        }
        job = scope.launch { runPipeline() }
    }

    fun cancel() {
        job?.cancel()
        job = null
        try {
            player.stop()
        } catch (e: Exception) {
            Log.w(TAG, "player.stop failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun runPipeline() {
        var reply: String? = null

        try {
            session.setStatus(ctx.getString(R.string.session_listening))

            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "RECORD_AUDIO permission not granted")
                reply = ctx.getString(R.string.status_mic_permission_missing)
            } else {
                val deepgramKey = withContext(Dispatchers.IO) {
                    try {
                        settings.deepgramKey()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Failed to read Deepgram key: ${e.javaClass.simpleName}"
                        )
                        ""
                    }
                }

                if (deepgramKey.isBlank()) {
                    Log.e(TAG, "Deepgram API key not configured")
                    reply = ctx.getString(R.string.status_deepgram_key_missing)
                } else {
                    try {
                        val stt = DeepgramStt(
                            client = JarvisSession.SHARED_OKHTTP,
                            apiKey = deepgramKey
                        )
                        val transcript = withTimeout(RECOGNITION_TIMEOUT_MS) {
                            stt.listenOnce()
                        }
                        if (transcript.isBlank()) {
                            Log.w(TAG, "Recognition returned a blank transcript")
                            reply = ctx.getString(R.string.status_no_speech)
                        } else {
                            // Do not log transcript content (may contain
                            // sensitive user speech). Log only its length.
                            Log.i(
                                TAG,
                                "Recognized transcript (length=${transcript.length})"
                            )
                            session.setStatus(ctx.getString(R.string.status_thinking))
                            val action = CommandInterpreter.interpret(transcript)
                            reply = executor.execute(action)
                        }
                    } catch (tc: TimeoutCancellationException) {
                        Log.w(TAG, "Recognition timed out after ${RECOGNITION_TIMEOUT_MS}ms")
                        reply = ctx.getString(R.string.status_no_speech_timeout)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: NoSpeechException) {
                        Log.w(TAG, "No speech detected")
                        reply = ctx.getString(R.string.status_no_speech)
                    } catch (e: DeepgramAuthException) {
                        Log.e(TAG, "Deepgram authentication failed")
                        reply = ctx.getString(R.string.status_deepgram_auth_error)
                    } catch (e: Exception) {
                        Log.e(TAG, "Recognition failed: ${e.javaClass.simpleName}")
                        reply = ctx.getString(R.string.status_recognition_error)
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed: ${e.javaClass.simpleName}")
            reply = ctx.getString(R.string.status_pipeline_failed)
        }

        if (reply != null) {
            speakReply(reply)
        }

        try {
            session.hide()
        } catch (e: Exception) {
            Log.w(TAG, "session.hide failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun speakReply(text: String) {
        try {
            session.setStatus(ctx.getString(R.string.status_speaking))
            val mp3 = withTimeout(TTS_TIMEOUT_MS) { azureTts.synthesize(text) }
            player.play(mp3)
        } catch (tc: TimeoutCancellationException) {
            Log.w(TAG, "TTS timed out")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (c: CancellationException) {
            throw c
        } catch (e: AzureTtsConfigException) {
            Log.e(TAG, "Azure TTS not configured")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (e: AzureTtsAuthException) {
            Log.e(TAG, "Azure TTS authentication failed")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (e: AzureTtsRateLimitException) {
            Log.w(TAG, "Azure TTS rate limit")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (e: AzureTtsHttpException) {
            Log.e(TAG, "Azure TTS HTTP error")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (e: AzureTtsEmptyResponseException) {
            Log.w(TAG, "Azure TTS empty audio")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (e: AzureTtsTimeoutException) {
            Log.w(TAG, "Azure TTS timeout")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (e: AzureTtsNetworkException) {
            Log.e(TAG, "Azure TTS network error")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        } catch (e: Exception) {
            Log.e(TAG, "TTS failed: ${e.javaClass.simpleName}")
            session.setStatus(ctx.getString(R.string.status_speech_failed))
        }
    }
}
