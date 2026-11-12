package com.jarvis.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Compatibility stub for the platform RecognitionService binding.
 *
 * JARVIS performs speech recognition exclusively in the cloud via Deepgram.
 * This class exists only for the voice interaction configuration.
 *
 * It never performs local speech recognition or opens the microphone.
 */
class JarvisStubRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        // Report the documented client-side error without importing
        // android.speech.SpeechRecognizer.
        listener?.error(ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {
        // Nothing is being listened to.
    }

    override fun onStopListening(listener: Callback?) {
        // Nothing is being listened to.
    }

    private companion object {
        // SpeechRecognizer.ERROR_CLIENT has the documented value 5.
        const val ERROR_CLIENT = 5
    }
}
