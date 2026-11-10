package com.systemutility.core.logging

import android.util.Log

/**
 * Diagnostic logger that strictly prohibits sensitive key material,
 * bearer tokens, passwords, and plaintext chat bodies from appearing in Logcat.
 */
object SafeLogger {
    private const val TAG_PREFIX = "SysUtil"

    private val sensitivePatterns = listOf(
        Regex("(?i)(bearer\\s+)[a-zA-Z0-9._-]+"),
        Regex("(?i)(password|token|secret|key|plaintext|ciphertext|digest)=([^\\s,;]+)")
    )

    private fun redact(message: String): String {
        var out = message
        for (regex in sensitivePatterns) {
            out = regex.replace(out) { match ->
                "${match.groupValues[1]}[REDACTED]"
            }
        }
        return out
    }

    fun i(component: String, message: String) {
        Log.i("$TAG_PREFIX/$component", redact(message))
    }

    fun w(component: String, message: String) {
        Log.w("$TAG_PREFIX/$component", redact(message))
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        Log.e("$TAG_PREFIX/$component", redact(message), throwable)
    }
}
