package com.systemutility.core.result

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(
        val code: String,
        val message: String,
        val retryAfterSeconds: Int? = null,
        val pendingDeviceId: String? = null,
        val cause: Throwable? = null
    ) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
}
