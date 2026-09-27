package com.imnotahuman.codingagent.workspace

/**
 * Minimal but real unified-diff applier (single file, multiple hunks).
 * Context lines are matched with a small search window so slightly stale
 * line numbers coming from the model still apply.
 */
object PatchApplier {

    data class Result(val applied: Boolean, val message: String, val newContent: String?)

    private data class Hunk(val oldStart: Int, val lines: List<String>)

    fun apply(original: String, patch: String): Result {
        val hunks = parse(patch)
        if (hunks.isEmpty()) return Result(false, "No hunks found in patch", null)

        val src = original.split("\n").toMutableList()
        var offset = 0

        for ((index, hunk) in hunks.withIndex()) {
            val expected = hunk.lines.filter { it.startsWith(" ") || it.startsWith("-") }.map { it.substring(1) }
            val replacement = hunk.lines.filter { it.startsWith(" ") || it.startsWith("+") }.map { it.substring(1) }

            val guess = (hunk.oldStart - 1 + offset).coerceAtLeast(0)
            val at = locate(src, expected, guess)
                ?: return Result(false, "Hunk #${index + 1} does not match the file content", null)

            repeat(expected.size) { if (at < src.size) src.removeAt(at) }
            src.addAll(at, replacement)
            offset += replacement.size - expected.size
        }
        return Result(true, "Applied ${hunks.size} hunk(s)", src.joinToString("\n"))
    }

    private fun locate(src: List<String>, expected: List<String>, guess: Int): Int? {
        if (expected.isEmpty()) return guess.coerceIn(0, src.size)
        fun matches(at: Int): Boolean {
            if (at < 0 || at + expected.size > src.size) return false
            for (i in expected.indices) if (src[at + i].trimEnd() != expected[i].trimEnd()) return false
            return true
        }
        if (matches(guess)) return guess
        for (delta in 1..200) {
            if (matches(guess - delta)) return guess - delta
            if (matches(guess + delta)) return guess + delta
        }
        return null
    }

    private fun parse(patch: String): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        var current: MutableList<String>? = null
        var start = 0
        val header = Regex("""^@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@""")
        for (raw in patch.split("\n")) {
            val m = header.find(raw)
            if (m != null) {
                current?.let { hunks.add(Hunk(start, it)) }
                start = m.groupValues[1].toInt()
                current = ArrayList()
                continue
            }
            if (current == null) continue
            if (raw.startsWith("--- ") || raw.startsWith("+++ ") || raw.startsWith("diff ") || raw.startsWith("index ")) continue
            if (raw.startsWith("\\ No newline")) continue
            if (raw.isEmpty()) {
                current!!.add(" ")
            } else if (raw[0] == ' ' || raw[0] == '+' || raw[0] == '-') {
                current!!.add(raw)
            }
        }
        current?.let { hunks.add(Hunk(start, it)) }
        return hunks
    }

    /** Simple LCS-based unified diff used by the diff viewer and by tool results. */
    fun diff(oldText: String, newText: String, path: String = "file", context: Int = 3): String {
        val a = oldText.split("\n")
        val b = newText.split("\n")
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        data class Op(val tag: Char, val text: String)
        val ops = ArrayList<Op>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { ops.add(Op(' ', a[i])); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { ops.add(Op('-', a[i])); i++ }
                else -> { ops.add(Op('+', b[j])); j++ }
            }
        }
        while (i < a.size) { ops.add(Op('-', a[i])); i++ }
        while (j < b.size) { ops.add(Op('+', b[j])); j++ }

        if (ops.none { it.tag != ' ' }) return "No changes in $path"

        val sb = StringBuilder("--- a/$path\n+++ b/$path\n")
        var idx = 0
        while (idx < ops.size) {
            if (ops[idx].tag == ' ') { idx++; continue }
            var from = maxOf(0, idx - context)
            var to = idx
            while (to < ops.size && (ops[to].tag != ' ' || ops.subList(to, minOf(ops.size, to + context + 1)).any { it.tag != ' ' })) to++
            to = minOf(ops.size, to + context)
            sb.append("@@ block @@\n")
            for (k in from until to) sb.append(ops[k].tag).append(ops[k].text).append('\n')
            idx = to
        }
        return sb.toString()
    }
}
