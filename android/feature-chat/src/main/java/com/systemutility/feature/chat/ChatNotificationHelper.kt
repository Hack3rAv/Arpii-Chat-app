package com.systemutility.feature.chat

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.systemutility.core.model.NotificationPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun showMessageNotification(
        prefs: NotificationPreferences,
        senderName: String,
        messagePreview: String
    ) {
        if (prefs.muteConversation || prefs.dndEnabled) return

        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (prefs.stealthNotificationText) "System Utility" else senderName
        val body = when {
            prefs.stealthNotificationText -> "Background sync notification"
            prefs.showNotificationPreviews -> messagePreview
            else -> "New encrypted message"
        }

        val builder = NotificationCompat.Builder(context, "sysutil_messages")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (!prefs.messageSoundEnabled) {
            builder.setSilent(true)
        }
        if (prefs.vibrateEnabled && prefs.messageSoundEnabled) {
            builder.setVibrate(longArrayOf(0L, 120L, 60L, 120L))
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_MESSAGE, builder.build())
    }

    fun cancelMessageNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_MESSAGE)
    }

    companion object {
        private const val NOTIFICATION_ID_MESSAGE = 4001
    }
}
