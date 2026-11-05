package com.nayem.liveagent.mcp

import android.content.Context
import com.nayem.liveagent.data.JsonFiles
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * JSON file store for MCP server configs, following the [JsonFiles] pattern
 * from data/AppStores.kt. Backed by filesDir/live_agent/mcp_servers.json.
 * No servers are preconfigured — the user adds them from the UI.
 */
class McpStore(context: Context) {
    private val dir: File = File(context.filesDir, "live_agent").apply { mkdirs() }
    private val serversFile = File(dir, "mcp_servers.json")

    fun loadServers(): List<McpServerConfig> =
        JsonFiles.readList(serversFile) { JsonFiles.json.decodeFromString<List<McpServerConfig>>(it) }

    fun saveServers(items: List<McpServerConfig>) =
        JsonFiles.writeList(serversFile, items) { JsonFiles.json.encodeToString(it) }
}
