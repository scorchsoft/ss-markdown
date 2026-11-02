package com.scorchsoft.ssmarkdown.share

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.settings.AppSettings
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Android Direct Share targets for recent files (ADR 0017; ux-spec "Share
 * into SS Markdown"): the system share sheet shows the user's recent files,
 * and tapping one opens our sheet with that file chosen. The same
 * shortcuts appear in the launcher's long-press menu, where they open the
 * file.
 *
 * Only files we may still write are offered, ranked as the sheet ranks
 * them ([ShareTargets.ranked]). A shortcut's id is a hash of the file's
 * URI, never the URI, and an incoming id is only ever mapped back through
 * our own recents ([resolve]): `ShareActivity` and `MainActivity` are
 * exported, so any app can send any id. A resolved id only *preselects*
 * the file; the user still taps Append.
 *
 * Every shortcut call goes through [publisher] on [io], and only when the
 * wanted set differs from the published one, since the platform
 * rate-limits shortcut updates.
 *
 * @param grants every persisted URI grant, true where writable (`PersistedPermissions.held`).
 */
class DirectShareTargets(
    private val publisher: ShortcutPublisher,
    private val settings: SettingsRepository,
    private val grants: () -> Map<DocumentUri, Boolean>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val lock = Any()

    /** What is published, as last read or written; null until read, or after a failed call. */
    private var published: Map<String, ShortcutSpec>? = null

    /**
     * Keeps the shortcuts in step with the setting and the recent files,
     * starting with a sync now (the graph's start).
     */
    fun start() {
        scope.launch(io) {
            settings.settings
                .map { SyncKey(it.share.directShare, it.share.lastTarget, it.recentFiles) }
                .distinctUntilChanged()
                .collect { sync() }
        }
    }

    /** Syncs again off the main thread: a grant may have been lost since the last change. */
    fun refresh() {
        scope.launch(io) { sync() }
    }

    /**
     * The recent file the shortcut [id] stands for, if it is one we would
     * offer now: the setting is on, the file is still recent, and we may
     * still write it. Anything else (unknown, forged, forgotten, lost
     * grant) is null, and the caller carries on as if no id came.
     *
     * Asking for the grants is a binder call, made only once [id] names one
     * of our recents. `ShareActivity` calls this on [io]. `MainActivity`'s
     * launcher shortcut calls it on the main thread, in `onCreate`, because
     * the editor must know what to open before its first frame (resolving
     * it later would open the launch default first, then switch); that path
     * already takes persistable grants there too (review r3 L8).
     */
    fun resolve(id: String?): String? {
        id ?: return null
        val s = settings.settings.value
        if (!s.share.directShare) return null
        val file = s.recentFiles.firstOrNull { DirectShareIds.of(it.uri) == id } ?: return null
        return file.uri.takeIf { Grants.writable(it, grants()) }
    }

    /** An append to [uri] succeeded: the platform ranks its shortcut higher. */
    fun reportUsed(uri: String) {
        if (!settings.settings.value.share.directShare) return
        scope.launch(io) { publisher.call { reportUsed(DirectShareIds.of(uri)) } }
    }

    /** Publishes exactly the wanted shortcuts, touching only what changed. */
    internal fun sync() {
        synchronized(lock) {
            val s = settings.settings.value
            val wanted =
                if (s.share.directShare) {
                    val limit = minOf(MAX_TARGETS, publisher.call { maxPerActivity } ?: MAX_TARGETS)
                    ShareTargets.directShare(s, grants(), limit).associateBy { it.id }
                } else {
                    emptyMap()
                }
            val current = published ?: publisher.call { current() }?.associateBy { it.id } ?: emptyMap()
            val stale = current.keys - wanted.keys
            var ok = true
            if (stale.isNotEmpty()) ok = publisher.call { remove(stale.toList()) } != null
            wanted.values.filter { current[it.id] != it }.forEach { spec ->
                // A push the platform declined (rate-limited) is a failure too, so the next sync retries it
                // rather than trusting a stale share sheet (review r3 L7).
                ok = publisher.call { push(spec) } == true && ok
            }
            // After a failure the next sync reads the truth back rather than trusting this.
            published = if (ok) wanted else null
        }
    }

    private data class SyncKey(
        val on: Boolean,
        val lastTarget: String?,
        val recents: List<RecentFile>,
    )

    private companion object {
        /** The share sheet shows a row of four; more is noise (ADR 0017). */
        const val MAX_TARGETS = 4
    }
}

/**
 * One recent file's shortcut, as the platform holds it. Equal specs need
 * no update, which is what keeps [DirectShareTargets] under the rate limit.
 *
 * @property id [DirectShareIds.of] the file's URI.
 * @property shortLabel the file's name.
 * @property longLabel "name · location".
 * @property rank 0 first.
 */
data class ShortcutSpec(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    val rank: Int,
)

/** The platform's shortcut store, behind a seam so the sync logic is tested without it. */
interface ShortcutPublisher {
    /** How many dynamic shortcuts one Activity may have. */
    val maxPerActivity: Int

    /** Our recent-file shortcuts as published now, dynamic or cached long-lived. */
    fun current(): List<ShortcutSpec>

    /** Adds or updates [spec]; false when the platform did not take it (rate-limited). */
    fun push(spec: ShortcutSpec): Boolean

    /** Removes [ids], dynamic and long-lived alike. */
    fun remove(ids: List<String>)

    /** The user used the shortcut [id]. */
    fun reportUsed(id: String)
}

/**
 * Runs [block], or returns null if the platform refused: rate-limited, the
 * user locked, a shortcut it will not take. A shortcut is a convenience,
 * so no failure here may reach the user.
 */
private inline fun <T> ShortcutPublisher.call(block: ShortcutPublisher.() -> T): T? =
    try {
        block()
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/** Shortcut ids for recent files. */
object DirectShareIds {
    /** Every recent-file shortcut id starts with this; nothing else of ours does. */
    const val PREFIX: String = "recent:"

    private const val HASH_CHARS = 16

    /**
     * [uri]'s shortcut id: a hash, so the URI itself is never handed to the
     * platform's shortcut store, the share sheet or the launcher. 64 bits
     * is plenty to tell 20 recent files apart. It is not a secret: safety
     * rests on [DirectShareTargets.resolve] and the user's tap.
     */
    fun of(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
    }
}

/** What the share sheet offers, and in which order. */
object ShareTargets {
    /** Recent files with the last target and pinned ones first, otherwise newest first (the sheet's order). */
    fun ranked(settings: AppSettings): List<RecentFile> =
        settings.recentFiles.sortedByDescending { it.uri == settings.share.lastTarget || it.pinned }

    /** Up to [limit] of [ranked] that [held] lets us write, as shortcuts. */
    fun directShare(
        settings: AppSettings,
        held: Map<DocumentUri, Boolean>,
        limit: Int,
    ): List<ShortcutSpec> =
        ranked(settings)
            .filter { Grants.writable(it.uri, held) }
            .take(limit.coerceAtLeast(0))
            .mapIndexed { rank, file ->
                val name = file.name.ifEmpty { file.uri.substringAfterLast('/') }
                val long = if (file.location.isEmpty()) name else "$name · ${file.location}"
                ShortcutSpec(DirectShareIds.of(file.uri), name, long, rank)
            }
}

/** Reading persisted grants. */
internal object Grants {
    /**
     * Whether [held] lets us write [uri]: a write grant on the document
     * itself, or on the folder tree it was opened from (folder mode's
     * documents are `<tree>/document/<id>`).
     */
    fun writable(
        uri: String,
        held: Map<DocumentUri, Boolean>,
    ): Boolean =
        held[DocumentUri(uri)] == true ||
            held.any { (grant, write) -> write && uri.startsWith(grant.value + "/document/") }
}
