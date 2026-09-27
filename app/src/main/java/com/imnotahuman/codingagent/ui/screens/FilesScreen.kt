package com.imnotahuman.codingagent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.EmptyHint
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.SectionTitle
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary

/** Directory browser: only the current folder is read, never the whole tree. */
@Composable
fun FilesScreen(vm: AgentViewModel, onOpenFile: () -> Unit, modifier: Modifier = Modifier) {
    val project by vm.activeProject.collectAsState()
    var path by remember(project?.id) { mutableStateOf("") }
    val io = remember(project?.id) { vm.fileIo() }

    if (io == null) {
        EmptyHint("No project selected.")
        return
    }

    val entries = remember(path, project?.id, io) { runCatching { io.list(path) }.getOrDefault(emptyList()) }

    Column(modifier.fillMaxSize()) {
        SectionTitle("/" + path)
        Row(Modifier.padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("ROOT") { path = "" }
            if (path.isNotBlank()) GhostButton("UP") { path = path.substringBeforeLast('/', "") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
            items(entries, key = { it.relativePath }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory) path = entry.relativePath
                            else {
                                vm.openFile(entry.relativePath)
                                onOpenFile()
                            }
                        }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        (if (entry.isDirectory) "▸ " else "  ") + entry.name,
                        style = MonoSmall,
                        color = if (entry.isDirectory) Accent else TextPrimary
                    )
                    if (!entry.isDirectory) Text("${entry.size}b", style = MonoSmall, color = TextSecondary)
                }
            }
        }
    }
}
