package com.m15.gptlive.realtime

import android.util.Base64
import android.util.Log
import com.m15.gptlive.tools.LiveTool
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

sealed interface RealtimeEvent {
    data class UserTranscriptDelta(
        val text: String,
        val startMs: Long = -1L,
        val endMs: Long = -1L,
    ) : RealtimeEvent
    data class AssistantTranscriptDelta(
        val text: String,
        val startMs: Long = -1L,
        val endMs: Long = -1L,
    ) : RealtimeEvent
    /** 24 kHz PCM-16 mono. [startMs]/[endMs] are on the server timeline; a gap means omitted silence. */
    data class AudioDelta(
        val pcm: ByteArray,
        val startMs: Long = -1L,
        val endMs: Long = -1L,
    ) : RealtimeEvent
    /** GPT-Live handed work to the backend (`target` is "responses" or "client"). */
    data class DelegationCreated(val delegationId: String, val target: String) : RealtimeEvent
    /** The delegated backend wants the app to run a function tool. */
    data class FunctionCall(
        val delegationId: String,
        val callId: String,
        val name: String,
        val arguments: String,
    ) : RealtimeEvent
    /** Cumulative session usage, sent about once a minute. [contextRatio] is 0..1 of the 128k window. */
    data class UsageUpdated(val seconds: Long, val contextRatio: Double) : RealtimeEvent
    /**
     * The session is over. [reason] is one of close_requested, expired, content,
     * remote_hangup, connection_lost. [confirmed] is false when the transport
     * dropped before `session.closed` arrived.
     */
    data class SessionClosed(val reason: String, val seconds: Long, val confirmed: Boolean) : RealtimeEvent
    /**
     * A GPT-Live error envelope. [fatal] is true when it arrived before
     * `session.started` (the session never opened). Command errors after
     * startup leave the session running; [clientEventId] says which command.
     */
    data class Error(
        val code: String,
        val message: String,
        val fatal: Boolean,
        val clientEventId: String? = null,
    ) : RealtimeEvent {
        val t: Throwable get() = RuntimeException("[$code] $message")
    }
    /** Raw unknown server event — useful while validating Live event names. */
    data class Unknown(val type: String) : RealtimeEvent
}

interface RealtimeClient {
    fun connect(
        apiKey: String,
        instructions: String,
        /** A built-in voice name or a custom voice id (`voice_...`). */
        voice: String = "marin",
        tools: List<LiveTool> = emptyList(),
        /** Responses model for delegated work; null keeps the client default. */
        delegationModel: String? = null,
        /** Ask the server to keep a recording of the session (forking / resumption). */
        store: Boolean = false,
    ): Flow<RealtimeEvent>
    fun sendPcm(pcm: ShortArray)
    fun updateInstructions(instructions: String)
    /** Replace the backend model and tool list mid-session (`session.update`). */
    fun updateDelegation(model: String?, tools: List<LiveTool>)
    /** Return a function result for [callId] and tell the backend to continue. */
    fun sendFunctionResult(callId: String, output: String)
    fun close()
}

/**
 * GPT-Live WebSocket client (`wss://api.openai.com/v1/live/sessions`, GA since 2026-09-10).
 *
 * Barge-in is native: GPT-Live stops speaking on its own and there is no
 * cancel event. The client discards locally queued assistant PCM when the
 * user's transcript starts arriving.
 *
 * Tool calling uses Responses delegation: function tools are declared under
 * `delegation.responses.tools`; calls arrive as `response.output_item.done`
 * nested inside `response.event` envelopes and are answered with
 * `response.item.create` + `response.create`.
 *
 * Every client command carries an `event_id`; errors echo it back as
 * `error.client_event_id`.
 */
class OpenAiRealtimeClient(
    private val okHttp: OkHttpClient,
    private val model: String = "gpt-live-1",
    private val defaultVoice: String = "marin",
    private val defaultDelegationModel: String = "gpt-6-luna",
) : RealtimeClient {

    private val TAG = "GptLiveClient"

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 512)
    private var ws: WebSocket? = null
    @Volatile private var sessionVoice: String = defaultVoice
    @Volatile private var sessionTools: List<LiveTool> = emptyList()
    @Volatile private var delegationModel: String = defaultDelegationModel
    @Volatile private var sessionStore: Boolean = false

    @Volatile private var wsReady = false
    /** Set once `session.close` is sent, so a `session.closed` is expected. */
    @Volatile private var closeRequested = false
    private val eventSeq = AtomicLong(0)
    // Mic frames captured before session.started; touched from the capture and WS threads.
    private val pendingAudio = ArrayDeque<ByteArray>()
    private val maxPendingFrames = 50 // ~1 s of 20 ms frames

    override fun connect(
        apiKey: String,
        instructions: String,
        voice: String,
        tools: List<LiveTool>,
        delegationModel: String?,
        store: Boolean,
    ): Flow<RealtimeEvent> {
        close()
        sessionStore = store
        sessionVoice = voice.trim().ifBlank { defaultVoice }
        sessionTools = tools
        this.delegationModel = delegationModel?.trim()?.ifBlank { null } ?: defaultDelegationModel
        closeRequested = false

        val req = Request.Builder()
            .url("wss://api.openai.com/v1/live/sessions")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        Log.i(TAG, "Connecting → live/sessions model=$model voice=$sessionVoice tools=${tools.map { it.name }}")
        ws = okHttp.newWebSocket(req, Listener(instructions))
        return _events.asSharedFlow()
    }

    override fun sendPcm(pcm: ShortArray) {
        val bytes = ByteArray(pcm.size * 2)
        var j = 0
        for (s in pcm) {
            bytes[j++] = (s.toInt() and 0xFF).toByte()
            bytes[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        if (!wsReady || ws == null) {
            synchronized(pendingAudio) {
                pendingAudio.addLast(bytes)
                while (pendingAudio.size > maxPendingFrames) pendingAudio.removeFirst()
            }
            return
        }
        sendAudioChunk(bytes)
    }

    override fun updateInstructions(instructions: String) {
        // Up to 500 tokens per append; null delegation_id = whole session, not one delegated task.
        send(JSONObject().apply {
            put("type", "session.instructions.append")
            put("delegation_id", JSONObject.NULL)
            put("content", instructions)
        }, "instructions")
    }

    override fun updateDelegation(model: String?, tools: List<LiveTool>) {
        delegationModel = model?.trim()?.ifBlank { null } ?: defaultDelegationModel
        sessionTools = tools
        if (!wsReady) return
        // session.update replaces delegation.responses as one object; nested fields are not patched.
        val id = send(JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("delegation", delegationSpec(tools))
            })
        }, "update") ?: return
        Log.i(TAG, "→ session.update ($id) delegation=$delegationModel tools=${tools.map { it.name }}")
    }

    override fun sendFunctionResult(callId: String, output: String) {
        if (ws == null) {
            Log.w(TAG, "sendFunctionResult dropped — socket closed")
            return
        }
        send(JSONObject().apply {
            put("type", "response.item.create")
            put("item", JSONObject().apply {
                put("type", "function_call_output")
                put("call_id", callId)
                put("output", output)
            })
        }, "fn_output")
        // Appending a result does not continue the backend on its own.
        send(JSONObject().put("type", "response.create"), "continue")
        Log.i(TAG, "→ function_call_output call_id=$callId (${output.length} chars) + response.create")
    }

    /**
     * Graceful close: send `session.close` and keep the transport open until
     * `session.closed` arrives with the final usage and reason. The listener
     * closes the socket on that event; [CLOSE_GRACE_MS] bounds the wait.
     */
    override fun close() {
        val socket = ws ?: return
        synchronized(pendingAudio) { pendingAudio.clear() }
        if (wsReady && !closeRequested) {
            closeRequested = true
            wsReady = false
            send(JSONObject().put("type", "session.close"), "close", socket)
            Thread({
                try { Thread.sleep(CLOSE_GRACE_MS) } catch (_: InterruptedException) {}
                // Still the current socket means neither session.closed nor a
                // new connect() dealt with it: finalization is unconfirmed.
                if (ws === socket) {
                    Log.w(TAG, "session.closed not received within ${CLOSE_GRACE_MS}ms — closing transport")
                    _events.tryEmit(RealtimeEvent.SessionClosed("close_requested", -1, confirmed = false))
                }
                dropSocket(socket, "close timeout") // no-op if already closed
            }, "gptlive-close").apply { isDaemon = true }.start()
        } else {
            wsReady = false
            dropSocket(socket, "session ended")
        }
    }

    private fun dropSocket(socket: WebSocket, reason: String) {
        try { socket.close(1000, reason) } catch (_: Throwable) {}
        if (ws === socket) ws = null
    }

    private fun sendAudioChunk(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        // Audio appends are not acknowledged, so no event_id is needed.
        ws?.send("""{"type":"session.input_audio.append","audio":"$b64"}""")
    }

    /** Stamp [payload] with an `event_id`, send it, and return the id (null if there is no socket). */
    private fun send(payload: JSONObject, tag: String, socket: WebSocket? = ws): String? {
        val s = socket ?: return null
        val id = "${tag}_${eventSeq.incrementAndGet()}"
        payload.put("event_id", id)
        s.send(payload.toString())
        return id
    }

    /**
     * GPT-Live decides on its own whether to delegate. Without an explicit
     * hint in its instructions it answers from memory ("I cannot check that
     * from here"), so the tool list is turned into a short rule it can follow.
     */
    private fun toolHint(tools: List<LiveTool>): String {
        val list = tools.joinToString("\n") { "- ${it.name}: ${it.description}" }
        return "You are connected to a backend assistant that has these tools:\n" + list + "\n" +
            "Whenever the user asks for anything one of these tools can provide, do not answer from memory " +
            "and never say you cannot check. Delegate the request to the backend, wait for its result, " +
            "and then tell the user what it found."
    }

    /** Built-in voices are plain strings; custom (prompt-to-voice / cloned) voices are `{ "id": "voice_..." }`. */
    private fun voiceSpec(voice: String): Any =
        if (voice.startsWith("voice_")) JSONObject().put("id", voice) else voice

    /** Complete `delegation` object; `session.update` requires the whole thing, not a patch. */
    private fun delegationSpec(tools: List<LiveTool>): JSONObject = JSONObject().apply {
        put("type", "responses")
        put("responses", JSONObject().apply {
            put("model", delegationModel)
            if (tools.isNotEmpty()) {
                put(
                    "instructions",
                    "You are the reasoning backend for a voice assistant. " +
                        "Call a tool whenever it can answer the user's question. " +
                        "Reply in one or two short spoken sentences."
                )
                put("tools", JSONArray().apply { tools.forEach { put(it.toSpec()) } })
                put("tool_choice", "auto")
                // One call at a time keeps the result/continue handshake simple.
                put("parallel_tool_calls", false)
            }
        })
    }

    private fun sendSessionStart(instructions: String) {
        val tools = sessionTools
        val liveInstructions = if (tools.isEmpty()) instructions else instructions + "\n\n" + toolHint(tools)
        val payload = JSONObject().apply {
            put("type", "session.start")
            put("session", JSONObject().apply {
                put("model", model)
                put("instructions", liveInstructions)
                put("store", sessionStore)
                put("audio", JSONObject().apply {
                    put("format", JSONObject().apply {
                        put("type", "audio/pcm")
                        put("rate", 24000)
                    })
                    put("output", JSONObject().apply {
                        put("voice", voiceSpec(sessionVoice))
                    })
                })
                put("delegation", delegationSpec(tools))
            })
        }
        send(payload, "start")
        Log.i(TAG, "session.start sent model=$model voice=$sessionVoice delegation=$delegationModel tools=${tools.size} store=$sessionStore")
    }

    private inner class Listener(private val instructions: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS opened ${response.code}")
            // Wait for session.started before marking ready / flushing mic —
            // docs require session.started before sending audio.
            sendSessionStart(instructions)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleServerEvent(webSocket, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // GPT-Live is JSON-only over WebSocket; tolerate a UTF-8 JSON binary frame.
            val asText = try { bytes.utf8() } catch (_: Throwable) { null }
            if (asText != null && asText.startsWith("{")) {
                handleServerEvent(webSocket, asText)
            } else {
                Log.w(TAG, "← binary WS frame ${bytes.size} bytes (not JSON) — ignored")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS closing $code $reason")
            wsReady = false
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS closed $code $reason")
            wsReady = false
            if (ws === webSocket) {
                // Transport gone without session.closed: finalization is unconfirmed.
                ws = null
                _events.tryEmit(RealtimeEvent.SessionClosed("connection_lost", -1, confirmed = false))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (ws !== webSocket) {
                // Already discarded (session.closed seen, or replaced by a new connect);
                // OkHttp still reports the server dropping the TCP link. Not an error.
                Log.d(TAG, "WS failure on discarded socket: ${t.javaClass.simpleName} ${t.message}")
                return
            }
            Log.e(TAG, "WS failure code=${response?.code}: ${t.message}")
            val wasReady = wsReady
            wsReady = false
            if (ws === webSocket) ws = null
            _events.tryEmit(
                RealtimeEvent.Error(
                    code = response?.code?.let { "http_$it" } ?: "transport",
                    message = t.message ?: "connection failed",
                    fatal = !wasReady,
                )
            )
            if (wasReady) _events.tryEmit(RealtimeEvent.SessionClosed("connection_lost", -1, confirmed = false))
        }
    }

    private fun handleServerEvent(socket: WebSocket, raw: String) {
        try {
            val obj = JSONObject(raw)
            when (val type = obj.optString("type")) {
                "session.started" -> {
                    val session = obj.optJSONObject("session")
                    Log.i(TAG, "← session.started id=${session?.optString("id")} expires_at=${session?.optLong("expires_at")} " +
                        "delegation=${session?.optJSONObject("delegation")?.toString()?.take(300)}")
                    if (!wsReady) {
                        wsReady = true
                        val backlog = synchronized(pendingAudio) {
                            pendingAudio.toList().also { pendingAudio.clear() }
                        }
                        backlog.forEach(::sendAudioChunk)
                    }
                }
                "session.updated" -> {
                    val delegation = obj.optJSONObject("session")?.optJSONObject("delegation")
                    Log.i(TAG, "← session.updated delegation=${delegation?.toString()?.take(300)}")
                }
                "session.closed" -> {
                    val reason = obj.optString("reason").ifBlank { "unknown" }
                    val seconds = obj.optJSONObject("usage")?.optLong("seconds", -1L) ?: -1L
                    Log.i(TAG, "← session.closed reason=$reason seconds=$seconds")
                    wsReady = false
                    _events.tryEmit(RealtimeEvent.SessionClosed(reason, seconds, confirmed = true))
                    dropSocket(socket, "session closed")
                }

                "session.input_transcript.delta" -> {
                    val d = obj.optString("delta")
                    if (d.isNotEmpty()) {
                        val startMs = obj.optLong("start_ms", -1L)
                        val endMs = obj.optLong("end_ms", -1L)
                        Log.i(TAG, "← userΔ [$startMs-$endMs]: $d")
                        _events.tryEmit(RealtimeEvent.UserTranscriptDelta(d, startMs, endMs))
                    }
                }
                "session.output_transcript.delta" -> {
                    val d = obj.optString("delta")
                    if (d.isNotEmpty()) {
                        val startMs = obj.optLong("start_ms", -1L)
                        val endMs = obj.optLong("end_ms", -1L)
                        Log.d(TAG, "← asstΔ [$startMs-$endMs]: ${d.take(40)}")
                        _events.tryEmit(RealtimeEvent.AssistantTranscriptDelta(d, startMs, endMs))
                    }
                }

                "session.output_audio.delta" -> {
                    val b64 = obj.optString("delta")
                    if (b64.isBlank()) {
                        Log.w(TAG, "← output_audio.delta with no delta keys=${obj.keys().asSequence().toList()}")
                    } else {
                        val pcm = Base64.decode(b64, Base64.DEFAULT)
                        if (pcm.isEmpty()) {
                            Log.w(TAG, "← output_audio.delta decoded empty")
                        } else {
                            // OpenAI's endpoint sends no timing on audio deltas (Azure's does).
                            val startMs = obj.optLong("start_ms", -1L)
                            val endMs = obj.optLong("end_ms", -1L)
                            val timing = if (startMs >= 0) " [$startMs-$endMs]" else ""
                            Log.d(TAG, "← output_audio.delta ${pcm.size} bytes$timing")
                            _events.tryEmit(RealtimeEvent.AudioDelta(pcm, startMs, endMs))
                        }
                    }
                }

                // --- Delegation / tool calling ---
                "session.delegation.created" -> {
                    val d = obj.optJSONObject("delegation")
                    val id = d?.optString("id").orEmpty()
                    val target = d?.optString("target").orEmpty()
                    Log.i(TAG, "← delegation.created id=$id target=$target response_id=${d?.optString("response_id")}")
                    _events.tryEmit(RealtimeEvent.DelegationCreated(id, target))
                }
                "response.event" -> handleDelegatedResponseEvent(obj)

                "session.usage.updated" -> {
                    val seconds = obj.optJSONObject("usage")?.optLong("seconds", -1L) ?: -1L
                    val ratio = obj.optJSONObject("context_window")?.optDouble("usage_ratio", -1.0) ?: -1.0
                    Log.i(TAG, "← usage seconds=$seconds context=${"%.0f".format(ratio * 100)}%")
                    _events.tryEmit(RealtimeEvent.UsageUpdated(seconds, ratio))
                }

                "session.instructions.appended",
                "session.commentary.appended",
                "session.thinking.appended",
                "session.input_audio.muted",
                "session.input_audio.unmuted" -> Log.d(TAG, "← $type client_event_id=${obj.optString("client_event_id")}")

                "error" -> {
                    val err = obj.optJSONObject("error")
                    val msg = err?.optString("message")?.ifBlank { null } ?: "Unknown GPT-Live error"
                    val code = err?.optString("code")?.ifBlank { null } ?: err?.optString("type").orEmpty()
                    val clientEventId = err?.optString("client_event_id")?.ifBlank { null }
                    val param = err?.optString("param")?.ifBlank { null }
                    // A startup error means session.started never comes; later errors leave the session open.
                    val fatal = !wsReady && !closeRequested
                    Log.e(TAG, "← error [$code] $msg param=$param client_event_id=$clientEventId fatal=$fatal")
                    _events.tryEmit(RealtimeEvent.Error(code, msg, fatal, clientEventId))
                }

                else -> {
                    Log.i(TAG, "← unknown event: $type")
                    _events.tryEmit(RealtimeEvent.Unknown(type))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Parse error: ${raw.take(200)}", t)
        }
    }

    /**
     * `response.event` wraps a Responses API event from the delegated backend.
     * Only completed function_call items need action; everything else is
     * informational (the spoken answer arrives via the normal audio path).
     * The forwarded `response.completed` carries an empty `output`, so items
     * must be read from `response.output_item.done`.
     */
    private fun handleDelegatedResponseEvent(envelope: JSONObject) {
        val delegationId = envelope.optString("delegation_id")
        val event = envelope.optJSONObject("event") ?: return
        when (val nestedType = event.optString("type")) {
            "response.output_item.done" -> {
                val item = event.optJSONObject("item") ?: return
                if (item.optString("type") == "function_call") {
                    val call = RealtimeEvent.FunctionCall(
                        delegationId = delegationId,
                        callId = item.optString("call_id"),
                        name = item.optString("name"),
                        arguments = item.optString("arguments").ifBlank { "{}" },
                    )
                    Log.i(TAG, "← function_call ${call.name}(${call.arguments}) call_id=${call.callId}")
                    _events.tryEmit(call)
                } else {
                    Log.d(TAG, "← response.output_item.done type=${item.optString("type")}")
                }
            }
            "response.completed", "response.done" -> {
                val usage = event.optJSONObject("response")?.optJSONObject("usage")
                Log.i(TAG, "← delegated response completed usage=$usage")
            }
            "response.failed", "response.incomplete", "error" -> {
                Log.w(TAG, "← delegated backend $nestedType: ${event.optJSONObject("error")?.optString("message") ?: event}")
            }
            else -> Log.d(TAG, "← response.event/$nestedType")
        }
    }

    private companion object {
        /** How long to wait for `session.closed` after sending `session.close`. */
        const val CLOSE_GRACE_MS = 2_000L
    }
}
