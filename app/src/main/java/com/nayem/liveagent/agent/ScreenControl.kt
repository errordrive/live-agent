package com.nayem.liveagent.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nayem.liveagent.live.GeminiRestClient
import com.nayem.liveagent.service.LiveAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One step of a screen-control attempt: which strategy ran, what it saw, did it work. */
data class AttemptStep(
    val strategy: String, // "node" | "scroll" | "vision" | "verify" | "popup" | "type"
    val seen: String,
    val success: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Full log of one [ScreenControl] call. Never thrown — failure is data, not a crash. */
data class AttemptLog(
    val steps: List<AttemptStep>,
    val success: Boolean
) {
    /** Short readable multi-line log for ToolResult details and the audit trail. */
    fun formatLog(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        val lines = steps.map { step ->
            val mark = if (step.success) "OK  " else "FAIL"
            "[${time.format(Date(step.timestampMs))}] $mark ${step.strategy}: ${step.seen}"
        }
        val verdict = if (success) "SUCCESS" else "FAILED"
        return (lines + "=> $verdict").joinToString("\n")
    }
}

/**
 * ScreenControl — layered, self-correcting screen control (v1.2.0).
 *
 * The old single-attempt tap failed silently whenever the node tree did not
 * contain the exact text. Every action here runs as a loop and is verified:
 *
 *   1. node   — fuzzy node-tree tap (Bangla + English, partial text)
 *   2. vision — screenshot + Gemini visionLocate, tap the coordinates.
 *               A FIRST-CLASS path, tried before any scrolling, not a last resort.
 *   3. scroll — scroll down and re-scan, up to 3 times, then scroll back up
 *               and re-scan once more
 *   4. self-correct — one retry with the other strategy on a fresh screenshot
 *   5. verify — after EVERY action, re-read the screen (package / window /
 *               node dump). Success is reported only when the screen proves
 *               the action landed.
 *
 * Nothing here throws: every failure is returned as a failed [AttemptLog]
 * that says exactly what was seen and which strategy failed.
 *
 * Flows this is designed for (no app-specific hacks): opening Chrome,
 * searching (type into the search box, verify results appeared), opening
 * Google Keep (com.google.android.keep), typing/pasting a note into Keep,
 * scrolling a page.
 */
class ScreenControl(
    private val context: Context,
    /** Gemini API key for the vision path; blank = vision steps are skipped, not fatal. */
    var apiKey: String = ""
) {
    private val phoneTools = PhoneTools(context)

    /**
     * Hook for a vision *description* of a screenshot. The UI worker wires
     * this to the vision-description method the other worker adds to
     * GeminiRestClient; until then it is null and [readScreen] stays an
     * honest node-text dump. [describeScreen] below just delegates to it.
     */
    var describeScreenHook: (suspend (imageBase64Jpeg: String, prompt: String) -> String?)? = null

    /** Vision description of a screenshot, or null when the hook is not wired / it fails. */
    suspend fun describeScreen(imageBase64Jpeg: String, prompt: String): String? {
        return try {
            describeScreenHook?.invoke(imageBase64Jpeg, prompt)
        } catch (e: Exception) {
            Log.w(TAG, "describeScreen failed", e)
            null
        }
    }

    private fun svc(): LiveAccessibilityService? = LiveAccessibilityService.instance

    /**
     * Node-text dump of the current screen (truncated to 4000 chars) plus a
     * visible-nodes summary. Honest: when the app exposes no nodes (WebView /
     * canvas / game), the dump is empty and says so — use [describeScreen]
     * for those screens once it is wired.
     */
    fun readScreen(): String {
        val svc = svc() ?: return "(accessibility service is not enabled)"
        return try {
            val dump = svc.dumpScreenText()
            val summary = svc.visibleNodesSummary()
            val body = if (dump.isBlank()) "(screen dump is empty — app may render in a WebView/canvas)" else dump
            "$body\n--- visible nodes ---\n$summary".take(4000)
        } catch (e: Exception) {
            Log.w(TAG, "readScreen failed", e)
            "(could not read screen: ${e.message})"
        }
    }

    /** Tap a target by label: node → vision → scroll-and-search → self-correct, all verified. */
    suspend fun tapTarget(target: String): AttemptLog {
        val steps = mutableListOf<AttemptStep>()
        try {
            if (target.isBlank()) {
                steps += AttemptStep("node", "empty target", false)
                return AttemptLog(steps, false)
            }
            val svc = svc()
            if (svc == null) {
                steps += AttemptStep("node", "accessibility service is not enabled", false)
                return AttemptLog(steps, false)
            }
            // A stray dialog ("Update available", permission prompt) blocks
            // every tap beneath it — clear it first, once.
            try {
                if (svc.dismissPopup()) {
                    steps += AttemptStep("popup", "dismissed a popup/dialog", true)
                    delay(400)
                }
            } catch (_: Exception) {
            }

            // (a) Fuzzy node-tree tap.
            if (nodeTapAttempt(svc, target, steps)) return AttemptLog(steps, true)

            // (c) Vision grounding — first-class, before any scrolling.
            if (visionTapAttempt(target, steps)) return AttemptLog(steps, true)

            // (b) Scroll-and-search: scroll down, re-scan, up to 3 times.
            repeat(3) { attempt ->
                val scrolled = try {
                    svc.scrollDown()
                } catch (_: Exception) {
                    false
                }
                steps += AttemptStep(
                    "scroll",
                    if (scrolled) "scrolled down (attempt ${attempt + 1}/3)" else "nothing to scroll",
                    scrolled
                )
                if (!scrolled) return@repeat
                delay(500)
                if (nodeTapAttempt(svc, target, steps)) return AttemptLog(steps, true)
            }
            // Scroll back up and re-scan once — the target may have been above.
            try {
                if (svc.scrollUp()) {
                    steps += AttemptStep("scroll", "scrolled back up, re-scanning", true)
                    delay(500)
                    if (nodeTapAttempt(svc, target, steps)) return AttemptLog(steps, true)
                }
            } catch (_: Exception) {
            }

            // Self-correct once: fresh screenshot, vision again on the current screen.
            steps += AttemptStep("vision", "self-correct: retrying vision on a fresh screenshot", false)
            if (visionTapAttempt(target, steps)) return AttemptLog(steps, true)

            steps += AttemptStep(
                "verify",
                "gave up: \"$target\" not found by node, vision or scroll; " +
                    "screen now shows: ${svc.visibleNodesSummary(5).replace("\n", " | ").take(200)}",
                false
            )
            return AttemptLog(steps, false)
        } catch (e: Exception) {
            Log.w(TAG, "tapTarget failed", e)
            steps += AttemptStep("verify", "tapTarget crashed internally: ${e.message}", false)
            return AttemptLog(steps, false)
        }
    }

    /** Focus/tap an editable field, type into it, verify the text landed; retry once. */
    suspend fun typeIntoField(text: String, fieldHint: String = ""): AttemptLog {
        val steps = mutableListOf<AttemptStep>()
        try {
            if (text.isEmpty()) {
                steps += AttemptStep("type", "empty text", false)
                return AttemptLog(steps, false)
            }
            val svc = svc()
            if (svc == null) {
                steps += AttemptStep("type", "accessibility service is not enabled", false)
                return AttemptLog(steps, false)
            }
            // Pass 1: focus the field (by hint when given, else the first editable)
            // and set the text.
            if (typeAttempt(svc, text, fieldHint, steps)) return AttemptLog(steps, true)
            // Pass 2 (the one retry): tap the field first, then type again.
            // This matters for editors like Keep/Chrome omnibox, which only
            // accept input once they hold focus.
            steps += AttemptStep("type", "retry: tap-then-type", false)
            if (typeAttempt(svc, text, fieldHint, steps, tapFirst = true)) return AttemptLog(steps, true)
            steps += AttemptStep(
                "verify",
                "typed text did not appear in the field; screen now shows: " +
                    svc.visibleNodesSummary(5).replace("\n", " | ").take(200),
                false
            )
            return AttemptLog(steps, false)
        } catch (e: Exception) {
            Log.w(TAG, "typeIntoField failed", e)
            steps += AttemptStep("verify", "typeIntoField crashed internally: ${e.message}", false)
            return AttemptLog(steps, false)
        }
    }

    // ---- One node-tap attempt (tap + verify), appending its steps. ----

    private suspend fun nodeTapAttempt(
        svc: LiveAccessibilityService,
        target: String,
        steps: MutableList<AttemptStep>
    ): Boolean {
        if (!svc.isTextVisible(target)) {
            steps += AttemptStep("node", "\"$target\" not in node tree", false)
            return false
        }
        val beforePkg = svc.currentPackage()
        val beforeDump = svc.dumpScreenText()
        val tapped = try {
            svc.tapText(target)
        } catch (_: Exception) {
            false
        }
        if (!tapped) {
            steps += AttemptStep("node", "found \"$target\" but the tap did not dispatch", false)
            return false
        }
        steps += AttemptStep("node", "tapped \"$target\"", true)
        return verifyAfterTap(svc, target, beforePkg, beforeDump, steps)
    }

    // ---- One vision-tap attempt (screenshot + locate + tap + verify). ----

    private suspend fun visionTapAttempt(
        target: String,
        steps: MutableList<AttemptStep>
    ): Boolean {
        if (apiKey.isBlank()) {
            steps += AttemptStep("vision", "skipped — no Gemini API key set", false)
            return false
        }
        val svc = svc() ?: run {
            steps += AttemptStep("vision", "accessibility service is not enabled", false)
            return false
        }
        val file = try {
            phoneTools.takeScreenshotFile()
        } catch (_: Exception) {
            null
        }
        if (file == null || !file.exists()) {
            steps += AttemptStep("vision", "could not take a screenshot", false)
            return false
        }
        val base64 = try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (_: Exception) {
            steps += AttemptStep("vision", "could not read the screenshot", false)
            return false
        }
        val point = try {
            GeminiRestClient(apiKey).visionLocate(base64, target)
        } catch (_: Exception) {
            null
        }
        if (point == null) {
            steps += AttemptStep("vision", "vision could not find \"$target\" in the screenshot", false)
            return false
        }
        val metrics = context.resources.displayMetrics
        val x = point.first / 1000f * metrics.widthPixels
        val y = point.second / 1000f * metrics.heightPixels
        val beforePkg = svc.currentPackage()
        val beforeDump = svc.dumpScreenText()
        val tapped = try {
            svc.tapAt(x, y)
        } catch (_: Exception) {
            false
        }
        if (!tapped) {
            steps += AttemptStep(
                "vision",
                "vision found \"$target\" at ${point.first.toInt()},${point.second.toInt()} but the tap did not dispatch",
                false
            )
            return false
        }
        steps += AttemptStep(
            "vision",
            "tapped \"$target\" via vision at ${point.first.toInt()},${point.second.toInt()}",
            true
        )
        return verifyAfterTap(svc, target, beforePkg, beforeDump, steps)
    }

    // ---- One type attempt (focus field + set text + verify). ----

    private suspend fun typeAttempt(
        svc: LiveAccessibilityService,
        text: String,
        fieldHint: String,
        steps: MutableList<AttemptStep>,
        tapFirst: Boolean = false
    ): Boolean {
        val field = try {
            svc.findEditableNodeByHint(fieldHint)
        } catch (_: Exception) {
            null
        }
        if (field == null) {
            // No editable node yet — maybe a popup is covering it, or the field
            // only appears after tapping its label (Chrome omnibox, Keep editor).
            try {
                svc.dismissPopup()
            } catch (_: Exception) {
            }
            if (fieldHint.isNotBlank()) {
                try {
                    svc.tapText(fieldHint)
                } catch (_: Exception) {
                }
                delay(500)
                val retry = try {
                    svc.findEditableNodeByHint(fieldHint)
                } catch (_: Exception) {
                    null
                }
                if (retry != null) return typeIntoNode(svc, retry, text, steps, tapFirst)
            }
            steps += AttemptStep(
                "type",
                "no editable field on screen" +
                    if (fieldHint.isNotBlank()) " for hint \"$fieldHint\"" else "",
                false
            )
            return false
        }
        return typeIntoNode(svc, field, text, steps, tapFirst)
    }

    private suspend fun typeIntoNode(
        svc: LiveAccessibilityService,
        field: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
        steps: MutableList<AttemptStep>,
        tapFirst: Boolean
    ): Boolean {
        if (tapFirst) {
            try {
                svc.tapNode(field)
            } catch (_: Exception) {
            }
            delay(400)
        }
        val typed = try {
            svc.setTextOnNode(field, text) || svc.typeText(text)
        } catch (_: Exception) {
            false
        }
        if (!typed) {
            steps += AttemptStep("type", "could not set text in the field", false)
            return false
        }
        steps += AttemptStep("type", "typed ${text.length} chars", true)
        delay(500)
        // Verify: the field now contains the text (re-dump the nodes).
        val probe = if (text.length > 40) text.take(40) else text
        val verified = try {
            svc.dumpScreenText().contains(text) ||
                svc.dumpScreenText().contains(probe) ||
                svc.isTextVisible(probe)
        } catch (_: Exception) {
            false
        }
        steps += AttemptStep(
            "verify",
            if (verified) "field now contains the typed text" else "typed text not visible in the field",
            verified
        )
        return verified
    }

    /**
     * Verify a tap landed: the foreground package changed, the active window
     * changed, or the node dump changed. A tap that leaves the screen
     * byte-identical is reported as unverified, never as success.
     */
    private suspend fun verifyAfterTap(
        svc: LiveAccessibilityService,
        target: String,
        beforePkg: String,
        beforeDump: String,
        steps: MutableList<AttemptStep>
    ): Boolean {
        delay(600)
        val afterPkg = try {
            svc.currentPackage()
        } catch (_: Exception) {
            ""
        }
        if (afterPkg.isNotEmpty() && beforePkg.isNotEmpty() && afterPkg != beforePkg) {
            steps += AttemptStep("verify", "package changed $beforePkg -> $afterPkg", true)
            return true
        }
        // A new window/activity inside the same app also proves the tap landed.
        val windowChanged = try {
            withContext(Dispatchers.IO) { svc.waitForWindowChange(1000) }
        } catch (_: Exception) {
            false
        }
        val afterDump = try {
            svc.dumpScreenText()
        } catch (_: Exception) {
            ""
        }
        val dumpChanged = afterDump != beforeDump &&
            (afterDump.isNotBlank() || beforeDump.isNotBlank())
        if (windowChanged || dumpChanged) {
            steps += AttemptStep(
                "verify",
                if (windowChanged) "window changed after tapping \"$target\""
                else "screen content changed after tapping \"$target\"",
                true
            )
            return true
        }
        steps += AttemptStep(
            "verify",
            "screen did not change after tapping \"$target\"" +
                if (beforeDump.isBlank()) " (screen dump is empty — WebView/canvas, cannot verify)" else "",
            false
        )
        return false
    }

    companion object {
        private const val TAG = "ScreenControl"
    }
}
