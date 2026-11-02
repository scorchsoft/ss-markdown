package com.scorchsoft.ssmarkdown.media

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/**
 * Which granted folder holds a document opened on its own (ADR 0018). A
 * single `.md` opened from Files, Drive or another app comes with a grant
 * for that one file, so the pictures beside it are out of reach until the
 * user grants its folder; this remembers that grant per document, so
 * reopening the file shows its pictures without asking again.
 */
interface FolderLinks {
    /** The tree remembered for [document], or null. */
    operator fun get(document: String): String?

    /** Remembers that [tree] holds [document]. */
    operator fun set(
        document: String,
        tree: String,
    )
}

/**
 * [FolderLinks] in a small preferences file, one entry per document with
 * when it was written; the oldest go past [MAX_LINKS], so it never grows
 * without bound. Read lazily: nothing here is on the launch path.
 */
class PreferencesFolderLinks(
    private val preferences: () -> SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) : FolderLinks {
    override fun get(document: String): String? = entry(document)?.optString(TREE)?.takeIf { it.isNotEmpty() }

    override fun set(
        document: String,
        tree: String,
    ) {
        val prefs = preferences()
        val all = prefs.all
        prefs.edit {
            putString(document, JSONObject().put(TREE, tree).put(AT, clock()).toString())
            if (all.size >= MAX_LINKS) {
                all.keys
                    .filter { it != document }
                    .sortedBy { key -> parse(all[key] as? String)?.optLong(AT) ?: 0L }
                    .take(all.size - MAX_LINKS + 1)
                    .forEach(::remove)
            }
        }
    }

    private fun entry(document: String): JSONObject? = parse(preferences().getString(document, null))

    private fun parse(raw: String?): JSONObject? =
        try {
            raw?.let(::JSONObject)
        } catch (_: JSONException) {
            null
        }

    companion object {
        /** Documents remembered at most; a phone's worth of notes, a few kilobytes. */
        const val MAX_LINKS: Int = 256
        private const val TREE = "tree"
        private const val AT = "at"
    }
}

/**
 * Finds the granted folder (tree) that holds a document, for its pictures:
 * the open folder in folder mode, else the folder the user granted for this
 * document ([links]), else any other folder grant the app already holds
 * that contains it ([heldTrees]), so a file inside a folder opened before
 * needs no new grant. A tree is used only if it really contains the
 * document: the provider must place it there ([TreeListings.pathTo]), or,
 * for providers that cannot, its id must sit under the tree's by whole
 * path segments (SEC-9).
 *
 * Answers are cached per document for the session; [invalidate] drops them
 * (a grant was added or released).
 */
class PictureFolders(
    private val listings: TreeListings,
    private val links: FolderLinks,
    private val folderTree: () -> String?,
    private val heldTrees: () -> List<String>,
) {
    private val found = HashMap<String, Result>()

    private class Result(
        val tree: String?,
    )

    /** The tree that holds [document], or null when none the app holds does. */
    fun treeFor(document: String): String? {
        synchronized(found) { found[document] }?.let { return it.tree }
        val candidates =
            sequence {
                folderTree()?.let { yield(it) }
                links[document]?.let { yield(it) }
                yieldAll(heldTrees().asSequence().filter(::isTree))
            }
        val tree = candidates.distinct().firstOrNull { contains(it, document) }
        synchronized(found) { found[document] = Result(tree) }
        return tree
    }

    /** True when [tree] holds [document]. */
    fun contains(
        tree: String,
        document: String,
    ): Boolean {
        // Ids mean something only within their provider: another app's id must never match by accident.
        if (!sameProvider(tree, document)) return false
        if (listings.pathTo(tree, document) != null) return true
        val docId = listings.tree.documentId(document) ?: return false
        val treeId = listings.tree.treeId(tree) ?: return false
        return docId != treeId && docId.contains(':') && PicturePaths.isWithin(docId, treeId)
    }

    /**
     * Remembers [tree] for [document] when it holds it; false (and nothing
     * remembered) when the user picked a folder that does not.
     */
    fun link(
        document: String,
        tree: String,
    ): Boolean {
        invalidate()
        if (!contains(tree, document)) return false
        links[document] = tree
        return true
    }

    /** Forgets every answer, and the listings with them. */
    fun invalidate() {
        synchronized(found) { found.clear() }
        listings.invalidate()
    }

    private companion object {
        fun isTree(uri: String): Boolean = uri.startsWith("content://") && "/tree/" in uri && "/document/" !in uri

        fun authority(uri: String): String = uri.removePrefix("content://").substringBefore('/')

        fun sameProvider(
            tree: String,
            document: String,
        ): Boolean = authority(tree) == authority(document)
    }
}
