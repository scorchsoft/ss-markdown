package com.scorchsoft.ssmarkdown.files.provider

/**
 * The outcome of one call into a document provider. Every failure a phone
 * can produce is a value here, so callers handle each one rather than
 * hoping a catch block somewhere does (docs/engineering.md: errors are
 * values).
 */
sealed interface ProviderResult<out T> {
    /**
     * The call worked.
     *
     * @property value what it produced.
     */
    data class Ok<out T>(
        val value: T,
    ) : ProviderResult<T>

    /** Every way a provider call can fail. */
    sealed interface Failure : ProviderResult<Nothing>

    /** The document is gone: deleted, moved, or its provider uninstalled. */
    data object NotFound : Failure

    /** The grant is gone or never covered this: revoked folder, temporary grant expired, SD card ejected. */
    data object PermissionDenied : Failure

    /** The device (or the provider's quota) is full: `ENOSPC`. */
    data object StorageFull : Failure

    /** The call took longer than its deadline allowed (a slow cloud provider); see `SaveTimeout`. */
    data object TimedOut : Failure

    /** The provider refused the requested open mode, as some do with `"wt"`. */
    data object ModeUnsupported : Failure

    /**
     * Anything else the provider threw.
     *
     * @property cause what it threw; for logs, never shown raw.
     */
    data class Failed(
        val cause: Exception,
    ) : Failure
}

/** The value, or null for any failure. */
fun <T> ProviderResult<T>.valueOrNull(): T? = (this as? ProviderResult.Ok)?.value
