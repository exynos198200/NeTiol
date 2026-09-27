package com.imnotahuman.codingagent.tools

import com.imnotahuman.codingagent.build.BuildManager
import com.imnotahuman.codingagent.data.Settings
import com.imnotahuman.codingagent.git.GitService
import com.imnotahuman.codingagent.github.GitHubApi
import com.imnotahuman.codingagent.github.RepoRef
import com.imnotahuman.codingagent.workspace.FileIo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ToolContext(
    val io: FileIo,
    val git: GitService,
    val github: GitHubApi,
    val buildManager: BuildManager,
    val settings: Settings,
    val repo: RepoRef?,
    /** Returns true when the user approved a dangerous operation. */
    val requestApproval: suspend (title: String, details: String) -> Boolean,
    /** Publishes a diff preview to the session UI. */
    val publishDiff: (path: String, diff: String) -> Unit
)

data class ToolResult(val ok: Boolean, val output: String)

interface Tool {
    val name: String
    val description: String
    /** JSON schema of the arguments object. */
    val parameters: JsonObject
    /** Whether this call must be confirmed by the user before running. */
    fun isDangerous(args: JsonObject, settings: Settings): Boolean = false
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotBlank() }?.content?.takeIf { it != "null" }

fun JsonObject.reqStr(key: String): String =
    str(key) ?: throw IllegalArgumentException("Missing required argument: $key")

fun JsonObject.int(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: default

fun JsonObject.bool(key: String, default: Boolean): Boolean {
    val p = this[key] as? JsonPrimitive ?: return default
    return p.booleanOrNull ?: (p.content.equals("true", ignoreCase = true))
}

fun JsonObject.strList(key: String): List<String> {
    val el = this[key] ?: return emptyList()
    return when (el) {
        is kotlinx.serialization.json.JsonArray -> el.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
        is JsonPrimitive -> el.content.split(",").map { it.trim() }.filter { it.isNotBlank() }
        else -> emptyList()
    }
}
