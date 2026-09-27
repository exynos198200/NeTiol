package com.imnotahuman.codingagent.agent

import kotlinx.serialization.Serializable

enum class ToolStatus { RUNNING, OK, FAILED, DENIED }

@Serializable
sealed class AgentEvent {
    abstract val id: String

    @Serializable
    data class User(override val id: String, val text: String) : AgentEvent()

    @Serializable
    data class Assistant(override val id: String, val text: String, val streaming: Boolean = false) : AgentEvent()

    @Serializable
    data class ToolRun(
        override val id: String,
        val tool: String,
        val args: String,
        val status: ToolStatus = ToolStatus.RUNNING,
        val result: String = "",
        val durationMs: Long = 0
    ) : AgentEvent()

    @Serializable
    data class DiffPreview(override val id: String, val path: String, val diff: String) : AgentEvent()

    @Serializable
    data class Status(override val id: String, val text: String) : AgentEvent()

    @Serializable
    data class Failure(override val id: String, val text: String) : AgentEvent()
}

@Serializable
data class AgentSession(
    val id: String,
    var title: String,
    val projectId: String?,
    val createdAt: Long,
    val events: MutableList<AgentEvent> = mutableListOf()
)

data class PendingApproval(
    val title: String,
    val details: String,
    val onDecision: (Boolean) -> Unit
)
