package com.nayem.liveagent.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** REST helper for the Gemini Developer API: key test, planning, vision fallback. */
class GeminiRestClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    suspend fun testKey(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$base/models?key=$apiKey").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    return@withContext Result.failure(Exception(msg ?: "HTTP ${resp.code}"))
                }
                val names = mutableListOf<String>()
                val models = JSONObject(body).optJSONArray("models")
                if (models != null) {
                    for (i in 0 until models.length()) {
                        val name = models.optJSONObject(i)?.optString("name") ?: ""
                        if (name.contains("live")) names += name.removePrefix("models/")
                    }
                }
                Result.success(names)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateText(prompt: String, model: String = "gemini-3.8-flash"): String =
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    ))
                    put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
                }
                val req = Request.Builder()
                    .url("$base/models/$model:generateContent?key=$apiKey")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) return@withContext ""
                    val parts = JSONObject(body)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext ""
                    val out = StringBuilder()
                    for (i in 0 until parts.length()) {
                        out.append(parts.optJSONObject(i)?.optString("text") ?: "")
                    }
                    out.toString()
                }
            } catch (_: Exception) {
                ""
            }
        }

    /** Ask the model where a UI element is. Returns 0–1000 coordinates, or null. */
    suspend fun visionLocate(imageBase64Jpeg: String, targetDescription: String): Pair<Float, Float>? =
        withContext(Dispatchers.IO) {
            try {
                val prompt = "Find this UI element: $targetDescription. " +
                    "Reply with ONLY JSON {\"x\": <0-1000>, \"y\": <0-1000>} for its center, " +
                    "or {\"x\": -1, \"y\": -1} if absent."
                val bodyJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", imageBase64Jpeg)
                                })
                            })
                            put(JSONObject().put("text", prompt))
                        })
                    ))
                }
                val req = Request.Builder()
                    .url("$base/models/gemini-3.8-flash:generateContent?key=$apiKey")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) return@withContext null
                    val text = JSONObject(body)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                        ?.optJSONObject(0)?.optString("text") ?: return@withContext null
                    val x = Regex("\"x\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    val y = Regex("\"y\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
                    if (x == null || y == null || x < 0 || y < 0) null else x to y
                }
            } catch (_: Exception) {
                null
            }
        }
}
