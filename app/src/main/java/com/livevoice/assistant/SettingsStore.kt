package com.livevoice.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/** Keys are stored encrypted on the device. */
class SettingsStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "kali_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(v) = prefs.edit().putString("gemini_key", v.trim()).apply()

    var picoKey: String
        get() = prefs.getString("pico_key", "") ?: ""
        set(v) = prefs.edit().putString("pico_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim().ifBlank { DEFAULT_MODEL }).apply()

    var voice: String
        get() = prefs.getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) = prefs.edit().putString("voice", v.trim().ifBlank { DEFAULT_VOICE }).apply()

    fun keywordFile(context: Context) = File(context.filesDir, "kali.ppn")

    companion object {
        // Check ai.google.dev/gemini-api/docs/models for the newest Live model and change it in Settings.
        const val DEFAULT_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VOICE = "Aoede"
    }
}
