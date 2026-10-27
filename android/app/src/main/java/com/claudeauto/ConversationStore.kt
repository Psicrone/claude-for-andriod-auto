package com.claudeauto

import com.claudeauto.bridge.BridgeClient
import com.claudeauto.bridge.BridgeException
import com.claudeauto.bridge.Turn
import com.claudeauto.bridge.TurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(
    val id: Long,
    val fromClaude: Boolean,
    val text: String,
    val time: Long,
    val read: Boolean,
)

data class Conversation(
    /** The Claude Code session id, or "new:<project>" for a conversation that hasn't started yet. */
    val key: String,
    val sessionId: String?,
    val project: String?,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val turn: Turn? = null,
    val lastActivity: Long = 0,
) {
    val isNew get() = sessionId == null
    val unread get() = messages.count { it.fromClaude && !it.read }
}

data class StoreState(
    val conversations: List<Conversation> = emptyList(),
    val connected: Boolean = false,
    val status: String = "Not connected",
)

/** A message from Claude that should reach the driver (notification and/or speech). */
data class ClaudeSaid(val conversation: Conversation, val message: ChatMessage)

/**
 * Shared state for the phone screen, the Android Auto screens and notifications:
 * the list of Claude Code conversations and the turn running in each.
 */
class ConversationStore(private val settings: Settings, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    private val _claudeSaid = MutableSharedFlow<ClaudeSaid>(extraBufferCapacity = 16)
    val claudeSaid: SharedFlow<ClaudeSaid> = _claudeSaid.asSharedFlow()

    private val ids = AtomicLong(System.currentTimeMillis())
    private var client: BridgeClient? = null
    private var clientKey = ""

    /** "new:<project>" keys whose conversation has since become a real session, mapped to the session id. */
    private val moved = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun conversation(key: String): Conversation? = _state.value.conversations.find { it.key == key }

    /** Follows a conversation that started as "new:<project>" to its session id. */
    fun resolve(key: String): String = moved[key] ?: key

    /** Call when the user deliberately opens a "new chat" slot, so it isn't redirected. */
    fun forgetMove(key: String) {
        moved.remove(key)
    }

    private fun client(): BridgeClient {
        check(settings.isConfigured) { "Pair the Claude Auto phone app with your computer first." }
        val key = listOf(settings.bridgeUrl, settings.token, settings.certFingerprint).joinToString("\u0000")
        if (client == null || key != clientKey) {
            client = BridgeClient(settings.bridgeUrl, settings.token, settings.certFingerprint.ifBlank { null })
            clientKey = key
        }
        return client!!
    }

    /** Loads projects and recent sessions from the bridge, keeping any local message history. */
    suspend fun refresh(): Result<Unit> = runCatching {
        val bridge = client()
        val projects = bridge.projects()
        val sessions = bridge.sessions()
        _state.update { current ->
            val existing = current.conversations.associateBy { it.key }
            val fresh = projects.map { project ->
                val key = "new:${project.name}"
                existing[key] ?: Conversation(key, null, project.name, "New chat in ${project.name}")
            } + sessions.map { session ->
                existing[session.id]?.copy(title = session.title)
                    ?: Conversation(session.id, session.id, session.project, session.title, lastActivity = session.lastModified)
            }
            // Keep conversations started here that the bridge didn't list (e.g. outside the project dirs).
            val kept = current.conversations.filter { c -> c.messages.isNotEmpty() && fresh.none { it.key == c.key } }
            current.copy(conversations = sort(fresh + kept), connected = true, status = "Connected")
        }
    }.onFailure { error ->
        _state.update { it.copy(connected = false, status = error.message ?: "Can't reach the bridge") }
    }

    /** Sends what the driver said to [key]: a new prompt, an approval answer, or "stop". */
    suspend fun send(key: String, text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        val conversation = conversation(key) ?: return
        addMessage(key, fromClaude = false, said)

        val turn = conversation.turn
        try {
            if (turn?.state == TurnState.AWAITING_APPROVAL && turn.approval != null) {
                when (VoiceCommands.answer(said)) {
                    VoiceCommands.Answer.YES -> client().answerApproval(turn.id, turn.approval.id, true)
                    VoiceCommands.Answer.NO -> client().answerApproval(turn.id, turn.approval.id, false)
                    VoiceCommands.Answer.UNCLEAR -> claude(key, "Sorry, was that a yes or a no? I'd like to ${turn.approval.summary}.")
                }
                return
            }
            if (turn?.isActive == true) {
                if (VoiceCommands.isStop(said)) {
                    client().cancel(turn.id)
                } else {
                    claude(key, "I'm still working on your last message. Say stop if you want me to cancel it.")
                }
                return
            }
            val started = client().startTurn(said, conversation.sessionId, conversation.project)
            setTurn(key, started)
            scope.launch { follow(key, started) }
        } catch (e: Exception) {
            claude(key, "I couldn't reach your computer. ${e.message ?: ""}".trim())
        }
    }

    /** Answers the pending approval directly (buttons on the phone or the car screen). */
    suspend fun answer(key: String, approve: Boolean) {
        val turn = conversation(key)?.turn ?: return
        val approval = turn.approval ?: return
        addMessage(key, fromClaude = false, if (approve) "Yes" else "No")
        runCatching { client().answerApproval(turn.id, approval.id, approve) }
            .onFailure { claude(key, "I couldn't send that answer. ${it.message ?: ""}".trim()) }
    }

    suspend fun stop(key: String) {
        val turn = conversation(key)?.turn?.takeIf { it.isActive } ?: return
        runCatching { client().cancel(turn.id) }
    }

    fun markRead(key: String) = edit(key) { c -> c.copy(messages = c.messages.map { it.copy(read = true) }) }

    /** Polls the bridge until the turn finishes, relaying approvals and the final reply. */
    private suspend fun follow(startKey: String, first: Turn) {
        var key = startKey
        var turn = first
        var announcedApproval: String? = null
        var failures = 0

        while (true) {
            key = setTurn(key, turn)
            when (turn.state) {
                TurnState.AWAITING_APPROVAL -> turn.approval?.let { approval ->
                    if (approval.id != announcedApproval) {
                        announcedApproval = approval.id
                        claude(key, "I'd like to ${approval.summary}. Say yes to allow it, or no to skip it.")
                    }
                }
                TurnState.DONE -> return claude(key, turn.speech?.ifBlank { null } ?: "Done.")
                TurnState.ERROR -> return claude(key, "That didn't work: ${turn.error ?: "unknown error"}")
                TurnState.CANCELLED -> return claude(key, "OK, I stopped.")
                TurnState.RUNNING -> Unit
            }

            turn = try {
                client().pollTurn(turn.id, turn.version).also { failures = 0 }
            } catch (e: BridgeException) {
                if (e.status == 404) return claude(key, "Your computer restarted the bridge, so I lost track of that request.")
                if (++failures == 3) claude(key, "I've lost the connection to your computer. I'll keep trying.")
                delay(backoff(failures))
                continue
            } catch (e: IOException) {
                if (++failures == 3) claude(key, "I've lost the connection to your computer. I'll keep trying.")
                if (failures > 60) return claude(key, "I gave up waiting for your computer. Ask me again when you're back in signal.")
                delay(backoff(failures))
                continue
            }
        }
    }

    private fun backoff(failures: Int) = minOf(30_000L, 1_000L shl minOf(failures, 5))

    /**
     * Records the latest turn. When a new conversation gets its Claude Code session id, its
     * history moves to a conversation keyed by that id and the "new" slot is emptied.
     * Returns the key the conversation now lives under.
     */
    private fun setTurn(key: String, turn: Turn): String {
        var newKey = key
        _state.update { current ->
            val conversation = current.conversations.find { it.key == key } ?: return@update current
            val sessionId = turn.sessionId
            if (conversation.isNew && sessionId != null) {
                newKey = sessionId
                moved[key] = sessionId
                val movedConversation = conversation.copy(
                    key = sessionId,
                    sessionId = sessionId,
                    title = conversation.messages.firstOrNull { !it.fromClaude }?.text?.take(60) ?: "New session",
                    turn = turn,
                    lastActivity = System.currentTimeMillis(),
                )
                val reset = conversation.copy(messages = emptyList(), turn = null)
                current.copy(conversations = sort(current.conversations.map { if (it.key == key) reset else it } + movedConversation))
            } else {
                current.copy(conversations = current.conversations.map { if (it.key == key) it.copy(turn = turn) else it })
            }
        }
        return newKey
    }

    private fun claude(key: String, text: String) {
        val message = addMessage(key, fromClaude = true, text) ?: return
        conversation(key)?.let { _claudeSaid.tryEmit(ClaudeSaid(it, message)) }
    }

    private fun addMessage(key: String, fromClaude: Boolean, text: String): ChatMessage? {
        val message = ChatMessage(ids.incrementAndGet(), fromClaude, text, System.currentTimeMillis(), read = !fromClaude)
        var added = false
        _state.update { current ->
            added = current.conversations.any { it.key == key }
            current.copy(
                conversations = sort(current.conversations.map {
                    if (it.key == key) it.copy(messages = (it.messages + message).takeLast(MAX_MESSAGES), lastActivity = message.time) else it
                }),
            )
        }
        return message.takeIf { added }
    }

    private fun edit(key: String, change: (Conversation) -> Conversation) = _state.update { current ->
        current.copy(conversations = current.conversations.map { if (it.key == key) change(it) else it })
    }

    /** New-chat slots first (one per project), then sessions by most recent activity. */
    private fun sort(list: List<Conversation>) =
        list.sortedWith(compareByDescending<Conversation> { it.isNew }.thenByDescending { it.lastActivity })

    companion object {
        const val MAX_MESSAGES = 30
    }
}
