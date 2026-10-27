package com.claudeauto.backend

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives CloudBackend against a fake Managed Agents API through the real Anthropic Java SDK,
 * so request building and event parsing are exercised end to end.
 */
class CloudBackendTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val sentEvents = CopyOnWriteArrayList<String>()
    private val history = CopyOnWriteArrayList<String>()
    private val live = LinkedBlockingQueue<String>()
    private var provisioned: Triple<String, Int, String>? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> route(ex) }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun backend(repos: List<String> = listOf("psicrone/demo")) = CloudBackend(
        CloudSettings(
            apiKey = "test-key",
            githubToken = "ghp_test",
            repos = repos,
            agentId = null,
            agentSpecVersion = 0,
            environmentId = null,
        ),
        onProvisioned = { a, v, e -> provisioned = Triple(a, v, e) },
        clientFactory = { key ->
            AnthropicOkHttpClient.builder().apiKey(key).baseUrl("http://127.0.0.1:${server.address.port}").build()
        },
    )

    @Test
    fun newConversationProvisionsOnceThenAsksForApprovalAndReplies() = runBlocking {
        val cloud = backend()
        var turn = cloud.startTurn("fix the flaky login test", sessionId = null, project = "demo")
        assertEquals("sesn_1", turn.sessionId)
        assertEquals(Triple("agent_1", CloudBackend.AGENT_SPEC_VERSION, "env_1"), provisioned)
        assertTrue(requests.any { it.startsWith("POST /v1/sessions ") && it.contains("github.com/psicrone/demo") && it.contains("ghp_test") })

        waitUntil { sentEvents.any { it.contains("user.message") } }
        emit("""{"type":"session.status_running","id":"e1"}""")
        emit("""{"type":"agent.tool_use","id":"sevt_tool","name":"bash","input":{"command":"npm test"},"evaluated_permission":"ask"}""")
        emit("""{"type":"session.status_idle","id":"e2","stop_reason":{"type":"requires_action","event_ids":["sevt_tool"]}}""")

        turn = poll(cloud, turn) { it.state == TurnState.AWAITING_APPROVAL }
        assertEquals("run the command npm test", turn.approval!!.summary)

        cloud.answerApproval(turn.id, "sevt_tool", approve = true)
        assertTrue(sentEvents.any { it.contains("user.tool_confirmation") && it.contains("sevt_tool") && it.contains("allow") })

        emit("""{"type":"agent.message","id":"e3","content":[{"type":"text","text":"**Fixed.** Pushed branch `claude/login-test`."}]}""")
        emit("""{"type":"session.status_idle","id":"e4","stop_reason":{"type":"end_turn"}}""")

        turn = poll(cloud, turn) { it.state == TurnState.DONE }
        assertEquals("Fixed. Pushed branch claude/login-test.", turn.speech)
    }

    @Test
    fun cancellingSendsAnInterrupt() = runBlocking {
        val cloud = backend(repos = emptyList())
        val turn = cloud.startTurn("refactor everything", null, null)
        waitUntil { sentEvents.any { it.contains("user.message") } }
        assertEquals(TurnState.CANCELLED, cloud.cancel(turn.id).state)
        assertTrue(sentEvents.any { it.contains("user.interrupt") })
        emit("""{"type":"session.status_idle","id":"e9","stop_reason":{"type":"end_turn"}}""")
    }

    @Test
    fun listsSessionsForThisAgentOnly() = runBlocking {
        val sessions = backend().sessions(5)
        assertEquals(listOf("Fix login"), sessions.map { it.title })
        assertEquals("demo", sessions.single().project)
        assertTrue(requests.any { it.startsWith("GET /v1/sessions?") && it.contains("agent_id=agent_1") })
    }

    // ------------------------------------------------------------------ fake API

    private fun emit(json: String) {
        history += json
        live.put(json)
    }

    private suspend fun poll(cloud: CloudBackend, start: Turn, until: (Turn) -> Boolean): Turn {
        var turn = start
        repeat(20) {
            if (until(turn)) return turn
            turn = cloud.pollTurn(turn.id, turn.version, 2)
        }
        throw AssertionError("Turn never reached the expected state: $turn")
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out; requests=$requests")
            Thread.sleep(20)
        }
    }

    private fun route(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().decodeToString()
        val path = ex.requestURI.toString()
        requests += "${ex.requestMethod} $path $body"
        val now = "2026-10-27T08:00:00Z"
        when {
            ex.requestMethod == "POST" && path.startsWith("/v1/environments") ->
                json(ex, """{"id":"env_1","type":"environment","name":"claude-auto","created_at":"$now"}""")
            ex.requestMethod == "POST" && path.startsWith("/v1/agents") ->
                json(ex, """{"id":"agent_1","type":"agent","name":"Claude Auto","version":1,"created_at":"$now","updated_at":"$now"}""")
            ex.requestMethod == "POST" && path.startsWith("/v1/sessions/sesn_1/events") -> {
                sentEvents += body
                json(ex, """{"data":[]}""")
            }
            ex.requestMethod == "POST" && path.startsWith("/v1/sessions") ->
                json(ex, """{"id":"sesn_1","type":"session","status":"idle","created_at":"$now","updated_at":"$now"}""")
            path.startsWith("/v1/sessions/sesn_1/events/stream") -> stream(ex)
            path.startsWith("/v1/sessions/sesn_1/events") ->
                json(ex, """{"data":[${history.joinToString(",")}],"next_page":null}""")
            path.startsWith("/v1/sessions") ->
                json(ex, """{"data":[{"id":"sesn_1","type":"session","title":"Fix login","status":"idle","metadata":{"repo":"psicrone/demo"},"created_at":"$now","updated_at":"$now"}],"next_page":null}""")
            else -> json(ex, """{"type":"error","error":{"type":"not_found_error","message":"$path"}}""", 404)
        }
    }

    private fun stream(ex: HttpExchange) {
        ex.responseHeaders.add("content-type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out ->
            while (true) {
                val event = live.poll(10, TimeUnit.SECONDS) ?: return
                out.write("event: message\ndata: $event\n\n".toByteArray())
                out.flush()
            }
        }
    }

    private fun json(ex: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("content-type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
