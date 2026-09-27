package com.imnotahuman.codingagent.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.agent.AgentEvent
import com.imnotahuman.codingagent.agent.ToolStatus
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.AccentButton
import com.imnotahuman.codingagent.ui.components.CodeBlock
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.OutlinedPanel
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.Danger
import com.imnotahuman.codingagent.ui.theme.MonoBody
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.OutlineDim
import com.imnotahuman.codingagent.ui.theme.Success
import com.imnotahuman.codingagent.ui.theme.Surface1
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary
import androidx.compose.runtime.collectAsState

@Composable
fun AgentScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val events by vm.events.collectAsState()
    val run by vm.runState.collectAsState()
    val listState = rememberLazyListState()
    var prompt by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }

    Column(modifier.fillMaxSize()) {
        ProjectHeader(vm)

        if (run.running) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Accent,
                trackColor = OutlineDim
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Spacer(Modifier.height(6.dp)) }
            items(events, key = { it.id }) { event -> EventRow(event) }
            item {
                if (run.running) {
                    Text("● step ${run.step}/${run.maxSteps}", style = MonoSmall, color = Accent, modifier = Modifier.padding(4.dp))
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Composer(
            prompt = prompt,
            onPromptChange = { prompt = it },
            running = run.running,
            onSend = {
                vm.send(prompt)
                prompt = ""
            },
            onStop = { vm.stop() }
        )
    }
}

@Composable
private fun ProjectHeader(vm: AgentViewModel) {
    val project by vm.activeProject.collectAsState()
    val status by vm.gitStatus.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface1)
            .border(1.dp, OutlineDim)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▌", color = Accent, style = MonoSmall)
            Spacer(Modifier.width(6.dp))
            Text(project?.name ?: "no project", style = MonoSmall, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(status?.branch ?: "—", style = MonoSmall, color = Accent)
        }
        Text(
            when {
                status == null -> "not a git repository"
                status!!.isClean -> "clean"
                else -> "${status!!.dirtyCount} change(s)"
            },
            style = MonoSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun EventRow(event: AgentEvent) {
    when (event) {
        is AgentEvent.User -> OutlinedPanel(borderColor = Accent.copy(alpha = 0.5f)) {
            Column {
                Text("you", style = MonoSmall, color = Accent)
                Text(event.text, style = MonoBody, color = TextPrimary)
            }
        }

        is AgentEvent.Assistant -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(if (event.streaming) "agent ▍" else "agent", style = MonoSmall, color = TextSecondary)
            Text(event.text, style = MonoBody, color = TextPrimary)
        }

        is AgentEvent.ToolRun -> ToolRunRow(event)

        is AgentEvent.DiffPreview -> ExpandableBlock(
            title = "diff · ${event.path}",
            titleColor = Accent,
            body = event.diff,
            initiallyExpanded = true,
            diff = true
        )

        is AgentEvent.Status -> Text("· ${event.text}", style = MonoSmall, color = TextSecondary)

        is AgentEvent.Failure -> OutlinedPanel(borderColor = Danger.copy(alpha = 0.6f)) {
            Text(event.text, style = MonoSmall, color = Danger)
        }
    }
}

@Composable
private fun ToolRunRow(event: AgentEvent.ToolRun) {
    val color = when (event.status) {
        ToolStatus.RUNNING -> Accent
        ToolStatus.OK -> Success
        ToolStatus.FAILED -> Danger
        ToolStatus.DENIED -> TextSecondary
    }
    ExpandableBlock(
        title = "${symbol(event.status)} ${event.tool}" + if (event.durationMs > 0) "  ${event.durationMs}ms" else "",
        titleColor = color,
        body = buildString {
            if (event.args.isNotBlank() && event.args != "{}") append(event.args).append("\n\n")
            append(event.result)
        }.trim(),
        initiallyExpanded = false
    )
}

private fun symbol(status: ToolStatus) = when (status) {
    ToolStatus.RUNNING -> "▶"
    ToolStatus.OK -> "✓"
    ToolStatus.FAILED -> "✗"
    ToolStatus.DENIED -> "⊘"
}

@Composable
private fun ExpandableBlock(
    title: String,
    titleColor: Color,
    body: String,
    initiallyExpanded: Boolean,
    diff: Boolean = false
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(4.dp))
            .border(1.dp, OutlineDim, RoundedCornerShape(4.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MonoSmall, color = titleColor, modifier = Modifier.weight(1f))
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextSecondary
                )
            }
        }
        AnimatedVisibility(visible = expanded && body.isNotBlank()) {
            Box(Modifier.heightIn(max = 320.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (diff) DiffText(body) else CodeBlock(body)
            }
        }
    }
}

@Composable
fun DiffText(diff: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
    ) {
        diff.split("\n").take(600).forEach { line ->
            val color = when {
                line.startsWith("+") -> Success
                line.startsWith("-") -> Danger
                line.startsWith("@@") -> Accent
                else -> TextSecondary
            }
            Text(line, style = MonoSmall, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun Composer(
    prompt: String,
    onPromptChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface1)
            .border(1.dp, OutlineDim)
            .padding(8.dp)
    ) {
        BasicTextField(
            value = prompt,
            onValueChange = onPromptChange,
            textStyle = MonoBody.copy(color = TextPrimary),
            cursorBrush = SolidColor(Accent),
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp, max = 140.dp)
                .padding(6.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("› ", style = MonoBody, color = Accent)
                    Box(Modifier.weight(1f)) {
                        if (prompt.isEmpty()) Text("ask the agent to change the project…", style = MonoBody, color = TextSecondary)
                        inner()
                    }
                }
            }
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            if (running) {
                GhostButton("STOP", onClick = onStop)
            } else {
                AccentButton("SEND", enabled = prompt.isNotBlank(), onClick = onSend)
            }
        }
    }
}
