package com.systemutility.feature.chat

import android.content.Context
import com.systemutility.core.notifications.BackgroundDeliveryMode
import com.systemutility.core.notifications.BackgroundDeliveryProvider
import com.systemutility.core.notifications.BackgroundDeliveryStatus
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase-free background delivery & sync implementation using WorkManager paired with
 * live authenticated WebSockets while connected. Replaceable for future transport modules.
 */
@Singleton
class WorkManagerBackgroundDeliveryProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : BackgroundDeliveryProvider {

    override val status: BackgroundDeliveryStatus = BackgroundDeliveryStatus(
        mode = BackgroundDeliveryMode.WEBSOCKET_AND_WORKMANAGER,
        supportsTerminatedAppWake = false,
        summary = "Live delivery over authenticated WebSockets while connected + WorkManager sync recovery. Instant push after app termination is disabled in this release."
    )

    override fun startDelivery() {
        MessageSyncWorker.schedulePeriodic(context)
    }

    override fun requestImmediateSync() {
        MessageSyncWorker.enqueueImmediate(context)
    }

    override fun stopDelivery() {
        MessageSyncWorker.cancelPeriodic(context)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundDeliveryModule {
    @Binds
    @Singleton
    abstract fun bindBackgroundDeliveryProvider(
        impl: WorkManagerBackgroundDeliveryProvider
    ): BackgroundDeliveryProvider
}
