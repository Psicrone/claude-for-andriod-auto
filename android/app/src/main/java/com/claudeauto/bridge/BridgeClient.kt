package com.claudeauto.bridge

import com.claudeauto.backend.Approval
import com.claudeauto.backend.Backend
import com.claudeauto.backend.BackendException
import com.claudeauto.backend.Project
import com.claudeauto.backend.RemoteSession
import com.claudeauto.backend.Turn
import com.claudeauto.backend.TurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the claude-auto bridge running next to Claude Code on your computer, over HTTPS
 * only. [fingerprint] pins the bridge's own certificate; leave it null when the bridge sits
 * behind a proxy with a publicly trusted certificate (Tailscale Serve).
 */
class BridgeClient(baseUrl: String, private val token: String, fingerprint: String?) : Backend {

    private val base = requireHttps(baseUrl)

    private val http = baseBuilder()
        .let { if (fingerprint.isNullOrBlank()) it else PinnedTls.pin(it, fingerprint) }
        .build()

    suspend fun health(): String {
        val json = call("GET", "/health")
        return json.optString("version", "?")
    }

    override suspend fun projects(): List<Project> {
        val array = call("GET", "/projects").getJSONArray("projects")
        return array.objects().map { Project(it.getString("name"), it.getString("path")) }
    }

    override suspend fun sessions(limit: Int): List<RemoteSession> {
        val array = call("GET", "/sessions?limit=$limit").getJSONArray("sessions")
        return array.objects().map {
            RemoteSession(
                id = it.getString("id"),
                title = it.optString("title", "Untitled"),
                project = it.optStringOrNull("project"),
                lastModified = it.optLong("lastModified"),
            )
        }
    }

    override suspend fun startTurn(text: String, sessionId: String?, project: String?): Turn {
        val body = JSONObject().put("text", text)
        sessionId?.let { body.put("sessionId", it) }
        project?.let { body.put("project", it) }
        return parseTurn(call("POST", "/turns", body))
    }

    /** Returns as soon as the turn changes after [sinceVersion], or after [waitSeconds]. */
    override suspend fun pollTurn(id: String, sinceVersion: Int, waitSeconds: Int): Turn =
        parseTurn(call("GET", "/turns/$id?since=$sinceVersion&wait=$waitSeconds"))

    override suspend fun answerApproval(turnId: String, approvalId: String, approve: Boolean): Turn {
        val body = JSONObject().put("approvalId", approvalId).put("approve", approve)
        return parseTurn(call("POST", "/turns/$turnId/approval", body))
    }

    override suspend fun cancel(turnId: String): Turn = parseTurn(call("POST", "/turns/$turnId/cancel", JSONObject()))

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
                    throw BackendException(message, response.code)
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
                throw BackendException("The bridge address must start with https://")
            }
            return trimmed
        }

        /** Reads the bridge's certificate without sending anything but a /health request. */
        suspend fun probe(url: String): Probe = withContext(Dispatchers.IO) {
            var chain: Array<java.security.cert.X509Certificate> = emptyArray()
            val client = PinnedTls.probe(baseBuilder()) { chain = it }.build()
            client.newCall(Request.Builder().url(requireHttps(url) + "/health").build()).execute().use { response ->
                if (!response.isSuccessful) throw BackendException("No Claude Auto bridge at that address (HTTP ${response.code})")
            }
            val leaf = chain.firstOrNull() ?: throw BackendException("The bridge didn't present a certificate")
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
                        throw BackendException(json.optStringOrNull("error") ?: "Pairing failed (HTTP ${response.code})", response.code)
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
