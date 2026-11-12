package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.View
import android.widget.TextView
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisSession(this)
    }
}

class JarvisSession(private val ctx: Context) : VoiceInteractionSession(ctx) {

    internal companion object {
        const val TAG = "JARVIS"
        val SHARED_OKHTTP: OkHttpClient by lazy { OkHttpClient.Builder().build() }
    }

    private var statusView: TextView? = null

    @Volatile
    private var taskManager: TaskManager? = null

    private val pendingAccessibilityHide = AtomicBoolean(false)

    override fun onCreateContentView(): View {
        val view = layoutInflater.inflate(R.layout.session_listening, null)
        statusView = view.findViewById(R.id.status_text)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        val isAssistGesture =
            (showFlags and VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE) != 0

        if (!isAssistGesture) {
            Log.i(
                TAG,
                "onShow ignored: not an assist-gesture invocation " +
                    "(showFlags=0x${showFlags.toString(16)})"
            )
            setStatus(ctx.getString(R.string.status_use_assist_gesture))
            return
        }

        taskManager?.cancel()
        taskManager = TaskManager(ctx.applicationContext, this).also { it.start() }
    }

    override fun onHide() {
        val wasAccessibilityHide = pendingAccessibilityHide.getAndSet(false)
        if (!wasAccessibilityHide) {
            taskManager?.cancel()
            taskManager = null
        }
        super.onHide()
    }

    override fun onDestroy() {
        pendingAccessibilityHide.set(false)
        taskManager?.cancel()
        taskManager = null
        super.onDestroy()
    }

    fun hideForAccessibility() {
        val dialog = try {
            getWindow()
        } catch (t: Throwable) {
            Log.w(TAG, "getWindow() failed: ${t.javaClass.simpleName}")
            null
        }

        if (dialog == null || !dialog.isShowing) {
            Log.w(TAG, "hideForAccessibility: session window not showing; skipping")
            return
        }

        pendingAccessibilityHide.set(true)
        try {
            hide()
        } catch (t: Throwable) {
            pendingAccessibilityHide.set(false)
            Log.w(TAG, "hide() failed: ${t.javaClass.simpleName}")
        }
    }

    fun launch(intent: Intent) {
        try {
            startAssistantActivity(intent)
            return
        } catch (t: Throwable) {
            Log.w(TAG, "startAssistantActivity threw: ${t.javaClass.simpleName}")
        }

        val fallback = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(fallback)
    }

    internal fun setStatus(text: String) {
        val view = statusView ?: return
        view.post { statusView?.text = text }
    }
}
