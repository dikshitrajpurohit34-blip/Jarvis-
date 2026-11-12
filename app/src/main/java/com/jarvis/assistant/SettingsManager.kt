package com.jarvis.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**

- Secure settings storage backed by EncryptedSharedPreferences.

- 

- Deepgram and Groq settings were added in earlier steps.

- Azure settings were added in Step 6.
  */
  class SettingsManager(ctx: Context) {

  private val appContext: Context = ctx.applicationContext

  private val prefs: SharedPreferences by lazy {
  val masterKey = MasterKey.Builder(appContext)
  .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
  .build()

  EncryptedSharedPreferences.create(  
       appContext,  
       PREFS_NAME,  
       masterKey,  
       EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,  
       EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM  
   )

  }

  // ---------- Deepgram ----------

  fun deepgramKey(): String =
  prefs.getString(KEY_DEEPGRAM, "").orEmpty()

  fun putDeepgramKey(value: String) {
  prefs.edit().putString(KEY_DEEPGRAM, value).apply()
  }

  fun hasDeepgramKey(): Boolean = deepgramKey().isNotBlank()

  // ---------- Groq ----------

  fun groqKey(): String =
  prefs.getString(KEY_GROQ_KEY, "").orEmpty()

  fun putGroqKey(value: String) {
  prefs.edit().putString(KEY_GROQ_KEY, value).apply()
  }

  fun hasGroqKey(): Boolean = groqKey().isNotBlank()

  fun groqModel(): String =
  prefs.getString(KEY_GROQ_MODEL, "").orEmpty()

  fun putGroqModel(value: String) {
  prefs.edit().putString(KEY_GROQ_MODEL, value).apply()
  }

  // ---------- Azure ----------

  fun azureKey(): String =
  prefs.getString(KEY_AZURE_KEY, "").orEmpty()

  fun putAzureKey(value: String) {
  prefs.edit().putString(KEY_AZURE_KEY, value).apply()
  }

  fun hasAzureKey(): Boolean = azureKey().isNotBlank()

  fun azureRegion(): String =
  prefs.getString(KEY_AZURE_REGION, "centralindia").orEmpty()

  fun putAzureRegion(value: String) {
  prefs.edit().putString(KEY_AZURE_REGION, value).apply()
  }

  fun azureVoice(): String =
  prefs.getString(KEY_AZURE_VOICE, "en-IN-NeerjaNeural").orEmpty()

  fun putAzureVoice(value: String) {
  prefs.edit().putString(KEY_AZURE_VOICE, value).apply()
  }

  fun azureLanguage(): String =
  prefs.getString(KEY_AZURE_LANGUAGE, "en-IN").orEmpty()

  fun putAzureLanguage(value: String) {
  prefs.edit().putString(KEY_AZURE_LANGUAGE, value).apply()
  }

  companion object {
  private const val PREFS_NAME = "jarvis_secure_prefs"
  private const val KEY_DEEPGRAM = "deepgram_key"
  private const val KEY_GROQ_KEY = "groq_key"
  private const val KEY_GROQ_MODEL = "groq_model"
  private const val KEY_AZURE_KEY = "azure_key"
  private const val KEY_AZURE_REGION = "azure_region"
  private const val KEY_AZURE_VOICE = "azure_voice"
  private const val KEY_AZURE_LANGUAGE = "azure_language"
  }
  }
