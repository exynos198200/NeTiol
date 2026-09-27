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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.OutlinedPanel
import com.imnotahuman.codingagent.ui.components.SectionTitle
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(vm: AgentViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val sessions by vm.sessions.collectAsState()
    LaunchedEffect(Unit) { vm.refreshSessions() }
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)

    LazyColumn(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("sessions (${sessions.size})")
            GhostButton("NEW SESSION") { vm.newChat(); onOpen() }
        }
        items(sessions, key = { it.id }) { session ->
            OutlinedPanel {
                Column(Modifier.fillMaxWidth().clickable { vm.openSession(session); onOpen() }) {
                    Text(session.title, style = MonoSmall, color = TextPrimary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt.format(Date(session.createdAt)), style = MonoSmall, color = TextSecondary)
                        Text("${session.events.size} events", style = MonoSmall, color = TextSecondary)
                    }
                    GhostButton("DELETE") { vm.deleteSession(session) }
                }
            }
        }
    }
}
