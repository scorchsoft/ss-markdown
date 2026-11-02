package com.scorchsoft.ssmarkdown.media

/**
 * A short-lived cache over a [DocumentTree], so a page of pictures costs a
 * listing per folder, not a provider walk per picture: fifty pictures in
 * `img/` are one `pathTo` for the document and one listing of `img/`
 * (docs/ux-spec.md, "Pictures"; `TreeListingsTest` counts them).
 *
 * Answers are kept for [ttlNanos] (a picture added in another app turns up
 * on the next render after that) and dropped at once by [invalidate], which
 * the app calls when it writes into a tree, when a folder is granted and
 * when the folder panel refreshes. A miss is cached too: a folder that
 * cannot be listed, or a document that is not in a tree, is not asked
 * again within the TTL. Thread-safe: the WebView asks from its own threads.
 */
class TreeListings(
    val tree: DocumentTree,
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttlNanos: Long = DEFAULT_TTL_NANOS,
) {
    private class Entry<T>(
        val at: Long,
        val value: T?,
    )

    private val listings = lru<Entry<Map<String, TreeEntry>>>()
    private val paths = lru<Entry<List<String>>>()

    /** [folder]'s children by display name; null when it cannot be listed. */
    fun children(
        tree: String,
        folder: String,
    ): Map<String, TreeEntry>? =
        cached(listings, "$tree\n$folder") {
            this.tree.list(tree, folder)?.let { entries ->
                // Two children with one name (Drive allows it): the first wins, as a file manager shows it.
                LinkedHashMap<String, TreeEntry>().apply { entries.forEach { putIfAbsent(it.name, it) } }
            }
        }

    /** The child of [folder] called [name], or null. */
    fun child(
        tree: String,
        folder: String,
        name: String,
    ): TreeEntry? = children(tree, folder)?.get(name)

    /** The child of [folder] whose id is [id], or null. */
    fun byId(
        tree: String,
        folder: String,
        id: String,
    ): TreeEntry? = children(tree, folder)?.values?.firstOrNull { it.id == id }

    /** [document]'s path in [tree], root first; null when it is not in [tree]. */
    fun pathTo(
        tree: String,
        document: String,
    ): List<String>? = cached(paths, "$tree\n$document") { this.tree.pathTo(tree, document) }

    /** Forgets everything: the next question goes to the provider. */
    fun invalidate() {
        synchronized(this) {
            listings.clear()
            paths.clear()
        }
    }

    private fun <T> cached(
        map: LinkedHashMap<String, Entry<T>>,
        key: String,
        load: () -> T?,
    ): T? {
        val now = nanoTime()
        synchronized(this) { map[key] }?.takeIf { now - it.at < ttlNanos }?.let { return it.value }
        val value = load()
        synchronized(this) { map[key] = Entry(now, value) }
        return value
    }

    private companion object {
        const val DEFAULT_TTL_NANOS = 5_000_000_000L
        const val CACHED = 64
        const val LOAD = 0.75f

        fun <T> lru(): LinkedHashMap<String, T> =
            object : LinkedHashMap<String, T>(CACHED, LOAD, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > CACHED
            }
    }
}
