package com.systemutility.core.network.ws

import com.systemutility.core.di.ApplicationScope
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.network.BuildConfig
import com.systemutility.core.network.interceptor.SessionRevocationBus
import com.systemutility.core.security.SecureSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@Singleton
class RealtimeWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessionStore: SecureSessionStore,
    private val revocationBus: SessionRevocationBus,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionState = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()

    private val _incomingEvents = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val incomingEvents: SharedFlow<JsonObject> = _incomingEvents.asSharedFlow()

    private var activeSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var shouldBeConnected = false
    private var attemptCount = 0

    @Synchronized
    fun connect() {
        val session = sessionStore.getSession() ?: return
        shouldBeConnected = true
        if (_connectionState.value == WsConnectionState.CONNECTED || _connectionState.value == WsConnectionState.CONNECTING) {
            return
        }
        val resolvedUrl = sessionStore.getServerBaseUrl(
            session.baseUrl.ifBlank { BuildConfig.DEFAULT_BASE_URL }
        )
        openSocket(resolvedUrl, session.token, session.deviceId)
    }

    @Synchronized
    fun disconnect() {
        shouldBeConnected = false
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        activeSocket?.close(1000, "Client disconnect")
        activeSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED
    }

    fun sendJson(payload: String): Boolean {
        val ws = activeSocket ?: return false
        return ws.send(payload)
    }

    private fun openSocket(baseUrl: String, token: String, deviceId: String) {
        _connectionState.value = WsConnectionState.CONNECTING
        val wsBase = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/')
        val request = Request.Builder()
            .url("$wsBase/api/v1/ws")
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", deviceId)
            .build()

        activeSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attemptCount = 0
                _connectionState.value = WsConnectionState.CONNECTED
                startHeartbeat()
                SafeLogger.i("WebSocket", "Realtime channel connected.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.decodeFromString<JsonObject>(text)
                    val type = obj["type"]?.jsonPrimitive?.content
                    if (type == "session_revoked") {
                        sessionStore.clearSession()
                        disconnect()
                        revocationBus.emitRevoked("Session revoked on another device.")
                        return
                    }
                    if (type != "pong") {
                        _incomingEvents.tryEmit(obj)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect(code == 4001)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                SafeLogger.w("WebSocket", "Connection dropped: ${t.message}")
                val isAuthReject = response?.code == 401
                handleDisconnect(isAuthReject)
            }
        })
    }

    private fun handleDisconnect(authRevoked: Boolean) {
        heartbeatJob?.cancel()
        activeSocket = null
        _connectionState.value = WsConnectionState.DISCONNECTED

        if (authRevoked) {
            shouldBeConnected = false
            sessionStore.clearSession()
            revocationBus.emitRevoked("Authentication rejected by WebSocket coordinator.")
            return
        }

        if (!shouldBeConnected || sessionStore.getSession() == null) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attemptCount++
            val backoffMs = min(30_000L, 1_000L * (1L shl min(attemptCount, 5)))
            delay(backoffMs)
            if (isActive && shouldBeConnected) {
                connect()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value == WsConnectionState.CONNECTED) {
                delay(25_000L)
                activeSocket?.send("""{"type":"ping"}""")
            }
        }
    }
}
