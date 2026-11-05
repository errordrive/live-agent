package com.nayem.liveagent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)

@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: String = "{}"
)

@Serializable
data class McpCallResult(
    val success: Boolean,
    val text: String = "",
    val error: String = ""
)

/**
 * Minimal MCP client (JSON-RPC 2.0 over HTTP, Streamable HTTP style).
 * POST is tried first; [listTools] falls back to a plain GET and parses any
 * "tools" array (covers SSE-streaming servers that answer GET).
 * No servers are hardcoded — the caller supplies [McpServerConfig].
 * All failures are returned as Result.failure with the real server message.
 */
class McpClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** POST tools/list; on failure fall back to GET and parse any "tools" array. */
    suspend fun listTools(server: McpServerConfig): Result<List<McpTool>> =
        withContext(Dispatchers.IO) {
            val postResult = runCatching {
                val body = rpcRequest("tools/list", buildJsonObject {})
                val raw = post(server, body)
                parseTools(raw)
            }
            if (postResult.isSuccess) return@withContext postResult
            runCatching {
                val raw = get(server)
                parseTools(raw)
            }.recoverCatching { getError ->
                throw IllegalStateException(
                    "tools/list failed: POST: ${postResult.exceptionOrNull()?.message}; " +
                        "GET fallback: ${getError.message}"
                )
            }
        }

    /** POST tools/call with the given tool name and arguments JSON object string. */
    suspend fun callTool(
        server: McpServerConfig,
        toolName: String,
        argumentsJson: String
    ): Result<McpCallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val arguments = try {
                json.parseToJsonElement(argumentsJson).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("argumentsJson is not a JSON object: ${e.message}")
            }
            val body = rpcRequest("tools/call") {
                put("name", toolName)
                put("arguments", arguments)
            }
            val raw = post(server, body)
            parseCallResult(raw)
        }
    }

    /** POST initialize; returns true when the server answers with a "result". */
    suspend fun ping(server: McpServerConfig): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = rpcRequest("initialize") {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "Live Agent")
                        put("version", "1.1.0")
                    }
                }
                val raw = post(server, body)
                json.parseToJsonElement(raw).jsonObject.containsKey("result")
            }
        }

    // ---- HTTP ----

    private fun post(server: McpServerConfig, bodyJson: JsonObject): String {
        val request = Request.Builder()
            .url(server.url)
            .apply { server.headers.forEach { (k, v) -> addHeader(k, v) } }
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("MCP server ${response.code}: ${body.ifBlank { response.message }}")
            }
            return body
        }
    }

    private fun get(server: McpServerConfig): String {
        val request = Request.Builder()
            .url(server.url)
            .apply { server.headers.forEach { (k, v) -> addHeader(k, v) } }
            .addHeader("Accept", "application/json, text/event-stream")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("MCP server ${response.code}: ${body.ifBlank { response.message }}")
            }
            return body
        }
    }

    // ---- JSON-RPC helpers ----

    private fun rpcRequest(method: String, params: JsonObject): JsonObject =
        rpcRequest(method) { params.forEach { (k, v) -> put(k, v) } }

    private fun rpcRequest(method: String, paramsBuilder: JsonObjectBuilderScope.() -> Unit): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", method)
            putJsonObject("params", paramsBuilder)
        }

    /** Parses tools out of a JSON-RPC result or a raw/SSE body containing a "tools" array. */
    private fun parseTools(raw: String): List<McpTool> {
        val element = extractJson(raw)
            ?: throw IllegalStateException("No JSON object found in server response")
        val root = element.jsonObject
        val result = root["result"]?.jsonObject ?: root
        val toolsArray = result["tools"]?.jsonArray
            ?: throw IllegalStateException("Response has no tools array: ${raw.take(300)}")
        return toolsArray.map { item ->
            val obj = item.jsonObject
            val schema = obj["inputSchema"]
            McpTool(
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = schema?.toString() ?: "{}"
            )
        }
    }

    /** Parses a tools/call result: joins text content parts; isError=false means success. */
    private fun parseCallResult(raw: String): McpCallResult {
        val element = extractJson(raw)
            ?: throw IllegalStateException("No JSON object found in server response")
        val root = element.jsonObject
        root["error"]?.jsonObject?.let { err ->
            val message = err["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return McpCallResult(success = false, error = message.ifBlank { "MCP error" })
        }
        val result = root["result"]?.jsonObject ?: root
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
        val content = result["content"]
        val text = when {
            content is JsonArray -> content.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.joinToString("\n")
            content is JsonObject -> content["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            content is JsonPrimitive -> content.contentOrNull.orEmpty()
            else -> result["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        return if (isError) {
            McpCallResult(success = false, text = text, error = text.ifBlank { "Tool reported an error" })
        } else {
            McpCallResult(success = true, text = text)
        }
    }

    /**
     * Extracts the first JSON object from a body that may be plain JSON or an
     * SSE stream ("data: {...}" lines, possibly several events).
     */
    private fun extractJson(raw: String): JsonObject? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        }
        for (line in raw.lines()) {
            val data = line.trim().removePrefix("data:").trim()
            if (data.startsWith("{")) {
                runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()?.let { return it }
            }
        }
        return null
    }
}

/** Type alias so the rpcRequest builder lambda reads clearly. */
private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder
