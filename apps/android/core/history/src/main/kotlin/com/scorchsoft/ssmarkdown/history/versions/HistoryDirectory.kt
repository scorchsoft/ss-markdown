package com.scorchsoft.ssmarkdown.history.versions

import java.io.File
import java.io.IOException

/** What [HistoryDirectory.open] found for one document. */
internal sealed interface OpenHistory {
    data class Ready(
        val history: DocumentHistory,
    ) : OpenHistory

    data class Unsupported(
        val formatVersion: Int,
    ) : OpenHistory
}

/**
 * Where every document's history lives: `<root>/docs/<hash of URI>/`,
 * plus `<root>/quarantine/` for histories whose index is damaged.
 *
 * Directory names are a hash of the URI, so any URI (slashes, `%2F`,
 * thousands of characters) maps to a safe, fixed-length name. The index
 * inside still records the URI itself.
 */
internal class HistoryDirectory(
    root: File,
) {
    private val documents = File(root, "docs")
    private val quarantine = File(root, "quarantine")
    private val cache = HashMap<String, DocumentHistory>()

    fun directoryFor(key: DocumentKey): File = File(documents, TextHash.sha256Hex(key.uri).take(NAME_CHARS))

    /** The document's history as it is on disk, without creating or changing anything. */
    fun load(key: DocumentKey): HistoryLoad {
        val dir = directoryFor(key)
        cache[dir.name]?.let { if (it.exists) return HistoryLoad.Loaded(it) }
        return DocumentHistory.load(dir).also { if (it is HistoryLoad.Loaded) cache[dir.name] = it.history }
    }

    /**
     * The document's history, creating an empty one if there is none.
     * A damaged index is moved to quarantine (never deleted) and a fresh
     * history started, so a bad file never stops new versions being kept.
     */
    fun open(key: DocumentKey): OpenHistory =
        when (val load = load(key)) {
            is HistoryLoad.Loaded -> {
                OpenHistory.Ready(load.history)
            }

            is HistoryLoad.Unsupported -> {
                OpenHistory.Unsupported(load.formatVersion)
            }

            HistoryLoad.Corrupt -> {
                moveToQuarantine(directoryFor(key))
                OpenHistory.Ready(fresh(key))
            }

            HistoryLoad.Missing -> {
                OpenHistory.Ready(fresh(key))
            }
        }

    private fun fresh(key: DocumentKey): DocumentHistory {
        val dir = directoryFor(key)
        return DocumentHistory.create(dir, key.uri).also { cache[dir.name] = it }
    }

    /** Every readable history with at least one version. Unsupported and damaged ones are left alone. */
    fun all(): List<DocumentHistory> =
        documents
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                val cached = cache[dir.name]?.takeIf { it.exists }
                cached ?: (DocumentHistory.load(dir) as? HistoryLoad.Loaded)?.history?.also { cache[dir.name] = it }
            }.filter { it.index.versions.isNotEmpty() }

    private fun moveToQuarantine(dir: File) {
        quarantine.mkdirs()
        val target = File(quarantine, "${dir.name}-${System.nanoTime()}")
        cache.remove(dir.name)
        if (!dir.renameTo(target)) throw IOException("could not quarantine $dir")
    }

    private companion object {
        /** 160 bits of the URI's SHA-256: collision-free in practice, short enough for any file system. */
        const val NAME_CHARS = 40
    }
}

/**
 * Removes versions and records every removal in the [log], so no
 * deletion, automatic or asked for, goes unrecorded.
 */
internal class Pruner(
    private val log: CleanupLog,
    private val clock: VersionClock,
) {
    /** Removes [ids] from [history] and logs it. Throws [IOException]. */
    fun remove(
        history: DocumentHistory,
        ids: Set<VersionId>,
        reason: CleanupReason,
        limitBytes: Long? = null,
    ): List<CleanupEntry> {
        if (ids.isEmpty() && history.index.garbageBytes == 0L) return emptyList()
        val key = history.key
        val result = history.rewrite(ids)
        val now = clock.nowMillis()
        val entries =
            buildList {
                if (result.removed.isNotEmpty()) {
                    add(CleanupEntry(now, key, result.removed.size, maxOf(0, result.freedBytes), reason, limitBytes))
                }
                if (result.lost.isNotEmpty()) add(CleanupEntry(now, key, result.lost.size, 0, CleanupReason.Damaged))
            }
        log.append(entries)
        return entries
    }
}
