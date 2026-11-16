package com.m15.gptlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m15.gptlive.prefs.LiveVoices

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerScreen(
    selectedVoiceId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    var custom by remember(selectedVoiceId) {
        mutableStateOf(if (LiveVoices.isCustom(selectedVoiceId)) selectedVoiceId else "")
    }
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Voice", color = Color.White, fontWeight = FontWeight.Bold)
                },
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
            items(LiveVoices.all, key = { it.id }) { voice ->
                val selected = voice.id == selectedVoiceId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(voice.id) },
                    color = if (selected) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                    shape = MaterialTheme.shapes.large,
                    border = if (selected) {
                        androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                    } else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = voice.label,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = voice.detail,
                                color = Color(0xFF888888),
                                fontSize = 13.sp
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Custom voice",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "A prompt-to-voice or cloned voice id from the API or the voice Playground. " +
                        "Applies to the next session.",
                    color = Color(0xFF888888),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                val trimmed = custom.trim()
                val active = trimmed.isNotBlank() && trimmed == selectedVoiceId
                val valid = LiveVoices.isCustom(trimmed) && trimmed.length > LiveVoices.CUSTOM_PREFIX.length
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text("Voice id") },
                    placeholder = { Text("voice_…") },
                    singleLine = true,
                    isError = trimmed.isNotBlank() && !valid,
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
                            onClick = { if (valid) onSelect(trimmed) },
                            enabled = valid && !active
                        ) { Text(if (active) "In use" else "Use") }
                    }
                )
            }
        }
    }
}
