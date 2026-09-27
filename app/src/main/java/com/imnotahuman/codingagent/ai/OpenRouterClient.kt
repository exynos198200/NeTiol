package com.imnotahuman.codingagent.ai

import com.imnotahuman.codingagent.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext

class AiException(message: String) : Exception(message)

/**
 * OpenRouter chat-completions client with SSE streaming and tool calling.
 * The API key is only ever attached as an Authorization header — it is never
 * placed into prompts, tool output or UI text.
 */
class OpenRouterClient(
    private val client: OkHttpClient,
    private val keyProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val endpoint = "https://openrouter.ai/api/v1/chat/completions"

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://openrouter.ai/api/v1/models").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AiException("models request failed (${resp.code})")
            val body = resp.body?.string().orEmpty()
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                ?.sorted() ?: emptyList()
        }
    }

    /**
     * Streams one assistant turn. [onContentDelta] receives visible answer text only;
     * reasoning/thinking fields are deliberately ignored and never surfaced.
     */
    suspend fun streamTurn(
        model: String,
        messages: List<ChatMessage>,
        tools: JsonArray,
        temperature: Float,
        onContentDelta: (String) -> Unit
    ): AssistantTurn = withContext(Dispatchers.IO) {
        val key = keyProvider() ?: throw AiException("OpenRouter API key is not set")

        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/imnotahuman/ai-coding-agent")
            .header("X-Title", "AI Coding Agent")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val content = StringBuilder()
        val calls = LinkedHashMap<Int, MutableToolCall>()
        var finish: String? = null

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw AiException(Redactor.redact("OpenRouter ${resp.code}: ${err.take(500)}"))
            }
            val source = resp.body?.source() ?: throw AiException("empty response")
            while (!source.exhausted()) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                chunk["error"]?.let { throw AiException(Redactor.redact("OpenRouter error: $it")) }
                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                choice["finish_reason"]?.jsonPrimitive?.content?.takeIf { it != "null" }?.let { finish = it }
                val delta = choice["delta"]?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && it != "null" }?.let {
                    content.append(it)
                    onContentDelta(it)
                }
                delta["tool_calls"]?.jsonArray?.forEach { tcEl ->
                    val tc = tcEl.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.int ?: 0
                    val slot = calls.getOrPut(index) { MutableToolCall() }
                    tc["id"]?.jsonPrimitive?.content?.let { slot.id = it }
                    tc["function"]?.jsonObject?.let { fn ->
                        fn["name"]?.jsonPrimitive?.content?.let { slot.name = it }
                        fn["arguments"]?.jsonPrimitive?.content?.let { slot.args.append(it) }
                    }
                }
            }
        }

        AssistantTurn(
            content = content.toString(),
            toolCalls = calls.values.filter { it.name.isNotBlank() }.mapIndexed { i, c ->
                ToolCall(id = c.id ?: "call_$i", function = FunctionCall(c.name, c.args.toString()))
            },
            finishReason = finish
        )
    }

    private class MutableToolCall {
        var id: String? = null
        var name: String = ""
        val args = StringBuilder()
    }

    private fun ChatMessage.toJson(): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content ?: "")
        toolCallId?.let { put("tool_call_id", it) }
        name?.let { put("name", it) }
        toolCalls?.takeIf { it.isNotEmpty() }?.let { list ->
            put("tool_calls", buildJsonArray {
                list.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tc.function.name)
                            put("arguments", tc.function.arguments)
                        })
                    })
                }
            })
        }
    }
}
