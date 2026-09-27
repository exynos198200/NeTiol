package com.imnotahuman.codingagent.workspace

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

data class Project(val id: String, val name: String, val dir: File) {
    val isGit: Boolean get() = File(dir, ".git").isDirectory
}

/**
 * All projects live in app-specific storage: filesDir/projects/<id>.
 * Nothing is loaded into memory eagerly: listings are shallow, file reads are ranged.
 */
class WorkspaceManager(context: Context) {

    private val root: File = File(context.filesDir, "projects").apply { mkdirs() }

    private val _projects = MutableStateFlow(scan())
    val projects: StateFlow<List<Project>> = _projects

    private val _active = MutableStateFlow<Project?>(null)
    val active: StateFlow<Project?> = _active

    fun refresh() {
        _projects.value = scan()
        val current = _active.value
        if (current != null && !current.dir.isDirectory) _active.value = null
    }

    private fun scan(): List<Project> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory }
        .sortedByDescending { it.lastModified() }
        .map { Project(it.name, readName(it), it) }

    private fun readName(dir: File): String {
        val marker = File(dir, ".agent_project_name")
        return if (marker.isFile) marker.readText().trim().ifBlank { dir.name } else dir.name
    }

    fun select(project: Project?) {
        _active.value = project
    }

    fun byId(id: String?): Project? = id?.let { pid -> _projects.value.firstOrNull { it.id == pid } }

    private fun newDir(name: String): File {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "project" }
        var candidate = File(root, slug)
        var i = 2
        while (candidate.exists()) {
            candidate = File(root, "$slug-$i"); i++
        }
        candidate.mkdirs()
        File(candidate, ".agent_project_name").writeText(name.trim())
        return candidate
    }

    suspend fun createEmpty(name: String): Project = withContext(Dispatchers.IO) {
        val dir = newDir(name)
        File(dir, "README.md").writeText("# ${name.trim()}\n")
        refresh()
        Project(dir.name, name.trim(), dir).also { _active.value = it }
    }

    /** Creates the directory for a clone target; the caller performs the JGit clone into it. */
    suspend fun prepareCloneTarget(name: String): File = withContext(Dispatchers.IO) {
        val dir = newDir(name)
        dir.listFiles()?.forEach { if (it.name != ".agent_project_name") it.deleteRecursively() }
        dir
    }

    fun registerExisting(dir: File): Project {
        refresh()
        val p = Project(dir.name, readName(dir), dir)
        _active.value = p
        return p
    }

    suspend fun importZip(context: Context, uri: Uri, name: String): Project = withContext(Dispatchers.IO) {
        val dir = newDir(name)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open ZIP stream" }
            ZipInputStream(input.buffered()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(dir, entry.name).canonicalFile
                    if (!target.path.startsWith(dir.canonicalPath)) throw SecurityException("Zip slip blocked: ${entry.name}")
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out ->
                            var read = zip.read(buffer)
                            while (read > 0) {
                                out.write(buffer, 0, read)
                                read = zip.read(buffer)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        flattenSingleRoot(dir)
        refresh()
        Project(dir.name, name.trim(), dir).also { _active.value = it }
    }

    /** github zip exports wrap everything in one folder; unwrap it. */
    private fun flattenSingleRoot(dir: File) {
        val children = dir.listFiles()?.filter { it.name != ".agent_project_name" } ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
            inner.deleteRecursively()
        }
    }

    fun delete(project: Project) {
        project.dir.deleteRecursively()
        if (_active.value?.id == project.id) _active.value = null
        refresh()
    }
}
