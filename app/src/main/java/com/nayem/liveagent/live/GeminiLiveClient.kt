package com.nayem.liveagent.live

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LiveEvent {
    data class Connected(val model: String) : LiveEvent
    data class Disconnected(val reason: String) : LiveEvent
    data class UserTranscript(val text: String, val isFinal: Boolean) : LiveEvent
    data class AgentTranscript(val text: String, val isFinal: Boolean) : LiveEvent
    data class AgentAudio(val bytes: ByteArray) : LiveEvent
    data object Interrupted : LiveEvent
    data class Error(val message: String) : LiveEvent
    data object SetupComplete : LiveEvent
    /** Socket handshake finished; the setup message is about to be sent. */
    data object SocketOpened : LiveEvent
    /** Setup message was sent; now waiting for the server's setupComplete. */
    data object SetupSent : LiveEvent
    /**
     * A raw frame arrived before setup completed (first ~160 chars only).
     * Logged so a silent server leaves evidence of exactly what, if anything,
     * came back — audio payloads are never logged once connected.
     */
    data class RawFrame(val summary: String) : LiveEvent
}

/**
 * Gemini Live API client (official BidiGenerateContent WebSocket).
 * The user's own API key is passed in at runtime; it is never stored here.
 * Handles both text frames and binary frames — the server may send either.
 *
 * connect() is standalone: it never calls the REST key test first — a failed
 * key test must not block a live session, and the socket reports its own errors.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String,
    private val onEvent: (LiveEvent) -> Unit,
    /** Retry-only fallback: model + responseModalities, nothing else. */
    private val minimalSetup: Boolean = false
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 15 s, not 30: a dead network must fail fast with a visible error
        // instead of leaving the UI on "Connecting..." for half a minute.
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile var isConnected: Boolean = false
        private set

    private val userPartial = StringBuilder()
    private val agentPartial = StringBuilder()

    /** 20 s guard: setupComplete must arrive after onOpen or the attempt is dead. */
    private val setupHandler = Handler(Looper.getMainLooper())
    private var setupTimeoutRunnable: Runnable? = null

    fun connect() {
        // A retry must start clean: drop any stale socket/partials/timer first.
        reset()
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=" + URLEncoder.encode(apiKey, "UTF-8")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onEvent(LiveEvent.SocketOpened)
                // Setup shape (per ai.google.dev Live API docs): speechConfig
                // belongs INSIDE generationConfig, not as a sibling of it.
                // The old shape (speechConfig beside generationConfig) made the
                // server stay silent — socket opened, setup sent, but no
                // setupComplete ever arrived. systemInstruction and the
                // transcription flags stay at setup top level (correct there).
                val setup = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", "models/$model")
                        if (minimalSetup) {
                            // Retry fallback: model + responseModalities only.
                            put("generationConfig", JSONObject().apply {
                                put("responseModalities", org.json.JSONArray().put("AUDIO"))
                            })
                        } else {
                            put("generationConfig", JSONObject().apply {
                                put("responseModalities", org.json.JSONArray().put("AUDIO"))
                                put("speechConfig", JSONObject().apply {
                                    put("voiceConfig", JSONObject().apply {
                                        put("prebuiltVoiceConfig", JSONObject().apply {
                                            put("voiceName", voice)
                                        })
                                    })
                                })
                            })
                            put("systemInstruction", JSONObject().apply {
                                put("parts", org.json.JSONArray().put(
                                    JSONObject().put("text", systemPrompt)
                                ))
                            })
                            put("inputAudioTranscription", JSONObject())
                            put("outputAudioTranscription", JSONObject())
                        }
                    })
                }
                // send() returns false (instead of throwing) when the socket
                // is already dead — surface that instead of waiting 20 s for
                // a setupComplete that can never arrive.
                val sent = try { webSocket.send(setup.toString()) } catch (_: Exception) { false }
                if (!sent) {
                    onEvent(LiveEvent.Error("Could not send setup to the server"))
                    disconnect()
                    return
                }
                onEvent(LiveEvent.SetupSent)
                startSetupTimeout()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                handleMessage(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                cancelSetupTimeout()
                isConnected = false
                socket = null
                val detail = t.message?.takeIf { it.isNotBlank() }
                    ?: response?.message?.takeIf { it.isNotBlank() }
                    ?: "Connection failed"
                // Include the failure kind (e.g. UnknownHostException) — it is
                // the difference between "no internet" and "key rejected".
                val kind = t.javaClass.simpleName
                val code = response?.code
                val message = if (code != null) "WebSocket failed (HTTP $code): $detail"
                else "WebSocket failed ($kind): $detail"
                onEvent(LiveEvent.Error(message))
                onEvent(LiveEvent.Disconnected(message))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                cancelSetupTimeout()
                isConnected = false
                socket = null
                val text = if (reason.isNotBlank()) "Closed ($code): $reason"
                else "Closed ($code)"
                onEvent(LiveEvent.Disconnected(text))
            }
        })
    }

    fun sendAudioPcm16(bytes: ByteArray) {
        val socket = socket ?: return
        if (!isConnected) return
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                })
            })
        }
        socket.send(msg.toString())
    }

    fun sendText(text: String) {
        val socket = socket ?: return
        val msg = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", org.json.JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", org.json.JSONArray().put(JSONObject().put("text", text)))
                    }
                ))
                put("turnComplete", true)
            })
        }
        socket.send(msg.toString())
    }

    /** Close cleanly and mark disconnected. Safe to call when already closed. */
    fun disconnect() {
        cancelSetupTimeout()
        try { socket?.close(1000, "Done") } catch (_: Exception) {}
        socket = null
        isConnected = false
    }

    /** Full reset so a retry starts clean: disconnect + clear partial transcripts. */
    fun reset() {
        disconnect()
        userPartial.clear()
        agentPartial.clear()
    }

    private fun startSetupTimeout() {
        cancelSetupTimeout()
        val runnable = Runnable {
            if (!isConnected) {
                onEvent(LiveEvent.Error("Timed out waiting for setup — check your key and network"))
                disconnect()
            }
        }
        setupTimeoutRunnable = runnable
        setupHandler.postDelayed(runnable, 20_000L)
    }

    private fun cancelSetupTimeout() {
        setupTimeoutRunnable?.let { setupHandler.removeCallbacks(it) }
        setupTimeoutRunnable = null
    }

    private fun handleMessage(raw: String) {
        // Raw-frame evidence, only before setup completes: if the server ever
        // stays silent again, the log shows exactly which frames arrived.
        if (!isConnected) {
            try { onEvent(LiveEvent.RawFrame(raw.take(160))) } catch (_: Exception) {}
        }
        try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                val err = json.optJSONObject("error")
                val status = err?.optString("status")?.takeIf { it.isNotBlank() }
                val message = err?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Server error"
                val detailReason = err?.optJSONArray("details")
                    ?.optJSONObject(0)?.optString("reason")?.takeIf { it.isNotBlank() }
                val full = buildString {
                    if (status != null) append("[$status] ")
                    append(message)
                    if (detailReason != null) append(" ($detailReason)")
                }
                onEvent(LiveEvent.Error(full))
                return
            }
            if (json.has("setupComplete")) {
                cancelSetupTimeout()
                isConnected = true
                onEvent(LiveEvent.SetupComplete)
                onEvent(LiveEvent.Connected(model))
                return
            }
            val server = json.optJSONObject("serverContent") ?: return
            if (server.optBoolean("interrupted", false)) {
                agentPartial.clear()
                onEvent(LiveEvent.Interrupted)
            }
            val turn = server.optJSONObject("modelTurn") ?: server.optJSONObject("model_turn")
            turn?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    val data = inline?.optString("data")
                    if (!data.isNullOrBlank()) {
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        onEvent(LiveEvent.AgentAudio(bytes))
                    }
                }
            }
            server.optJSONObject("inputTranscription")?.optString("text")?.let { text ->
                if (text.isNotBlank()) {
                    userPartial.append(text)
                    onEvent(LiveEvent.UserTranscript(userPartial.toString(), false))
                }
            }
            server.optJSONObject("outputTranscription")?.optString("text")?.let { text ->
                if (text.isNotBlank()) {
                    agentPartial.append(text)
                    onEvent(LiveEvent.AgentTranscript(agentPartial.toString(), false))
                }
            }
            if (server.optBoolean("turnComplete", false)) {
                if (userPartial.isNotEmpty()) {
                    onEvent(LiveEvent.UserTranscript(userPartial.toString(), true))
                    userPartial.clear()
                }
                if (agentPartial.isNotEmpty()) {
                    onEvent(LiveEvent.AgentTranscript(agentPartial.toString(), true))
                    agentPartial.clear()
                }
            }
        } catch (_: Exception) {
            // Malformed frames must never crash the session.
        }
    }
}

/**
 * One-shot live check for Settings ("Test live connection"): opens the real
 * Live socket, sends setup, and waits for setupComplete (max 20 s).
 * Returns plain success or the exact server error / HTTP code / timeout.
 * Never touches the REST key test — the socket reports its own errors.
 */
suspend fun testLiveConnection(
    apiKey: String,
    model: String,
    voice: String,
    systemPrompt: String
): String = withContext(Dispatchers.IO) {
    val done = CompletableDeferred<String>()
    // NOTE: the lambda must stay inside the parentheses — onEvent is not the
    // last constructor parameter (minimalSetup follows it), so the trailing
    // lambda form does not compile here.
    val client = GeminiLiveClient(apiKey, model, voice, systemPrompt, { event ->
        when (event) {
            is LiveEvent.SetupComplete -> done.complete("Live connected OK ($model)")
            is LiveEvent.Connected -> { /* SetupComplete already reports success */ }
            is LiveEvent.Error -> done.complete("Live connection failed: ${event.message}")
            is LiveEvent.Disconnected -> done.complete("Live connection closed: ${event.reason}")
            else -> { /* audio/transcripts are irrelevant for the test */ }
        }
    })
    try {
        client.connect()
        withTimeoutOrNull(20_000L) { done.await() }
            ?: "Timed out after 20s — no setupComplete received (check key and network)"
    } catch (e: Exception) {
        "Live connection failed: ${e.message}"
    } finally {
        try { client.disconnect() } catch (_: Exception) {}
    }
}
