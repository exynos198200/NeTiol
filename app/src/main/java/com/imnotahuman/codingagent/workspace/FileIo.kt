package com.imnotahuman.codingagent.workspace

import java.io.File
import java.io.RandomAccessFile

data class FileEntry(val name: String, val relativePath: String, val isDirectory: Boolean, val size: Long)

/**
 * Sandboxed, lazy file access inside a single project directory.
 * Every path is resolved and verified against the project root (no traversal outside).
 */
class FileIo(val root: File) {

    private val ignored = setOf(".git", "build", ".gradle", ".idea", "node_modules", ".agent_project_name")

    fun resolve(relative: String): File {
        val clean = relative.trim().removePrefix("./").trim('/')
        val file = if (clean.isEmpty()) root else File(root, clean)
        val canonical = file.canonicalFile
        if (canonical != root.canonicalFile && !canonical.path.startsWith(root.canonicalPath + File.separator)) {
            throw SecurityException("Path escapes project root: $relative")
        }
        return canonical
    }

    fun relativize(file: File): String =
        file.canonicalPath.removePrefix(root.canonicalPath).trimStart(File.separatorChar).replace(File.separatorChar, '/')

    fun list(relative: String = "", includeIgnored: Boolean = false): List<FileEntry> {
        val dir = resolve(relative)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { includeIgnored || it.name !in ignored }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { FileEntry(it.name, relativize(it), it.isDirectory, if (it.isFile) it.length() else 0L) }
    }

    /** Depth-limited tree walk, capped so huge repos never explode memory. */
    fun tree(relative: String = "", maxDepth: Int = 3, maxEntries: Int = 400): List<String> {
        val out = ArrayList<String>(64)
        fun walk(dir: File, depth: Int, prefix: String) {
            if (depth > maxDepth || out.size >= maxEntries) return
            val children = (dir.listFiles() ?: return)
                .filter { it.name !in ignored }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            for (child in children) {
                if (out.size >= maxEntries) {
                    out.add("$prefix… (truncated)")
                    return
                }
                if (child.isDirectory) {
                    out.add("$prefix${child.name}/")
                    walk(child, depth + 1, "$prefix  ")
                } else {
                    out.add("$prefix${child.name}  (${child.length()}b)")
                }
            }
        }
        walk(resolve(relative), 1, "")
        return out
    }

    fun isProbablyBinary(file: File): Boolean {
        if (!file.isFile) return false
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(raf.length(), 4096L).toInt()
            val buf = ByteArray(len)
            raf.readFully(buf)
            var nulls = 0
            for (b in buf) if (b.toInt() == 0) nulls++
            return nulls > 0
        }
    }

    /** Reads a line range lazily; never loads the whole file when a range is given. */
    fun readLines(relative: String, startLine: Int = 1, maxLines: Int = 400, maxChars: Int = 40_000): String {
        val file = resolve(relative)
        require(file.isFile) { "Not a file: $relative" }
        if (isProbablyBinary(file)) return "<binary file, ${file.length()} bytes>"
        val sb = StringBuilder()
        var index = 0
        var emitted = 0
        file.bufferedReader().useLines { seq ->
            for (line in seq) {
                index++
                if (index < startLine) continue
                if (emitted >= maxLines || sb.length > maxChars) {
                    sb.append("… truncated at line ").append(index).append('\n')
                    break
                }
                sb.append(index).append(": ").append(line).append('\n')
                emitted++
            }
        }
        return sb.toString()
    }

    fun readText(relative: String, maxChars: Int = 400_000): String {
        val file = resolve(relative)
        require(file.isFile) { "Not a file: $relative" }
        if (file.length() > maxChars) {
            val buf = CharArray(maxChars)
            file.reader().use { it.read(buf) }
            return String(buf)
        }
        return file.readText()
    }

    fun writeText(relative: String, content: String): File {
        val file = resolve(relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    fun create(relative: String, content: String): File {
        val file = resolve(relative)
        if (file.exists()) throw IllegalStateException("File already exists: $relative")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    fun delete(relative: String): Boolean {
        val file = resolve(relative)
        if (file.canonicalFile == root.canonicalFile) throw SecurityException("Refusing to delete project root")
        return file.deleteRecursively()
    }

    fun move(from: String, to: String): File {
        val src = resolve(from)
        val dst = resolve(to)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
        return dst
    }

    /** Glob-ish search over file names. */
    fun searchFiles(pattern: String, limit: Int = 100): List<String> {
        val regex = globToRegex(pattern)
        val out = ArrayList<String>()
        root.walkTopDown()
            .onEnter { it.name !in ignored }
            .forEach { f ->
                if (out.size >= limit) return@forEach
                if (f.isFile) {
                    val rel = relativize(f)
                    if (regex.containsMatchIn(rel)) out.add(rel)
                }
            }
        return out
    }

    /** Streaming grep: reads file by file, line by line. */
    fun searchText(query: String, glob: String?, limit: Int = 100, regexMode: Boolean = false): List<String> {
        val matcher = if (regexMode) Regex(query) else null
        val fileFilter = glob?.let { globToRegex(it) }
        val out = ArrayList<String>()
        root.walkTopDown()
            .onEnter { it.name !in ignored }
            .forEach { f ->
                if (out.size >= limit || !f.isFile) return@forEach
                val rel = relativize(f)
                if (fileFilter != null && !fileFilter.containsMatchIn(rel)) return@forEach
                if (f.length() > 2_000_000 || isProbablyBinary(f)) return@forEach
                var n = 0
                f.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        n++
                        val hit = if (matcher != null) matcher.containsMatchIn(line) else line.contains(query, ignoreCase = true)
                        if (hit) {
                            out.add("$rel:$n: ${line.trim().take(200)}")
                            if (out.size >= limit) return@useLines
                        }
                    }
                }
            }
        return out
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append('.')
                '.' -> sb.append("\\.")
                '/' -> sb.append('/')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
