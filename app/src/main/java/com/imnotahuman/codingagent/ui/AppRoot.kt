package com.imnotahuman.codingagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.ui.components.CodeBlock
import com.imnotahuman.codingagent.ui.screens.AgentScreen
import com.imnotahuman.codingagent.ui.screens.BuildScreen
import com.imnotahuman.codingagent.ui.screens.EditorScreen
import com.imnotahuman.codingagent.ui.screens.FilesScreen
import com.imnotahuman.codingagent.ui.screens.GitScreen
import com.imnotahuman.codingagent.ui.screens.ProjectsScreen
import com.imnotahuman.codingagent.ui.screens.SessionsScreen
import com.imnotahuman.codingagent.ui.screens.SettingsScreen
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.AmoledBlack
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.Surface1
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary
import kotlinx.coroutines.launch

enum class Screen(val label: String) {
    AGENT("Agent"),
    PROJECTS("Projects"),
    FILES("Files"),
    EDITOR("Editor"),
    GIT("Git"),
    BUILD("Build"),
    SESSIONS("Sessions"),
    SETTINGS("Settings")
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AgentViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.AGENT) }
    val snackbar = remember { SnackbarHostState() }
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    val approval by vm.approval.collectAsState()

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it.text)
            vm.clearNotice()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Surface1, modifier = Modifier.width(230.dp)) {
                Column(Modifier.fillMaxHeight().padding(12.dp)) {
                    Text("AI CODING AGENT", style = MonoSmall, color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Screen.entries.forEach { item ->
                        Text(
                            (if (item == screen) "› " else "  ") + item.label.lowercase(),
                            style = MonoSmall,
                            color = if (item == screen) Accent else TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    screen = item
                                    scope.launch { drawerState.close() }
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    busy?.let { Text("… $it", style = MonoSmall, color = Accent) }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = AmoledBlack,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AmoledBlack, titleContentColor = TextPrimary),
                    title = { Text(screen.label.lowercase(), style = MonoSmall, color = TextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "menu", tint = Accent)
                        }
                    },
                    actions = {
                        if (screen == Screen.AGENT) {
                            IconButton(onClick = { vm.newChat() }) {
                                Icon(Icons.Default.Add, contentDescription = "new session", tint = Accent)
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    Screen.AGENT -> AgentScreen(vm)
                    Screen.PROJECTS -> ProjectsScreen(vm)
                    Screen.FILES -> FilesScreen(vm, onOpenFile = { screen = Screen.EDITOR })
                    Screen.EDITOR -> EditorScreen(vm)
                    Screen.GIT -> GitScreen(vm)
                    Screen.BUILD -> BuildScreen(vm)
                    Screen.SESSIONS -> SessionsScreen(vm, onOpen = { screen = Screen.AGENT })
                    Screen.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }

    approval?.let { pending ->
        AlertDialog(
            onDismissRequest = { pending.onDecision(false) },
            containerColor = Surface1,
            title = { Text(pending.title, style = MonoSmall, color = Accent) },
            text = {
                Box(Modifier.heightIn(max = 260.dp)) {
                    CodeBlock(pending.details, color = TextPrimary)
                }
            },
            confirmButton = {
                TextButton(onClick = { pending.onDecision(true) }) { Text("APPROVE", style = MonoSmall, color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { pending.onDecision(false) }) { Text("DENY", style = MonoSmall, color = TextSecondary) }
            }
        )
    }
}
