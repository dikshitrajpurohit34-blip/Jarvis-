package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes the fixed [Action] whitelist.
 *
 * Step 4: Intent-only actions.
 * Step 5: Groq-backed [Action.Ask].
 * Step 7: Accessibility-backed Global / Scroll / ClickText / EnterText.
 *
 * Accessibility actions first hide the assistant overlay via
 * [JarvisSession.hideForAccessibility] (which suppresses cancellation for
 * one bounded window), wait [ACCESSIBILITY_HIDE_DELAY_MS] for the target
 * app window to become active, then operate on the current window.
 *
 * Every branch — success or failure — returns a short user-facing
 * sentence suitable for TTS. Network work occurs only for [Action.Ask]
 * and only on Dispatchers.IO. Cancellation is always rethrown.
 */
class ActionExecutor(
    private val ctx: Context,
    private val session: JarvisSession
) {

    private companion object {
        const val TAG = "JARVIS"

        /** Bounded delay after hiding the overlay before an accessibility action. */
        const val ACCESSIBILITY_HIDE_DELAY_MS = 300L

        /**
         * Shared OkHttpClient for Groq. Created lazily, reused for every
         * request. It has its own timeouts suited to LLM latency.
         */
        val GROQ_HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }

    private val settings: SettingsManager by lazy {
        SettingsManager(ctx.applicationContext)
    }

    private val aliases: Map<String, String> = mapOf(
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "whatsapp" to "com.whatsapp",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera2",
        "photos" to "com.google.android.apps.photos",
        "clock" to "com.google.android.deskclock",
        "calendar" to "com.google.android.calendar",
        "play store" to "com.android.vending"
    )

    suspend fun execute(action: Action): String {
        return try {
            when (action) {
                is Action.LaunchApp -> launchApp(action.app)
                is Action.SearchInApp -> searchInApp(action.app, action.query)
                is Action.SearchWeb -> searchWeb(action.query)
                is Action.OpenSettings -> openSettings(action.page)
                is Action.Global -> global(action.type)
                is Action.Scroll -> scroll(action.forward)
                is Action.ClickText -> clickText(action.text)
                is Action.EnterText -> enterText(action.text)
                is Action.Ask -> ask(action.question)
                Action.Cancel -> "Okay."
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Action execution failed for ${action::class.simpleName}: " +
                    t.javaClass.simpleName
            )
            "Sorry, that didn't work."
        }
    }

    // ---------- LaunchApp ----------

    private fun launchApp(name: String): String {
        val pkg = resolvePackage(name)
            ?: return "I couldn't find an app called $name."

        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "I couldn't open $name."

        return try {
            session.launch(launchIntent)
            "Opening $name."
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch $name: ${t.javaClass.simpleName}")
            "I couldn't open $name."
        }
    }

    private fun resolvePackage(name: String): String? {
        val target = name.lowercase().trim()
        if (target.isEmpty()) return null

        aliases[target]?.let { pkg ->
            if (ctx.packageManager.getLaunchIntentForPackage(pkg) != null) return pkg
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val resolved = ctx.packageManager
            .queryIntentActivities(launcherIntent, 0)

        resolved.firstOrNull { ri ->
            ri.loadLabel(ctx.packageManager).toString()
                .lowercase().trim() == target
        }?.let { return it.activityInfo.packageName }

        resolved.firstOrNull { ri ->
            ri.loadLabel(ctx.packageManager).toString()
                .lowercase().contains(target)
        }?.let { return it.activityInfo.packageName }

        return null
    }

    // ---------- Search ----------

    private fun searchWeb(query: String): String {
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        return try {
            session.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            "Searching the web for $query."
        } catch (t: Throwable) {
            Log.e(TAG, "Web search failed: ${t.javaClass.simpleName}")
            "I couldn't open a web search."
        }
    }

    private fun searchInApp(app: String, query: String): String {
        if (app.equals("youtube", ignoreCase = true)) {
            val url =
                "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
            return try {
                session.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                "Searching YouTube for $query."
            } catch (t: Throwable) {
                Log.e(TAG, "YouTube search failed: ${t.javaClass.simpleName}")
                "I couldn't search YouTube."
            }
        }

        // No public API to deep-search arbitrary apps without app-specific
        // URIs. Fall back honestly to a web search and say so.
        val url = "https://www.google.com/search?q=${Uri.encode(query)}"
        return try {
            session.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            "I can't search inside $app yet, so I'm searching the web for $query."
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Search-in-app fallback failed: ${t.javaClass.simpleName}"
            )
            "I couldn't search for that."
        }
    }

    // ---------- Settings ----------

    private fun openSettings(page: String): String {
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            session.launch(Intent(action))
            "Opening $page settings."
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Settings launch failed for $page: ${t.javaClass.simpleName}"
            )
            try {
                session.launch(Intent(Settings.ACTION_SETTINGS))
                "Opening settings."
            } catch (t2: Throwable) {
                Log.e(
                    TAG,
                    "Fallback settings launch failed: ${t2.javaClass.simpleName}"
                )
                "I couldn't open settings."
            }
        }
    }

    // ---------- Step 7: Accessibility actions ----------

    private suspend fun global(type: GlobalType): String {
        hideForAccessibility()

        val service = JarvisAccessibilityService.instance
            ?: return "Accessibility service is not enabled."

        val ok = when (type) {
            GlobalType.BACK -> service.performBack()
            GlobalType.HOME -> service.performHome()
            GlobalType.RECENTS -> service.performRecents()
        }

        return if (ok) {
            when (type) {
                GlobalType.BACK -> "Going back."
                GlobalType.HOME -> "Going home."
                GlobalType.RECENTS -> "Opening recent apps."
            }
        } else {
            "I couldn't do that."
        }
    }

    private suspend fun scroll(forward: Boolean): String {
        hideForAccessibility()

        val service = JarvisAccessibilityService.instance
            ?: return "Accessibility service is not enabled."

        val ok = service.scroll(forward)
        return if (ok) {
            if (forward) "Scrolling down." else "Scrolling up."
        } else {
            "I couldn't scroll."
        }
    }

    private suspend fun clickText(text: String): String {
        hideForAccessibility()

        val service = JarvisAccessibilityService.instance
            ?: return "Accessibility service is not enabled."

        val ok = service.clickByText(text)
        return if (ok) "Tapped $text." else "I couldn't find $text."
    }

    private suspend fun enterText(text: String): String {
        hideForAccessibility()

        val service = JarvisAccessibilityService.instance
            ?: return "Accessibility service is not enabled."

        val ok = service.enterText(text)
        return if (ok) "Typed." else "No text field is focused."
    }

    /**
     * Hide the assistant overlay so the target app becomes the active
     * window, then wait a short bounded delay for that transition. The
     * hide is a special accessibility hide and does not cancel the
     * running pipeline.
     */
    private suspend fun hideForAccessibility() {
        session.hideForAccessibility()
        delay(ACCESSIBILITY_HIDE_DELAY_MS)
    }

    // ---------- Groq (Step 5) ----------

    private suspend fun ask(question: String): String = withContext(Dispatchers.IO) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            Log.w(TAG, "Empty question for Action.Ask")
            return@withContext "I didn't catch a question."
        }

        val key = try {
            settings.groqKey()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read Groq key: ${t.javaClass.simpleName}")
            return@withContext "I couldn't read the Groq API key."
        }

        if (key.isBlank()) {
            Log.e(TAG, "Groq API key not configured")
            return@withContext "Please set the Groq API key in settings."
        }

        val model = try {
            settings.groqModel()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read Groq model: ${t.javaClass.simpleName}")
            return@withContext "I couldn't read the Groq model setting."
        }

        if (model.isBlank()) {
            Log.e(TAG, "Groq model ID not configured")
            return@withContext "Please set a Groq model ID in settings."
        }

        try {
            val client = GroqClient(
                client = GROQ_HTTP,
                apiKey = key,
                model = model
            )
            client.askOnce(trimmed)
        } catch (c: CancellationException) {
            throw c
        } catch (e: GroqConfigException) {
            Log.e(TAG, "Groq configuration error")
            "The question service is not configured correctly."
        } catch (e: GroqAuthException) {
            Log.e(TAG, "Groq authentication failed")
            "Groq authentication failed. Please check the API key."
        } catch (e: GroqRateLimitException) {
            Log.w(TAG, "Groq rate limit reached")
            "The question service is busy. Please try again shortly."
        } catch (e: GroqHttpException) {
            Log.e(TAG, "Groq HTTP error")
            "The question service returned an error."
        } catch (e: GroqParseException) {
            Log.e(TAG, "Groq response could not be parsed")
            "I couldn't understand the answer."
        } catch (e: GroqEmptyResponseException) {
            Log.w(TAG, "Groq returned an empty answer")
            "I didn't get an answer."
        } catch (e: GroqTimeoutException) {
            Log.w(TAG, "Groq request timed out")
            "The question service timed out. Please try again."
        } catch (e: GroqNetworkException) {
            Log.e(TAG, "Groq network error: ${e.cause?.javaClass?.simpleName}")
            "I couldn't reach the question service."
        } catch (t: Throwable) {
            Log.e(TAG, "Groq unexpected failure: ${t.javaClass.simpleName}")
            "Sorry, question answering failed."
        }
    }
}
