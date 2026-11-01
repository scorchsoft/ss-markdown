package com.scorchsoft.ssmarkdown.settings

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.DocumentLocation

/**
 * The short "where" under a recent file's name ("Internal storage ›
 * Documents › specs", "Google Drive"), from `feature/files`'
 * [DocumentLocation]: worked out from the URI alone, so it is free for
 * every row and works for a file whose grant has lapsed. Drafts and
 * other non-provider URIs get no label rather than a guess.
 */
object LocationLabel {
    /** The label for [uri]; empty when nothing honest can be said. */
    fun of(uri: String): String =
        if (uri.startsWith("content://")) DocumentLocation.of(DocumentUri(uri)).displayString() else ""
}
