package com.nayem.liveagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API keys and provider keys. Encrypted on-device; never hardcoded, never logged. */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "live_agent_keys", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback only if the keystore is unavailable on the device.
        context.getSharedPreferences("live_agent_keys_fallback", Context.MODE_PRIVATE)
    }

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(v) { prefs.edit().putString("gemini_api_key", v).apply() }

    var openRouterApiKey: String
        get() = prefs.getString("openrouter_api_key", "") ?: ""
        set(v) { prefs.edit().putString("openrouter_api_key", v).apply() }

    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(v) { prefs.edit().putString("groq_api_key", v).apply() }

    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()
}
