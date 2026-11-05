package com.nayem.liveagent.data

import java.util.UUID

/**
 * Auto-save memory, like Gemini / ChatGPT / Claude.
 * Every conversation turn is stored on-device (Room-free JSON store, same as
 * the rest of the app), and durable facts are extracted from what the user
 * says so the planner can use them later. Everything is on-device only.
 * Nothing is shared, and the Memory screen can view / edit / forget any entry.
 */
object MemoryAuto {

    /** Extract durable facts from one user turn. Returns (key, value) pairs. */
    fun extractFacts(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val t = text.trim()
        if (t.isBlank()) return out
        // "remember that my favorite X is Y" / "remember my X is Y"
        val remember = Regex(
            """(?i)remember (?:that )?(?:my )?(.+?)\s+is\s+(.+)"""
        ).find(t)
        if (remember != null) {
            out += remember.groupValues[1].trim().lowercase()
                .replaceFirstChar { it.uppercaseChar() } to remember.groupValues[2].trim().trimEnd('.', '!')
        }
        // "my favorite X is Y"
        val fav = Regex("""(?i)my favourite (.+?) is (.+)""").find(t)
            ?: Regex("""(?i)my favorite (.+?) is (.+)""").find(t)
        if (fav != null) {
            out += "Favorite ${fav.groupValues[1].trim()}" to fav.groupValues[2].trim().trimEnd('.', '!')
        }
        // "I prefer X" / "I like X"
        val prefer = Regex("""(?i)I prefer (.+)""").find(t)
        if (prefer != null) out += "Preference" to prefer.groupValues[1].trim().trimEnd('.', '!')
        // "call me X" — preferred name
        val name = Regex("""(?i)call me (.+)""").find(t)
        if (name != null) out += "Preferred name" to name.groupValues[1].trim().trimEnd('.', '!')
        return out.distinct()
    }

    /** Save a finished conversation turn. Facts go to memory; the turn itself
     *  is kept as a short "Conversation" entry so long chats can be summarised. */
    fun autoSaveTurn(stores: AppStores, role: String, text: String) {
        if (text.isBlank()) return
        try {
            val now = System.currentTimeMillis()
            val entries = stores.loadMemory().toMutableList()
            if (role == "user") {
                for ((key, value) in extractFacts(text)) {
                    val existing = entries.indexOfFirst { it.key.equals(key, ignoreCase = true) }
                    if (existing >= 0) {
                        entries[existing] = entries[existing].copy(value = value, updatedAt = now)
                    } else {
                        entries.add(
                            MemoryEntry(
                                id = UUID.randomUUID().toString(),
                                key = key, value = value,
                                createdAt = now, updatedAt = now
                            )
                        )
                    }
                }
            }
            // Keep a rolling summary of the conversation (last 30 turns, capped).
            val convoKey = "Conversation summary"
            val summary = entries.firstOrNull { it.key == convoKey }
            val line = "${if (role == "user") "You" else "Agent"}: ${text.take(200)}"
            val newValue = if (summary == null) line
            else (summary.value + "\n" + line).lines().takeLast(30).joinToString("\n")
            if (summary == null) {
                entries.add(
                    MemoryEntry(
                        id = UUID.randomUUID().toString(),
                        key = convoKey, value = newValue,
                        createdAt = now, updatedAt = now
                    )
                )
            } else {
                val idx = entries.indexOfFirst { it.key == convoKey }
                entries[idx] = summary.copy(value = newValue, updatedAt = now)
            }
            stores.saveMemory(entries)
        } catch (_: Exception) {
            // Memory must never break a conversation.
        }
    }

    /** Short memory context for the planner: durable facts only, not the raw log. */
    fun memoryContext(stores: AppStores): String {
        return try {
            val entries = stores.loadMemory()
                .filter { it.key != "Conversation summary" }
                .take(20)
            if (entries.isEmpty()) "" else
                "What you remember about the user:\n" +
                    entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        } catch (_: Exception) { "" }
    }
}
