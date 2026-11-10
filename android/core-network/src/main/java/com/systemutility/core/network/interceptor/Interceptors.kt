package com.systemutility.core.network.interceptor

import com.systemutility.core.network.BuildConfig
import com.systemutility.core.security.SecureSessionStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRevocationBus @Inject constructor() {
    private val _revokedEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val revokedEvents: SharedFlow<String> = _revokedEvents.asSharedFlow()

    fun emitRevoked(reason: String) {
        _revokedEvents.tryEmit(reason)
    }
}

@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val sessionStore: SecureSessionStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val configuredUrl = sessionStore.getServerBaseUrl(BuildConfig.DEFAULT_BASE_URL).toHttpUrlOrNull()
        if (configuredUrl != null) {
            val rebuilt = original.url.newBuilder()
                .scheme(configuredUrl.scheme)
                .host(configuredUrl.host)
                .port(configuredUrl.port)
                .build()
            return chain.proceed(original.newBuilder().url(rebuilt).build())
        }
        return chain.proceed(original)
    }
}

@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val sessionStore: SecureSessionStore,
    private val revocationBus: SessionRevocationBus
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath

        val isPublicAuthPath = path.endsWith("/api/v1/auth/salt") ||
            path.endsWith("/api/v1/auth/login") ||
            path.endsWith("/api/v1/auth/recover") ||
            path.endsWith("/api/v1/health")

        val session = sessionStore.getSession()
        val requestBuilder = original.newBuilder()
        if (!isPublicAuthPath && session != null) {
            requestBuilder
                .header("Authorization", "Bearer ${session.token}")
                .header("X-Device-Id", session.deviceId)
        }

        val response = chain.proceed(requestBuilder.build())
        if (!isPublicAuthPath && response.code == 401 && session != null) {
            sessionStore.clearSession()
            revocationBus.emitRevoked("Session expired or revoked remotely.")
        }
        return response
    }
}
