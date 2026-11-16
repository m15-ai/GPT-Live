package com.m15.gptlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m15.gptlive.AgentUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAgentScreen(
    ui: AgentUiState,
    onDismissSession: () -> Unit,
    onToggleVisualizer: () -> Unit,
    showVisualizer: Boolean,
    ttsLevel: Float,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ui.error?.let { err ->
        LaunchedEffect(err) {
            snackbarHostState.showSnackbar(err, actionLabel = "OK", duration = SnackbarDuration.Long)
        }
    }

    val lastUserMsg      = ui.messages.lastOrNull { it.first == "user" }?.second
    val lastAssistantMsg = ui.messages.lastOrNull { it.first == "assistant" }?.second
    val showLiveUser      = !ui.liveUserPartial.isNullOrEmpty() && ui.liveUserPartial != lastUserMsg
    val showLiveAssistant = !ui.assistantLive.isNullOrEmpty() && ui.assistantLive != lastAssistantMsg

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "GPT Live",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp,
                    letterSpacing = 1.5.sp,
                    maxLines = 1
                )
            }
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FloatingActionButton(
                    onClick = onDismissSession,
                    containerColor = Color.White,
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Default.Close, contentDescription = "End Session", modifier = Modifier.size(32.dp))
                }

                FloatingActionButton(
                    onClick = onToggleVisualizer,
                    containerColor = if (showVisualizer) Color.White else Color(0xFF1A1A1A),
                    contentColor = if (showVisualizer) Color.Black else Color.White
                ) {
                    Icon(
                        imageVector = if (showVisualizer) Icons.Default.Chat else Icons.Default.GraphicEq,
                        contentDescription = "Toggle Visualizer"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(pad)
        ) {
            if (showVisualizer) {
                AudioBlobVisualizer(
                    level = ttsLevel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                        .clipToBounds(),
                    accent = Color.White
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        reverseLayout = true,
                        contentPadding = PaddingValues(bottom = 208.dp)
                    ) {
                        if (showLiveAssistant) {
                            item { ChatBubble("assistant", ui.assistantLive!!, Color.White) }
                        }
                        if (showLiveUser) {
                            item { ChatBubble("user", ui.liveUserPartial!!, Color(0xFF888888)) }
                        }
                        items(ui.messages.asReversed()) { (role, msg) ->
                            val color = when (role) {
                                "assistant" -> Color.White
                                "tool" -> Color(0xFF7FD1A8)
                                else -> Color(0xFF888888)
                            }
                            ChatBubble(role, msg, color)
                        }
                    }

                    if (ui.isThinking) {
                        Text(
                            "thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

        }
    }
}

@Composable
fun ChatBubble(role: String, text: String, color: Color) {
    val alignment = if (role == "user") Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.large)
                .padding(14.dp)
                .widthIn(max = 320.dp)
        ) {
            Text(text = text, color = color, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
