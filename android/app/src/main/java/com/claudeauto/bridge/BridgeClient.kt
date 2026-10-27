package com.claudeauto.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BridgeSession(
    val id: String,
    val title: String,
    val project: String?,
    val lastModified: Long,
)

data class BridgeProject(val name: String, val path: String)

data class Approval(val id: String, val tool: String, val summary: String)

enum class TurnState { RUNNING, AWAITING_APPROVAL, DONE, ERROR, CANCELLED }

data class Turn(
    val id: String,
    val version: Int,
    val state: TurnState,
    val sessionId: String?,
    val project: String,
    val progress: String,
    val approval: Approval?,
    val reply: String?,
    val speech: String?,
    val error: String?,
) {
    val isActive get() = state == TurnState.RUNNING || state == TurnState.AWAITING_APPROVAL
}

class BridgeException(message: String, val status: Int = 0) : IOException(message)

/**
 * Talks to the claude-auto bridge running next to Claude Code on your computer, over HTTPS
 * only. [fingerprint] pins the bridge's own certificate; leave it null when the bridge sits
 * behind a proxy with a publicly trusted certificate (Tailscale Serve).
 */
class BridgeClient(baseUrl: String, private val token: String, fingerprint: String?) {

    private val base = requireHttps(baseUrl)

    private val http = baseBuilder()
        .let { if (fingerprint.isNullOrBlank()) it else PinnedTls.pin(it, fingerprint) }
        .build()

    suspend fun health(): String {
        val json = call("GET", "/health")
        return json.optString("version", "?")
    }

    suspend fun projects(): List<BridgeProject> {
        val array = call("GET", "/projects").getJSONArray("projects")
        return array.objects().map { BridgeProject(it.getString("name"), it.getString("path")) }
    }

    suspend fun sessions(limit: Int = 8): List<BridgeSession> {
        val array = call("GET", "/sessions?limit=$limit").getJSONArray("sessions")
        return array.objects().map {
            BridgeSession(
                id = it.getString("id"),
                title = it.optString("title", "Untitled"),
                project = it.optStringOrNull("project"),
                lastModified = it.optLong("lastModified"),
            )
        }
    }

    suspend fun startTurn(text: String, sessionId: String?, project: String?): Turn {
        val body = JSONObject().put("text", text)
        sessionId?.let { body.put("sessionId", it) }
        project?.let { body.put("project", it) }
        return parseTurn(call("POST", "/turns", body))
    }

    /** Returns as soon as the turn changes after [sinceVersion], or after [waitSeconds]. */
    suspend fun pollTurn(id: String, sinceVersion: Int, waitSeconds: Int = 25): Turn =
        parseTurn(call("GET", "/turns/$id?since=$sinceVersion&wait=$waitSeconds"))

    suspend fun answerApproval(turnId: String, approvalId: String, approve: Boolean): Turn {
        val body = JSONObject().put("approvalId", approvalId).put("approve", approve)
        return parseTurn(call("POST", "/turns/$turnId/approval", body))
    }

    suspend fun cancel(turnId: String): Turn = parseTurn(call("POST", "/turns/$turnId/cancel", JSONObject()))

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer $token")
                .method(method, body?.toString()?.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (!response.isSuccessful) {
                    val message = json.optStringOrNull("error") ?: "Bridge returned HTTP ${response.code}"
                    throw BridgeException(message, response.code)
                }
                json
            }
        }

    /** What the bridge presented on first contact, for the user to confirm before pairing. */
    data class Probe(val fingerprint: String, val systemTrusted: Boolean)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun baseBuilder() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Long polls wait up to 25s on the server.
            .readTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        fun requireHttps(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            if (!trimmed.startsWith("https://", ignoreCase = true)) {
                throw BridgeException("The bridge address must start with https://")
            }
            return trimmed
        }

        /** Reads the bridge's certificate without sending anything but a /health request. */
        suspend fun probe(url: String): Probe = withContext(Dispatchers.IO) {
            var chain: Array<java.security.cert.X509Certificate> = emptyArray()
            val client = PinnedTls.probe(baseBuilder()) { chain = it }.build()
            client.newCall(Request.Builder().url(requireHttps(url) + "/health").build()).execute().use { response ->
                if (!response.isSuccessful) throw BridgeException("No Claude Auto bridge at that address (HTTP ${response.code})")
            }
            val leaf = chain.firstOrNull() ?: throw BridgeException("The bridge didn't present a certificate")
            Probe(PinnedTls.fingerprint(leaf), PinnedTls.systemTrusts(chain))
        }

        /** Exchanges the one-time pairing code for this phone's own device token. */
        suspend fun pair(url: String, fingerprint: String?, code: String, deviceName: String): String =
            withContext(Dispatchers.IO) {
                val client = baseBuilder().let { if (fingerprint.isNullOrBlank()) it else PinnedTls.pin(it, fingerprint) }.build()
                val body = JSONObject().put("code", code).put("deviceName", deviceName).toString().toRequestBody(JSON)
                client.newCall(Request.Builder().url(requireHttps(url) + "/pair").post(body).build()).execute().use { response ->
                    val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
                    if (!response.isSuccessful) {
                        throw BridgeException(json.optStringOrNull("error") ?: "Pairing failed (HTTP ${response.code})", response.code)
                    }
                    json.getString("token")
                }
            }

        fun parseTurn(json: JSONObject): Turn {
            val approval = json.optJSONObject("approval")?.let {
                Approval(it.getString("id"), it.optString("tool"), it.optString("summary"))
            }
            return Turn(
                id = json.getString("id"),
                version = json.optInt("version"),
                state = when (json.optString("state")) {
                    "awaiting_approval" -> TurnState.AWAITING_APPROVAL
                    "done" -> TurnState.DONE
                    "error" -> TurnState.ERROR
                    "cancelled" -> TurnState.CANCELLED
                    else -> TurnState.RUNNING
                },
                sessionId = json.optStringOrNull("sessionId"),
                project = json.optString("project"),
                progress = json.optString("progress"),
                approval = approval,
                reply = json.optStringOrNull("reply"),
                speech = json.optStringOrNull("speech"),
                error = json.optStringOrNull("error"),
            )
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
