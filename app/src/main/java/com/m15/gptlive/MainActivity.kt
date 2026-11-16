package com.m15.gptlive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m15.gptlive.prefs.LiveVoices
import com.m15.gptlive.prefs.LocalPrefs
import com.m15.gptlive.ui.CustomPromptScreen
import com.m15.gptlive.ui.DelegationScreen
import com.m15.gptlive.ui.ToolSetting
import com.m15.gptlive.ui.SettingsScreen
import com.m15.gptlive.ui.SetupScreen
import com.m15.gptlive.ui.VoiceAgentScreen
import com.m15.gptlive.ui.VoicePickerScreen
import com.m15.gptlive.ui.theme.GptLiveTheme

class MainActivity : ComponentActivity() {

    private val vm by viewModels<GptLiveViewModel>()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startSession()
    }

    private fun startSessionWithMic() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startSession()
        else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GptLiveTheme {
                val context = LocalContext.current
                val prefs = remember { LocalPrefs(context) }

                val gate          by vm.gate.collectAsStateWithLifecycle()
                val uiState       by vm.ui.collectAsStateWithLifecycle()
                val showViz       by vm.showVisualizer.collectAsStateWithLifecycle()
                val ttsLevel      by vm.ttsLevel.collectAsStateWithLifecycle()
                val micLevel      by vm.micLevel.collectAsStateWithLifecycle()
                val systemMessage by vm.systemMessage.collectAsStateWithLifecycle()
                val voiceId       by vm.voice.collectAsStateWithLifecycle()
                val delegationModel by vm.delegationModel.collectAsStateWithLifecycle()
                val disabledTools by vm.disabledTools.collectAsStateWithLifecycle()
                val storeEnabled  by vm.storeEnabled.collectAsStateWithLifecycle()

                // setup | settings | instructions | voice
                var screen by rememberSaveable { mutableStateOf("setup") }

                LaunchedEffect(Unit) {
                    vm.setSystemMessage(prefs.getSystemMessage())
                    vm.setVoice(prefs.getVoice())
                }
                LaunchedEffect(systemMessage) { prefs.setSystemMessage(systemMessage) }
                LaunchedEffect(voiceId) { prefs.setVoice(voiceId) }

                when (val g = gate) {
                    is GptLiveViewModel.GateState.Checking -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }

                    is GptLiveViewModel.GateState.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = g.message.ifBlank { "No API key configured." },
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { vm.retryGate() }) { Text("Retry") }
                            }
                        }
                    }

                    is GptLiveViewModel.GateState.Ready -> {
                        if (uiState.sessionActive) {
                            VoiceAgentScreen(
                                ui = uiState,
                                onDismissSession = { vm.stopSession() },
                                onToggleVisualizer = vm::toggleVisualizer,
                                showVisualizer = showViz,
                                ttsLevel = maxOf(ttsLevel, micLevel),
                            )
                        } else {
                            when (screen) {
                                "settings" -> SettingsScreen(
                                    instructionsPreview = systemMessage,
                                    voiceLabel = LiveVoices.labelFor(voiceId),
                                    delegationSummary = run {
                                        val on = ServiceLocator.tools.count { it.name !in disabledTools }
                                        "responses · $delegationModel · $on of ${ServiceLocator.tools.size} tools"
                                    },
                                    storeEnabled = storeEnabled,
                                    onToggleStore = vm::setStoreEnabled,
                                    onBack = { screen = "setup" },
                                    onOpenInstructions = { screen = "instructions" },
                                    onOpenVoice = { screen = "voice" },
                                    onOpenDelegation = { screen = "delegation" },
                                )
                                "delegation" -> DelegationScreen(
                                    selectedModel = delegationModel,
                                    tools = ServiceLocator.tools.map {
                                        ToolSetting(it.name, it.description, it.name !in disabledTools)
                                    },
                                    onSelectModel = vm::setDelegationModel,
                                    onToggleTool = vm::setToolEnabled,
                                    onBack = { screen = "settings" },
                                )
                                "instructions" -> CustomPromptScreen(
                                    initialPrompt = systemMessage,
                                    onSave = { vm.setSystemMessage(it); screen = "settings" },
                                    onCancel = { screen = "settings" },
                                )
                                "voice" -> VoicePickerScreen(
                                    selectedVoiceId = voiceId,
                                    onSelect = { vm.setVoice(it); screen = "settings" },
                                    onBack = { screen = "settings" },
                                )
                                else -> SetupScreen(
                                    onOpenSettings = { screen = "settings" },
                                    onStartSession = { startSessionWithMic() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopSession()
    }
}
