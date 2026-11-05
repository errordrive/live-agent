package com.nayem.liveagent.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Result of a detailed API-key check (see [GeminiRestClient.testKeyDetailed]). */
data class KeyTestResult(
    val totalModels: Int,
    val liveModels: List<String>,
    val liveReady: Boolean,
    val message: String
)

/** Lenient JSON parser shared by the key-test helpers below. */
private val modelsJson = Json { ignoreUnknownKeys = true }

/**
 * True when a model is a Live model: its API [name] (e.g. "models/gemini-3.8-live")
 * or its [displayName] contains "live", case-insensitively.
 */
fun isLiveModel(name: String, displayName: String): Boolean =
    name.contains("live", ignoreCase = true) ||
        displayName.contains("live", ignoreCase = true)

/**
 * Parse one page of a `GET /v1beta/models` list response.
 * Returns (total models on this page, short names of the Live models on this page).
 * Short names have the "models/" prefix stripped. Malformed JSON yields (0, emptyList()).
 * Pure function — no Android APIs, unit-testable on the JVM.
 */
fun parseModelsList(json: String): Pair<Int, List<String>> {
    return try {
        val root = modelsJson.parseToJsonElement(json).jsonObject
        val models = root["models"]?.jsonArray ?: return 0 to emptyList()
        val live = mutableListOf<String>()
        for (element in models) {
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: ""
            if (isLiveModel(name, displayName)) {
                val shortName = name.removePrefix("models/").ifBlank { displayName }
                if (shortName.isNotBlank()) live += shortName
            }
        }
        models.size to live
    } catch (_: Exception) {
        0 to emptyList()
    }
}

/** Extract `nextPageToken` from a models-list response, or null when absent/blank. */
fun parseNextPageToken(json: String): String? {
    return try {
        modelsJson.parseToJsonElement(json).jsonObject["nextPageToken"]
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/**
 * Extract the real server error message from a Gemini error body
 * (`{"error": {"message": "..."}}`), falling back to [fallback] when the body
 * has no usable message. Never invents a generic message when the server gave one.
 */
fun parseErrorMessage(json: String, fallback: String): String {
    return try {
        modelsJson.parseToJsonElement(json).jsonObject["error"]
            ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: fallback
    } catch (_: Exception) {
        fallback
    }
}

/** The user-facing message for a key test: exact strings the UI shows. */
fun keyTestMessage(totalModels: Int, liveReady: Boolean): String =
    if (liveReady) "Key OK — Live ready (gemini-3.8-live available)"
    else "Key OK — $totalModels models available, Live not listed"

/** REST helper for the Gemini Developer API: key test, planning, vision fallback. */
class GeminiRestClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    /**
     * Detailed API-key test.
     *
     * 1. Lists ALL models (`GET $base/models`), following `nextPageToken` for up to
     *    3 pages, counting every model and collecting Live ones case-insensitively
     *    from `name` or `displayName`.
     * 2. Directly probes `GET $base/models/gemini-3.8-live`: HTTP 200 means Live is
     *    ready even if the list did not mention it. A non-200 here is NOT fatal —
     *    the list result already proved the key.
     *
     * On an HTTP error from the list call, fails with the real server error message
     * parsed from `error.message` in the response body — never a generic message.
     * Fails only when the key produced no models at all and Live is not reachable.
     */
    suspend fun testKeyDetailed(): Result<KeyTestResult> = withContext(Dispatchers.IO) {
        try {
            var total = 0
            val live = mutableListOf<String>()
            var pageToken: String? = null
            for (page in 0 until 3) {
                val url = buildString {
                    append("$base/models?key=$apiKey")
                    if (pageToken != null) append("&pageToken=$pageToken")
                }
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            Exception(parseErrorMessage(body, "HTTP ${resp.code}"))
                        )
                    }
                    val (pageTotal, pageLive) = parseModelsList(body)
                    total += pageTotal
                    live += pageLive
                    pageToken = parseNextPageToken(body)
                }
                if (pageToken.isNullOrBlank()) break
            }

            var liveReady = live.any { it.equals("gemini-3.8-live", ignoreCase = true) }
            if (!liveReady) {
                // Best-effort direct probe: the list call already proved the key, so
                // a failure here (404, network blip) must not fail the whole test.
                try {
                    val req = Request.Builder()
                        .url("$base/models/gemini-3.8-live?key=$apiKey").get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.code == 200) liveReady = true
                    }
                } catch (_: Exception) {
                    // Ignore: liveReady stays as determined by the list.
                }
            }

            if (total == 0 && !liveReady) {
                return@withContext Result.failure(
                    Exception("Key responded but returned no models")
                )
            }
            Result.success(
                KeyTestResult(
                    totalModels = total,
                    liveModels = live.toList(),
                    liveReady = liveReady,
                    message = keyTestMessage(total, liveReady)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compatibility wrapper for the old key test. Returns the Live model names,
     * or an empty list when the detailed check fails — callers should migrate to
     * [testKeyDetailed] for the real message and error.
     */
    suspend fun testKey(): Result<List<String>> {
        val detailed = testKeyDetailed()
        return if (detailed.isSuccess) {
            Result.success(detailed.getOrNull()?.liveModels ?: emptyList())
        } else {
            Result.success(emptyList())
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
