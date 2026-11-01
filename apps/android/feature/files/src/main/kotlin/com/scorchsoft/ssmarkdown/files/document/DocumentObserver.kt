package com.scorchsoft.ssmarkdown.files.document

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri

/**
 * A `ContentObserver` on a document, for the providers that notify: a
 * change ping becomes an [OutsideChangeMonitor.onContentChanged] check.
 * Providers that never notify are still covered by the poll.
 */
class DocumentObserver(
    private val resolver: ContentResolver,
    private val monitor: OutsideChangeMonitor,
    private val handler: Handler,
) {
    private val observers = HashMap<DocumentUri, ContentObserver>()

    /** Starts observing [uri]. Some providers refuse; the poll covers them. */
    fun observe(uri: DocumentUri) {
        if (uri.isDraft || uri in observers) return
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = monitor.onContentChanged(uri)
            }
        try {
            resolver.registerContentObserver(Uri.parse(uri.value), false, observer)
            observers[uri] = observer
        } catch (_: SecurityException) {
            // Not allowed to observe this provider; polling still checks it.
        }
    }

    /** Stops observing [uri]. */
    fun stop(uri: DocumentUri) {
        observers.remove(uri)?.let(resolver::unregisterContentObserver)
    }
}
