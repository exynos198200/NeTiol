package com.imnotahuman.codingagent.tools

import com.imnotahuman.codingagent.data.Settings
import com.imnotahuman.codingagent.security.Redactor
import kotlinx.serialization.json.JsonObject

object GitStatusTool : Tool {
    override val name = "git_status"
    override val description = "Show git status of the current project (branch, staged, modified, untracked)."
    override val parameters = schema()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.git.isRepo(ctx.io.root)) return ToolResult(false, "project is not a git repository")
        return ToolResult(true, ctx.git.status(ctx.io.root).render())
    }
}

object GitDiffTool : Tool {
    override val name = "git_diff"
    override val description = "Show the unified diff of working tree (or staged) changes against HEAD."
    override val parameters = schema(
        "path" to prop("string", "Optional path filter"),
        "staged" to prop("boolean", "Diff the index instead of the working tree")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.git.isRepo(ctx.io.root)) return ToolResult(false, "project is not a git repository")
        val diff = ctx.git.diff(ctx.io.root, staged = args.bool("staged", false), path = args.str("path"))
        args.str("path")?.let { ctx.publishDiff(it, diff) }
        return ToolResult(true, diff)
    }
}

object GitCommitTool : Tool {
    override val name = "git_commit"
    override val description = "Stage all changes and create a commit with the given message."
    override val parameters = schema(
        "message" to prop("string", "Commit message"),
        "add_all" to prop("boolean", "Stage every change first, default true"),
        required = listOf("message")
    )

    override fun isDangerous(args: JsonObject, settings: Settings): Boolean = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.git.isRepo(ctx.io.root)) return ToolResult(false, "project is not a git repository")
        val status = ctx.git.status(ctx.io.root)
        if (status.isClean) return ToolResult(false, "nothing to commit, working tree clean")
        return ToolResult(true, ctx.git.commit(ctx.io.root, args.reqStr("message"), args.bool("add_all", true)))
    }
}

object GitPushTool : Tool {
    override val name = "git_push"
    override val description = "Push the current branch to origin using the stored GitHub PAT."
    override val parameters = schema(
        "force" to prop("boolean", "Force push, requires explicit confirmation")
    )

    override fun isDangerous(args: JsonObject, settings: Settings): Boolean = settings.confirmDangerous

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ctx.git.isRepo(ctx.io.root)) return ToolResult(false, "project is not a git repository")
        val out = ctx.git.push(ctx.io.root, force = args.bool("force", false))
        return ToolResult(!out.contains("REJECTED") && !out.startsWith("push failed"), Redactor.redact(out))
    }
}

object GithubBuildTool : Tool {
    override val name = "github_build"
    override val description =
        "Trigger the GitHub Actions APK workflow (workflow_dispatch), or read the status/logs of the latest run. " +
            "action=start|status|logs."
    override val parameters = schema(
        "action" to prop("string", "start, status or logs (default status)"),
        "ref" to prop("string", "Git ref/branch for the dispatch")
    )

    override fun isDangerous(args: JsonObject, settings: Settings): Boolean = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val repo = ctx.repo ?: return ToolResult(false, "GitHub repository is not configured in Settings")
        return when (args.str("action")?.lowercase() ?: "status") {
            "start" -> {
                ctx.buildManager.start(repo, ctx.settings.workflowFile, args.str("ref") ?: ctx.settings.workflowRef)
                ToolResult(true, "workflow_dispatch sent for ${ctx.settings.workflowFile} on ${args.str("ref") ?: ctx.settings.workflowRef}. Poll with action=status.")
            }
            "logs" -> {
                val run = ctx.github.latestRuns(repo, ctx.settings.workflowFile, 1).firstOrNull()
                    ?: return ToolResult(false, "no runs found")
                ToolResult(true, ctx.github.jobs(repo, run.id) + "\n" + ctx.github.runLogs(repo, run.id))
            }
            else -> {
                val run = ctx.github.latestRuns(repo, ctx.settings.workflowFile, 1).firstOrNull()
                    ?: return ToolResult(false, "no runs found")
                ToolResult(true, "run ${run.id}: status=${run.status} conclusion=${run.conclusion} branch=${run.headBranch}\n${run.htmlUrl}")
            }
        }
    }
}

object ToolRegistry {
    val all: List<Tool> = listOf(
        ListFilesTool, ReadFileTool, SearchFilesTool, SearchTextTool,
        WriteFileTool, CreateFileTool, DeleteFileTool, MoveFileTool, PatchFileTool,
        RunCommandTool,
        GitStatusTool, GitDiffTool, GitCommitTool, GitPushTool,
        GithubBuildTool
    )

    private val byName = all.associateBy { it.name }

    fun find(name: String): Tool? = byName[name]

    fun openAiSchema(): kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.buildJsonArray {
        all.forEach { tool ->
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("function"))
                put("function", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(tool.name))
                    put("description", kotlinx.serialization.json.JsonPrimitive(tool.description))
                    put("parameters", tool.parameters)
                })
            })
        }
    }
}
