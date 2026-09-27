package com.imnotahuman.codingagent.data

import android.content.Context
import com.imnotahuman.codingagent.agent.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Sessions are persisted one JSON file per session and loaded lazily (metadata first). */
class SessionStore(context: Context) {

    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    suspend fun save(session: AgentSession) = withContext(Dispatchers.IO) {
        runCatching {
            File(dir, "${session.id}.json").writeText(json.encodeToString(AgentSession.serializer(), session))
        }
        Unit
    }

    suspend fun load(id: String): AgentSession? = withContext(Dispatchers.IO) {
        val file = File(dir, "$id.json")
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString(AgentSession.serializer(), file.readText()) }.getOrNull()
    }

    suspend fun list(): List<AgentSession> = withContext(Dispatchers.IO) {
        (dir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .sortedByDescending { it.lastModified() }
            .take(50)
            .mapNotNull { f -> runCatching { json.decodeFromString(AgentSession.serializer(), f.readText()) }.getOrNull() }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        Unit
    }
}
