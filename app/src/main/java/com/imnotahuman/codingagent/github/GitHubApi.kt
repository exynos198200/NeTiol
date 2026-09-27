package com.imnotahuman.codingagent.github

import com.imnotahuman.codingagent.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.zip.ZipInputStream

data class RepoRef(val owner: String, val repo: String) {
    override fun toString() = "$owner/$repo"
}

data class WorkflowRun(
    val id: Long,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val createdAt: String,
    val headBranch: String?
)

class GitHubException(message: String) : Exception(message)

/** Thin GitHub REST client. HTTPS only, PAT sent only in the Authorization header. */
class GitHubApi(
    private val client: OkHttpClient,
    private val patProvider: () -> String?
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.github.com"

    private fun request(url: String, accept: String = "application/vnd.github+json"): Request.Builder {
        val pat = patProvider() ?: throw GitHubException("GitHub PAT is not configured")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AICodingAgent-Android")
    }

    private fun exec(req: Request): String {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GitHubException(Redactor.redact("GitHub ${resp.code}: ${body.take(500)}"))
            }
            return body
        }
    }

    suspend fun currentUser(): String = withContext(Dispatchers.IO) {
        val body = exec(request("$base/user").get().build())
        json.parseToJsonElement(body).jsonObject["login"]?.jsonPrimitive?.content ?: "unknown"
    }

    suspend fun listRepos(limit: Int = 50): List<RepoRef> = withContext(Dispatchers.IO) {
        val body = exec(request("$base/user/repos?per_page=$limit&sort=updated&affiliation=owner,collaborator").get().build())
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val full = o["full_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val parts = full.split("/")
            if (parts.size == 2) RepoRef(parts[0], parts[1]) else null
        }
    }

    suspend fun dispatchWorkflow(repo: RepoRef, workflowFile: String, ref: String, inputs: Map<String, String> = emptyMap()): Unit =
        withContext(Dispatchers.IO) {
            val payload: JsonObject = buildJsonObject {
                put("ref", ref)
                if (inputs.isNotEmpty()) {
                    put("inputs", buildJsonObject { inputs.forEach { (k, v) -> put(k, v) } })
                }
            }
            val req = request("$base/repos/${repo.owner}/${repo.repo}/actions/workflows/$workflowFile/dispatches")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 204 && !resp.isSuccessful) {
                    throw GitHubException(Redactor.redact("Dispatch failed ${resp.code}: ${resp.body?.string()?.take(400)}"))
                }
            }
        }

    suspend fun latestRuns(repo: RepoRef, workflowFile: String? = null, limit: Int = 10): List<WorkflowRun> =
        withContext(Dispatchers.IO) {
            val path = if (workflowFile.isNullOrBlank()) {
                "$base/repos/${repo.owner}/${repo.repo}/actions/runs?per_page=$limit"
            } else {
                "$base/repos/${repo.owner}/${repo.repo}/actions/workflows/$workflowFile/runs?per_page=$limit"
            }
            val body = exec(request(path).get().build())
            json.parseToJsonElement(body).jsonObject["workflow_runs"]?.jsonArray?.map { it.jsonObject.toRun() } ?: emptyList()
        }

    suspend fun run(repo: RepoRef, runId: Long): WorkflowRun = withContext(Dispatchers.IO) {
        val body = exec(request("$base/repos/${repo.owner}/${repo.repo}/actions/runs/$runId").get().build())
        json.parseToJsonElement(body).jsonObject.toRun()
    }

    private fun JsonObject.toRun() = WorkflowRun(
        id = this["id"]!!.jsonPrimitive.long,
        status = this["status"]?.jsonPrimitive?.content ?: "unknown",
        conclusion = this["conclusion"]?.jsonPrimitive?.contentOrNullSafe(),
        htmlUrl = this["html_url"]?.jsonPrimitive?.content ?: "",
        createdAt = this["created_at"]?.jsonPrimitive?.content ?: "",
        headBranch = this["head_branch"]?.jsonPrimitive?.contentOrNullSafe()
    )

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this.content == "null") null else this.content

    /** Job-level summary, cheap compared to full logs. */
    suspend fun jobs(repo: RepoRef, runId: Long): String = withContext(Dispatchers.IO) {
        val body = exec(request("$base/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/jobs").get().build())
        val arr = json.parseToJsonElement(body).jsonObject["jobs"]?.jsonArray ?: return@withContext "no jobs"
        buildString {
            for (j in arr) {
                val o = j.jsonObject
                append(o["name"]?.jsonPrimitive?.content).append(" — ")
                    .append(o["status"]?.jsonPrimitive?.content).append('/')
                    .append(o["conclusion"]?.jsonPrimitive?.content).append('\n')
                o["steps"]?.jsonArray?.forEach { s ->
                    val so = s.jsonObject
                    append("   • ").append(so["number"]?.jsonPrimitive?.int).append(". ")
                        .append(so["name"]?.jsonPrimitive?.content).append(" [")
                        .append(so["conclusion"]?.jsonPrimitive?.content).append("]\n")
                }
            }
        }
    }

    /**
     * Downloads the run log archive and keeps only the tail of each entry,
     * so multi-megabyte logs never sit in RAM as a whole.
     */
    suspend fun runLogs(repo: RepoRef, runId: Long, maxChars: Int = 24_000): String = withContext(Dispatchers.IO) {
        val req = request("$base/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/logs").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw GitHubException("Logs unavailable (${resp.code})")
            val stream = resp.body?.byteStream() ?: return@withContext "empty log response"
            val tails = LinkedHashMap<String, ArrayDeque<String>>()
            ZipInputStream(stream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".txt")) {
                        val deque = ArrayDeque<String>()
                        zip.bufferedReader().let { reader ->
                            var line = reader.readLine()
                            while (line != null) {
                                deque.addLast(line.take(400))
                                if (deque.size > 120) deque.removeFirst()
                                line = reader.readLine()
                            }
                        }
                        tails[entry.name] = deque
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val sb = StringBuilder()
            for ((name, tail) in tails) {
                sb.append("=== ").append(name).append(" (tail) ===\n")
                tail.forEach { sb.append(it).append('\n') }
                if (sb.length > maxChars) break
            }
            Redactor.redact(sb.toString().take(maxChars).ifBlank { "no textual log entries" })
        }
    }

    data class Artifact(val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean)

    suspend fun artifacts(repo: RepoRef, runId: Long): List<Artifact> = withContext(Dispatchers.IO) {
        val body = exec(request("$base/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/artifacts").get().build())
        json.parseToJsonElement(body).jsonObject["artifacts"]?.jsonArray?.map {
            val o = it.jsonObject
            Artifact(
                id = o["id"]!!.jsonPrimitive.long,
                name = o["name"]?.jsonPrimitive?.content ?: "artifact",
                sizeBytes = o["size_in_bytes"]?.jsonPrimitive?.long ?: 0L,
                expired = o["expired"]?.jsonPrimitive?.content == "true"
            )
        } ?: emptyList()
    }

    /** Streams the artifact ZIP straight to disk. */
    suspend fun downloadArtifact(repo: RepoRef, artifactId: Long, target: File): File = withContext(Dispatchers.IO) {
        val req = request("$base/repos/${repo.owner}/${repo.repo}/actions/artifacts/$artifactId/zip").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw GitHubException("Artifact download failed (${resp.code})")
            target.parentFile?.mkdirs()
            resp.body?.byteStream()?.use { input ->
                target.outputStream().buffered().use { out -> input.copyTo(out, 64 * 1024) }
            } ?: throw GitHubException("Empty artifact body")
        }
        target
    }
}
