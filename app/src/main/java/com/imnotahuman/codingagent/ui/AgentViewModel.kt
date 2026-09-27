package com.imnotahuman.codingagent.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imnotahuman.codingagent.AgentApp
import com.imnotahuman.codingagent.agent.AgentEngine
import com.imnotahuman.codingagent.agent.AgentEvent
import com.imnotahuman.codingagent.agent.AgentSession
import com.imnotahuman.codingagent.agent.PendingApproval
import com.imnotahuman.codingagent.build.BuildManager
import com.imnotahuman.codingagent.data.Settings
import com.imnotahuman.codingagent.git.GitStatusInfo
import com.imnotahuman.codingagent.github.RepoRef
import com.imnotahuman.codingagent.security.Redactor
import com.imnotahuman.codingagent.tools.ToolContext
import com.imnotahuman.codingagent.workspace.FileIo
import com.imnotahuman.codingagent.workspace.PatchApplier
import com.imnotahuman.codingagent.workspace.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class UiNotice(val text: String)

data class EditorState(
    val path: String? = null,
    val content: String = "",
    val dirty: Boolean = false,
    val diff: String? = null
)

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AgentApp).container
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<Settings> = container.settings.state
    val projects = container.workspace.projects
    val activeProject = container.workspace.active

    val buildManager = BuildManager(app, container.github, viewModelScope)
    val buildState = buildManager.state

    val engine = AgentEngine(viewModelScope, container.ai)
    val events: StateFlow<List<AgentEvent>> = engine.events
    val runState = engine.runState
    val approval: StateFlow<PendingApproval?> = engine.approval

    private val _gitStatus = MutableStateFlow<GitStatusInfo?>(null)
    val gitStatus: StateFlow<GitStatusInfo?> = _gitStatus

    private val _branches = MutableStateFlow<List<String>>(emptyList())
    val branches: StateFlow<List<String>> = _branches

    private val _notice = MutableStateFlow<UiNotice?>(null)
    val notice: StateFlow<UiNotice?> = _notice

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _editor = MutableStateFlow(EditorState())
    val editor: StateFlow<EditorState> = _editor

    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val sessions: StateFlow<List<AgentSession>> = _sessions

    private var currentSession: AgentSession = newSession()

    val secretsMasked: Pair<String, String>
        get() = container.secrets.mask(container.secrets.openRouterKey) to container.secrets.mask(container.secrets.githubPat)

    init {
        container.workspace.refresh()
        container.workspace.byId(settings.value.lastProjectId)?.let { selectProject(it) }
        engine.onChanged = { persistSession() }
        refreshSessions()
    }

    // ---------- projects ----------

    fun selectProject(project: Project?) {
        container.workspace.select(project)
        container.settings.update { it.copy(lastProjectId = project?.id) }
        refreshGit()
        _editor.value = EditorState()
    }

    fun createProject(name: String) = launchBusy("creating project") {
        val p = container.workspace.createEmpty(name)
        ensureWorkflowTemplate(p.dir)
        container.git.init(p.dir)
        selectProject(p)
        notify("Project ${p.name} created")
    }

    fun importZip(uri: Uri, name: String) = launchBusy("importing zip") {
        val p = container.workspace.importZip(getApplication<Application>(), uri, name)
        selectProject(p)
        notify("Imported ${p.name}")
    }

    fun cloneRepo(url: String, name: String, branch: String?) = launchBusy("cloning repository") {
        val target = container.workspace.prepareCloneTarget(name.ifBlank { url.substringAfterLast('/').removeSuffix(".git") })
        val authUrl = injectPat(url)
        container.git.clone(authUrl, target, branch)
        container.git.remoteUrl(target)?.let { container.git.setRemote(target, stripPat(it)) }
        val p = container.workspace.registerExisting(target)
        selectProject(p)
        notify("Cloned into ${p.name}")
    }

    fun deleteProject(project: Project) = launchBusy("deleting project") {
        container.workspace.delete(project)
        notify("Deleted ${project.name}")
    }

    /** PAT travels only inside the transport URL and is stripped from the stored remote. */
    private fun injectPat(url: String): String {
        val pat = container.secrets.githubPat ?: return url
        if (!url.startsWith("https://")) return url
        return url.replace("https://", "https://$pat@")
    }

    private fun stripPat(url: String): String = url.replace(Regex("""https://[^/@]+@"""), "https://")

    // ---------- git ----------

    fun refreshGit() = viewModelScope.launch {
        val dir = activeProject.value?.dir
        if (dir == null || !container.git.isRepo(dir)) {
            _gitStatus.value = null
            _branches.value = emptyList()
            return@launch
        }
        runCatching { container.git.status(dir) }.onSuccess { _gitStatus.value = it }
        runCatching { container.git.branches(dir) }.onSuccess { _branches.value = it }
    }

    fun gitCommit(message: String) = launchBusy("committing") {
        val dir = requireProject()
        notify(container.git.commit(dir, message))
        refreshGit()
    }

    fun gitPush(force: Boolean = false) = launchBusy("pushing") {
        val dir = requireProject()
        notify(Redactor.redact(container.git.push(dir, force)))
        refreshGit()
    }

    fun gitPull() = launchBusy("pulling") {
        notify(container.git.pull(requireProject()))
        refreshGit()
    }

    fun gitCheckout(branch: String) = launchBusy("checkout") {
        notify(container.git.checkout(requireProject(), branch))
        refreshGit()
    }

    fun gitCreateBranch(branch: String) = launchBusy("branching") {
        notify(container.git.createBranch(requireProject(), branch))
        refreshGit()
    }

    fun gitAddAll() = launchBusy("staging") {
        notify(container.git.add(requireProject(), emptyList()))
        refreshGit()
    }

    suspend fun gitDiffText(path: String? = null): String {
        val dir = activeProject.value?.dir ?: return "no project"
        if (!container.git.isRepo(dir)) return "not a git repository"
        return runCatching { container.git.diff(dir, false, path) }.getOrElse { "diff failed: ${it.message}" }
    }

    // ---------- editor ----------

    fun openFile(relative: String) = viewModelScope.launch {
        val io = fileIo() ?: return@launch
        runCatching { io.readText(relative) }
            .onSuccess { _editor.value = EditorState(relative, it, dirty = false) }
            .onFailure { notify("Cannot open: ${it.message}") }
    }

    fun editorChanged(text: String) {
        _editor.value = _editor.value.copy(content = text, dirty = true)
    }

    fun saveEditor() = viewModelScope.launch {
        val state = _editor.value
        val io = fileIo() ?: return@launch
        val path = state.path ?: return@launch
        runCatching { io.writeText(path, state.content) }
            .onSuccess {
                _editor.value = state.copy(dirty = false)
                notify("Saved $path")
                refreshGit()
            }
            .onFailure { notify("Save failed: ${it.message}") }
    }

    fun showEditorDiff() = viewModelScope.launch {
        val state = _editor.value
        val io = fileIo() ?: return@launch
        val path = state.path ?: return@launch
        val onDisk = runCatching { io.readText(path) }.getOrDefault("")
        _editor.value = state.copy(diff = PatchApplier.diff(onDisk, state.content, path))
    }

    fun closeDiff() {
        _editor.value = _editor.value.copy(diff = null)
    }

    fun fileIo(): FileIo? = activeProject.value?.let { FileIo(it.dir) }

    // ---------- agent ----------

    fun send(prompt: String) {
        if (activeProject.value == null) {
            notify("Open or create a project first")
            return
        }
        if (!container.secrets.hasOpenRouterKey()) {
            notify("Set your OpenRouter API key in Settings")
            return
        }
        engine.send(prompt, settings.value) { toolContext() }
    }

    fun stop() = engine.stop()

    fun newChat() {
        engine.clear()
        currentSession = newSession()
        refreshSessions()
    }

    fun askAiToFixBuild() {
        val context = buildManager.failureContextForAgent()
        send("Fix the build. Here is the GitHub Actions output:\n\n```\n$context\n```\nAnalyse the project, locate the root cause, apply the minimal fix and show me the diff.")
    }

    private fun toolContext(): ToolContext {
        val project = activeProject.value ?: throw IllegalStateException("No project selected")
        val s = settings.value
        val repo = if (s.githubOwner.isNotBlank() && s.githubRepo.isNotBlank()) RepoRef(s.githubOwner, s.githubRepo) else null
        return ToolContext(
            io = FileIo(project.dir),
            git = container.git,
            github = container.github,
            buildManager = buildManager,
            settings = s,
            repo = repo,
            requestApproval = { title, details ->
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    engine.requestApprovalFlow(title, details) { approved ->
                        if (cont.isActive) cont.resumeWith(Result.success(approved))
                    }
                }
            },
            publishDiff = { path, diff ->
                engine.publishDiff(path, diff)
                refreshGit()
            }
        )
    }

    // ---------- sessions ----------

    private fun newSession() = AgentSession(
        id = UUID.randomUUID().toString(),
        title = "Session ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date())}",
        projectId = activeProject.value?.id,
        createdAt = System.currentTimeMillis()
    )

    private fun persistSession() {
        val evts = events.value
        if (evts.isEmpty()) return
        val session = currentSession.copy(events = evts.toMutableList())
        (evts.firstOrNull { it is AgentEvent.User } as? AgentEvent.User)?.let {
            session.title = it.text.take(48)
        }
        currentSession = session
        ioScope.launch { container.sessions.save(session) }
    }

    fun refreshSessions() = viewModelScope.launch {
        _sessions.value = container.sessions.list()
    }

    fun openSession(session: AgentSession) {
        currentSession = session
        engine.restore(session.events)
    }

    fun deleteSession(session: AgentSession) = viewModelScope.launch {
        container.sessions.delete(session.id)
        refreshSessions()
    }

    // ---------- github / build ----------

    fun saveOpenRouterKey(key: String) {
        container.secrets.openRouterKey = key.trim().ifBlank { null }
        notify(if (key.isBlank()) "OpenRouter key cleared" else "OpenRouter key stored securely")
    }

    fun saveGithubPat(pat: String) {
        container.secrets.githubPat = pat.trim().ifBlank { null }
        notify(if (pat.isBlank()) "GitHub PAT cleared" else "GitHub PAT stored securely")
    }

    fun updateSettings(block: (Settings) -> Settings) = container.settings.update(block)

    fun checkGithub() = launchBusy("checking GitHub") {
        notify("Authenticated as ${container.github.currentUser()}")
    }

    fun startBuild() {
        val s = settings.value
        if (s.githubOwner.isBlank() || s.githubRepo.isBlank()) {
            notify("Set repository owner/name in Settings")
            return
        }
        buildManager.start(RepoRef(s.githubOwner, s.githubRepo), s.workflowFile, s.workflowRef)
    }

    fun cancelBuildPolling() = buildManager.cancel()

    fun addWorkflowToProject() = launchBusy("adding workflow") {
        val dir = requireProject()
        ensureWorkflowTemplate(dir)
        notify("Added .github/workflows/build-apk.yml")
        refreshGit()
    }

    private fun ensureWorkflowTemplate(dir: File) {
        val target = File(dir, ".github/workflows/build-apk.yml")
        if (target.isFile) return
        target.parentFile?.mkdirs()
        val asset = getApplication<Application>().assets.open("build-apk.yml").bufferedReader().use { it.readText() }
        target.writeText(asset)
    }

    // ---------- helpers ----------

    private fun requireProject(): File =
        activeProject.value?.dir ?: throw IllegalStateException("No project selected")

    private fun launchBusy(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = label
            runCatching { block() }.onFailure { notify(Redactor.redact(it.message ?: it.toString())) }
            _busy.value = null
        }
    }

    fun notify(text: String) {
        _notice.value = UiNotice(Redactor.redact(text))
    }

    fun clearNotice() {
        _notice.value = null
    }
}
