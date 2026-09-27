package com.imnotahuman.codingagent.tools

import com.imnotahuman.codingagent.data.Settings
import com.imnotahuman.codingagent.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/**
 * Runs a command with the Android shell available to the app sandbox.
 * No root and no Termux dependency: whatever is not available simply reports an error.
 */
object RunCommandTool : Tool {
    override val name = "run_command"
    override val description =
        "Run a shell command inside the project directory using the device shell (no root). " +
            "Useful for ls, cat, grep, find, wc, unzip and similar toolbox commands."
    override val parameters = schema(
        "command" to prop("string", "Command line to execute"),
        "timeout_sec" to prop("integer", "Timeout in seconds, default 30, max 120"),
        required = listOf("command")
    )

    private val DANGEROUS = listOf(
        "rm -rf", "rm -r", "mkfs", "dd if=", ":(){", "chmod 777 /", "reboot", "pm uninstall",
        "su ", "curl ", "wget ", "nc ", "> /dev/", "shutdown"
    )

    override fun isDangerous(args: JsonObject, settings: Settings): Boolean {
        if (!settings.confirmDangerous) return false
        val cmd = args.str("command")?.lowercase() ?: return true
        return DANGEROUS.any { cmd.contains(it) }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val command = args.reqStr("command")
        val timeout = args.int("timeout_sec", 30).coerceIn(1, 120).toLong()
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(ctx.io.root)
            .redirectErrorStream(true)
            .start()

        val out = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val readerThread = Thread {
            runCatching {
                var line = reader.readLine()
                while (line != null) {
                    if (out.length < 60_000) out.append(line).append('\n')
                    line = reader.readLine()
                }
            }
        }
        readerThread.start()

        val finished = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            readerThread.join(1000)
            return@withContext ToolResult(false, "command timed out after ${timeout}s\n" + Redactor.redact(out.toString()).take(4000))
        }
        readerThread.join(2000)
        val code = process.exitValue()
        ToolResult(code == 0, "exit=$code\n" + Redactor.redact(out.toString()))
    }
}
