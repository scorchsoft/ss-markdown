package com.scorchsoft.ssmarkdown.files.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri

/**
 * Keeps access to files and folders the user picked across restarts
 * (ux-spec "Folder mode": access persists). A grant that cannot be
 * persisted (a temporary VIEW grant) is reported, so the UI can explain
 * that this file will need picking again.
 */
class PersistedPermissions(
    private val resolver: ContentResolver,
) {
    /** Persists read and, when [write], write access to [uri]. False if the provider refused. */
    fun take(
        uri: DocumentUri,
        write: Boolean = true,
    ): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        return try {
            resolver.takePersistableUriPermission(Uri.parse(uri.value), flags)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Gives back a persisted grant: the user removed the folder or file. */
    fun release(uri: DocumentUri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri.value), flags)
        } catch (_: SecurityException) {
            // Nothing was held.
        }
    }

    /** Every URI we hold, with whether we may write it. */
    fun held(): Map<DocumentUri, Boolean> =
        resolver.persistedUriPermissions.associate { DocumentUri(it.uri.toString()) to it.isWritePermission }
}
