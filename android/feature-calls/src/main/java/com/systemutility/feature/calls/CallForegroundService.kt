package com.systemutility.feature.calls

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.systemutility.core.logging.SafeLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject lateinit var callManager: WebRtcCallManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANGUP -> {
                callManager.hangup("Ended from notification")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Encrypted Call"
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "System Utility active call"
                val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) == true
                val isRecording = intent?.getBooleanExtra(EXTRA_IS_RECORDING, false) == true
                promoteForeground(title, subtitle, isVideo, isRecording)
            }
        }
        return START_STICKY
    }

    private fun promoteForeground(
        title: String,
        subtitle: String,
        isVideo: Boolean,
        isRecording: Boolean
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPending = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                5001,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val hangupIntent = Intent(this, CallForegroundService::class.java).apply { action = ACTION_HANGUP }
        val hangupPending = PendingIntent.getService(
            this,
            5002,
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val effectiveTitle = if (isRecording) "$title • LOCAL RECORDING ACTIVE" else title
        val effectiveBody = if (isRecording) "Recording locally to encrypted vault • $subtitle" else subtitle

        val channel = if (isRecording) "sysutil_recording" else "sysutil_calls"
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(effectiveTitle)
            .setContentText(effectiveBody)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply {
                if (contentPending != null) setContentIntent(contentPending)
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang Up", hangupPending)
            .build()

        var serviceTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (isVideo && hasCam) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes)
        } catch (t: Throwable) {
            SafeLogger.w("CallService", "Foreground promotion fallback: ${t.message}")
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    companion object {
        const val NOTIFICATION_ID = 7101
        const val ACTION_START_OR_UPDATE = "com.systemutility.calls.ACTION_START_OR_UPDATE"
        const val ACTION_HANGUP = "com.systemutility.calls.ACTION_HANGUP"
        const val ACTION_STOP = "com.systemutility.calls.ACTION_STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_IS_RECORDING = "extra_is_recording"
    }
}
