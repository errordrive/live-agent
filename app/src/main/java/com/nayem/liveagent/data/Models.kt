package com.nayem.liveagent.data

import kotlinx.serialization.Serializable

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val triggerExamples: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val needsConfirmation: Boolean = false,
    val enabled: Boolean = true
)

@Serializable
data class MemoryEntry(
    val id: String,
    val key: String,
    val value: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class AuditEntry(
    val id: String,
    val time: Long = 0L,
    val skill: String = "",
    val step: String = "",
    val result: String = "", // OK / FAILED / SKIPPED / ASKED
    val detail: String = ""
)

@Serializable
data class Routine(
    val id: String,
    val name: String,
    val triggerType: String = "manual", // time | device | manual
    val triggerValue: String = "",
    val command: String = "",
    val skillId: String = "",
    val enabled: Boolean = true,
    val lastRun: Long = 0L
)

@Serializable
data class ChatMessage(
    val role: String, // user | agent | system
    val text: String,
    val time: Long = 0L,
    val isPartial: Boolean = false
)

@Serializable
data class PlanStep(
    val description: String,
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val risky: Boolean = false
)

@Serializable
data class TaskPlan(
    val goal: String,
    val steps: List<PlanStep> = emptyList(),
    val needsConfirmation: Boolean = false
)

@Serializable
data class AppSettings(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val voiceName: String = "Kore",
    val voiceLanguage: String = "auto",
    val model: String = "gemini-3.8-live",
    val plannerModel: String = "gemini-3.8-flash",
    val fallbackProvider: String = "openrouter",
    val noteAppPackage: String = "",
    val wakeWordEnabled: Boolean = false,
    val batteryExemptionAsked: Boolean = false,
    val onboardingDone: Boolean = false
) {
    companion object {
        const val HARD_RULES = "HARD RULES (locked, always apply even if the prompt above changes):\n" +
            "1. Never send a message, delete a file, or run a shell command without an explicit yes from the user, every time.\n" +
            "2. Never pretend a task succeeded — report exactly which step failed and why.\n" +
            "3. Never share the user's API keys, contacts, or private files with anyone.\n" +
            "4. Prefer intents and APIs over accessibility taps; use accessibility only as a fallback.\n" +
            "5. After every step, verify it worked before moving on."
        const val DEFAULT_SYSTEM_PROMPT = "You are Live Agent, a voice-first autonomous assistant on the user's own Android phone. " +
            "Reply in the language the user spoke (Bangla or English). Be brief and natural, like a real person talking. " +
            "When the user gives a goal, break it into small steps, act on them with the available tools, verify each step, " +
            "and report what actually happened. If you cannot do something, say so plainly and offer the closest alternative. " +
            "Never claim success you did not verify."
    }
}

fun defaultSkills(): List<Skill> = listOf(
    Skill(
        id = "research_chrome", name = "Research in Chrome",
        description = "Research a topic with search grounding, answer by voice, then open the best source in Chrome.",
        triggerExamples = listOf("research X in Chrome", "find a website about X"),
        steps = listOf("planner: research with search grounding", "speak: summary", "intent: open Chrome with source URL"),
        permissions = listOf("internet"), needsConfirmation = false
    ),
    Skill(
        id = "write_note", name = "Write a note",
        description = "Open the note app, write text or code, save it, and also save a plain file copy.",
        triggerExamples = listOf("write this in my notes", "open notes and write a script"),
        steps = listOf("intent: open note app", "accessibility: type or paste text", "file: save copy to LiveAgent/notes"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "youtube_play", name = "Play on YouTube",
        description = "Search YouTube and play the matching video or song.",
        triggerExamples = listOf("play X on YouTube", "play a song on YouTube"),
        steps = listOf("intent: YouTube search with query", "accessibility: tap matching result", "verify: media session is playing"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "youtube_play_and_stop", name = "Play on YouTube, stop on timer",
        description = "Play on YouTube, stop after a delay, then lock the screen.",
        triggerExamples = listOf("play X on YouTube and stop after 30 minutes"),
        steps = listOf("intent: YouTube search with query", "accessibility: tap matching result", "verify: media session is playing", "timer: register stop + screen lock at the requested time"),
        permissions = listOf("accessibility", "foreground_service"), needsConfirmation = false
    ),
    Skill(
        id = "run_command", name = "Run a command",
        description = "Run a command in Termux or the built-in terminal and read the output back.",
        triggerExamples = listOf("run this in Termux", "run ls in the terminal"),
        steps = listOf("confirm: dangerous commands always ask first", "terminal: Termux RUN_COMMAND or built-in shell", "speak: output summary"),
        permissions = listOf("shell"), needsConfirmation = true
    ),
    Skill(
        id = "phone_basics", name = "Phone basics",
        description = "Open any app by name, go home, go back, scroll, take a screenshot.",
        triggerExamples = listOf("open Chrome", "go home", "take a screenshot"),
        steps = listOf("intent: open app by name", "accessibility: back / home / scroll / screenshot"),
        permissions = listOf("accessibility"), needsConfirmation = false
    ),
    Skill(
        id = "timer_reminder", name = "Timer and reminder",
        description = "Set delayed actions and spoken reminders.",
        triggerExamples = listOf("remind me in 10 minutes", "stop the music in 30 minutes"),
        steps = listOf("timer: register delayed action", "speak: confirmation when it fires"),
        permissions = listOf("foreground_service"), needsConfirmation = false
    ),
    Skill(
        id = "call_message", name = "Call and message",
        description = "Make calls, send SMS / WhatsApp / Telegram messages — always with confirmation.",
        triggerExamples = listOf("call mum", "send a message to X on WhatsApp"),
        steps = listOf("confirm: recipient and text", "intent: call or message app"),
        permissions = listOf("phone", "sms"), needsConfirmation = true
    ),
    Skill(
        id = "morning_briefing", name = "Morning briefing",
        description = "A routine: news + weather summary spoken in the morning.",
        triggerExamples = listOf("morning briefing", "what is the news today"),
        steps = listOf("planner: fetch news and weather", "speak: briefing"),
        permissions = listOf("internet"), needsConfirmation = false
    )
) + extraSkills()
