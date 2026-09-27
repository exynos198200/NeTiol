package com.imnotahuman.codingagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.ui.AgentViewModel
import com.imnotahuman.codingagent.ui.components.EmptyHint
import com.imnotahuman.codingagent.ui.components.GhostButton
import com.imnotahuman.codingagent.ui.components.TerminalField
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.OutlineDim
import com.imnotahuman.codingagent.ui.theme.Surface1
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while",
    "import", "package", "private", "public", "internal", "override", "suspend", "data",
    "null", "true", "false", "in", "is", "as", "try", "catch", "finally", "throw", "this", "super",
    "const", "companion", "sealed", "enum", "typealias", "def", "public", "static", "void", "new"
)

/** Regex-based highlighter: cheap enough for a Go device, no lexer, no background parsing. */
private object CodeHighlighter : VisualTransformation {
    private val stringRegex = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
    private val commentRegex = Regex("//[^\n]*|#[^\n]*|/\\*[\\s\\S]*?\\*/")
    private val wordRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val numberRegex = Regex("\\b\\d+(\\.\\d+)?\\b")

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        val annotated = buildAnnotatedString {
            append(src)
            numberRegex.findAll(src).forEach { addStyle(SpanStyle(color = Color(0xFFB5CEA8)), it.range.first, it.range.last + 1) }
            wordRegex.findAll(src).forEach {
                if (it.value in KEYWORDS) addStyle(SpanStyle(color = Accent), it.range.first, it.range.last + 1)
            }
            stringRegex.findAll(src).forEach { addStyle(SpanStyle(color = Color(0xFFCE9178)), it.range.first, it.range.last + 1) }
            commentRegex.findAll(src).forEach { addStyle(SpanStyle(color = TextSecondary), it.range.first, it.range.last + 1) }
        }
        return TransformedText(annotated, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

@Composable
fun EditorScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val state by vm.editor.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var showFind by remember { mutableStateOf(false) }
    val undoStack = remember { ArrayDeque<String>() }
    val redoStack = remember { ArrayDeque<String>() }

    if (state.path == null) {
        EmptyHint("Open a file from the Files tab.")
        return
    }

    val diff = state.diff
    if (diff != null) {
        Column(modifier.fillMaxSize().padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("diff · ${state.path}", style = MonoSmall, color = Accent, modifier = Modifier.weight(1f))
                GhostButton("CLOSE") { vm.closeDiff() }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f)) { DiffText(diff) }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                (if (state.dirty) "* " else "") + (state.path ?: ""),
                style = MonoSmall,
                color = if (state.dirty) Accent else TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GhostButton("SAVE", enabled = state.dirty) { vm.saveEditor() }
            GhostButton("DIFF") { vm.showEditorDiff() }
            GhostButton("FIND") { showFind = !showFind }
            GhostButton("UNDO", enabled = undoStack.isNotEmpty()) {
                val previous = undoStack.removeLastOrNull()
                if (previous != null) {
                    redoStack.addLast(state.content)
                    vm.editorChanged(previous)
                }
            }
            GhostButton("REDO", enabled = redoStack.isNotEmpty()) {
                val next = redoStack.removeLastOrNull()
                if (next != null) {
                    undoStack.addLast(state.content)
                    vm.editorChanged(next)
                }
            }
        }

        if (showFind) {
            Column(Modifier.padding(8.dp)) {
                TerminalField(searchQuery, { searchQuery = it }, "find")
                Spacer(Modifier.height(4.dp))
                TerminalField(replaceWith, { replaceWith = it }, "replace with")
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${if (searchQuery.isBlank()) 0 else Regex(Regex.escape(searchQuery)).findAll(state.content).count()} matches",
                        style = MonoSmall,
                        color = TextSecondary
                    )
                    GhostButton("REPLACE ALL", enabled = searchQuery.isNotBlank()) {
                        undoStack.addLast(state.content)
                        vm.editorChanged(state.content.replace(searchQuery, replaceWith))
                    }
                }
            }
        }

        val scroll = rememberScrollState()
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, OutlineDim)
                .verticalScroll(scroll)
        ) {
            val lineCount = state.content.count { it == '\n' } + 1
            Column(
                Modifier
                    .width(40.dp)
                    .background(Surface1)
                    .padding(vertical = 8.dp, horizontal = 4.dp)
            ) {
                for (i in 1..lineCount) Text("$i", style = MonoSmall, color = TextSecondary)
            }
            Box(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                BasicTextField(
                    value = state.content,
                    onValueChange = {
                        if (undoStack.size > 50) undoStack.removeFirst()
                        undoStack.addLast(state.content)
                        redoStack.clear()
                        vm.editorChanged(it)
                    },
                    textStyle = MonoSmall.copy(color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    visualTransformation = CodeHighlighter,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
