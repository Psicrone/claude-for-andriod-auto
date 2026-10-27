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

    /** "bridge" talks to Claude Code on your computer; "cloud" uses Anthropic-hosted agents. */
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
    }
}
