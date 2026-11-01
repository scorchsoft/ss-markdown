package com.scorchsoft.ssmarkdown.files.document

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri

/**
 * A `ContentObserver` on a document, for the providers that notify: a
 * change ping becomes an [OutsideChangeMonitor.onContentChanged] check,
 * or whatever [onChange] does. Providers that never notify are still
 * covered by the poll.
 *
 * @param onChange called on [handler]'s thread with the document that changed.
 */
class DocumentObserver(
    private val resolver: ContentResolver,
    private val handler: Handler,
    private val onChange: (DocumentUri) -> Unit,
) {
    /** Hands every change to [monitor], which checks the file against its base. */
    constructor(
        resolver: ContentResolver,
        monitor: OutsideChangeMonitor,
        handler: Handler,
    ) : this(resolver, handler, monitor::onContentChanged)

    private val observers = HashMap<DocumentUri, ContentObserver>()

    /** Starts observing [uri]. Some providers refuse; the poll covers them. */
    fun observe(uri: DocumentUri) {
        if (uri.isDraft || uri in observers) return
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = this@DocumentObserver.onChange(uri)
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

    /** Stops observing everything: the window or folder closed. */
    fun stopAll() {
        observers.values.forEach(resolver::unregisterContentObserver)
        observers.clear()
    }

    /** The documents observed now. */
    val observed: Set<DocumentUri> get() = observers.keys.toSet()
}
