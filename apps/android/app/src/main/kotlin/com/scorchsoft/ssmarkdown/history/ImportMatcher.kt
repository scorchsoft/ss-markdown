package com.scorchsoft.ssmarkdown.history

import com.scorchsoft.ssmarkdown.history.versions.ExportedDocument
import java.security.MessageDigest
import java.util.Locale

/** Where an imported history should go. */
sealed interface ImportMatch {
    /** One document in the export is clearly this file's: by content, else by name. */
    data class Matched(
        val document: ExportedDocument,
    ) : ImportMatch

    /** Nothing matches for certain: the user picks which of [choices] belongs to this file. */
    data class Ask(
        val choices: List<ExportedDocument>,
    ) : ImportMatch

    /** The export holds no documents. */
    data object Empty : ImportMatch
}

/**
 * Picks the document in a history export that belongs to the file on
 * screen (ux-spec "Import history": attach "to the matching file by name
 * and content fingerprint, or to a file you pick"). Content wins over the
 * name, since a renamed file keeps its text; a name alone counts only
 * when exactly one document has it. Anything else asks, rather than
 * quietly attaching another file's history to this one.
 */
object ImportMatcher {
    /** The match for a file called [fileName] whose text hashes to [sha256Hex]. */
    fun match(
        documents: List<ExportedDocument>,
        fileName: String,
        sha256Hex: String?,
    ): ImportMatch {
        if (documents.isEmpty()) return ImportMatch.Empty
        val byContent = sha256Hex?.let { sha -> documents.filter { it.latestSha256Hex.equals(sha, ignoreCase = true) } }
        val byName = documents.filter { it.displayName.equals(fileName, ignoreCase = true) }
        return when {
            byContent?.size == 1 -> ImportMatch.Matched(byContent.single())
            byContent.isNullOrEmpty() && byName.size == 1 -> ImportMatch.Matched(byName.single())
            else -> ImportMatch.Ask(documents)
        }
    }

    /** SHA-256 of [text]'s UTF-8 bytes, lower-case hex: how history fingerprints a version. */
    fun sha256Hex(text: CharSequence): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
