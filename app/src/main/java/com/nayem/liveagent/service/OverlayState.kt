package com.nayem.liveagent.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Small helper for the "Display over other apps" (SYSTEM_ALERT_WINDOW)
 * overlay feature. No Android UI here — the Settings/More card UI is added
 * by the UI worker using these helpers.
 *
 * The overlay is optional: if the permission is not granted the app works
 * exactly as before and [FloatingBubbleService] simply never shows a bubble.
 */
object OverlayState {

    /**
     * User's choice for the floating bubble. Default true.
     * In-memory only; the UI worker persists it in settings if needed.
     */
    var overlayEnabled: Boolean = true

    /** True when the system "Display over other apps" permission is granted. */
    fun isOverlayAllowed(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** Opens the system screen where the user grants the overlay permission. */
    fun openOverlaySettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("OverlayState", "openOverlaySettings failed", e)
        }
    }
}
