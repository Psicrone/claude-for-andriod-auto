package com.claudeauto

import android.content.Context
import androidx.core.content.edit

/** Connection settings, entered once on the phone. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("claude_auto", Context.MODE_PRIVATE)

    /**
     * The computer bridge and the on-phone (Termux) bridge are paired separately, so switching
     * between them doesn't throw away the other pairing.
     */
    private fun bridgeKey(name: String) = if (mode == MODE_PHONE) "phone_$name" else name

    var bridgeUrl: String
        get() = bridgeUrlFor(mode)
        set(value) = prefs.edit { putString(bridgeKey("bridge_url"), value.trim()) }

    /** The saved bridge address for [mode], before that mode is switched to. */
    fun bridgeUrlFor(mode: String): String {
        val key = if (mode == MODE_PHONE) "phone_bridge_url" else "bridge_url"
        return prefs.getString(key, null) ?: if (mode == MODE_PHONE) PHONE_BRIDGE_URL else ""
    }

    /** This phone's own device token, issued by the bridge when pairing. */
    var token: String
        get() = prefs.getString(bridgeKey("device_token"), "").orEmpty()
        set(value) = prefs.edit { putString(bridgeKey("device_token"), value.trim()) }

    /** SHA-256 of the bridge's certificate, confirmed when pairing. Empty when the system trusts it. */
    var certFingerprint: String
        get() = prefs.getString(bridgeKey("cert_fingerprint"), "").orEmpty()
        set(value) = prefs.edit { putString(bridgeKey("cert_fingerprint"), value.trim()) }

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(value) = prefs.edit { putBoolean("speak_replies", value) }

    /** The conversation the phone screen last had open. */
    var currentConversation: String?
        get() = prefs.getString("current_conversation", null)
        set(value) = prefs.edit { putString("current_conversation", value) }

    /**
     * "bridge" talks to Claude Code on your computer, "phone" to Claude Code running in Termux
     * on this phone, and "cloud" uses Anthropic-hosted agents.
     */
    var mode: String
        get() = prefs.getString("mode", MODE_BRIDGE) ?: MODE_BRIDGE
        set(value) = prefs.edit { putString("mode", value) }

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) = prefs.edit { putString("api_key", value.trim()) }

    var githubToken: String
        get() = prefs.getString("github_token", "").orEmpty()
        set(value) = prefs.edit { putString("github_token", value.trim()) }

    /** "owner/repo" entries for cloud mode. */
    var repos: List<String>
        get() = prefs.getString("repos", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit {
            putString("repos", value.map { it.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/') }.filter { it.contains('/') }.joinToString("\n"))
        }

    /** The cloud agent and environment this phone created, reused for every conversation. */
    var cloudAgentId: String?
        get() = prefs.getString("cloud_agent_id", null)
        set(value) = prefs.edit { putString("cloud_agent_id", value) }

    var cloudAgentSpec: Int
        get() = prefs.getInt("cloud_agent_spec", 0)
        set(value) = prefs.edit { putInt("cloud_agent_spec", value) }

    var cloudEnvironmentId: String?
        get() = prefs.getString("cloud_environment_id", null)
        set(value) = prefs.edit { putString("cloud_environment_id", value) }

    val isCloud get() = mode == MODE_CLOUD

    val isConfigured
        get() = if (isCloud) apiKey.isNotBlank() else bridgeUrl.isNotBlank() && token.isNotBlank()

    companion object {
        const val MODE_BRIDGE = "bridge"
        const val MODE_CLOUD = "cloud"
        const val MODE_PHONE = "phone"

        /** Where the Termux bridge listens: loopback only, so nothing off the phone can reach it. */
        const val PHONE_BRIDGE_URL = "https://127.0.0.1:8787"
    }
}
