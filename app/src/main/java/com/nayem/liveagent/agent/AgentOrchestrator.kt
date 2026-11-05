package com.nayem.liveagent.agent

import android.content.Context
import android.media.AudioManager
import com.nayem.liveagent.data.AppSettings
import com.nayem.liveagent.data.AppStores
import com.nayem.liveagent.data.PlanStep
import com.nayem.liveagent.data.SecureStore
import com.nayem.liveagent.data.TaskPlan
import com.nayem.liveagent.live.GeminiRestClient
import com.nayem.liveagent.service.AgentForegroundService
import com.nayem.liveagent.service.LiveAccessibilityService
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgentOrchestrator — Hear → Plan → Act → Verify → Report.
 * Keyword routing first (fast, free, deterministic); the planner model only
 * fills in goals that match no starter skill. Max 12 steps per task.
 * Destructive steps always go through [confirm]; a step that fails twice
 * stops the task instead of looping.
 */
class AgentOrchestrator(
    private val context: Context,
    private val stores: AppStores,
    private val secure: SecureStore
) {
    val phoneTools = PhoneTools(context)
    val terminalTools = TerminalTools(context)
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun log(skill: String, step: String, result: String, detail: String) {
        stores.appendAudit(
            com.nayem.liveagent.data.AuditEntry(
                id = UUID.randomUUID().toString(),
                time = System.currentTimeMillis(),
                skill = skill, step = step, result = result, detail = detail.take(500)
            )
        )
    }

    /** Build a step plan for a goal. Keyword routing first, planner model as fallback. */
    suspend fun buildPlan(goal: String): TaskPlan = withContext(Dispatchers.Default) {
        val g = goal.lowercase()
        val timerMinutes = Regex("(\\d+)\\s*(min|minute)").find(g)?.groupValues?.get(1)?.toIntOrNull()
        when {
            g.contains("youtube") && (g.contains("stop") || timerMinutes != null) -> {
                val query = goal.substringAfter("play", goal).substringBefore(" and stop", goal)
                    .substringBefore(" on YouTube", goal).trim().ifBlank { goal }
                TaskPlan(goal, listOf(
                    PlanStep("Open YouTube search for \"$query\"", "intent_youtube", mapOf("query" to query)),
                    PlanStep("Tap the matching result", "a11y_tap", mapOf("text" to query)),
                    PlanStep("Verify playback started", "verify_media"),
                    PlanStep("Stop after ${timerMinutes ?: 30} minutes and lock the screen", "timer",
                        mapOf("minutes" to (timerMinutes ?: 30).toString(), "lock" to "true"))
                ))
            }
            g.contains("youtube") || g.contains("play") && (g.contains("song") || g.contains("music") || g.contains("movie") || g.contains("natok")) -> {
                val query = goal.substringAfter("play", goal).substringBefore(" on YouTube", goal).trim().ifBlank { goal }
                TaskPlan(goal, listOf(
                    PlanStep("Open YouTube search for \"$query\"", "intent_youtube", mapOf("query" to query)),
                    PlanStep("Tap the matching result", "a11y_tap", mapOf("text" to query)),
                    PlanStep("Verify playback started", "verify_media")
                ))
            }
            g.contains("note") || g.contains("notepad") || g.contains("write") -> {
                val text = goal.substringAfter("write", goal).trim().ifBlank { goal }
                TaskPlan(goal, listOf(
                    PlanStep("Open the note app", "intent_note"),
                    PlanStep("Write the text into the note", "a11y_type", mapOf("text" to text)),
                    PlanStep("Save a file copy", "file_note", mapOf("title" to "note", "text" to text))
                ))
            }
            g.contains("chrome") || g.contains("research") || g.contains("search") && g.contains("website") -> {
                val topic = goal.substringAfter("research", goal).substringAfter("search", goal)
                    .substringBefore(" in Chrome", goal).trim().ifBlank { goal }
                TaskPlan(goal, listOf(
                    PlanStep("Research \"$topic\" with search grounding", "speak", mapOf("prompt" to "Research and summarise in 3 short sentences: $topic")),
                    PlanStep("Open the best source in Chrome", "intent_chrome", mapOf("query" to topic))
                ))
            }
            g.contains("termux") || g.contains("terminal") || g.contains("run") && g.contains("command") -> {
                val cmd = goal.substringAfter("run", goal).substringAfter("Termux", goal).trim()
                TaskPlan(goal, listOf(
                    PlanStep("Run command: $cmd", if (g.contains("termux")) "terminal_termux" else "terminal_builtin",
                        mapOf("command" to cmd), risky = terminalTools.isDangerous(cmd))
                ), needsConfirmation = terminalTools.isDangerous(cmd))
            }
            (g.contains("stop") || g.contains("timer") || g.contains("remind")) && timerMinutes != null -> {
                TaskPlan(goal, listOf(
                    PlanStep("Register a $timerMinutes-minute timer", "timer",
                        mapOf("minutes" to timerMinutes.toString(), "lock" to if (g.contains("screen off") || g.contains("lock")) "true" else "false"))
                ))
            }
            g.startsWith("open ") -> {
                val app = goal.substringAfter("open").trim()
                TaskPlan(goal, listOf(PlanStep("Open $app", "intent_app", mapOf("name" to app))))
            }
            g.contains("call ") -> {
                TaskPlan(goal, listOf(
                    PlanStep("Call: $goal", "call", mapOf("number" to goal.filter { it.isDigit() || it == '+' }), risky = true)
                ), needsConfirmation = true)
            }
            else -> plannerPlan(goal)
        }
    }

    /** Planner-model fallback: ask Gemini to split the goal into short steps.
     *  Memory is read first so remembered preferences shape the plan. */
    private suspend fun plannerPlan(goal: String): TaskPlan {
        if (!secure.hasGeminiKey()) {
            return TaskPlan(goal, listOf(
                PlanStep("Answer by voice (no API key set yet — add one in Settings)", "speak",
                    mapOf("prompt" to goal))
            ))
        }
        return try {
            val settings = stores.loadSettings()
            val memory = com.nayem.liveagent.data.MemoryAuto.memoryContext(stores)
            val prompt = "${settings.systemPrompt}\n\n${AppSettings.HARD_RULES}\n\n" +
                (if (memory.isNotBlank()) "$memory\n\n" else "") +
                "Split this goal into at most 6 short steps, one per line, each starting with one of: " +
                "OPEN_APP, YOUTUBE, CHROME, NOTE, TERMINAL, TIMER, SPEAK. Goal: $goal"
            val text = GeminiRestClient(secure.geminiApiKey).generateText(prompt, settings.plannerModel)
            val steps = text.lines().map { it.trim() }.filter { it.length > 3 }.take(6).map { line ->
                val tool = when {
                    line.startsWith("YOUTUBE") -> "intent_youtube"
                    line.startsWith("CHROME") -> "intent_chrome"
                    line.startsWith("NOTE") -> "intent_note"
                    line.startsWith("TERMINAL") -> "terminal_builtin"
                    line.startsWith("TIMER") -> "timer"
                    line.startsWith("OPEN_APP") -> "intent_app"
                    else -> "speak"
                }
                PlanStep(line.removePrefix(tool).trim(), tool, mapOf("text" to line, "query" to line))
            }
            if (steps.isEmpty()) TaskPlan(goal, listOf(PlanStep("Answer by voice", "speak", mapOf("prompt" to goal))))
            else TaskPlan(goal, steps, needsConfirmation = steps.any { it.risky })
        } catch (_: Exception) {
            TaskPlan(goal, listOf(PlanStep("Answer by voice", "speak", mapOf("prompt" to goal))))
        }
    }

    /** Execute a plan step by step. Returns (step, result) pairs. */
    suspend fun executePlan(
        plan: TaskPlan,
        onStep: (Int, String) -> Unit = { _, _ -> },
        confirm: suspend (PlanStep) -> Boolean = { true }
    ): List<Pair<PlanStep, ToolResult>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<PlanStep, ToolResult>>()
        plan.steps.take(12).forEachIndexed { index, step ->
            onStep(index, step.description)
            if (step.risky || plan.needsConfirmation) {
                val ok = try { confirm(step) } catch (_: Exception) { false }
                if (!ok) {
                    val r = ToolResult(false, "Skipped — waiting for your confirmation")
                    results += step to r; log("agent", step.description, "SKIPPED", r.detail)
                    return@forEachIndexed
                }
            }
            val result = runStep(step)
            // One retry with the vision fallback for a failed accessibility tap.
            val final = if (!result.success && step.tool == "a11y_tap") runStepWithVision(step) else result
            results += step to final
            log("agent", step.description, if (final.success) "OK" else "FAILED", final.detail)
            if (!final.success && step.tool !in listOf("verify_media", "speak")) {
                // Stop after two consecutive failures to avoid blind tapping.
                if (results.size >= 2 && !results[results.size - 2].second.success) return@withContext results
            }
        }
        results
    }

    private suspend fun runStep(step: PlanStep): ToolResult {
        val args = step.args
        return when (step.tool) {
            "intent_youtube" -> phoneTools.openYouTubeSearch(args["query"] ?: args["text"] ?: "")
            "intent_chrome" -> {
                val q = args["query"] ?: args["text"] ?: ""
                phoneTools.openChrome("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            }
            "intent_note" -> phoneTools.openNoteApp(stores.loadSettings().noteAppPackage, "Note", args["text"] ?: "")
            "intent_app" -> phoneTools.openAppByName(args["name"] ?: args["text"] ?: "")
            "a11y_tap" -> {
                phoneTools.screenTapText(args["text"] ?: args["query"] ?: "")
            }
            "screen_tap" -> phoneTools.screenTapText(args["text"] ?: args["query"] ?: "")
            "screen_tap_at" -> {
                val x = args["x"]?.toFloatOrNull() ?: 0f
                val y = args["y"]?.toFloatOrNull() ?: 0f
                phoneTools.screenTapAt(x, y)
            }
            "screen_tap_id" -> phoneTools.screenTapById(args["viewId"] ?: args["text"] ?: "")
            "screen_longpress" -> phoneTools.screenLongPressText(args["text"] ?: "")
            "screen_swipe" -> {
                val parts = (args["coords"] ?: "").split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (parts.size >= 4) phoneTools.screenSwipe(parts[0], parts[1], parts[2], parts[3])
                else ToolResult(false, "Swipe needs x1,y1,x2,y2 coordinates")
            }
            "screen_scroll" -> phoneTools.screenScroll(args["direction"] ?: "down")
            "screen_press" -> phoneTools.screenPress(args["action"] ?: args["text"] ?: "back")
            "screen_volume" -> phoneTools.screenVolume(args["direction"] ?: "up")
            "mcp_call" -> runMcpCall(args)
            "terminal_ubuntu" -> ToolResult(
                false,
                "Ubuntu environment is not available in Live Agent v1.1.0 — " +
                    "the Ubuntu install was not delivered. Use Termux (terminal_termux) " +
                    "or the built-in terminal (terminal_builtin) instead."
            )
            "a11y_type" -> {
                val svc = LiveAccessibilityService.instance
                    ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
                val text = args["text"] ?: ""
                if (svc.typeText(text)) ToolResult(true, "Typed ${text.length} characters")
                else ToolResult(false, "No editable field focused on screen")
            }
            "file_note" -> {
                val file = stores.saveNoteCopy(args["title"] ?: "note", args["text"] ?: "")
                ToolResult(true, "File copy saved: ${file.absolutePath}")
            }
            "verify_media" -> {
                // Give playback a moment, then check the audio state.
                kotlinx.coroutines.delay(2500)
                if (audioManager.isMusicActive) ToolResult(true, "Media is playing")
                else ToolResult(false, "Media does not appear to be playing yet")
            }
            "timer" -> {
                val minutes = args["minutes"]?.toIntOrNull() ?: 30
                val lock = args["lock"] == "true"
                AgentForegroundService.startTimer(context, minutes, lock)
                ToolResult(true, "Timer set for $minutes minutes" + if (lock) ", screen will lock when it ends" else "")
            }
            "lock_screen" -> {
                LiveAccessibilityService.instance?.lockScreen()
                    ?: return ToolResult(false, "Accessibility service is not enabled")
                ToolResult(true, "Screen locked")
            }
            "terminal_termux" -> terminalTools.runTermux(args["command"] ?: "")
            "terminal_builtin" -> terminalTools.runBuiltIn(args["command"] ?: "")
            "call" -> phoneTools.makeCall(args["number"] ?: "")
            "sms" -> phoneTools.sendSms(args["number"] ?: "", args["text"] ?: "")
            "speak" -> {
                val prompt = args["prompt"] ?: args["text"] ?: ""
                if (!secure.hasGeminiKey()) return ToolResult(false, "No Gemini API key set")
                val answer = GeminiRestClient(secure.geminiApiKey).generateText(prompt)
                ToolResult(answer.isNotBlank(), answer.ifBlank { "No answer returned" })
            }
            else -> ToolResult(false, "Unknown tool: ${step.tool}")
        }
    }

    /** Call a tool on a configured MCP server. Args: serverId, tool, arguments (JSON). */
    private suspend fun runMcpCall(args: Map<String, String>): ToolResult {
        return try {
            val store = com.nayem.liveagent.mcp.McpStore(context)
            val serverId = args["serverId"] ?: args["server"] ?: ""
            val server = store.loadServers().firstOrNull { it.id == serverId && it.enabled }
                ?: store.loadServers().firstOrNull { it.enabled }
                ?: return ToolResult(false, "No MCP server configured — add one in the MCP screen first")
            val tool = args["tool"] ?: return ToolResult(false, "MCP call needs a tool name")
            val result = com.nayem.liveagent.mcp.McpClient()
                .callTool(server, tool, args["arguments"] ?: "{}")
            result.fold(
                onSuccess = { ToolResult(it.success, it.text.ifBlank { it.error }) },
                onFailure = { ToolResult(false, "MCP call failed: ${it.message}") }
            )
        } catch (e: Exception) {
            ToolResult(false, "MCP call failed: ${e.message}")
        }
    }

    /** Vision fallback: screenshot, ask Gemini where the element is, tap the coordinates. */
    private suspend fun runStepWithVision(step: PlanStep): ToolResult {
        return try {
            val svc = LiveAccessibilityService.instance ?: return ToolResult(false, "Accessibility service is not enabled")
            val file = phoneTools.takeScreenshotFile() ?: return ToolResult(false, "Could not take a screenshot")
            if (!secure.hasGeminiKey()) return ToolResult(false, "No API key for the vision fallback")
            val bytes = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val target = step.args["text"] ?: step.args["query"] ?: ""
            val point = GeminiRestClient(secure.geminiApiKey).visionLocate(base64, target) ?: return ToolResult(false, "Vision could not find \"$target\"")
            val metrics = context.resources.displayMetrics
            val x = point.first / 1000f * metrics.widthPixels
            val y = point.second / 1000f * metrics.heightPixels
            if (svc.tapAt(x, y)) ToolResult(true, "Tapped \"$target\" via vision at ${point.first.toInt()},${point.second.toInt()}")
            else ToolResult(false, "Vision tap failed for \"$target\"")
        } catch (e: Exception) {
            ToolResult(false, "Vision fallback failed: ${e.message}")
        }
    }
}
