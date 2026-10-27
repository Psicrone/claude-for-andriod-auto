package com.claudeauto.backend

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.agents.AgentCreateParams
import com.anthropic.models.beta.agents.AgentUpdateParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolset20260401Params
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolsetDefaultConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAutoPolicy
import com.anthropic.models.beta.agents.BetaManagedAgentsModel
import com.anthropic.models.beta.agents.BetaManagedAgentsWebFetchToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsWebSearchToolConfigParams
import com.anthropic.models.beta.environments.BetaCloudConfigParams
import com.anthropic.models.beta.environments.BetaLimitedNetworkParams
import com.anthropic.models.beta.environments.EnvironmentCreateParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsGitHubRepositoryResourceParams
import com.anthropic.models.beta.sessions.SessionCreateParams
import com.anthropic.models.beta.sessions.SessionListParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserInterruptEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserToolConfirmationEventParams
import com.anthropic.models.beta.sessions.events.EventSendParams
import com.claudeauto.Speech
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the phone stores for cloud mode. */
data class CloudSettings(
    val apiKey: String,
    /** Fine-grained GitHub token with Contents read/write on the repos below. */
    val githubToken: String,
    /** "owner/repo" entries; each becomes a "New chat in <repo>" slot. */
    val repos: List<String>,
    val agentId: String?,
    val agentSpecVersion: Int,
    val environmentId: String?,
)

/**
 * Runs conversations on Anthropic-hosted agents (Managed Agents), so no computer is needed.
 * Each conversation is a cloud session with its own sandbox, with the chosen GitHub repo
 * cloned into it. Claude can edit, run and push code there; risky tool calls the server
 * can't clear on its own pause for a spoken yes or no.
 */
class CloudBackend(
    private val settings: CloudSettings,
    /** Persists the agent and environment ids created on first use. */
    private val onProvisioned: (agentId: String, specVersion: Int, environmentId: String) -> Unit,
    clientFactory: (String) -> AnthropicClient = { key -> AnthropicOkHttpClient.builder().apiKey(key).build() },
) : Backend {

    private val client = clientFactory(settings.apiKey)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val setupLock = Mutex()
    private var agentId = settings.agentId.takeIf { settings.agentSpecVersion == AGENT_SPEC_VERSION }
    private var environmentId = settings.environmentId
    private val turns = ConcurrentHashMap<String, CloudTurn>()

    override suspend fun projects(): List<Project> =
        settings.repos.map { Project(name = it.substringAfterLast('/'), path = it) }
            .ifEmpty { listOf(Project(name = "scratch", path = "")) }

    override suspend fun sessions(limit: Int): List<RemoteSession> {
        val agent = ensureAgent()
        val page = io { client.beta().sessions().list(SessionListParams.builder().agentId(agent).limit(limit).build()) }
        return page.data().map { session ->
            val json = tree(session)
            RemoteSession(
                id = json.path("id").asText(),
                title = json.path("title").asText("").ifBlank { "Cloud session" },
                project = json.path("metadata").path("repo").asText("").substringAfterLast('/').ifBlank { null },
                lastModified = parseTime(json.path("updated_at").asText(json.path("created_at").asText(""))),
            )
        }
    }

    override suspend fun startTurn(text: String, sessionId: String?, project: String?): Turn {
        val sid = sessionId ?: createSession(text, project)
        val busy = turns.values.firstOrNull { it.sessionId == sid && it.state.value.isActive }
        if (busy != null) throw BackendException("Claude is still working on the last message in this session.", 409)

        val turn = CloudTurn(UUID.randomUUID().toString(), sid, project ?: "cloud")
        turns[turn.id] = turn
        scope.launch { run(turn, text) }
        return turn.state.value
    }

    override suspend fun pollTurn(id: String, sinceVersion: Int, waitSeconds: Int): Turn {
        val turn = turns[id] ?: throw BackendException("Unknown turn", 404)
        withTimeoutOrNull(waitSeconds * 1000L) { turn.state.first { it.version > sinceVersion } }
        return turn.state.value
    }

    override suspend fun answerApproval(turnId: String, approvalId: String, approve: Boolean): Turn {
        val turn = turns[turnId] ?: throw BackendException("Unknown turn", 404)
        if (turn.state.value.approval?.id != approvalId) throw BackendException("That approval is no longer pending.", 409)
        io {
            val confirmation = BetaManagedAgentsUserToolConfirmationEventParams.builder()
                .toolUseId(approvalId)
                .result(
                    if (approve) BetaManagedAgentsUserToolConfirmationEventParams.Result.ALLOW
                    else BetaManagedAgentsUserToolConfirmationEventParams.Result.DENY,
                )
                .apply { if (!approve) denyMessage("The driver declined this action. Ask before trying something else.") }
                .build()
            client.beta().sessions().events().send(turn.sessionId, EventSendParams.builder().addEvent(confirmation).build())
        }
        turn.bump { it.copy(state = TurnState.RUNNING, approval = null, progress = if (approve) "Approved" else "Skipped") }
        return turn.state.value
    }

    override suspend fun cancel(turnId: String): Turn {
        val turn = turns[turnId] ?: throw BackendException("Unknown turn", 404)
        if (turn.state.value.isActive) {
            turn.cancelled = true
            io {
                client.beta().sessions().events().send(
                    turn.sessionId,
                    EventSendParams.builder().addEvent(BetaManagedAgentsUserInterruptEventParams.builder().build()).build(),
                )
            }
            turn.bump { it.copy(state = TurnState.CANCELLED, approval = null, progress = "Cancelled") }
        }
        return turn.state.value
    }

    // ---------------------------------------------------------------- setup

    /** Creates the cloud environment and agent once, then reuses their ids. */
    private suspend fun ensureAgent(): String = setupLock.withLock {
        agentId?.let { return it }
        io {
            val env = environmentId ?: client.beta().environments().create(
                EnvironmentCreateParams.builder()
                    .name("claude-auto")
                    .config(
                        BetaCloudConfigParams.builder()
                            .networking(BetaLimitedNetworkParams.builder().allowPackageManagers(true).build())
                            .build(),
                    )
                    .build(),
            ).id()

            val toolset = BetaManagedAgentsAgentToolset20260401Params.builder()
                .defaultConfig(
                    BetaManagedAgentsAgentToolsetDefaultConfigParams.builder()
                        // The server runs safe calls, refuses high-risk ones, and pauses the rest
                        // for the driver's spoken yes or no.
                        .permissionPolicy(BetaManagedAgentsAutoPolicy.builder().build())
                        .build(),
                )
                .addConfig(BetaManagedAgentsWebFetchToolConfigParams.builder().enabled(false).build())
                .addConfig(BetaManagedAgentsWebSearchToolConfigParams.builder().enabled(false).build())
                .build()

            val existing = settings.agentId
            val id = if (existing != null) {
                // Created by an older version of the app: bring its prompt and tools up to date.
                val current = client.beta().agents().retrieve(existing)
                client.beta().agents().update(
                    existing,
                    AgentUpdateParams.builder()
                        .version(current.version())
                        .system(SYSTEM_PROMPT)
                        .addTool(toolset)
                        .build(),
                ).id()
            } else {
                client.beta().agents().create(
                    AgentCreateParams.builder()
                        .name("Claude Auto")
                        .model(BetaManagedAgentsModel.of(MODEL))
                        .system(SYSTEM_PROMPT)
                        .addTool(toolset)
                        .build(),
                ).id()
            }
            agentId = id
            environmentId = env
            onProvisioned(id, AGENT_SPEC_VERSION, env)
            id
        }
    }

    private suspend fun createSession(firstMessage: String, project: String?): String {
        val agent = ensureAgent()
        val repo = settings.repos.firstOrNull { it == project || it.substringAfterLast('/') == project }
        return io {
            val params = SessionCreateParams.builder()
                .agent(agent)
                .environmentId(environmentId!!)
                .title(firstMessage.take(60))
            if (repo != null) {
                params.addResource(
                    BetaManagedAgentsGitHubRepositoryResourceParams.builder()
                        .url("https://github.com/$repo")
                        .mountPath("/workspace/${repo.substringAfterLast('/')}")
                        .authorizationToken(settings.githubToken)
                        .build(),
                )
                params.metadata(SessionCreateParams.Metadata.builder().putAdditionalProperty("repo", com.anthropic.core.JsonValue.from(repo)).build())
            }
            client.beta().sessions().create(params.build()).id()
        }
    }

    // ---------------------------------------------------------------- running a turn

    /**
     * Streams the session's events for one user message. The stream has no replay, so on
     * every (re)connect the event history is read too and anything already handled is skipped.
     */
    private suspend fun run(turn: CloudTurn, text: String) {
        val seen = HashSet<String>()
        var sent = false
        var failures = 0
        while (turn.state.value.isActive || (turn.cancelled && !turn.finished)) {
            try {
                io {
                    client.beta().sessions().events().streamStreaming(turn.sessionId).use { stream ->
                        // History first: covers the gap before this connection, and on the
                        // first pass marks earlier turns' events as already seen.
                        for (event in client.beta().sessions().events().list(turn.sessionId).autoPager()) {
                            val json = tree(event)
                            if (!seen.add(json.path("id").asText())) continue
                            if (sent) handle(turn, json)
                        }
                        if (!sent) {
                            client.beta().sessions().events().send(
                                turn.sessionId,
                                EventSendParams.builder()
                                    .addEvent(BetaManagedAgentsUserMessageEventParams.builder().addTextContent(text).build())
                                    .build(),
                            )
                            sent = true
                        }
                        val iterator = stream.stream().iterator()
                        while (iterator.hasNext() && !turn.finished) {
                            val json = tree(iterator.next())
                            val id = json.path("id").asText()
                            if (id.isNotEmpty() && !seen.add(id)) continue
                            handle(turn, json)
                        }
                    }
                }
                failures = 0
            } catch (e: Exception) {
                if (!sent) {
                    turn.bump { it.copy(state = TurnState.ERROR, error = e.message ?: "Couldn't reach Anthropic") }
                    return
                }
                if (++failures > 30) {
                    turn.bump { it.copy(state = TurnState.ERROR, error = "Lost the connection to the cloud session.") }
                    return
                }
                delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
            }
            if (turn.finished) return
        }
    }

    private fun handle(turn: CloudTurn, event: JsonNode) {
        when (event.path("type").asText()) {
            "agent.message" -> {
                val text = event.path("content").filter { it.path("type").asText() == "text" }.joinToString("") { it.path("text").asText() }
                if (text.isNotBlank()) turn.lastMessage = text
            }
            "agent.tool_use", "agent.mcp_tool_use" -> {
                val name = event.path("name").asText("tool")
                @Suppress("UNCHECKED_CAST")
                val input = jsonMapper().convertValue(event.path("input"), Map::class.java) as? Map<String, Any?> ?: emptyMap()
                val summary = Speech.describeTool(name, input)
                if (event.path("evaluated_permission").asText() == "ask") {
                    turn.bump {
                        it.copy(
                            state = TurnState.AWAITING_APPROVAL,
                            approval = Approval(event.path("id").asText(), name, summary),
                            progress = "Claude wants to $summary",
                        )
                    }
                } else {
                    turn.bump { it.copy(progress = summary.replaceFirstChar(Char::uppercase)) }
                }
            }
            "session.status_idle" -> when (event.path("stop_reason").path("type").asText()) {
                "requires_action" -> Unit // waiting on the approval already announced
                "budget_reached" -> turn.finish { it.copy(state = TurnState.ERROR, error = "The session reached its spending limit.") }
                else -> turn.finish { current ->
                    if (turn.cancelled) current.copy(state = TurnState.CANCELLED)
                    else {
                        val reply = turn.lastMessage ?: "Done."
                        current.copy(state = TurnState.DONE, reply = reply, speech = Speech.fromMarkdown(reply), progress = "Done", approval = null)
                    }
                }
            }
            "session.status_terminated" -> turn.finish { it.copy(state = TurnState.ERROR, error = "The cloud session ended.") }
            "session.error" -> turn.finish {
                it.copy(state = TurnState.ERROR, error = event.path("error").path("message").asText("").ifBlank { "The cloud session hit an error." })
            }
        }
    }

    private class CloudTurn(val id: String, val sessionId: String, project: String) {
        val state = MutableStateFlow(
            Turn(id, 0, TurnState.RUNNING, sessionId, project, "Thinking", null, null, null, null),
        )
        @Volatile var cancelled = false
        @Volatile var finished = false
        @Volatile var lastMessage: String? = null

        fun bump(change: (Turn) -> Turn) = state.update { change(it).copy(version = it.version + 1) }

        fun finish(change: (Turn) -> Turn) {
            if (finished) return
            finished = true
            bump(change)
        }
    }

    private fun tree(value: Any): JsonNode = jsonMapper().valueToTree(value)

    private fun parseTime(iso: String): Long = runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val MODEL = "claude-opus-5-5"

        /** Bump when SYSTEM_PROMPT or the toolset changes so existing installs update their agent. */
        const val AGENT_SPEC_VERSION = 1

        val SYSTEM_PROMPT = listOf(
            "You are Claude, working on the user's code in a cloud sandbox while they drive.",
            "They talk to you through a car voice app: their messages come from speech recognition, so expect missing punctuation and misheard words, and infer the intent.",
            "Your final message is read aloud by text-to-speech: answer in two to four short spoken sentences, with no Markdown, tables, code blocks or file paths unless they asked for one. Summarise what you changed or found rather than showing it.",
            "Repositories are cloned under /workspace. When you change code, commit it to a new branch named claude/<short-topic>, push it, and say the branch name in your reply. Never push to main.",
            "Some actions need the driver's spoken approval, so batch risky work into few steps.",
        ).joinToString(" ")
    }
}
