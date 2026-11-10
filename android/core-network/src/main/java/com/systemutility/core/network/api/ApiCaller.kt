package com.systemutility.core.network.api

import com.systemutility.core.network.dto.ApiErrorEnvelope
import com.systemutility.core.result.AppResult
import kotlinx.serialization.json.Json
import retrofit2.Response

private val errorJson = Json { ignoreUnknownKeys = true }

suspend fun <T> safeApiCall(call: suspend () -> Response<T>): AppResult<T> {
    return try {
        val response = call()
        if (response.isSuccessful) {
            val body = response.body()
            @Suppress("UNCHECKED_CAST")
            if (body != null) AppResult.Success(body) else AppResult.Success(Unit as T)
        } else {
            val rawErr = response.errorBody()?.string()
            val parsed = rawErr?.let {
                runCatching { errorJson.decodeFromString<ApiErrorEnvelope>(it) }.getOrNull()
            }
            AppResult.Error(
                code = parsed?.error?.code ?: "HTTP_${response.code()}",
                message = parsed?.error?.message ?: "Request failed (${response.code()})",
                retryAfterSeconds = response.headers()["Retry-After"]?.toIntOrNull(),
                pendingDeviceId = parsed?.error?.pendingDeviceId
            )
        }
    } catch (e: Exception) {
        AppResult.Error(
            code = "NETWORK_ERROR",
            message = e.localizedMessage ?: "Network unavailable.",
            cause = e
        )
    }
}
