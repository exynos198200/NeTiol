package com.imnotahuman.codingagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.imnotahuman.codingagent.ui.theme.Accent
import com.imnotahuman.codingagent.ui.theme.MonoSmall
import com.imnotahuman.codingagent.ui.theme.OutlineDim
import com.imnotahuman.codingagent.ui.theme.Surface1
import com.imnotahuman.codingagent.ui.theme.TextPrimary
import com.imnotahuman.codingagent.ui.theme.TextSecondary

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        color = Accent,
        style = MonoSmall
    )
}

@Composable
fun OutlinedPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = OutlineDim,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(4.dp))
            .padding(10.dp)
    ) { content() }
}

@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, color: Color = TextPrimary, maxLines: Int = Int.MAX_VALUE) {
    Box(modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Text(text, style = MonoSmall, color = color, maxLines = maxLines, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun AccentButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
        modifier = modifier
    ) { Text(text, style = MonoSmall) }
}

@Composable
fun GhostButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.6f)),
        modifier = modifier
    ) { Text(text, style = MonoSmall, color = Accent) }
}

@Composable
fun TerminalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MonoSmall, color = TextSecondary) },
        placeholder = placeholder?.let { { Text(it, style = MonoSmall, color = TextSecondary) } },
        singleLine = singleLine,
        textStyle = MonoSmall.copy(color = TextPrimary),
        shape = RoundedCornerShape(4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Surface1,
            unfocusedContainerColor = Surface1,
            focusedIndicatorColor = Accent,
            unfocusedIndicatorColor = OutlineDim,
            cursorColor = Accent
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MonoSmall, color = TextSecondary)
        Text(value, style = MonoSmall, color = TextPrimary)
    }
}

@Composable
fun EmptyHint(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(text, style = MonoSmall, color = TextSecondary)
    }
}
