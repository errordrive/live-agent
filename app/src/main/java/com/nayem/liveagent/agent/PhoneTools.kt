package com.nayem.liveagent.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.view.KeyEvent
import com.nayem.liveagent.service.LiveAccessibilityService
import java.io.File
import java.net.URLEncoder

data class ToolResult(val success: Boolean, val detail: String)

class PhoneTools(private val context: Context) {

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return resolveInfos.map { info ->
            val label = info.loadLabel(context.packageManager)?.toString()
                ?: info.activityInfo.packageName
            label to info.activityInfo.packageName
        }.sortedBy { it.first.lowercase() }
    }

    fun openAppByName(name: String): ToolResult {
        if (name.isBlank()) return ToolResult(false, "App name is empty")
        val query = name.trim().lowercase()
        val match = launchableApps().firstOrNull { (label, pkg) ->
            label.lowercase().contains(query) || pkg.lowercase().contains(query)
        } ?: return ToolResult(false, "App not found: $name")
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(match.second)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ToolResult(false, "No launch intent for ${match.first}")
        return try {
            context.startActivity(launchIntent)
            ToolResult(true, "Opened ${match.first} (${match.second})")
        } catch (e: Exception) {
            ToolResult(false, "Failed to open ${match.first}: ${e.message}")
        }
    }

    fun openChrome(url: String): ToolResult {
        val target = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://$url"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isPackageInstalled("com.android.chrome")) {
                setPackage("com.android.chrome")
            }
        }
        return try {
            context.startActivity(intent)
            ToolResult(true, "Opened $target in Chrome")
        } catch (e: Exception) {
            ToolResult(false, "Failed to open $target: ${e.message}")
        }
    }

    fun openYouTubeSearch(query: String): ToolResult {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val uri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isPackageInstalled("com.google.android.youtube")) {
                setPackage("com.google.android.youtube")
            }
        }
        return try {
            context.startActivity(intent)
            ToolResult(true, "YouTube search: $query")
        } catch (e: Exception) {
            ToolResult(false, "Failed to search YouTube for \"$query\": ${e.message}")
        }
    }

    fun openNoteApp(packageName: String?, title: String, text: String): ToolResult {
        if (!packageName.isNullOrBlank()) {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return ToolResult(false, "Note app not found: $packageName")
            return try {
                context.startActivity(launchIntent)
                ToolResult(true, "Opened note app $packageName (title: $title)")
            } catch (e: Exception) {
                ToolResult(false, "Failed to open $packageName: ${e.message}")
            }
        }
        val match = launchableApps().firstOrNull { (label, _) ->
            val lower = label.lowercase()
            lower.contains("note") || lower.contains("keep") || lower.contains("notepad")
        } ?: return ToolResult(false, "No note app found on this device")
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(match.second)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ToolResult(false, "No launch intent for ${match.first}")
        return try {
            context.startActivity(launchIntent)
            ToolResult(true, "Opened ${match.first} (${match.second}) for note \"$title\"")
        } catch (e: Exception) {
            ToolResult(false, "Failed to open ${match.first}: ${e.message}")
        }
    }

    /**
     * Share text into Google Keep (com.google.android.keep) via ACTION_SEND.
     *
     * Keep has no public write API, so this share intent is the honest path:
     * it opens Keep's new-note editor with the title/text already filled in.
     * The user still sees the editor and can edit before the note saves —
     * nothing is written silently behind their back. Never crashes when
     * Keep is missing; that is a plain failed ToolResult.
     */
    fun shareToKeep(title: String, text: String): ToolResult {
        if (!isPackageInstalled("com.google.android.keep")) {
            return ToolResult(false, "Google Keep is not installed")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_SUBJECT, title)
            setPackage("com.google.android.keep")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult(true, "Shared to Google Keep: \"$title\" (${text.length} chars)")
        } catch (e: Exception) {
            ToolResult(false, "Failed to share to Google Keep: ${e.message}")
        }
    }

    /** Open Google Keep directly (package first, launcher label as fallback). */
    fun openKeep(): ToolResult {
        if (isPackageInstalled("com.google.android.keep")) {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage("com.google.android.keep")
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) {
                return try {
                    context.startActivity(launchIntent)
                    ToolResult(true, "Opened Google Keep (com.google.android.keep)")
                } catch (e: Exception) {
                    ToolResult(false, "Failed to open Google Keep: ${e.message}")
                }
            }
        }
        return openAppByName("Keep Notes")
    }

    fun makeCall(number: String): ToolResult {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                false,
                "CALL_PHONE permission not granted — cannot call $number"
            )
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            ToolResult(true, "Calling $number")
        } catch (e: Exception) {
            ToolResult(false, "Failed to call $number: ${e.message}")
        }
    }

    fun sendSms(number: String, text: String): ToolResult {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                false,
                "SEND_SMS permission not granted — cannot send SMS to $number"
            )
        }
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, text, null, null)
            ToolResult(true, "SMS sent to $number")
        } catch (e: Exception) {
            ToolResult(false, "Failed to send SMS to $number: ${e.message}")
        }
    }

    fun openWhatsApp(number: String, text: String): ToolResult {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return ToolResult(false, "Invalid WhatsApp number: $number")
        val encoded = URLEncoder.encode(text, "UTF-8")
        val uri = Uri.parse("https://wa.me/$digits?text=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isPackageInstalled("com.whatsapp")) {
                setPackage("com.whatsapp")
            }
        }
        return try {
            context.startActivity(intent)
            ToolResult(true, "Opened WhatsApp chat with $digits")
        } catch (e: Exception) {
            ToolResult(false, "Failed to open WhatsApp for $digits: ${e.message}")
        }
    }

    fun takeScreenshotFile(): File? {
        return LiveAccessibilityService.instance?.takeScreenshotToFile(context)
    }

    /** Full hands-free screen control (requires the accessibility service). */
    fun screenTapText(text: String): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        return if (svc.tapText(text)) ToolResult(true, "Tapped \"$text\"")
        else ToolResult(false, "Could not find \"$text\" on screen")
    }

    fun screenTapAt(x: Float, y: Float): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        return if (svc.tapAt(x, y)) ToolResult(true, "Tapped at ${x.toInt()},${y.toInt()}")
        else ToolResult(false, "Tap at ${x.toInt()},${y.toInt()} failed")
    }

    fun screenTapById(viewId: String): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        return if (svc.tapById(viewId)) ToolResult(true, "Tapped $viewId")
        else ToolResult(false, "Could not find view $viewId")
    }

    fun screenLongPressText(text: String): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        return if (svc.longPressText(text)) ToolResult(true, "Long-pressed \"$text\"")
        else ToolResult(false, "Could not long-press \"$text\"")
    }

    fun screenSwipe(x1: Float, y1: Float, x2: Float, y2: Float): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        return if (svc.swipe(x1, y1, x2, y2)) ToolResult(true, "Swiped")
        else ToolResult(false, "Swipe failed")
    }

    fun screenScroll(direction: String): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        val ok = if (direction == "up") svc.scrollUp() else svc.scrollDown()
        return if (ok) ToolResult(true, "Scrolled $direction") else ToolResult(false, "Nothing to scroll")
    }

    fun screenPress(action: String): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        val ok = when (action) {
            "back" -> svc.pressBack()
            "home" -> svc.pressHome()
            "recents" -> svc.pressRecents()
            "lock" -> svc.lockScreen()
            else -> false
        }
        return if (ok) ToolResult(true, "Pressed $action") else ToolResult(false, "Press $action failed")
    }

    fun screenVolume(direction: String): ToolResult {
        val svc = LiveAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility service is not enabled — enable it in Settings")
        val ok = if (direction == "up") svc.volumeUp() else svc.volumeDown()
        return if (ok) ToolResult(true, "Volume $direction") else ToolResult(false, "Volume $direction failed")
    }

    fun listApps(): List<Pair<String, String>> = launchableApps()

    fun pauseMedia(): ToolResult {
        return try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dispatched = run {
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
                )
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
                )
                true
            }
            if (!dispatched) {
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP)
                )
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP)
                )
            }
            ToolResult(true, "Media pause key event dispatched")
        } catch (e: Exception) {
            ToolResult(false, "Failed to pause media: ${e.message}")
        }
    }
}
