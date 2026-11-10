package com.systemutility.core.notifications

/**
 * Replaceable background delivery abstraction.
 *
 * In this Firebase-free release, real-time events arrive via authenticated WebSockets
 * while connected, with WorkManager deferred sync/retry recovery. Future releases can
 * substitute an alternative Firebase-free push transport without modifying feature callers.
 */
enum class BackgroundDeliveryMode {
    WEBSOCKET_AND_WORKMANAGER
}

data class BackgroundDeliveryStatus(
    val mode: BackgroundDeliveryMode,
    val supportsTerminatedAppWake: Boolean,
    val summary: String
)

interface BackgroundDeliveryProvider {
    val status: BackgroundDeliveryStatus
    fun startDelivery()
    fun requestImmediateSync()
    fun stopDelivery()
}
