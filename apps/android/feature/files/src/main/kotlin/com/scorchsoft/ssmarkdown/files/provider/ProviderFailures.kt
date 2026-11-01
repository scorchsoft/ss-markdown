package com.scorchsoft.ssmarkdown.files.provider

import java.io.FileNotFoundException
import java.io.IOException

/**
 * Turns whatever a provider threw into a [ProviderResult.Failure].
 *
 * Providers run in other processes and throw across Binder, so the type is
 * only a hint: a revoked grant is a `SecurityException`, a missing file a
 * `FileNotFoundException`, a full disk an `IOException` whose message (or
 * an `ErrnoException` cause) names `ENOSPC`.
 */
internal object ProviderFailures {
    private val FULL_MARKERS = listOf("ENOSPC", "EDQUOT", "No space left", "Disk quota exceeded")
    private val MODE_MARKERS = listOf("mode", "Mode")

    /** Classifies [error] from an open, read or write. [openingForWrite] lets mode rejections be told apart. */
    fun classify(
        error: Throwable,
        openingForWrite: Boolean = false,
    ): ProviderResult.Failure =
        when {
            error is SecurityException -> ProviderResult.PermissionDenied
            isStorageFull(error) -> ProviderResult.StorageFull
            openingForWrite && isModeRejection(error) -> ProviderResult.ModeUnsupported
            error is FileNotFoundException -> ProviderResult.NotFound
            error is Exception -> ProviderResult.Failed(error)
            else -> ProviderResult.Failed(IOException(error))
        }

    private fun isStorageFull(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { cause -> FULL_MARKERS.any { cause.message?.contains(it) == true } }

    private fun isModeRejection(error: Throwable): Boolean =
        error is IllegalArgumentException ||
            error is UnsupportedOperationException ||
            (error is FileNotFoundException && MODE_MARKERS.any { error.message?.contains(it) == true })

    private const val MAX_CAUSE_DEPTH = 8
}

/**
 * Runs [block], turning anything it throws into a failure. The one place
 * a broad catch is right: a provider may throw any runtime exception
 * across process boundaries, and none of them may crash the editor.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> providerCall(
    openingForWrite: Boolean = false,
    block: () -> T,
): ProviderResult<T> =
    try {
        ProviderResult.Ok(block())
    } catch (e: Exception) {
        ProviderFailures.classify(e, openingForWrite)
    }
