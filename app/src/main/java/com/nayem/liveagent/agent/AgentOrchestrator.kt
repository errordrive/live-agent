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
    /** Layered screen control (v1.2.0): fuzzy node tap, vision, scroll-and-search, verify. */
    val screenControl = ScreenControl(context, secure.geminiApiKey)
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
        // Composite research → note flow, checked FIRST: "open Chrome, research
        // AI, note the key points, send it to me / use Google Keep". The speak
        // step is real search-grounded research (GeminiRestClient.generateText
        // uses the googleSearch tool); its answer is chained into the Keep
        // share and the file copy via the "{{prev}}" placeholder in executePlan.
        val wantsResearch = g.contains("research") || g.contains("search") || g.contains("chrome")
        val wantsNote = g.contains("note") || g.contains("keep") || g.contains("notepad") || g.contains("write")
        if (wantsResearch && wantsNote) {
            val topic = extractResearchTopic(goal)
            return@withContext TaskPlan(goal, listOf(
                PlanStep("Research \"$topic\" with search grounding", "speak",
                    mapOf("prompt" to "Research $topic and list the key points as short bullet lines")),
                PlanStep("Open Chrome with \"$topic\" search", "intent_chrome",
                    mapOf("query" to topic)),
                PlanStep("Save the key points to Google Keep", "share_keep",
                    mapOf("title" to "AI research notes", "text" to "{{prev}}")),
                PlanStep("Save a file copy of the notes", "file_note",
                    mapOf("title" to "note", "text" to "{{prev}}"))
            ))
        }
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

    /**
     * Topic for the composite research → note plan: the text after
     * "research"/"search", cut at the first of "in Chrome" / "and note" /
     * "note" / "keep" / "notepad" / "write" / "send it". Falls back to the
     * whole goal when nothing usable remains.
     * e.g. "open Chrome, research AI, note the key points, send it to me" -> "AI".
     */
    private fun extractResearchTopic(goal: String): String {
        val lower = goal.lowercase()
        val start = listOf("research", "search")
            .mapNotNull { word ->
                lower.indexOf(word).takeIf { it >= 0 }?.plus(word.length)
            }.minOrNull() ?: 0
        var topic = if (start > 0) goal.substring(start) else goal
        val cutMarkers = listOf(
            "in chrome", "and note", "note", "keep", "notepad", "write",
            "send it to me", "send it", "to me", "for me"
        )
        var earliest = topic.length
        for (marker in cutMarkers) {
            val idx = topic.lowercase().indexOf(marker)
            if (idx >= 0 && idx < earliest) earliest = idx
        }
        topic = topic.substring(0, earliest)
            .trim()
            .trim(',', '.', ':', ';')
            .trim()
            .removePrefix("for ").removePrefix("about ").removePrefix("on ")
            .removeSuffix(" and").removeSuffix(" for")
            .trim()
        return topic.ifBlank { goal.trim() }
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
        // Step-result chaining for "{{prev}}": the detail of the last
        // successful step. "{{prev}}" prefers the last successful "speak"
        // (research) result, so an intervening step such as opening Chrome
        // does not clobber the research text the next step needs to save;
        // it falls back to the last successful step's detail otherwise.
        var lastSuccessDetail = ""
        var lastSpeakDetail = ""
        // The API key may have been entered after this orchestrator was built.
        screenControl.apiKey = secure.geminiApiKey
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
            val needsPrev = step.args.values.any { it.contains("{{prev}}") }
            val prevText = lastSpeakDetail.ifBlank { lastSuccessDetail }
            val final: ToolResult
            if (needsPrev && prevText.isBlank()) {
                final = ToolResult(
                    false,
                    "No research result to save yet — the earlier step produced nothing to pass on"
                )
            } else {
                val effectiveStep = if (needsPrev) {
                    step.copy(args = step.args.mapValues { (_, v) -> v.replace("{{prev}}", prevText) })
                } else step
                val result = runStep(effectiveStep)
                // One retry with the vision fallback for a failed accessibility tap.
                final = if (!result.success && effectiveStep.tool == "a11y_tap") runStepWithVision(effectiveStep) else result
            }
            results += step to final
            log("agent", step.description, if (final.success) "OK" else "FAILED", final.detail)
            if (final.success) {
                lastSuccessDetail = final.detail
                if (step.tool == "speak") lastSpeakDetail = final.detail
            }
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
            "intent_keep" -> phoneTools.openKeep()
            "share_keep" -> phoneTools.shareToKeep(args["title"] ?: "Note", args["text"] ?: "")
            "intent_app" -> phoneTools.openAppByName(args["name"] ?: args["text"] ?: "")
            "a11y_tap", "screen_tap" -> {
                // Layered loop (v1.2.0): fuzzy node tap → vision → scroll-and-search,
                // verified after every action. The attempt log IS the detail.
                screenControl.apiKey = secure.geminiApiKey
                val attempt = screenControl.tapTarget(args["text"] ?: args["query"] ?: "")
                ToolResult(attempt.success, attempt.formatLog())
            }
            "screen_tap_at" -> {
                val x = args["x"]?.toFloatOrNull() ?: 0f
                val y = args["y"]?.toFloatOrNull() ?: 0f
                verifyAfter(phoneTools.screenTapAt(x, y))
            }
            "screen_tap_id" -> verifyAfter(phoneTools.screenTapById(args["viewId"] ?: args["text"] ?: ""))
            "screen_longpress" -> verifyAfter(phoneTools.screenLongPressText(args["text"] ?: ""))
            "screen_swipe" -> {
                val parts = (args["coords"] ?: "").split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (parts.size >= 4) verifyAfter(phoneTools.screenSwipe(parts[0], parts[1], parts[2], parts[3]))
                else ToolResult(false, "Swipe needs x1,y1,x2,y2 coordinates")
            }
            "screen_scroll" -> verifyAfter(phoneTools.screenScroll(args["direction"] ?: "down"))
            "screen_press" -> verifyAfter(phoneTools.screenPress(args["action"] ?: args["text"] ?: "back"))
            "screen_volume" -> phoneTools.screenVolume(args["direction"] ?: "up")
            "mcp_call" -> runMcpCall(args)
            "terminal_ubuntu" -> ToolResult(
                false,
                "Ubuntu environment is not available in Live Agent v1.2.0 — " +
                    "the Ubuntu install was not delivered. Use Termux (terminal_termux) " +
                    "or the built-in terminal (terminal_builtin) instead."
            )
            "a11y_type" -> {
                // Layered type: focus the field by hint, set text, verify it
                // landed, retry once via tap-then-type. Log IS the detail.
                screenControl.apiKey = secure.geminiApiKey
                val attempt = screenControl.typeIntoField(
                    args["text"] ?: "",
                    args["fieldHint"] ?: args["hint"] ?: ""
                )
                ToolResult(attempt.success, attempt.formatLog())
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

    /**
     * Verify pass after a raw PhoneTools screen step: re-read the screen and
     * append what is actually visible now to the step's detail, so a tap at
     * coordinates or a scroll is never reported on dispatch alone.
     */
    private fun verifyAfter(result: ToolResult): ToolResult {
        val seen = try {
            screenControl.readScreen().replace("\n", " | ").take(250)
                .ifBlank { "(screen unreadable)" }
        } catch (_: Exception) {
            "(screen unreadable)"
        }
        return ToolResult(result.success, "${result.detail}\nVerify (re-read screen): $seen")
    }

    /** Vision fallback: now delegates to the layered ScreenControl loop
     *  (fuzzy node → vision → scroll-and-search, all verified). Kept as the
     *  executePlan retry entry point for a failed accessibility tap. */
    private suspend fun runStepWithVision(step: PlanStep): ToolResult {
        screenControl.apiKey = secure.geminiApiKey
        val target = step.args["text"] ?: step.args["query"] ?: ""
        val attempt = screenControl.tapTarget(target)
        return ToolResult(attempt.success, attempt.formatLog())
    }
}
