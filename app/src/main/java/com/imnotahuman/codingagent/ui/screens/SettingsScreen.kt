package com.imnotahuman.codingagent.ui.screens

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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.data.KnownModels
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.AccentButton
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.KeyValueRow
import com.imnotahuman.codingagent.ui.components.OutlinedPanel
import com.imnotahuman.codingagent.ui.components.SectionTitle
import com.imnotahuman.codingagent.ui.components.TerminalField
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary

@Composable
fun SettingsScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsState()
    var apiKey by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var owner by remember(settings.githubOwner) { mutableStateOf(settings.githubOwner) }
    var repo by remember(settings.githubRepo) { mutableStateOf(settings.githubRepo) }
    var workflow by remember(settings.workflowFile) { mutableStateOf(settings.workflowFile) }
    var ref by remember(settings.workflowRef) { mutableStateOf(settings.workflowRef) }
    var steps by remember(settings.maxSteps) { mutableStateOf(settings.maxSteps.toString()) }
    var timeout by remember(settings.stepTimeoutSec) { mutableStateOf(settings.stepTimeoutSec.toString()) }
    var outputCap by remember(settings.maxToolOutputChars) { mutableStateOf(settings.maxToolOutputChars.toString()) }
    val masked = vm.secretsMasked

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("credentials (encrypted, keystore-backed)")
            OutlinedPanel {
                Column {
                    KeyValueRow("openrouter key", masked.first)
                    KeyValueRow("github pat", masked.second)
                    Spacer(Modifier.height(8.dp))
                    TerminalField(apiKey, { apiKey = it }, "OpenRouter API key", placeholder = "sk-or-…")
                    Spacer(Modifier.height(6.dp))
                    AccentButton("SAVE KEY") { vm.saveOpenRouterKey(apiKey); apiKey = "" }
                    Spacer(Modifier.height(10.dp))
                    TerminalField(pat, { pat = it }, "GitHub Personal Access Token", placeholder = "ghp_… (repo + workflow scope)")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccentButton("SAVE PAT") { vm.saveGithubPat(pat); pat = "" }
                        GhostButton("TEST GITHUB") { vm.checkGithub() }
                    }
                }
            }
        }
        item {
            SectionTitle("model")
            OutlinedPanel {
                Column {
                    TerminalField(model, { model = it }, "OpenRouter model id")
                    Spacer(Modifier.height(6.dp))
                    KnownModels.list.forEach { m ->
                        Text(
                            "· $m",
                            style = MonoSmall,
                            color = if (m == model) Accent else TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable { model = m }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AccentButton("APPLY MODEL") { vm.updateSettings { it.copy(model = model.trim()) } }
                }
            }
        }
        item {
            SectionTitle("github repository")
            OutlinedPanel {
                Column {
                    TerminalField(owner, { owner = it }, "owner")
                    Spacer(Modifier.height(6.dp))
                    TerminalField(repo, { repo = it }, "repository")
                    Spacer(Modifier.height(6.dp))
                    TerminalField(workflow, { workflow = it }, "workflow file")
                    Spacer(Modifier.height(6.dp))
                    TerminalField(ref, { ref = it }, "ref / branch")
                    Spacer(Modifier.height(6.dp))
                    AccentButton("SAVE REPO") {
                        vm.updateSettings {
                            it.copy(
                                githubOwner = owner.trim(),
                                githubRepo = repo.trim(),
                                workflowFile = workflow.trim().ifBlank { "build-apk.yml" },
                                workflowRef = ref.trim().ifBlank { "main" }
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("agent limits")
            OutlinedPanel {
                Column {
                    TerminalField(steps, { steps = it }, "max agent steps")
                    Spacer(Modifier.height(6.dp))
                    TerminalField(timeout, { timeout = it }, "step timeout, sec")
                    Spacer(Modifier.height(6.dp))
                    TerminalField(outputCap, { outputCap = it }, "max tool output chars")
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("confirm dangerous actions", style = MonoSmall, color = TextPrimary, modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.confirmDangerous,
                            onCheckedChange = { checked -> vm.updateSettings { it.copy(confirmDangerous = checked) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f))
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AccentButton("SAVE LIMITS") {
                        vm.updateSettings {
                            it.copy(
                                maxSteps = steps.toIntOrNull()?.coerceIn(1, 60) ?: it.maxSteps,
                                stepTimeoutSec = timeout.toIntOrNull()?.coerceIn(10, 600) ?: it.stepTimeoutSec,
                                maxToolOutputChars = outputCap.toIntOrNull()?.coerceIn(1000, 60000) ?: it.maxToolOutputChars
                            )
                        }
                    }
                }
            }
        }
    }
}
