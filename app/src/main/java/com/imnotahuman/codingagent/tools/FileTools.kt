package com.imnotahuman.codingagent.tools

import com.imnotahuman.codingagent.data.Settings
import com.imnotahuman.codingagent.workspace.PatchApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

object ListFilesTool : Tool {
    override val name = "list_files"
    override val description = "List the project tree (directories and files) starting at a relative path. Depth limited."
    override val parameters = schema(
        "path" to prop("string", "Relative directory path, empty for project root"),
        "depth" to prop("integer", "Tree depth, 1-5, default 2")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.str("path") ?: ""
        val depth = args.int("depth", 2).coerceIn(1, 5)
        val lines = ctx.io.tree(path, maxDepth = depth)
        ToolResult(true, if (lines.isEmpty()) "(empty)" else lines.joinToString("\n"))
    }
}

object ReadFileTool : Tool {
    override val name = "read_file"
    override val description = "Read a text file with line numbers. Supports ranged reads for big files."
    override val parameters = schema(
        "path" to prop("string", "Relative file path"),
        "start_line" to prop("integer", "First line to read, default 1"),
        "max_lines" to prop("integer", "How many lines to return, default 400"),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val text = ctx.io.readLines(path, args.int("start_line", 1).coerceAtLeast(1), args.int("max_lines", 400).coerceIn(1, 2000))
        ToolResult(true, if (text.isBlank()) "(empty file)" else text)
    }
}

object SearchFilesTool : Tool {
    override val name = "search_files"
    override val description = "Find files by name pattern (glob-like, e.g. *.kt or app/src/**Activity*)."
    override val parameters = schema(
        "pattern" to prop("string", "Name/path pattern"),
        "limit" to prop("integer", "Max results, default 100"),
        required = listOf("pattern")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val res = ctx.io.searchFiles(args.reqStr("pattern"), args.int("limit", 100).coerceIn(1, 500))
        ToolResult(true, if (res.isEmpty()) "no matches" else res.joinToString("\n"))
    }
}

object SearchTextTool : Tool {
    override val name = "search_text"
    override val description = "Grep the project content line by line. Optionally restrict by file glob or use regex."
    override val parameters = schema(
        "query" to prop("string", "Text or regex to find"),
        "glob" to prop("string", "Optional file filter, e.g. *.kt"),
        "regex" to prop("boolean", "Treat query as regex, default false"),
        "limit" to prop("integer", "Max matches, default 80"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val res = ctx.io.searchText(
            query = args.reqStr("query"),
            glob = args.str("glob"),
            limit = args.int("limit", 80).coerceIn(1, 300),
            regexMode = args.bool("regex", false)
        )
        ToolResult(true, if (res.isEmpty()) "no matches" else res.joinToString("\n"))
    }
}

object CreateFileTool : Tool {
    override val name = "create_file"
    override val description = "Create a new file with content. Fails if the file already exists."
    override val parameters = schema(
        "path" to prop("string", "Relative file path"),
        "content" to prop("string", "Full file content"),
        required = listOf("path", "content")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val content = args.str("content") ?: ""
        val file = ctx.io.create(path, content)
        ctx.publishDiff(path, PatchApplier.diff("", content, path))
        ToolResult(true, "created $path (${file.length()} bytes)")
    }
}

object WriteFileTool : Tool {
    override val name = "write_file"
    override val description = "Overwrite a file with new full content, creating it if needed. Returns the resulting diff."
    override val parameters = schema(
        "path" to prop("string", "Relative file path"),
        "content" to prop("string", "Full new file content"),
        required = listOf("path", "content")
    )

    override fun isDangerous(args: JsonObject, settings: Settings): Boolean = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val content = args.str("content") ?: ""
        val existing = runCatching { ctx.io.readText(path) }.getOrDefault("")
        val diff = PatchApplier.diff(existing, content, path)
        ctx.io.writeText(path, content)
        ctx.publishDiff(path, diff)
        ToolResult(true, "wrote $path\n" + diff.take(4000))
    }
}

object DeleteFileTool : Tool {
    override val name = "delete_file"
    override val description = "Delete a file or directory inside the project."
    override val parameters = schema(
        "path" to prop("string", "Relative path to delete"),
        required = listOf("path")
    )

    override fun isDangerous(args: JsonObject, settings: Settings): Boolean = settings.confirmDangerous

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val ok = ctx.io.delete(path)
        ToolResult(ok, if (ok) "deleted $path" else "nothing deleted at $path")
    }
}

object MoveFileTool : Tool {
    override val name = "move_file"
    override val description = "Move or rename a file or directory inside the project."
    override val parameters = schema(
        "from" to prop("string", "Source relative path"),
        "to" to prop("string", "Destination relative path"),
        required = listOf("from", "to")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val dst = ctx.io.move(args.reqStr("from"), args.reqStr("to"))
        ToolResult(true, "moved to ${ctx.io.relativize(dst)}")
    }
}

object PatchFileTool : Tool {
    override val name = "patch_file"
    override val description = "Apply a unified diff patch to one file. Use for surgical edits instead of rewriting big files."
    override val parameters = schema(
        "path" to prop("string", "Relative file path"),
        "patch" to prop("string", "Unified diff with @@ hunks; +/-/space prefixed lines"),
        required = listOf("path", "patch")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val original = ctx.io.readText(path)
        val result = PatchApplier.apply(original, args.reqStr("patch"))
        if (!result.applied || result.newContent == null) return@withContext ToolResult(false, "patch failed: ${result.message}")
        ctx.io.writeText(path, result.newContent)
        val diff = PatchApplier.diff(original, result.newContent, path)
        ctx.publishDiff(path, diff)
        ToolResult(true, "${result.message} in $path\n" + diff.take(4000))
    }
}
