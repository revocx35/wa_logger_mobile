package io.github.revocx35.walmobile.core

/**
 * A failed operation with a message that is safe to show (like the server's API errors, which the UI
 * was written against). [code] is stable: "locked", "invalid_credentials", "rate_limited", "validation",
 * "conflict", "not_found", "unavailable", "internal".
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryAfter: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Text for the UI, including the wait time for rate limits. */
    val userMessage: String get() = if (retryAfter != null && retryAfter > 0) "$message (try again in ${formatWait(retryAfter)})" else message

    companion object {
        fun locked() = ApiException(401, "locked", "The app is locked.")
        fun notFound(what: String) = ApiException(404, "not_found", "$what not found.")
        fun conflict(message: String) = ApiException(409, "conflict", message)
        fun validation(message: String) = ApiException(400, "validation", message)
        fun unavailable(message: String) = ApiException(503, "unavailable", message)

        private fun formatWait(s: Int): String = when {
            s < 90 -> "${s}s"
            s < 5400 -> "${(s + 30) / 60} min"
            else -> "${(s + 1800) / 3600} h"
        }
    }
}

/** UI text for any error. */
fun Throwable.userMessage(): String = (this as? ApiException)?.userMessage ?: "Something went wrong."
