package com.imnotahuman.codingagent.agent

import com.imnotahuman.codingagent.ai.AssistantTurn
import com.imnotahuman.codingagent.ai.ChatMessage
import com.imnotahuman.codingagent.ai.OpenRouterClient
import com.imnotahuman.codingagent.data.Settings
import com.imnotahuman.codingagent.security.Redactor
import com.imnotahuman.codingagent.tools.ToolContext
import com.imnotahuman.codingagent.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

private const val SYSTEM_PROMPT = """
You are an autonomous coding agent running on an Android phone, operating on a real local project.

Rules:
- Work with tools. Never guess file contents: read files before editing them.
- Prefer patch_file for small edits and write_file when a file must be rewritten.
- Keep tool output small: use ranged reads, globs and grep instead of dumping whole trees.
- Never print, echo or ask for API keys, tokens or credentials. They are not available to you.
- Do not expose your reasoning. Reply with short, concrete status text and the final answer only.
- After changing files, summarise the change and, when asked, commit and push.
- Destructive operations (delete, push, force push, risky shell commands) may require user confirmation; if a call is denied, adapt instead of retrying blindly.
- When fixing a build, read the provided build log first, locate the failing module/file, then make the smallest correct fix.
"""

data class AgentRunState(
    val running: Boolean = false,
    val step: Int = 0,
    val maxSteps: Int = 0
)

/**
 * The agent loop: user prompt → model → tool calls → tool results → model → … → final answer.
 * Bounded by max steps, per-step timeout and tool output truncation. Cancellable via [stop].
 */
class AgentEngine(
    private val scope: CoroutineScope,
    private val ai: OpenRouterClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _events = MutableStateFlow<List<AgentEvent>>(emptyList())
    val events: StateFlow<List<AgentEvent>> = _events

    private val _runState = MutableStateFlow(AgentRunState())
    val runState: StateFlow<AgentRunState> = _runState

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval

    private val history = mutableListOf<ChatMessage>()
    private var job: Job? = null

    var onChanged: (() -> Unit)? = null

    fun restore(events: List<AgentEvent>) {
        _events.value = events
        history.clear()
        history.add(ChatMessage("system", SYSTEM_PROMPT.trim()))
        for (e in events) when (e) {
            is AgentEvent.User -> history.add(ChatMessage("user", e.text))
            is AgentEvent.Assistant -> if (e.text.isNotBlank()) history.add(ChatMessage("assistant", e.text))
            else -> Unit
        }
    }

    fun clear() {
        stop()
        _events.value = emptyList()
        history.clear()
    }

    fun stop() {
        job?.cancel()
        job = null
        _approval.value?.onDecision?.invoke(false)
        _approval.value = null
        _runState.value = AgentRunState(running = false)
        finishStreaming()
    }

    private fun push(event: AgentEvent) {
        _events.value = _events.value + event
        onChanged?.invoke()
    }

    private fun replace(id: String, transform: (AgentEvent) -> AgentEvent) {
        _events.value = _events.value.map { if (it.id == id) transform(it) else it }
        onChanged?.invoke()
    }

    private fun finishStreaming() {
        _events.value = _events.value.map {
            if (it is AgentEvent.Assistant && it.streaming) it.copy(streaming = false) else it
        }
    }

    fun send(prompt: String, settings: Settings, contextProvider: () -> ToolContext) {
        if (_runState.value.running) return
        val text = prompt.trim()
        if (text.isEmpty()) return

        push(AgentEvent.User(uid(), text))
        if (history.isEmpty()) history.add(ChatMessage("system", SYSTEM_PROMPT.trim()))
        history.add(ChatMessage("user", text))

        job = scope.launch {
            _runState.value = AgentRunState(running = true, step = 0, maxSteps = settings.maxSteps)
            try {
                loop(settings, contextProvider)
            } catch (c: kotlinx.coroutines.CancellationException) {
                push(AgentEvent.Status(uid(), "stopped by user"))
            } catch (t: Throwable) {
                push(AgentEvent.Failure(uid(), Redactor.redact(t.message ?: t.toString())))
            } finally {
                finishStreaming()
                _runState.value = AgentRunState(running = false)
                onChanged?.invoke()
            }
        }
    }

    private suspend fun loop(settings: Settings, contextProvider: () -> ToolContext) {
        val tools = ToolRegistry.openAiSchema()
        var step = 0
        while (step < settings.maxSteps) {
            step++
            _runState.value = _runState.value.copy(step = step)

            val assistantId = uid()
            push(AgentEvent.Assistant(assistantId, "", streaming = true))
            val buffer = StringBuilder()

            val turn: AssistantTurn = withTimeout(settings.stepTimeoutSec * 1000L) {
                ai.streamTurn(
                    model = settings.model,
                    messages = history.toList(),
                    tools = tools,
                    temperature = settings.temperature
                ) { delta ->
                    buffer.append(delta)
                    replace(assistantId) { (it as AgentEvent.Assistant).copy(text = Redactor.redact(buffer.toString()), streaming = true) }
                }
            }

            if (turn.content.isBlank() && turn.toolCalls.isNotEmpty()) {
                _events.value = _events.value.filterNot { it.id == assistantId }
            } else {
                replace(assistantId) { (it as AgentEvent.Assistant).copy(text = Redactor.redact(turn.content), streaming = false) }
            }

            history.add(
                ChatMessage(
                    role = "assistant",
                    content = turn.content,
                    toolCalls = turn.toolCalls.takeIf { it.isNotEmpty() }
                )
            )

            if (turn.toolCalls.isEmpty()) {
                onChanged?.invoke()
                return
            }

            for (call in turn.toolCalls) {
                val tool = ToolRegistry.find(call.function.name)
                val eventId = uid()
                val argsText = Redactor.redact(call.function.arguments.take(1200))
                push(AgentEvent.ToolRun(eventId, call.function.name, argsText))

                if (tool == null) {
                    replace(eventId) { (it as AgentEvent.ToolRun).copy(status = ToolStatus.FAILED, result = "unknown tool") }
                    history.add(ChatMessage("tool", "unknown tool ${call.function.name}", toolCallId = call.id, name = call.function.name))
                    continue
                }

                val args: JsonObject = runCatching {
                    json.parseToJsonElement(call.function.arguments.ifBlank { "{}" }).jsonObject
                }.getOrElse { kotlinx.serialization.json.JsonObject(emptyMap()) }

                val ctx = contextProvider()
                val started = System.currentTimeMillis()

                if (tool.isDangerous(args, settings)) {
                    val approved = ctx.requestApproval(
                        "Confirm ${tool.name}",
                        Redactor.redact(prettyArgs(args))
                    )
                    if (!approved) {
                        replace(eventId) {
                            (it as AgentEvent.ToolRun).copy(status = ToolStatus.DENIED, result = "denied by user")
                        }
                        history.add(ChatMessage("tool", "User denied this operation. Do not retry it; propose an alternative.", toolCallId = call.id, name = tool.name))
                        continue
                    }
                }

                val result = runCatching {
                    withTimeout(settings.stepTimeoutSec * 1000L) { tool.execute(args, ctx) }
                }.getOrElse { com.imnotahuman.codingagent.tools.ToolResult(false, "error: ${Redactor.redact(it.message ?: it.toString())}") }

                val output = Redactor.redact(result.output).let {
                    if (it.length > settings.maxToolOutputChars) it.take(settings.maxToolOutputChars) + "\n… output truncated" else it
                }

                replace(eventId) {
                    (it as AgentEvent.ToolRun).copy(
                        status = if (result.ok) ToolStatus.OK else ToolStatus.FAILED,
                        result = output,
                        durationMs = System.currentTimeMillis() - started
                    )
                }
                history.add(ChatMessage("tool", output.ifBlank { "(no output)" }, toolCallId = call.id, name = tool.name))
            }
        }
        push(AgentEvent.Status(uid(), "step limit reached (${settings.maxSteps}) — send a new prompt to continue"))
    }

    fun publishDiff(path: String, diff: String) {
        if (diff.isBlank()) return
        push(AgentEvent.DiffPreview(uid(), path, diff.take(20_000)))
    }

    fun requestApprovalFlow(title: String, details: String, onDecision: (Boolean) -> Unit) {
        _approval.value = PendingApproval(title, details) {
            _approval.value = null
            onDecision(it)
        }
    }

    private fun prettyArgs(args: JsonObject): String =
        args.entries.joinToString("\n") { (k, v) -> "$k = ${v.toString().take(400)}" }

    private fun uid(): String = UUID.randomUUID().toString()
}
