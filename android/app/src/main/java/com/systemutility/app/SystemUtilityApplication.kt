package com.systemutility.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.network.ws.RealtimeWebSocketClient
import com.systemutility.core.notifications.BackgroundDeliveryProvider
import com.systemutility.core.security.SecureSessionStore
import com.systemutility.feature.calls.WebRtcCallManager
import com.systemutility.feature.chat.ChatRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SystemUtilityApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionStore: SecureSessionStore
    @Inject lateinit var webSocketClient: RealtimeWebSocketClient
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var callManager: WebRtcCallManager
    @Inject lateinit var backgroundDeliveryProvider: BackgroundDeliveryProvider

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        if (sessionStore.getSession() != null) {
            chatRepository.startRealtimeObserver()
            if (FeatureFlags.CALLS_ENABLED) {
                callManager.startSignalingObserver()
            }
            webSocketClient.connect()
            backgroundDeliveryProvider.startDelivery()
        }
        SafeLogger.i("App", "System Utility Application initialized (${backgroundDeliveryProvider.status.mode}).")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val messages = NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            val calls = NotificationChannel(
                CHANNEL_CALLS,
                getString(R.string.channel_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(false)
            }
            val recording = NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.channel_recording_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannels(listOf(messages, calls, recording))
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "sysutil_messages"
        const val CHANNEL_CALLS = "sysutil_calls"
        const val CHANNEL_RECORDING = "sysutil_recording"
    }
}
