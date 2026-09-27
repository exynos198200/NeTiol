package com.imnotahuman.codingagent.git

import com.imnotahuman.codingagent.security.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File

data class GitStatusInfo(
    val branch: String,
    val added: List<String>,
    val changed: List<String>,
    val modified: List<String>,
    val removed: List<String>,
    val missing: List<String>,
    val untracked: List<String>,
    val conflicting: List<String>
) {
    val isClean: Boolean
        get() = added.isEmpty() && changed.isEmpty() && modified.isEmpty() &&
                removed.isEmpty() && missing.isEmpty() && untracked.isEmpty() && conflicting.isEmpty()

    val dirtyCount: Int
        get() = added.size + changed.size + modified.size + removed.size + missing.size + untracked.size

    fun render(): String = buildString {
        append("branch: ").append(branch).append('\n')
        if (isClean) append("working tree clean") else {
            fun section(title: String, items: List<String>) {
                if (items.isNotEmpty()) append(title).append(": ").append(items.joinToString(", ")).append('\n')
            }
            section("staged-added", added)
            section("staged-changed", changed)
            section("modified", modified)
            section("staged-removed", removed)
            section("deleted", missing)
            section("untracked", untracked)
            section("conflicting", conflicting)
        }
    }
}

/** All git work goes through JGit; the system `git` binary is never used. */
class GitService(private val patProvider: () -> String?) {

    private fun creds(): UsernamePasswordCredentialsProvider? {
        val pat = patProvider() ?: return null
        return UsernamePasswordCredentialsProvider(pat, "")
    }

    private fun open(dir: File): Git = Git.open(dir)

    fun isRepo(dir: File): Boolean = File(dir, ".git").isDirectory

    suspend fun clone(url: String, target: File, branch: String? = null): String = withContext(Dispatchers.IO) {
        val cmd = Git.cloneRepository()
            .setURI(url)
            .setDirectory(target)
            .setCloneAllBranches(false)
        branch?.takeIf { it.isNotBlank() }?.let { cmd.setBranch(it) }
        creds()?.let { cmd.setCredentialsProvider(it) }
        cmd.call().use { git ->
            Redactor.redact("Cloned ${git.repository.branch} into ${target.name}")
        }
    }

    suspend fun init(dir: File): String = withContext(Dispatchers.IO) {
        Git.init().setDirectory(dir).call().use { "Initialized empty repository" }
    }

    suspend fun status(dir: File): GitStatusInfo = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val s = git.status().call()
            GitStatusInfo(
                branch = git.repository.branch ?: "(detached)",
                added = s.added.sorted(),
                changed = s.changed.sorted(),
                modified = s.modified.sorted(),
                removed = s.removed.sorted(),
                missing = s.missing.sorted(),
                untracked = s.untracked.sorted(),
                conflicting = s.conflicting.sorted()
            )
        }
    }

    /** Diff of the working tree (optionally staged) against HEAD, truncated for mobile. */
    suspend fun diff(dir: File, staged: Boolean = false, path: String? = null, maxChars: Int = 20_000): String =
        withContext(Dispatchers.IO) {
            open(dir).use { git ->
                val out = ByteArrayOutputStream()
                DiffFormatter(out).use { fmt ->
                    fmt.setRepository(git.repository)
                    fmt.setContext(3)
                    path?.takeIf { it.isNotBlank() }?.let {
                        fmt.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(it))
                    }
                    val headId = git.repository.resolve(Constants.HEAD + "^{tree}")
                    val oldTree = if (headId != null) {
                        CanonicalTreeParser().apply {
                            git.repository.newObjectReader().use { reader -> reset(reader, headId) }
                        }
                    } else null
                    val newTree = if (staged) {
                        org.eclipse.jgit.dircache.DirCacheIterator(git.repository.readDirCache())
                    } else {
                        FileTreeIterator(git.repository)
                    }
                    if (oldTree == null) {
                        out.write("no HEAD commit yet\n".toByteArray())
                    } else {
                        fmt.format(oldTree, newTree)
                    }
                }
                val text = out.toString("UTF-8")
                if (text.isBlank()) "no changes" else text.take(maxChars)
            }
        }

    suspend fun add(dir: File, patterns: List<String>): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val cmd = git.add()
            if (patterns.isEmpty()) cmd.addFilepattern(".") else patterns.forEach { cmd.addFilepattern(it) }
            cmd.call()
            git.add().setUpdate(true).addFilepattern(".").call()
            "staged: " + (if (patterns.isEmpty()) "all changes" else patterns.joinToString(", "))
        }
    }

    suspend fun commit(dir: File, message: String, addAll: Boolean = true, author: String = "AI Coding Agent", email: String = "agent@local"): String =
        withContext(Dispatchers.IO) {
            open(dir).use { git ->
                if (addAll) {
                    git.add().addFilepattern(".").call()
                    git.add().setUpdate(true).addFilepattern(".").call()
                }
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(PersonIdent(author, email))
                    .setCommitter(PersonIdent(author, email))
                    .call()
                "committed ${commit.name.take(8)}: ${commit.shortMessage}"
            }
        }

    suspend fun push(dir: File, force: Boolean = false, remote: String = "origin"): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val cmd = git.push().setRemote(remote).setForce(force)
            creds()?.let { cmd.setCredentialsProvider(it) }
                ?: return@withContext "push failed: GitHub PAT is not configured"
            val results = cmd.call()
            val sb = StringBuilder()
            for (r in results) for (u in r.remoteUpdates) sb.append(u.remoteName).append(" -> ").append(u.status).append('\n')
            Redactor.redact(sb.toString().ifBlank { "nothing to push" })
        }
    }

    suspend fun pull(dir: File, remote: String = "origin"): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val cmd = git.pull().setRemote(remote)
            creds()?.let { cmd.setCredentialsProvider(it) }
            val res = cmd.call()
            Redactor.redact("pull: successful=${res.isSuccessful} ${res.mergeResult?.mergeStatus ?: ""}")
        }
    }

    suspend fun branches(dir: File): List<String> = withContext(Dispatchers.IO) {
        open(dir).use { git -> git.branchList().call().map { it.name.removePrefix("refs/heads/") } }
    }

    suspend fun currentBranch(dir: File): String = withContext(Dispatchers.IO) {
        open(dir).use { it.repository.branch ?: "(unknown)" }
    }

    suspend fun createBranch(dir: File, name: String): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            git.checkout().setCreateBranch(true).setName(name).call()
            "created and checked out $name"
        }
    }

    suspend fun checkout(dir: File, name: String): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            git.checkout().setName(name).call()
            "checked out $name"
        }
    }

    suspend fun reset(dir: File, mode: ResetCommand.ResetType, ref: String = "HEAD"): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            git.reset().setMode(mode).setRef(ref).call()
            "reset $mode to $ref"
        }
    }

    suspend fun log(dir: File, limit: Int = 20): List<String> = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            git.log().setMaxCount(limit).call().map { "${it.name.take(8)}  ${it.shortMessage}" }
        }
    }

    suspend fun remoteUrl(dir: File): String? = withContext(Dispatchers.IO) {
        open(dir).use { git -> git.repository.config.getString("remote", "origin", "url") }
    }

    suspend fun setRemote(dir: File, url: String, remote: String = "origin"): String = withContext(Dispatchers.IO) {
        open(dir).use { git ->
            val cfg = git.repository.config
            cfg.setString("remote", remote, "url", url)
            cfg.setString("remote", remote, "fetch", "+refs/heads/*:refs/remotes/$remote/*")
            cfg.save()
            "remote $remote set"
        }
    }
}
