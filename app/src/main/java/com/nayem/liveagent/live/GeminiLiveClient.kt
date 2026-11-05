package com.nayem.liveagent.live

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

sealed interface LiveEvent {
    data class Connected(val model: String) : LiveEvent
    data class Disconnected(val reason: String) : LiveEvent
    data class UserTranscript(val text: String, val isFinal: Boolean) : LiveEvent
    data class AgentTranscript(val text: String, val isFinal: Boolean) : LiveEvent
    data class AgentAudio(val bytes: ByteArray) : LiveEvent
    data object Interrupted : LiveEvent
    data class Error(val message: String) : LiveEvent
    data object SetupComplete : LiveEvent
}

/**
 * Gemini Live API client (official BidiGenerateContent WebSocket).
 * The user's own API key is passed in at runtime; it is never stored here.
 * Handles both text frames and binary frames — the server may send either.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String,
    private val onEvent: (LiveEvent) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile var isConnected: Boolean = false
        private set

    private val userPartial = StringBuilder()
    private val agentPartial = StringBuilder()

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
            "?key=" + URLEncoder.encode(apiKey, "UTF-8")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", "models/$model")
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", org.json.JSONArray().put("AUDIO"))
                        })
                        put("systemInstruction", JSONObject().apply {
                            put("parts", org.json.JSONArray().put(
                                JSONObject().put("text", systemPrompt)
                            ))
                        })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voice)
                                })
                            })
                        })
                        put("inputAudioTranscription", JSONObject())
                        put("outputAudioTranscription", JSONObject())
                    })
                }
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                handleMessage(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                onEvent(LiveEvent.Error(t.message ?: "Connection failed"))
                onEvent(LiveEvent.Disconnected(t.message ?: "Connection failed"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onEvent(LiveEvent.Disconnected(reason.ifBlank { "Closed ($code)" }))
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

    fun disconnect() {
        try { socket?.close(1000, "Done") } catch (_: Exception) {}
        socket = null
        isConnected = false
    }

    private fun handleMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                val err = json.optJSONObject("error")
                onEvent(LiveEvent.Error(err?.optString("message") ?: "Server error"))
                return
            }
            if (json.has("setupComplete")) {
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
