package com.nayem.liveagent.data

import android.content.Context
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tiny JSON file stores — no Room/KSP, so the build stays fast and reliable.
 *  All user data lives in the app's private internal storage. */
object JsonFiles {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun <T> readList(file: File, decode: (String) -> List<T>): List<T> = try {
        if (!file.exists()) emptyList() else decode(file.readText())
    } catch (_: Exception) { emptyList() }

    fun <T> writeList(file: File, items: List<T>, encode: (List<T>) -> String) {
        try { file.parentFile?.mkdirs(); file.writeText(encode(items)) } catch (_: Exception) {}
    }

    fun <T> readObj(file: File, default: T, decode: (String) -> T): T = try {
        if (!file.exists()) default else decode(file.readText())
    } catch (_: Exception) { default }

    fun <T> writeObj(file: File, value: T, encode: (T) -> String) {
        try { file.parentFile?.mkdirs(); file.writeText(encode(value)) } catch (_: Exception) {}
    }
}

class AppStores(context: Context) {
    private val dir: File = File(context.filesDir, "live_agent").apply { mkdirs() }
    private val notesDir: File = File(context.getExternalFilesDir(null), "LiveAgent/notes").apply { mkdirs() }

    private val skillsFile = File(dir, "skills.json")
    private val memoryFile = File(dir, "memory.json")
    private val auditFile = File(dir, "audit.json")
    private val routinesFile = File(dir, "routines.json")
    private val settingsFile = File(dir, "settings.json")

    fun loadSkills(): List<Skill> {
        val list = JsonFiles.readList(skillsFile) { JsonFiles.json.decodeFromString<List<Skill>>(it) }
        return if (list.isEmpty()) defaultSkills().also { saveSkills(it) } else list
    }
    fun saveSkills(items: List<Skill>) =
        JsonFiles.writeList(skillsFile, items) { JsonFiles.json.encodeToString(it) }

    fun loadMemory(): List<MemoryEntry> =
        JsonFiles.readList(memoryFile) { JsonFiles.json.decodeFromString<List<MemoryEntry>>(it) }
    fun saveMemory(items: List<MemoryEntry>) =
        JsonFiles.writeList(memoryFile, items) { JsonFiles.json.encodeToString(it) }

    fun loadAudit(): List<AuditEntry> =
        JsonFiles.readList(auditFile) { JsonFiles.json.decodeFromString<List<AuditEntry>>(it) }
    fun saveAudit(items: List<AuditEntry>) =
        JsonFiles.writeList(auditFile, items.takeLast(500)) { JsonFiles.json.encodeToString(it) }

    fun appendAudit(entry: AuditEntry) {
        val items = (loadAudit() + entry).takeLast(500)
        saveAudit(items)
    }

    fun loadRoutines(): List<Routine> =
        JsonFiles.readList(routinesFile) { JsonFiles.json.decodeFromString<List<Routine>>(it) }
    fun saveRoutines(items: List<Routine>) =
        JsonFiles.writeList(routinesFile, items) { JsonFiles.json.encodeToString(it) }

    fun loadSettings(): AppSettings =
        JsonFiles.readObj(settingsFile, AppSettings()) { JsonFiles.json.decodeFromString<AppSettings>(it) }
    fun saveSettings(value: AppSettings) =
        JsonFiles.writeObj(settingsFile, value) { JsonFiles.json.encodeToString(it) }

    /** Save a plain file copy of a note (the Write skill also writes into the note app). */
    fun saveNoteCopy(title: String, text: String): File {
        val safe = title.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "note" }
        val file = File(notesDir, "$safe.txt")
        file.writeText(text)
        return file
    }

    fun notesDir(): File = notesDir
}
