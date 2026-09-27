package com.imnotahuman.codingagent.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.AccentButton
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.OutlinedPanel
import com.imnotahuman.codingagent.ui.components.SectionTitle
import com.imnotahuman.codingagent.ui.components.TerminalField
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary

@Composable
fun ProjectsScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val projects by vm.projects.collectAsState()
    val active by vm.activeProject.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var repoUrl by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("") }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri, name.ifBlank { "imported-project" })
    }

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("new project")
            OutlinedPanel {
                Column {
                    TerminalField(name, { name = it }, "Project name")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentButton("NEW", enabled = name.isNotBlank()) { vm.createProject(name); name = "" }
                        GhostButton("IMPORT ZIP") { zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                    }
                }
            }
        }
        item {
            SectionTitle("clone github repository")
            OutlinedPanel {
                Column {
                    TerminalField(repoUrl, { repoUrl = it }, "https://github.com/owner/repo.git")
                    Spacer(Modifier.height(6.dp))
                    TerminalField(branch, { branch = it }, "branch (optional)")
                    Spacer(Modifier.height(8.dp))
                    AccentButton("CLONE", enabled = repoUrl.isNotBlank()) {
                        vm.cloneRepo(repoUrl.trim(), name.ifBlank { repoUrl.substringAfterLast('/').removeSuffix(".git") }, branch.trim().ifBlank { null })
                        repoUrl = ""; branch = ""
                    }
                }
            }
        }
        item { SectionTitle("projects (${projects.size})") }
        items(projects, key = { it.id }) { project ->
            OutlinedPanel(borderColor = if (project.id == active?.id) Accent else com.imnotahuman.codingagent.ui.theme.OutlineDim) {
                Column(Modifier.fillMaxWidth().clickable { vm.selectProject(project) }) {
                    Text(project.name, style = MonoSmall, color = TextPrimary)
                    Text(
                        (if (project.isGit) "git · " else "plain · ") + project.dir.absolutePath.takeLast(46),
                        style = MonoSmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("OPEN") { vm.selectProject(project) }
                        GhostButton("DELETE") { vm.deleteProject(project) }
                    }
                }
            }
        }
    }
}
