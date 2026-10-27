package com.claudeauto.backend

import java.io.IOException

/**
 * Where conversations run: Claude Code on your computer (through the bridge) or
 * Anthropic-hosted agents in the cloud. Both expose the same turn-based model so the
 * phone and car screens don't care which one is in use.
 */
interface Backend {
    suspend fun projects(): List<Project>

    suspend fun sessions(limit: Int = 8): List<RemoteSession>

    /** Sends [text] to [sessionId], or starts a new session in [project] when it's null. */
    suspend fun startTurn(text: String, sessionId: String?, project: String?): Turn

    /** Returns as soon as the turn changes after [sinceVersion], or after [waitSeconds]. */
    suspend fun pollTurn(id: String, sinceVersion: Int, waitSeconds: Int = 25): Turn

    suspend fun answerApproval(turnId: String, approvalId: String, approve: Boolean): Turn

    suspend fun cancel(turnId: String): Turn
}

data class RemoteSession(
    val id: String,
    val title: String,
    val project: String?,
    val lastModified: Long,
)

data class Project(val name: String, val path: String)

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

/** [status] is the HTTP status when there was one; 404 on a turn means the backend forgot it. */
class BackendException(message: String, val status: Int = 0) : IOException(message)
