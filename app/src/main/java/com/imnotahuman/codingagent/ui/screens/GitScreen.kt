package com.imnotahuman.codingagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.AccentButton
import com.imnotahuman.codingagent.ui.components.EmptyHint
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.OutlinedPanel
import com.imnotahuman.codingagent.ui.components.SectionTitle
import com.imnotahuman.codingagent.ui.components.TerminalField
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary

@Composable
fun GitScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val project by vm.activeProject.collectAsState()
    val status by vm.gitStatus.collectAsState()
    val branches by vm.branches.collectAsState()
    var commitMessage by remember { mutableStateOf("") }
    var newBranch by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf("") }

    LaunchedEffect(project?.id) { vm.refreshGit() }

    if (project == null) {
        EmptyHint("No project selected.")
        return
    }

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("status")
            OutlinedPanel {
                Text(status?.render() ?: "not a git repository", style = MonoSmall, color = TextPrimary)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostButton("REFRESH") { vm.refreshGit() }
                GhostButton("STAGE ALL") { vm.gitAddAll() }
                GhostButton("PULL") { vm.gitPull() }
            }
        }
        item {
            SectionTitle("commit")
            OutlinedPanel {
                Column {
                    TerminalField(commitMessage, { commitMessage = it }, "commit message")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccentButton("COMMIT", enabled = commitMessage.isNotBlank()) {
                            vm.gitCommit(commitMessage); commitMessage = ""
                        }
                        GhostButton("PUSH") { vm.gitPush(false) }
                    }
                }
            }
        }
        item {
            SectionTitle("branches")
            OutlinedPanel {
                Column {
                    branches.forEach { b ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(b, style = MonoSmall, color = if (b == status?.branch) TextPrimary else TextSecondary)
                            GhostButton("CHECKOUT") { vm.gitCheckout(b) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TerminalField(newBranch, { newBranch = it }, "new branch")
                    Spacer(Modifier.height(6.dp))
                    GhostButton("CREATE", enabled = newBranch.isNotBlank()) { vm.gitCreateBranch(newBranch); newBranch = "" }
                }
            }
        }
        item { SectionTitle("diff") }
        item {
            LoadDiffButton(vm) { diff = it }
            if (diff.isNotBlank()) {
                Box(Modifier.heightIn(max = 400.dp)) { DiffText(diff) }
            }
        }
    }
}

@Composable
private fun LoadDiffButton(vm: AgentViewModel, onLoaded: (String) -> Unit) {
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            onLoaded(vm.gitDiffText())
            loading = false
        }
    }
    AccentButton("SHOW WORKING DIFF", enabled = !loading) { loading = true }
}
