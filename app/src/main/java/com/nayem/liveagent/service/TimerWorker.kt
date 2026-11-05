package com.nayem.liveagent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nayem.liveagent.agent.PhoneTools
import kotlinx.coroutines.delay

class TimerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val CHANNEL_ID = "live_agent_timer"
        private const val NOTIFICATION_ID = 2001
        private const val KEY_MINUTES = "minutes"
        private const val KEY_LOCK_AFTER = "lockAfter"

        fun enqueue(context: Context, minutes: Int, lockAfter: Boolean) {
            val request = OneTimeWorkRequestBuilder<TimerWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MINUTES to minutes,
                        KEY_LOCK_AFTER to lockAfter
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        val minutes = inputData.getInt(KEY_MINUTES, 30)
        val lockAfter = inputData.getBoolean(KEY_LOCK_AFTER, true)
        delay(minutes * 60_000L)
        PhoneTools(applicationContext).pauseMedia()
        if (lockAfter) {
            LiveAccessibilityService.instance?.lockScreen()
        }
        postFinishedNotification()
        return Result.success()
    }

    private fun postFinishedNotification() {
        val manager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Agent timer",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
        val notification = android.app.Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Timer finished")
            .setContentText("Timer finished — media stopped")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted; nothing more to do.
        }
    }
}
