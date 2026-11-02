package com.scorchsoft.ssmarkdown.fonts.download

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Caps on what one download may write, so a misbehaving response can never
 * fill the phone. Real Google Fonts static TTFs are 20–400 KB each.
 *
 * @property maxFileBytes the largest single font file accepted.
 * @property maxFamilyBytes the largest family (all styles together).
 * @property maxCssBytes the largest CSS2 response read.
 */
data class DownloadLimits(
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val maxFamilyBytes: Long = DEFAULT_MAX_FAMILY_BYTES,
    val maxCssBytes: Int = DEFAULT_MAX_CSS_BYTES,
)

private const val DEFAULT_MAX_FILE_BYTES = 8L * 1024 * 1024
private const val DEFAULT_MAX_FAMILY_BYTES = 24L * 1024 * 1024
private const val DEFAULT_MAX_CSS_BYTES = 64 * 1024

/**
 * True when [error] means "no connection at all" rather than "the
 * connection broke": the difference between "you're offline" and "the
 * connection dropped".
 */
internal fun isOffline(error: IOException): Boolean =
    error is UnknownHostException ||
        error is ConnectException ||
        error is NoRouteToHostException ||
        error is SocketTimeoutException
