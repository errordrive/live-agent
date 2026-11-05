package com.nayem.liveagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nayem.liveagent.agent.PhoneTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AgentForegroundService : Service() {

    companion object {
        private const val TAG = "AgentFgService"
        private const val CHANNEL_ID = "live_agent_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "start"
        const val ACTION_START_TIMER = "start_timer"
        const val ACTION_CANCEL_TIMER = "cancel_timer"

        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_LOCK_AFTER = "lock_after"
        private const val EXTRA_LABEL = "label"

        fun startTimer(context: Context, minutes: Int, lockAfter: Boolean) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START_TIMER
                putExtra(EXTRA_MINUTES, minutes)
                putExtra(EXTRA_LOCK_AFTER, lockAfter)
            }
            context.startForegroundService(intent)
        }

        fun cancelTimer(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_CANCEL_TIMER
            }
            context.startForegroundService(intent)
        }

        fun startService(context: Context, label: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            context.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 30)
                val lockAfter = intent.getBooleanExtra(EXTRA_LOCK_AFTER, true)
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Live Agent — timer running (${minutes} min)")
                )
                timerJob?.cancel()
                timerJob = serviceScope.launch {
                    Log.i(TAG, "Timer started: $minutes min, lockAfter=$lockAfter")
                    delay(minutes * 60_000L)
                    PhoneTools(applicationContext).pauseMedia()
                    if (lockAfter) {
                        LiveAccessibilityService.instance?.lockScreen()
                    }
                    Log.i(TAG, "Timer finished: media paused, lockAfter=$lockAfter")
                    stopSelf()
                }
            }
            ACTION_CANCEL_TIMER -> {
                Log.i(TAG, "Timer cancelled")
                timerJob?.cancel()
                timerJob = null
                stopSelf()
            }
            else -> {
                val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Live Agent"
                startForeground(NOTIFICATION_ID, buildNotification(label))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Agent",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Live Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
