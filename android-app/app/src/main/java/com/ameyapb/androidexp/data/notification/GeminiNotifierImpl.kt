package com.ameyapb.androidexp.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val GEMINI_NOTIFICATION_TITLE = "Gemini"
private const val NOTIFICATION_CHANNEL_ID = "gemini_replies"
private const val NOTIFICATION_CHANNEL_NAME = "Gemini replies"
private const val NOTIFICATION_ID = 1

class GeminiNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GeminiNotifier {

    init {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun notify(replyText: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(GEMINI_NOTIFICATION_TITLE)
            .setContentText(replyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(replyText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (permissionError: SecurityException) {
            return
        }
    }
}
