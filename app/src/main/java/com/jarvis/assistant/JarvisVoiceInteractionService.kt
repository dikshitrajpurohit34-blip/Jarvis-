package com.jarvis.assistant

import android.service.voice.VoiceInteractionService

class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Stub – no action
    }

    override fun onShutdown() {
        super.onShutdown()
    }
}
