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
        val nodeText = node.text?.toString()?.lowercase()
        val description = node.contentDescription?.toString()?.lowercase()
        if ((nodeText != null && nodeText.contains(query)) ||
            (description != null && description.contains(query))
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, query)
            if (found != null) return found
        }
        return null
    }

    fun tapText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findScrollableNode(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableNode(root)
            ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

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
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Swipe from (x1,y1) to (x2,y2) over ~300 ms. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Tap the node whose view resource id matches (e.g. "com.app:id/button"). */
    fun tapById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = current.parent
            }
        }
        return false
    }

    /** Long-press the first node matching the given text. */
    fun longPressText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isLongClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            current = current.parent
        }
        // Fallback: gesture long-press at the node's center.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return if (!bounds.isEmpty) longPressAt(bounds.centerX().toFloat(), bounds.centerY().toFloat()) else false
    }

    /** Select all text in the currently focused editable field. */
    fun selectAllText(): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableNode(root) ?: return false
        val length = target.text?.length ?: 0
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    fun dumpScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        dumpNodeRecursive(root, builder)
        val text = builder.toString()
        return if (text.length > 4000) text.substring(0, 4000) else text
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, out: StringBuilder) {
        if (out.length >= 4000) return
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        val className = node.className?.toString()
        if (!text.isNullOrBlank() || !description.isNullOrBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            out.append(className ?: "?")
                .append(" [").append(bounds.toShortString()).append("]")
            if (!text.isNullOrBlank()) out.append(" text=\"").append(text).append("\"")
            if (!description.isNullOrBlank()) {
                out.append(" desc=\"").append(description).append("\"")
            }
            out.append('\n')
        }
        for (i in 0 until node.childCount) {
            if (out.length >= 4000) return
            val child = node.getChild(i) ?: continue
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
