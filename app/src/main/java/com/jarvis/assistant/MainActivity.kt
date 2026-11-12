package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Settings-only screen. Never touches the network, never starts the
 * voice pipeline, and never loads saved API keys into an EditText.
 *
 * All persistent storage goes through [SettingsManager] (EncryptedShared-
 * Preferences). API keys are write-only from this screen's perspective:
 * the user types a new value, saves, and the field is cleared.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_MIC = 1001
        const val SETTING_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    }

    private lateinit var settings: SettingsManager

    private lateinit var deepgramStatus: TextView
    private lateinit var deepgramInput: EditText

    private lateinit var groqStatus: TextView
    private lateinit var groqKeyInput: EditText
    private lateinit var groqModelInput: EditText

    private lateinit var azureStatus: TextView
    private lateinit var azureKeyInput: EditText
    private lateinit var azureRegionInput: EditText
    private lateinit var azureVoiceInput: EditText
    private lateinit var azureLanguageInput: EditText

    private lateinit var micStatus: TextView
    private lateinit var assistantStatus: TextView
    private lateinit var accessibilityStatus: TextView

    private enum class TriState { YES, NO, UNKNOWN }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            settings = SettingsManager(applicationContext)
        } catch (e: Exception) {
            toast(R.string.settings_error_message)
            finish()
            return
        }

        bindViews()
        wireButtons()
        loadNonSecretValues()
        refreshAllStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            refreshDeviceStatuses()
        }
    }

    // ---------- View binding ----------

    private fun bindViews() {
        deepgramStatus = findViewById(R.id.deepgram_status)
        deepgramInput = findViewById(R.id.deepgram_key_input)

        groqStatus = findViewById(R.id.groq_status)
        groqKeyInput = findViewById(R.id.groq_key_input)
        groqModelInput = findViewById(R.id.groq_model_input)

        azureStatus = findViewById(R.id.azure_status)
        azureKeyInput = findViewById(R.id.azure_key_input)
        azureRegionInput = findViewById(R.id.azure_region_input)
        azureVoiceInput = findViewById(R.id.azure_voice_input)
        azureLanguageInput = findViewById(R.id.azure_language_input)

        micStatus = findViewById(R.id.device_mic_status)
        assistantStatus = findViewById(R.id.device_assistant_status)
        accessibilityStatus = findViewById(R.id.device_accessibility_status)
    }

    private fun wireButtons() {
        // Deepgram
        findViewById<Button>(R.id.deepgram_save).setOnClickListener {
            val value = deepgramInput.text.toString().trim()
            if (value.isBlank()) {
                toast(R.string.blank_key_message)
                return@setOnClickListener
            }
            try {
                settings.putDeepgramKey(value)
                deepgramInput.setText("")
                toast(R.string.key_saved_message)
                refreshKeyStatuses()
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }
        findViewById<Button>(R.id.deepgram_clear).setOnClickListener {
            try {
                settings.putDeepgramKey("")
                deepgramInput.setText("")
                toast(R.string.key_cleared_message)
                refreshKeyStatuses()
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }

        // Groq key
        findViewById<Button>(R.id.groq_save_key).setOnClickListener {
            val value = groqKeyInput.text.toString().trim()
            if (value.isBlank()) {
                toast(R.string.blank_key_message)
                return@setOnClickListener
            }
            try {
                settings.putGroqKey(value)
                groqKeyInput.setText("")
                toast(R.string.key_saved_message)
                refreshKeyStatuses()
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }
        findViewById<Button>(R.id.groq_clear_key).setOnClickListener {
            try {
                settings.putGroqKey("")
                groqKeyInput.setText("")
                toast(R.string.key_cleared_message)
                refreshKeyStatuses()
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }

        // Groq model
        findViewById<Button>(R.id.groq_save_model).setOnClickListener {
            val value = groqModelInput.text.toString().trim()
            if (value.isBlank()) {
                toast(R.string.enter_model_message)
                return@setOnClickListener
            }
            try {
                settings.putGroqModel(value)
                toast(R.string.model_saved_message)
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }

        // Azure key
        findViewById<Button>(R.id.azure_save_key).setOnClickListener {
            val value = azureKeyInput.text.toString().trim()
            if (value.isBlank()) {
                toast(R.string.blank_key_message)
                return@setOnClickListener
            }
            try {
                settings.putAzureKey(value)
                azureKeyInput.setText("")
                toast(R.string.key_saved_message)
                refreshKeyStatuses()
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }
        findViewById<Button>(R.id.azure_clear_key).setOnClickListener {
            try {
                settings.putAzureKey("")
                azureKeyInput.setText("")
                toast(R.string.key_cleared_message)
                refreshKeyStatuses()
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }

        // Azure non-secret config
        findViewById<Button>(R.id.azure_save_config).setOnClickListener {
            try {
                settings.putAzureRegion(azureRegionInput.text.toString().trim())
                settings.putAzureVoice(azureVoiceInput.text.toString().trim())
                settings.putAzureLanguage(azureLanguageInput.text.toString().trim())
                toast(R.string.azure_saved_message)
            } catch (e: Exception) {
                toast(R.string.settings_error_message)
            }
        }

        // Device setup
        findViewById<Button>(R.id.device_mic_button).setOnClickListener {
            requestMicPermission()
        }
        findViewById<Button>(R.id.device_assistant_button).setOnClickListener {
            openDefaultAssistantSettings()
        }
        findViewById<Button>(R.id.device_accessibility_button).setOnClickListener {
            openAccessibilitySettings()
        }
    }

    /**
     * Loads only non-secret values into EditTexts. API keys are never
     * loaded from settings into the UI.
     */
    private fun loadNonSecretValues() {
        try {
            groqModelInput.setText(settings.groqModel())
            azureRegionInput.setText(settings.azureRegion())
            azureVoiceInput.setText(settings.azureVoice())
            azureLanguageInput.setText(settings.azureLanguage())
        } catch (e: Exception) {
            toast(R.string.settings_error_message)
        }
    }

    // ---------- Status refresh ----------

    private fun refreshAllStatuses() {
        refreshKeyStatuses()
        refreshDeviceStatuses()
    }

    private fun refreshKeyStatuses() {
        try {
            deepgramStatus.text = getString(
                if (settings.hasDeepgramKey()) R.string.deepgram_status_saved
                else R.string.deepgram_status_not_saved
            )
            groqStatus.text = getString(
                if (settings.hasGroqKey()) R.string.groq_status_saved
                else R.string.groq_status_not_saved
            )
            azureStatus.text = getString(
                if (settings.hasAzureKey()) R.string.azure_status_saved
                else R.string.azure_status_not_saved
            )
        } catch (e: Exception) {
            // Status refresh failures are non-fatal; leave previous text.
        }
    }

    private fun refreshDeviceStatuses() {
        micStatus.text = getString(
            if (hasMicPermission()) R.string.status_granted
            else R.string.status_not_granted
        )

        assistantStatus.text = when (checkDefaultAssistant()) {
            TriState.YES -> getString(R.string.status_active)
            TriState.NO -> getString(R.string.status_inactive)
            TriState.UNKNOWN -> getString(R.string.status_unknown)
        }

        accessibilityStatus.text = getString(
            if (isAccessibilityServiceEnabled()) R.string.status_enabled
            else R.string.status_not_enabled
        )
    }

    // ---------- Device status checks ----------

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Default-assistant check.
     *
     * Primary: RoleManager.ROLE_ASSISTANT via isRoleAvailable / isRoleHeld.
     * Fallback: Settings.Secure "voice_interaction_service" comparison.
     * Returns UNKNOWN when neither source can be queried.
     */
    private fun checkDefaultAssistant(): TriState {
        return try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    TriState.YES
                } else {
                    TriState.NO
                }
            } else {
                fallbackAssistantCheck()
            }
        } catch (e: Exception) {
            TriState.UNKNOWN
        }
    }

    private fun fallbackAssistantCheck(): TriState {
        return try {
            val current = Settings.Secure.getString(
                contentResolver,
                SETTING_VOICE_INTERACTION_SERVICE
            )
            if (current.isNullOrBlank()) {
                return TriState.NO
            }
            val expected = ComponentName(
                this,
                JarvisVoiceInteractionService::class.java
            )
            val parsed = ComponentName.unflattenFromString(current)
            if (parsed != null && parsed == expected) TriState.YES else TriState.NO
        } catch (e: Exception) {
            TriState.UNKNOWN
        }
    }

    /**
     * Accessibility check via Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES.
     * The setting stores a colon-separated list of flattened component names.
     * Comparison is case-insensitive and also accepts ComponentName.unflatten
     * for relative-class forms (package/.Class).
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val raw = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val target = ComponentName(
                this,
                JarvisAccessibilityService::class.java
            )
            val targetFlat = target.flattenToString()

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(raw)
            while (splitter.hasNext()) {
                val entry = splitter.next()
                if (entry.equals(targetFlat, ignoreCase = true)) return true
                val parsed = ComponentName.unflattenFromString(entry)
                if (parsed != null && parsed == target) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Device setup actions ----------

    private fun requestMicPermission() {
        if (hasMicPermission()) {
            toast(R.string.status_granted)
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun openDefaultAssistantSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Fallback: general settings. The user can still reach the
            // default-assistant screen manually.
            try {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                toast(R.string.settings_error_message)
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast(R.string.settings_error_message)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()
    }
}
