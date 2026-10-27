package com.claudeauto

import android.content.Context
import androidx.core.content.edit

/** Connection settings, entered once on the phone. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("claude_auto", Context.MODE_PRIVATE)

    var bridgeUrl: String
        get() = prefs.getString("bridge_url", "").orEmpty()
        set(value) = prefs.edit { putString("bridge_url", value.trim()) }

    /** This phone's own device token, issued by the bridge when pairing. */
    var token: String
        get() = prefs.getString("device_token", "").orEmpty()
        set(value) = prefs.edit { putString("device_token", value.trim()) }

    /** SHA-256 of the bridge's certificate, confirmed when pairing. Empty when the system trusts it. */
    var certFingerprint: String
        get() = prefs.getString("cert_fingerprint", "").orEmpty()
        set(value) = prefs.edit { putString("cert_fingerprint", value.trim()) }

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(value) = prefs.edit { putBoolean("speak_replies", value) }

    /** The conversation the phone screen last had open. */
    var currentConversation: String?
        get() = prefs.getString("current_conversation", null)
        set(value) = prefs.edit { putString("current_conversation", value) }

    val isConfigured get() = bridgeUrl.isNotBlank() && token.isNotBlank()
}
