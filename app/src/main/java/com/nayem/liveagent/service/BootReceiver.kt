package com.nayem.liveagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // Intentionally a no-op: timers are re-created from the Routines
            // screen, nothing is started automatically at boot.
            Log.i(TAG, "BOOT_COMPLETED received; no automatic restart")
        }
    }
}
