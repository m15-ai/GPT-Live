package com.n15.gptlive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n15.gptlive.prefs.DelegationModels

/** A tool as shown in settings: its API name, description and whether it is offered to the backend. */
data class ToolSetting(val name: String, val description: String, val enabled: Boolean)

/**
 * Delegation settings: which Responses model handles delegated work and which
 * function tools it may call. Changes are pushed with session.update, so they apply mid-session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DelegationScreen(
    selectedModel: String,
    tools: List<ToolSetting>,
    onSelectModel: (String) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val suggestedIds = DelegationModels.suggested.map { it.id }
    var custom by remember(selectedModel) {
        mutableStateOf(if (selectedModel in suggestedIds) "" else selectedModel)
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Delegation", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionHeader(
                    "Backend model",
                    "GPT-Live hands questions it cannot answer alone to this Responses model. " +
                        "It runs the tools below. Changes apply immediately, even mid-session."
                )
            }
            items(DelegationModels.suggested, key = { it.id }) { m ->
                val selected = m.id == selectedModel
                OptionRow(
                    title = m.id,
                    subtitle = m.detail,
                    selected = selected,
                    onClick = { custom = ""; onSelectModel(m.id) }
                )
            }
            DelegationModels.retiring[selectedModel]?.let { note ->
                item {
                    Text(
                        text = "$selectedModel is deprecated ($note). Pick a current model above.",
                        color = Color(0xFFE0A030),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            item {
                val active = custom.isNotBlank() && custom.trim() == selectedModel
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Custom model id") },
                    placeholder = { Text("e.g. gpt-6-sol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = if (active) Color.White else Color(0xFF666666),
                        unfocusedBorderColor = if (active) Color.White else Color(0xFF444444),
                        focusedLabelColor = Color(0xFFAAAAAA),
                        unfocusedLabelColor = Color(0xFF888888),
                        cursorColor = Color.White,
                    ),
                    trailingIcon = {
                        TextButton(
                            onClick = { if (custom.isNotBlank()) onSelectModel(custom.trim()) },
                            enabled = custom.isNotBlank() && !active
                        ) { Text(if (active) "In use" else "Use") }
                    }
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                SectionHeader(
                    "Tools",
                    if (tools.isEmpty()) "No tools registered. Add one to ServiceLocator.tools."
                    else "Function tools the backend may call. Disabled tools are not sent to the API."
                )
            }
            items(tools, key = { it.name }) { t ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1A1A1A),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(t.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(t.description, color = Color(0xFF888888), fontSize = 13.sp)
                        }
                        Switch(
                            checked = t.enabled,
                            onCheckedChange = { onToggleTool(t.name, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF2A2A2A),
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(hint, color = Color(0xFF888888), fontSize = 13.sp)
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(1.dp, Color.White) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color(0xFF888888), fontSize = 13.sp)
            }
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White)
        }
    }
}
