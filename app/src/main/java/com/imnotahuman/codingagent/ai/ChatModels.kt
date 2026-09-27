package com.imnotahuman.codingagent.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FunctionCall(
    val name: String = "",
    val arguments: String = ""
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

/** One assistant turn produced by the streaming client. */
data class AssistantTurn(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?
)
