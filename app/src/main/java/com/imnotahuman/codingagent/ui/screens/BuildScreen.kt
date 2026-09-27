package com.imnotahuman.codingagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.build.BuildPhase
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.AccentButton
import com.imnotahuman.codingagent.ui.components.CodeBlock
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.KeyValueRow
import com.imnotahuman.codingagent.ui.components.OutlinedPanel
import com.imnotahuman.codingagent.ui.components.SectionTitle
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.Danger
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.Success
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary

@Composable
fun BuildScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val state by vm.buildState.collectAsState()
    val settings by vm.settings.collectAsState()

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("github actions")
            OutlinedPanel {
                Column {
                    KeyValueRow("repository", if (settings.githubOwner.isBlank()) "not configured" else "${settings.githubOwner}/${settings.githubRepo}")
                    KeyValueRow("workflow", settings.workflowFile)
                    KeyValueRow("ref", settings.workflowRef)
                    KeyValueRow("phase", state.phase.name)
                    state.runId?.let { KeyValueRow("run id", it.toString()) }
                    state.artifactPath?.let { KeyValueRow("artifact", it.substringAfterLast('/')) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.message,
                        style = MonoSmall,
                        color = when (state.phase) {
                            BuildPhase.SUCCESS -> Success
                            BuildPhase.FAILED, BuildPhase.ERROR -> Danger
                            else -> TextSecondary
                        }
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AccentButton("BUILD APK") { vm.startBuild() }
                GhostButton("STOP POLLING") { vm.cancelBuildPolling() }
                GhostButton("ADD WORKFLOW") { vm.addWorkflowToProject() }
            }
        }
        if (state.phase == BuildPhase.FAILED || state.phase == BuildPhase.ERROR) {
            item {
                AccentButton("ASK AI TO FIX BUILD", modifier = Modifier.fillMaxWidth()) { vm.askAiToFixBuild() }
            }
        }
        item {
            SectionTitle("build logs")
            OutlinedPanel(borderColor = Accent.copy(alpha = 0.4f)) {
                if (state.logs.isBlank()) {
                    Text("no logs yet", style = MonoSmall, color = TextSecondary)
                } else {
                    CodeBlock(state.logs, modifier = Modifier.heightIn(max = 460.dp), color = TextPrimary)
                }
            }
        }
    }
}
