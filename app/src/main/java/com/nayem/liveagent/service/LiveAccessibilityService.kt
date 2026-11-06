package com.nayem.liveagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LiveA11yService"

        @Volatile
        var instance: LiveAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val componentName = ComponentName(context, LiveAccessibilityService::class.java)
                .flattenToString()
            return enabled.contains(componentName)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: this service is used for control actions, not event listening.
        Log.d(TAG, "Accessibility event: ${event?.eventType}")
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByTextRecursive(root, text.lowercase())
    }

    private fun findNodeByTextRecursive(
        node: AccessibilityNodeInfo,
        query: String
    ): AccessibilityNodeInfo? {
        val nodeText = try {
            node.text?.toString()?.lowercase()
        } catch (_: Exception) {
            null
        }
        val description = try {
            node.contentDescription?.toString()?.lowercase()
        } catch (_: Exception) {
            null
        }
        if ((nodeText != null && nodeText.contains(query)) ||
            (description != null && description.contains(query))
        ) {
            return node
        }
        val childCount = try {
            node.childCount
        } catch (_: Exception) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val found = findNodeByTextRecursive(child, query)
            if (found != null) return found
        }
        return null
    }

    fun tapText(text: String): Boolean {
        // Fuzzy best-match tap (v1.2.0): exact text first via the scorer, with a
        // gesture fallback at the node's centre when no clickable ancestor exists.
        return try {
            val node = findBestNode(text) ?: return false
            tapNode(node)
        } catch (_: Exception) {
            false
        }
    }

    // ---- Fuzzy node search (v1.2.0, layered screen control) ----
    //
    // Matches Bangla + English the same way: lowercase + trim + collapse
    // whitespace, then score every node on text / contentDescription /
    // viewId / class name. A whole-query hit beats a word hit; visible +
    // clickable nodes win ties, so a half-remembered label ("search",
    // "নোট লিখুন") still lands on the right button instead of failing.

    fun normalizeQuery(text: String): String =
        text.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun boundsOnScreen(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        return bounds.left < metrics.widthPixels && bounds.top < metrics.heightPixels &&
            bounds.right > 0 && bounds.bottom > 0
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): Rect {
        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
        } catch (_: Exception) {
            // Leave bounds empty; the node simply scores lower.
        }
        return bounds
    }

    /** Score a node against a normalised query (0 = no match). */
    private fun scoreNode(node: AccessibilityNodeInfo, query: String, words: List<String>): Int {
        val text = normalizeQuery(node.text?.toString() ?: "")
        val desc = normalizeQuery(node.contentDescription?.toString() ?: "")
        val viewId = (node.viewIdResourceName ?: "").lowercase()
        val className = (node.className?.toString() ?: "").lowercase()
        var score = 0
        if (query.isNotEmpty()) {
            when {
                text == query -> score += 120
                text.isNotEmpty() && text.contains(query) -> score += 100
                else -> score += 25 * words.count { it.length > 1 && text.contains(it) }
            }
            when {
                desc == query -> score += 90
                desc.isNotEmpty() && desc.contains(query) -> score += 80
                else -> score += 20 * words.count { it.length > 1 && desc.contains(it) }
            }
            if (viewId.isNotEmpty() && viewId.contains(query)) score += 70
            if (className.contains(query)) score += 40
        }
        if (score <= 0) return 0
        // Prefer nodes the user can actually see and tap.
        if (node.isClickable) score += 30
        if (node.isVisibleToUser) score += 20
        if (boundsOnScreen(nodeBounds(node))) score += 10
        if (node.isEditable) score += 10
        if (node.childCount == 0) score += 5
        return score
    }

    /** Best-scoring node for a (possibly partial, Bangla or English) query, or null. */
    fun findBestNode(query: String): AccessibilityNodeInfo? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val q = normalizeQuery(query)
        if (q.isEmpty()) return null
        val words = q.split(" ").filter { it.isNotEmpty() }
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val s = try {
                scoreNode(node, q, words)
            } catch (_: Exception) {
                0
            }
            if (s > bestScore) {
                bestScore = s
                best = node
            }
            val childCount = try {
                node.childCount
            } catch (_: Exception) {
                0
            }
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                stack.add(child)
            }
        }
        return best
    }

    /** True when any visible node matches the query (fuzzy, same scoring). */
    fun isTextVisible(text: String): Boolean = try {
        findBestNode(text) != null
    } catch (_: Exception) {
        false
    }

    /** Tap a node: nearest clickable ancestor, else a gesture at its centre. */
    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = try {
                    current.parent
                } catch (_: Exception) {
                    null
                }
            }
            val bounds = nodeBounds(node)
            if (!bounds.isEmpty) tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            else false
        } catch (_: Exception) {
            false
        }
    }

    /** Package of the foreground window, or "" when there is no active window. */
    fun currentPackage(): String = try {
        rootInActiveWindow?.packageName?.toString() ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * Poll until the foreground window belongs to [packageName] (or the timeout
     * elapses). Timeout is capped at ~5s; polls every 200 ms. Never throws.
     */
    fun waitForPackage(packageName: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, 5000L)
        while (System.currentTimeMillis() <= deadline) {
            if (packageName.isNotEmpty() && currentPackage() == packageName) return true
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return packageName.isNotEmpty() && currentPackage() == packageName
    }

    /**
     * Poll until the foreground window changes (different package or window id).
     * Capped at ~5s, 200 ms polls. Never throws.
     */
    fun waitForWindowChange(timeoutMs: Long = 5000): Boolean {
        val startPkg = currentPackage()
        val startWindow = try {
            rootInActiveWindow?.windowId ?: -1
        } catch (_: Exception) {
            -1
        }
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, 5000L)
        while (System.currentTimeMillis() <= deadline) {
            val pkg = currentPackage()
            val win = try {
                rootInActiveWindow?.windowId ?: -1
            } catch (_: Exception) {
                -1
            }
            if (pkg != startPkg || win != startWindow) return true
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** Poll until a node matching [text] appears (fuzzy), or the timeout elapses. */
    fun waitForNodeText(text: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, 5000L)
        while (System.currentTimeMillis() <= deadline) {
            if (isTextVisible(text)) return true
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return isTextVisible(text)
    }

    /**
     * Dismiss a popup/dialog by tapping a short, button-like label.
     * Only taps when the matched node's own text/description IS (nearly) the
     * label — a long sentence merely containing "Cancel" is never tapped.
     * Returns true when one button was tapped.
     */
    fun dismissPopup(): Boolean {
        val labels = listOf(
            "OK", "Got it", "Allow", "Cancel", "Dismiss",
            "Close", "Not now", "Skip", "No thanks", "Maybe later"
        )
        for (label in labels) {
            try {
                val node = findBestNode(label) ?: continue
                val own = normalizeQuery(node.text?.toString() ?: "")
                    .ifEmpty { normalizeQuery(node.contentDescription?.toString() ?: "") }
                val want = normalizeQuery(label)
                if (own != want && !(own.length <= 20 && own.contains(want))) continue
                if (tapNode(node)) {
                    Log.i(TAG, "Dismissed popup via \"$label\"")
                    return true
                }
            } catch (_: Exception) {
                // Try the next label.
            }
        }
        return false
    }

    /** Short summary of the visible clickable/text nodes (top ~30) for diagnostics. */
    fun visibleNodesSummary(maxNodes: Int = 30): String {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return "(no active window)"
        val lines = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty() && lines.size < maxNodes) {
            val node = stack.removeFirst()
            try {
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                val bounds = nodeBounds(node)
                if ((text.isNotEmpty() || desc.isNotEmpty()) &&
                    node.isVisibleToUser && boundsOnScreen(bounds)
                ) {
                    val cls = (node.className?.toString() ?: "?").substringAfterLast('.')
                    val line = StringBuilder("[$cls]")
                    if (node.isClickable) line.append(" tap")
                    if (node.isEditable) line.append(" edit")
                    if (text.isNotEmpty()) line.append(" \"").append(text.take(60)).append("\"")
                    if (desc.isNotEmpty()) line.append(" desc=\"").append(desc.take(60)).append("\"")
                    lines += line.toString()
                }
            } catch (_: Exception) {
                // Stale node — skip it, keep scanning the rest.
            }
            val childCount = try {
                node.childCount
            } catch (_: Exception) {
                0
            }
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                stack.add(child)
            }
        }
        return if (lines.isEmpty()) "(no visible text nodes)"
        else lines.joinToString("\n")
    }

    /** Best editable node: by hint text/description when [hint] is given, else the first. */
    fun findEditableNodeByHint(hint: String): AccessibilityNodeInfo? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        val q = normalizeQuery(hint)
        var first: AccessibilityNodeInfo? = null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val editable = try {
                node.isEditable
            } catch (_: Exception) {
                false
            }
            if (editable) {
                if (first == null) first = node
                if (q.isNotEmpty()) {
                    val hay = listOf(
                        node.text?.toString() ?: "",
                        node.contentDescription?.toString() ?: "",
                        try {
                            node.hintText?.toString() ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                    ).joinToString(" ").let { normalizeQuery(it) }
                    val s = when {
                        hay == q -> 120
                        hay.contains(q) -> 100
                        else -> 25 * q.split(" ").count { it.length > 1 && hay.contains(it) }
                    }
                    if (s > bestScore) {
                        bestScore = s
                        best = node
                    }
                }
            }
            val childCount = try {
                node.childCount
            } catch (_: Exception) {
                0
            }
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                stack.add(child)
            }
        }
        return best ?: first
    }

    /** Set text on a specific editable node (focus first, then ACTION_SET_TEXT). */
    fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Exception) {
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
    }

    fun tapAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun findScrollableNode(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        if (try {
                node.isScrollable
            } catch (_: Exception) {
                false
            }
        ) return node
        val childCount = try {
            node.childCount
        } catch (_: Exception) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun scrollDown(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val scrollable = findScrollableNode(root) ?: return false
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } catch (_: Exception) {
            false
        }
    }

    fun scrollUp(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val scrollable = findScrollableNode(root) ?: return false
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        } catch (_: Exception) {
            false
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (try {
                node.isEditable
            } catch (_: Exception) {
                false
            }
        ) return node
        val childCount = try {
            node.childCount
        } catch (_: Exception) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun typeText(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditableNode(root)
                ?: return false
            setTextOnNode(target, text)
        } catch (_: Exception) {
            false
        }
    }

    fun pressBack(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_BACK)
    } catch (_: Exception) {
        false
    }

    fun pressHome(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_HOME)
    } catch (_: Exception) {
        false
    }

    fun pressRecents(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    } catch (_: Exception) {
        false
    }

    fun lockScreen(): Boolean = try {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    } catch (_: Exception) {
        false
    }

    fun volumeUp(): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return false
        return try {
            audio.adjustVolume(android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
            true
        } catch (_: Exception) { false }
    }

    fun volumeDown(): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return false
        return try {
            audio.adjustVolume(android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
            true
        } catch (_: Exception) { false }
    }

    /** Long-press at coordinates (500 ms hold). */
    fun longPressAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    /** Swipe from (x1,y1) to (x2,y2) over ~300 ms. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    /** Tap the node whose view resource id matches (e.g. "com.app:id/button"). */
    fun tapById(viewId: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            // A full resource id ("com.app:id/button") that finds nothing by
            // id may still match by fuzzy text/viewId — but a bare word like
            // "button" must NOT fuzzy-match any Button on screen, so the
            // fallback only runs for id-shaped queries.
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                .ifEmpty {
                    if (viewId.contains(":") || viewId.contains("/")) {
                        findBestNode(viewId)?.let { listOf(it) } ?: emptyList()
                    } else emptyList()
                }
            for (node in nodes) {
                if (tapNode(node)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Long-press the first node matching the given text. */
    fun longPressText(text: String): Boolean {
        return try {
            val node = findBestNode(text) ?: return false
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isLongClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }
                current = try {
                    current.parent
                } catch (_: Exception) {
                    null
                }
            }
            // Fallback: gesture long-press at the node's center.
            val bounds = nodeBounds(node)
            if (!bounds.isEmpty) longPressAt(bounds.centerX().toFloat(), bounds.centerY().toFloat()) else false
        } catch (_: Exception) {
            false
        }
    }

    /** Select all text in the currently focused editable field. */
    fun selectAllText(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditableNode(root) ?: return false
            val length = target.text?.length ?: 0
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (_: Exception) {
            false
        }
    }

    fun dumpScreenText(): String {
        return try {
            val root = rootInActiveWindow ?: return ""
            val builder = StringBuilder()
            dumpNodeRecursive(root, builder)
            val text = builder.toString()
            if (text.length > 4000) text.substring(0, 4000) else text
        } catch (_: Exception) {
            ""
        }
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, out: StringBuilder) {
        if (out.length >= 4000) return
        val text = try {
            node.text?.toString()
        } catch (_: Exception) {
            null
        }
        val description = try {
            node.contentDescription?.toString()
        } catch (_: Exception) {
            null
        }
        val className = try {
            node.className?.toString()
        } catch (_: Exception) {
            null
        }
        if (!text.isNullOrBlank() || !description.isNullOrBlank()) {
            val bounds = nodeBounds(node)
            out.append(className ?: "?")
                .append(" [").append(bounds.toShortString()).append("]")
            if (!text.isNullOrBlank()) out.append(" text=\"").append(text).append("\"")
            if (!description.isNullOrBlank()) {
                out.append(" desc=\"").append(description).append("\"")
            }
            out.append('\n')
        }
        val childCount = try {
            node.childCount
        } catch (_: Exception) {
            0
        }
        for (i in 0 until childCount) {
            if (out.length >= 4000) return
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            dumpNodeRecursive(child, out)
        }
    }

    // NewApi is suppressed here deliberately: the function returns null on
    // API < 30 before touching any of these APIs, so the guard is real.
    @SuppressLint("NewApi")
    fun takeScreenshotToFile(context: Context): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var resultFile: File? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            context.mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = buffer?.let {
                            Bitmap.wrapHardwareBuffer(it, screenshot.colorSpace)
                        }
                        if (bitmap != null) {
                            val file = File(context.cacheDir, "screenshot.jpg")
                            FileOutputStream(file).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            resultFile = file
                            try { buffer.close() } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save screenshot", e)
                        resultFile = null
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed, errorCode=$errorCode")
                    latch.countDown()
                }
            }
        )
        latch.await(5, TimeUnit.SECONDS)
        return resultFile
    }
}
