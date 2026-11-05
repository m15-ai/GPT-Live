package com.n15.gptlive

import android.media.*
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.n15.gptlive.realtime.RealtimeEvent
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class AgentUiState(
    val sessionId: String? = null,
    val sessionActive: Boolean = false,
    val liveUserPartial: String? = null,   // streaming user transcript
    val assistantLive: String? = null,     // streaming assistant transcript
    val isThinking: Boolean = false,       // waiting for first audio delta
    val error: String? = null,
    val messages: List<Pair<String, String>> = emptyList(),
    /** Cumulative billed voice seconds from `session.usage.updated` (-1 until the first update). */
    val usageSeconds: Long = -1L,
    /** 0..1 of the 128k context window used; summarization kicks in above 0.9. */
    val contextRatio: Double = -1.0,
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class GptLiveViewModel : ViewModel() {

    companion object {
        private const val TAG = "GptLiveVM"
        private const val SAMPLE_RATE = 24_000
        /** AudioTrack capacity. Only bounds how far ahead the pump can write. */
        private const val TRACK_BUFFER_MS = 600
        /** Initial reserve queued before play() starts, and rebuilt after every real pause. */
        private const val PREROLL_MS = 200
        /** Start anyway if a reply is shorter than PREROLL_MS. */
        private const val PREROLL_TIMEOUT_MS = 400L
        /** Below this much buffered audio with nothing queued, pad with silence instead of running dry. */
        private const val LOW_WATER_MS = 50
        /** Size of each silence slice. Small enough to be inaudible when it lands mid-word. */
        private const val PAD_MS = 20
        /** Padding this long means a real pause: hold the next chunks until PREROLL_MS is queued. */
        private const val REBUILD_AFTER_PAD_MS = 120
        /** After this much continuous padding the utterance is over: park the track. */
        private const val MAX_PAD_MS = 600
        /** Fade applied at every discontinuity so pads never click. */
        private const val FADE_MS = 8
        /** Session-timeline gap used to split same-speaker bubbles (Live has no turn-done). */
        private const val TURN_GAP_MS = 1_200L
    }

    // --- Gate (startup API key check) ---
    sealed interface GateState {
        data object Checking : GateState
        data object Ready : GateState
        data class Error(val message: String) : GateState
    }

    private val _gate = MutableStateFlow<GateState>(GateState.Checking)
    val gate: StateFlow<GateState> = _gate

    private val _ui = MutableStateFlow(AgentUiState())
    val ui: StateFlow<AgentUiState> = _ui

    // --- System message ---
    private val _systemMessage = MutableStateFlow(ServiceLocator.localPrefs.getSystemMessage())
    val systemMessage: StateFlow<String> = _systemMessage

    private val _voice = MutableStateFlow(ServiceLocator.localPrefs.getVoice())
    val voice: StateFlow<String> = _voice

    // --- Delegation (backend model + tool switches); applied at the next session start ---
    private val _delegationModel = MutableStateFlow(ServiceLocator.localPrefs.getDelegationModel())
    val delegationModel: StateFlow<String> = _delegationModel

    private val _disabledTools = MutableStateFlow(ServiceLocator.localPrefs.getDisabledTools())
    val disabledTools: StateFlow<Set<String>> = _disabledTools

    private val _storeEnabled = MutableStateFlow(ServiceLocator.localPrefs.getStoreEnabled())
    val storeEnabled: StateFlow<Boolean> = _storeEnabled

    /** Tools that will be offered to the backend, honouring the user's switches. */
    private fun activeTools() = ServiceLocator.tools.filter { it.name !in _disabledTools.value }

    // --- Visualizer levels ---
    private val _ttsLevel = MutableStateFlow(0f)
    val ttsLevel: StateFlow<Float> = _ttsLevel

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    private val _showVisualizer = MutableStateFlow(true)  // default to orb
    val showVisualizer: StateFlow<Boolean> = _showVisualizer

    // --- Live transcript segmentation (deltas only; no authoritative turn-done) ---
    private val userBuf = StringBuilder()
    private val assistantBuf = StringBuilder()
    private var lastUserEndMs = -1L
    private var lastAssistantEndMs = -1L

    // --- Internal state ---
    private val am: AudioManager = ServiceLocator.audioManager
    private var sessionJob: Job? = null
    private var micStarted = false
    private val ttsSpeaking = AtomicBoolean(false)

    // Playback: a dedicated audio-priority thread owns the AudioTrack (see playbackLoop).
    @Volatile private var audioTrack: AudioTrack? = null   // exposed only for routing changes
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(256)
    private var playbackThread: Thread? = null
    @Volatile private var flushRequested = false
    private var focusGranted = false
    private var focusRequest: AudioFocusRequest? = null

    init {
        Log.i(TAG, "GptLiveViewModel created")
        bootstrapGate()
    }

    // -----------------------------------------------------------------------
    // Gate (preflight API key check)
    // -----------------------------------------------------------------------

    private fun bootstrapGate() {
        viewModelScope.launch {
            _gate.value = GateState.Checking
            runCatching { ServiceLocator.apiKeyProvider.getApiKey() }
                .onSuccess { _gate.value = GateState.Ready }
                .onFailure { _gate.value = GateState.Error(it.message ?: "No API key available") }
        }
    }

    fun retryGate() = viewModelScope.launch { bootstrapGate() }

    // -----------------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------------

    fun startSession() {
        if (_ui.value.sessionActive) return

        _showVisualizer.value = true

        enterSpeakerphoneMode()

        sessionJob = viewModelScope.launch {
            val sid = ServiceLocator.repo.newSession("Voice Chat")
            _ui.update { it.copy(sessionId = sid, sessionActive = true, error = null) }

            val apiKey = try {
                ServiceLocator.apiKeyProvider.getApiKey()
            } catch (t: Throwable) {
                _ui.update { it.copy(error = "Failed to get API key: ${t.message}", sessionActive = false) }
                return@launch
            }

            // Connect to GPT-Live and collect events
            ServiceLocator.realtimeClient
                .connect(
                    apiKey = apiKey,
                    instructions = _systemMessage.value,
                    voice = _voice.value,
                    tools = activeTools(),
                    delegationModel = _delegationModel.value,
                    store = _storeEnabled.value,
                )
                .collect { event ->
                    handleRealtimeEvent(event, sid)
                }
        }

        // Start mic after collector is live
        if (!micStarted) {
            runCatching {
                ServiceLocator.audio.start { pcm ->
                    ServiceLocator.realtimeClient.sendPcm(pcm)
                    updateMicLevel(pcm)
                }
            }.onSuccess {
                micStarted = true
                Log.i(TAG, "Mic started → streaming 24 kHz PCM to GPT-Live")
            }.onFailure {
                Log.e(TAG, "Failed to start mic: ${it.message}")
                _ui.update { s -> s.copy(error = it.message) }
            }
        }
    }

    fun stopSession() {
        if (!_ui.value.sessionActive) return

        // Commit any open caption rows before clearing.
        flushUserTurn(sessionId = _ui.value.sessionId)
        flushAssistantTurn(sessionId = _ui.value.sessionId)

        _ui.update {
            it.copy(
                sessionActive = false,
                isThinking = false,
                liveUserPartial = null,
                assistantLive = null
            )
        }
        _ttsLevel.value = 0f
        _micLevel.value = 0f
        ttsSpeaking.set(false)
        clearTranscriptBuffers()

        stopAudioPlayback()
        releaseAudioTrack()
        abandonAudioFocus()

        runCatching { ServiceLocator.realtimeClient.close() }
        runCatching { ServiceLocator.audio.stop() }
        micStarted = false

        sessionJob?.cancel(); sessionJob = null
        leaveSpeakerphoneMode()

        Log.i(TAG, "Session stopped")
    }

    // -----------------------------------------------------------------------
    // Event handler
    // -----------------------------------------------------------------------

    private fun handleRealtimeEvent(
        event: RealtimeEvent,
        sessionId: String,
    ) {
        when (event) {
            is RealtimeEvent.UserTranscriptDelta -> {
                // Speaker change / resume: close open assistant row (kept after barge-in).
                if (assistantBuf.isNotEmpty()) flushAssistantTurn(sessionId)
                if (shouldSplitSameSpeaker(lastUserEndMs, event.startMs)) {
                    flushUserTurn(sessionId)
                }
                userBuf.append(event.text)
                if (event.endMs >= 0) lastUserEndMs = event.endMs
                _ui.update { it.copy(liveUserPartial = userBuf.toString()) }
            }

            is RealtimeEvent.AssistantTranscriptDelta -> {
                if (userBuf.isNotEmpty()) flushUserTurn(sessionId)
                if (shouldSplitSameSpeaker(lastAssistantEndMs, event.startMs)) {
                    flushAssistantTurn(sessionId)
                }
                assistantBuf.append(event.text)
                if (event.endMs >= 0) lastAssistantEndMs = event.endMs
                _ui.update { st ->
                    st.copy(
                        isThinking = false,
                        assistantLive = assistantBuf.toString()
                    )
                }
            }

            is RealtimeEvent.AudioDelta -> {
                _ui.update { it.copy(isThinking = false) }
                ttsSpeaking.set(true)
                enqueueAudio(event.pcm)
            }

            is RealtimeEvent.DelegationCreated -> {
                Log.i(TAG, "Delegation created id=${event.delegationId} target=${event.target}")
                _ui.update { it.copy(isThinking = true) }
            }

            is RealtimeEvent.FunctionCall -> runTool(event, sessionId)

            is RealtimeEvent.UsageUpdated -> {
                _ui.update { it.copy(usageSeconds = event.seconds, contextRatio = event.contextRatio) }
            }

            is RealtimeEvent.SessionClosed -> {
                // Server-side end (expired, content filter, dropped link) or an
                // unconfirmed close. Our own stopSession() cancels the collector
                // before its session.closed arrives, so this is never the echo.
                Log.w(TAG, "Session closed by server reason=${event.reason} seconds=${event.seconds} confirmed=${event.confirmed}")
                val message = when (event.reason) {
                    "expired" -> "Session reached its duration limit"
                    "content" -> "Session ended by the safety filter"
                    "connection_lost" -> "Connection lost"
                    "remote_hangup" -> "Remote side hung up"
                    else -> "Session ended (${event.reason})"
                }
                stopSession()
                _ui.update { it.copy(error = message) }
            }

            is RealtimeEvent.Unknown -> {
                Log.i(TAG, "Unknown Live event: ${event.type}")
            }

            is RealtimeEvent.Error -> {
                Log.e(TAG, "Live error [${event.code}] ${event.message} fatal=${event.fatal} client_event_id=${event.clientEventId}")
                if (event.fatal) {
                    // Startup or transport failure: session.started will never come.
                    stopSession()
                    _ui.update { it.copy(error = "[${event.code}] ${event.message}") }
                } else {
                    // Command or moderation error; the session stays up. A
                    // moderation cutoff stops the server's audio, so drop ours too.
                    stopAudioPlayback()
                    flushAssistantTurn(sessionId)
                    _ui.update { it.copy(isThinking = false, error = "[${event.code}] ${event.message}") }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Audio playback (24 kHz PCM-16 from GPT-Live)
    //
    // The server streams 100 ms chunks at (slightly under) real-time pace with
    // network jitter, and omits silence. A dedicated URGENT_AUDIO thread owns
    // the AudioTrack and runs an adaptive jitter buffer:
    //   * play() starts once PREROLL_MS is queued;
    //   * if a chunk is late and the reserve drops below LOW_WATER_MS, short
    //     faded slices of silence are padded in, so the hardware never runs
    //     dry (an underrun is an audible click) and the reserve grows to match
    //     the jitter actually seen;
    //   * after a real pause the reserve is rebuilt before audio resumes;
    //   * after MAX_PAD_MS of padding the track is parked until the next chunk.
    // -----------------------------------------------------------------------

    private fun playbackLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        var framesWritten = 0L          // frames handed to the track since the last flush (incl. padding)
        var primed = false              // track.play() has been called for this burst
        var firstChunkAtMs = 0L         // when the current burst started (for the pre-roll timeout)
        var lastUnderruns = 0
        var padFrames = 0L              // silence padded since the last real audio
        var fadeInPending = true        // next real audio starts after a discontinuity
        var held: ByteArray? = null     // tail of the last chunk, kept back so it can be faded out
        val pending = ArrayList<ByteArray>() // chunks waiting while the reserve is rebuilt after a pause
        var pendingBytes = 0
        var pendingSinceMs = 0L

        val prerollFrames = SAMPLE_RATE * PREROLL_MS / 1000
        val lowWaterFrames = SAMPLE_RATE * LOW_WATER_MS / 1000
        val maxPadFrames = SAMPLE_RATE * MAX_PAD_MS / 1000
        val rebuildAfterFrames = SAMPLE_RATE * REBUILD_AFTER_PAD_MS / 1000
        val fadeBytes = SAMPLE_RATE * FADE_MS / 1000 * 2
        val silence = ByteArray(SAMPLE_RATE * PAD_MS / 1000 * 2)

        fun buffered(t: AudioTrack): Long {
            val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            return (framesWritten - played).coerceAtLeast(0)
        }

        fun startTrack(t: AudioTrack) {
            try { t.play() } catch (e: Throwable) { Log.w(TAG, "play() failed: ${e.message}"); return }
            primed = true
            Log.d(TAG, "playback started with ${framesWritten * 1000 / SAMPLE_RATE} ms buffered")
        }

        fun rePrime(t: AudioTrack, reason: String) {
            try { t.pause(); t.flush() } catch (_: Throwable) {}
            framesWritten = 0
            primed = false
            padFrames = 0
            fadeInPending = true
            held = null
            pending.clear(); pendingBytes = 0
            ttsSpeaking.set(false)
            _ttsLevel.value = 0f
            Log.d(TAG, "playback paused ($reason)")
        }

        /** Non-blocking write loop. Returns false if interrupted or flushed mid-way. */
        fun writeAll(t: AudioTrack, pcm: ByteArray, len: Int = pcm.size): Boolean {
            var offset = 0
            while (offset < len) {
                if (flushRequested || Thread.currentThread().isInterrupted) return false
                val wrote = t.write(pcm, offset, len - offset, AudioTrack.WRITE_NON_BLOCKING)
                when {
                    wrote < 0 -> { Log.w(TAG, "AudioTrack write error: $wrote state=${t.playState}"); return false }
                    wrote == 0 -> {
                        // Buffer full. Cannot happen before play() (pre-roll < buffer), but be safe.
                        if (!primed) startTrack(t)
                        try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                    else -> { offset += wrote; framesWritten += wrote / 2 }
                }
            }
            return true
        }

        /** Write one chunk, applying a fade-in after a discontinuity and holding back its tail. */
        fun writeChunk(t: AudioTrack, pcm: ByteArray) {
            if (fadeInPending) { fadeIn(pcm, fadeBytes); fadeInPending = false }
            held?.let { writeAll(t, it) }
            val keep = minOf(fadeBytes, pcm.size)
            writeAll(t, pcm, pcm.size - keep)
            held = pcm.copyOfRange(pcm.size - keep, pcm.size)
        }

        fun flushPending(t: AudioTrack, why: String) {
            Log.d(TAG, "resuming after ${padFrames * 1000 / SAMPLE_RATE} ms pause with ${pendingBytes / 2 * 1000 / SAMPLE_RATE} ms queued ($why)")
            padFrames = 0
            for (p in pending) writeChunk(t, p)
            pending.clear(); pendingBytes = 0
        }

        try {
            while (!Thread.currentThread().isInterrupted) {
                if (flushRequested) {
                    flushRequested = false
                    playbackQueue.clear()
                    track?.let { rePrime(it, "flush") }
                }

                val pcm = try {
                    playbackQueue.poll(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }

                if (pcm == null) {
                    val t = track ?: continue
                    val now = System.currentTimeMillis()
                    if (!primed) {
                        // Short reply that never reached the pre-roll threshold: play what we have.
                        if (framesWritten > 0 && now - firstChunkAtMs >= PREROLL_TIMEOUT_MS) startTrack(t)
                        continue
                    }
                    if (pending.isNotEmpty() && now - pendingSinceMs >= PREROLL_TIMEOUT_MS) {
                        flushPending(t, "timeout")
                    }
                    if (buffered(t) <= lowWaterFrames) {
                        if (padFrames >= maxPadFrames && pending.isNotEmpty()) {
                            flushPending(t, "max pad")
                            continue
                        }
                        if (padFrames >= maxPadFrames) {
                            // Long silence: the utterance is over. Park the track cleanly (it is
                            // already outputting zeros, so pausing here cannot click).
                            rePrime(t, "idle")
                            continue
                        }
                        // The next chunk is late. Pad with a slice of silence rather than let the
                        // hardware run dry: fade the held tail out first so there is no step.
                        held?.let { fadeOut(it, it.size); writeAll(t, it); held = null }
                        writeAll(t, silence)
                        padFrames += silence.size / 2
                        fadeInPending = true
                        _ttsLevel.value = 0f
                    }
                    continue
                }
                if (pcm.isEmpty()) continue

                val t = track ?: createAudioTrack().also {
                    track = it
                    audioTrack = it
                }
                if (framesWritten == 0L) firstChunkAtMs = System.currentTimeMillis()
                _ttsLevel.value = rms16leTo01(pcm)

                if (primed && padFrames >= rebuildAfterFrames && pending.isEmpty()) {
                    // A real pause just ended. Hold chunks back until a full pre-roll is queued so
                    // the reserve is rebuilt; the track keeps padding silence meanwhile.
                    pendingSinceMs = System.currentTimeMillis()
                }
                if (pending.isNotEmpty() || (primed && padFrames >= rebuildAfterFrames)) {
                    pending.add(pcm); pendingBytes += pcm.size
                    if (pendingBytes / 2 >= prerollFrames) flushPending(t, "pre-roll")
                    continue
                }

                if (padFrames > 0) Log.d(TAG, "stretched by ${padFrames * 1000 / SAMPLE_RATE} ms")
                padFrames = 0
                writeChunk(t, pcm)

                if (!primed && framesWritten >= prerollFrames) startTrack(t)

                val underruns = t.underrunCount
                if (underruns != lastUnderruns) {
                    Log.w(TAG, "AudioTrack underrun count=$underruns (+${underruns - lastUnderruns})")
                    lastUnderruns = underruns
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "playback loop died: ${e.message}", e)
        } finally {
            track?.let { t ->
                try { t.pause(); t.flush(); t.stop() } catch (_: Throwable) {}
                try { t.release() } catch (_: Throwable) {}
            }
            audioTrack = null
            Log.i(TAG, "playback thread exited")
        }
    }

    /** Linear fade-in over the first [bytes] of a PCM16LE buffer, in place. */
    private fun fadeIn(pcm: ByteArray, bytes: Int) {
        val n = minOf(bytes, pcm.size) / 2
        if (n <= 0) return
        for (i in 0 until n) {
            val idx = i * 2
            val s = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort()
            val v = (s * i / n).toInt()
            pcm[idx] = (v and 0xFF).toByte()
            pcm[idx + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }

    /** Linear fade-out over the last [bytes] of a PCM16LE buffer, in place. */
    private fun fadeOut(pcm: ByteArray, bytes: Int) {
        val n = minOf(bytes, pcm.size) / 2
        if (n <= 0) return
        val start = pcm.size / 2 - n
        for (i in 0 until n) {
            val idx = (start + i) * 2
            val s = ((pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)).toShort()
            val v = (s * (n - i) / n).toInt()
            pcm[idx] = (v and 0xFF).toByte()
            pcm[idx + 1] = ((v shr 8) and 0xFF).toByte()
        }
    }

    private fun createAudioTrack(): AudioTrack {
        requestAudioFocus()

        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelMask, encoding)
        // Capacity only; latency is governed by PREROLL_MS, not by this size.
        val bufSize = max(minBuf, SAMPLE_RATE * 2 * TRACK_BUFFER_MS / 1000)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track ->
                try { track.setVolume(1f) } catch (_: Throwable) {}
                // Not started here: the pump calls play() once PREROLL_MS is buffered.
                Log.i(TAG, "AudioTrack created buf=${track.bufferSizeInFrames} frames (min=${minBuf / 2}) preroll=${PREROLL_MS}ms")
            }
    }

    private fun ensurePlaybackPump() {
        if (playbackThread?.isAlive == true) return
        flushRequested = false
        playbackThread = Thread(::playbackLoop, "gptlive-playback").apply {
            isDaemon = true
            start()
        }
    }

    private fun enqueueAudio(pcm: ByteArray) {
        ensurePlaybackPump()
        if (!playbackQueue.offer(pcm)) {
            // Queue holds many seconds of audio; only reachable if the sink is stuck.
            Log.w(TAG, "playback queue full (${playbackQueue.size}) — dropping oldest chunk")
            playbackQueue.poll()
            playbackQueue.offer(pcm)
        }
    }

    /** Discard everything queued and buffered (barge-in / cancel); the pump re-primes on the next chunk. */
    private fun stopAudioPlayback() {
        playbackQueue.clear()
        flushRequested = true
        ttsSpeaking.set(false)
        _ttsLevel.value = 0f
    }

    /** Stop the pump thread; it releases the AudioTrack on the way out. */
    private fun releaseAudioTrack() {
        playbackQueue.clear()
        playbackThread?.let { t ->
            t.interrupt()
            try { t.join(500) } catch (_: InterruptedException) {}
        }
        playbackThread = null
        audioTrack = null
    }

    private fun rms16leTo01(data: ByteArray): Float {
        val n = data.size / 2
        if (n <= 0) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < data.size) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt()
            val s = (hi shl 8) or lo
            sum += (s / 32768.0).let { it * it }
            i += 2
        }
        return (sqrt(sum / n).toFloat() * 5f).coerceIn(0f, 1f)
    }

    // -----------------------------------------------------------------------
    // Mic level
    // -----------------------------------------------------------------------

    private fun updateMicLevel(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        var sumSq = 0.0
        for (s in pcm) { val v = s.toDouble(); sumSq += v * v }
        val rms = sqrt(sumSq / pcm.size) / 32768.0
        val scaled = (rms * 6.0).coerceIn(0.0, 1.0).toFloat()
        _micLevel.value = max(scaled, _micLevel.value * 0.82f)
    }

    // -----------------------------------------------------------------------
    // UI actions
    // -----------------------------------------------------------------------

    fun setSystemMessage(message: String) {
        _systemMessage.value = message
        if (_ui.value.sessionActive) {
            ServiceLocator.realtimeClient.updateInstructions(message)
        }
    }

    fun setVoice(voiceId: String) {
        _voice.value = voiceId
    }

    fun setDelegationModel(model: String) {
        val m = model.trim().ifBlank { return }
        _delegationModel.value = m
        ServiceLocator.localPrefs.setDelegationModel(m)
        pushDelegationUpdate()
    }

    fun setToolEnabled(name: String, enabled: Boolean) {
        val next = if (enabled) _disabledTools.value - name else _disabledTools.value + name
        _disabledTools.value = next
        ServiceLocator.localPrefs.setDisabledTools(next)
        pushDelegationUpdate()
    }

    /** Backend model and tools can change mid-session via `session.update`; the voice model cannot. */
    private fun pushDelegationUpdate() {
        if (!_ui.value.sessionActive) return
        ServiceLocator.realtimeClient.updateDelegation(_delegationModel.value, activeTools())
    }

    fun setStoreEnabled(enabled: Boolean) {
        _storeEnabled.value = enabled
        ServiceLocator.localPrefs.setStoreEnabled(enabled)
    }


    // -----------------------------------------------------------------------
    // Tool calling (Responses delegation)
    // -----------------------------------------------------------------------

    /**
     * Execute a function call from the delegated backend off the main thread,
     * then return the output and continue the response. Errors are reported
     * back to the model as text so it can explain rather than stall.
     */
    private fun runTool(call: RealtimeEvent.FunctionCall, sessionId: String) {
        val tool = activeTools().firstOrNull { it.name == call.name }
        viewModelScope.launch(Dispatchers.Default) {
            val output = try {
                if (tool == null) {
                    """{"error":"unknown tool '${call.name}'"}"""
                } else {
                    val args = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
                    tool.execute(args)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "tool ${call.name} failed: ${t.message}")
                """{"error":${JSONObject.quote(t.message ?: "tool failed")}}"""
            }
            Log.i(TAG, "tool ${call.name}(${call.arguments}) -> $output")
            ServiceLocator.realtimeClient.sendFunctionResult(call.callId, output)

            val label = "${call.name} → $output"
            withContext(Dispatchers.Main) {
                _ui.update { st -> st.copy(messages = st.messages + ("tool" to label)) }
            }
            if (sessionId.isNotBlank()) ServiceLocator.repo.addToolText(sessionId, label)
        }
    }

    // -----------------------------------------------------------------------
    // Transcript turn grouping (application-side; Live has no turn-done event)
    // -----------------------------------------------------------------------

    private fun shouldSplitSameSpeaker(prevEndMs: Long, nextStartMs: Long): Boolean {
        if (prevEndMs < 0 || nextStartMs < 0) return false
        return nextStartMs - prevEndMs >= TURN_GAP_MS
    }

    private fun clearTranscriptBuffers() {
        userBuf.clear()
        assistantBuf.clear()
        lastUserEndMs = -1L
        lastAssistantEndMs = -1L
    }

    private fun flushUserTurn(sessionId: String?) {
        val text = userBuf.toString()
        userBuf.clear()
        lastUserEndMs = -1L
        if (text.isBlank()) {
            _ui.update { it.copy(liveUserPartial = null) }
            return
        }
        if (!sessionId.isNullOrBlank()) {
            viewModelScope.launch { ServiceLocator.repo.addUserText(sessionId, text) }
        }
        _ui.update { st ->
            st.copy(
                liveUserPartial = null,
                messages = st.messages + ("user" to text)
            )
        }
    }

    private fun flushAssistantTurn(sessionId: String?) {
        val text = assistantBuf.toString()
        assistantBuf.clear()
        lastAssistantEndMs = -1L
        if (text.isBlank()) {
            _ui.update { it.copy(assistantLive = null, isThinking = false) }
            return
        }
        if (!sessionId.isNullOrBlank()) {
            viewModelScope.launch { ServiceLocator.repo.addAssistantText(sessionId, text) }
        }
        _ui.update { st ->
            st.copy(
                assistantLive = null,
                isThinking = false,
                messages = st.messages + ("assistant" to text)
            )
        }
    }

    fun toggleVisualizer() { _showVisualizer.value = !_showVisualizer.value }

    // -----------------------------------------------------------------------
    // Audio routing: always speakerphone. This is a hands-free voice chat app,
    // so the in-call path (MODE_IN_COMMUNICATION + built-in speaker) is fixed
    // and the platform's speakerphone echo cancellation is used on the uplink.
    // -----------------------------------------------------------------------

    private fun enterSpeakerphoneMode() {
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    if (!am.setCommunicationDevice(speaker)) Log.w(TAG, "setCommunicationDevice(speaker) refused")
                } else {
                    Log.w(TAG, "No built-in speaker among communication devices")
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enterSpeakerphoneMode failed: ${t.message}")
        }
    }

    private fun leaveSpeakerphoneMode() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        } catch (t: Throwable) {
            Log.w(TAG, "leaveSpeakerphoneMode failed: ${t.message}")
        }
    }

    private fun requestAudioFocus() {
        if (focusGranted) return
        focusGranted = try {
            if (Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setOnAudioFocusChangeListener {}
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusRequest = r
                am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) { false }
    }

    private fun abandonAudioFocus() {
        if (!focusGranted) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") am.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
        focusGranted = false
        focusRequest = null
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
