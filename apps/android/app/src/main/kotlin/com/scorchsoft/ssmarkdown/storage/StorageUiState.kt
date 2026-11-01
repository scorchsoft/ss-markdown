package com.scorchsoft.ssmarkdown.storage

import androidx.compose.runtime.Immutable
import com.scorchsoft.ssmarkdown.files.storage.StorageBreakdown
import com.scorchsoft.ssmarkdown.history.versions.CleanupEntry
import com.scorchsoft.ssmarkdown.history.versions.ClearPreview
import com.scorchsoft.ssmarkdown.history.versions.ClearRequest
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.FileUsage
import com.scorchsoft.ssmarkdown.history.versions.HistoryPolicy
import com.scorchsoft.ssmarkdown.history.versions.StorageLimit

/** "History by file": largest first, or by last opened. */
enum class StorageSort { Largest, LastOpened }

/** Which clean-up a confirmation is for; the cache is the one that needs no export. */
enum class ClearKind { NotOpened, OlderThan, Cache, All, OneFile }

/**
 * The are-you-sure dialog every clear goes through (StorageConfirm board):
 * exactly what goes, "This can't be undone.", what is kept, and **Export
 * these versions first (.zip)**, ticked by default.
 */
@Immutable
data class StorageConfirm(
    val kind: ClearKind,
    val versions: Int,
    val files: Int,
    val bytes: Long,
    val days: Int = 0,
    val document: String? = null,
    val exportFirst: Boolean = true,
    val includePinned: Boolean = false,
) {
    /** The request [kind] stands for; null for the cache. */
    val request: ClearRequest?
        get() =
            when (kind) {
                ClearKind.NotOpened -> ClearRequest.NotOpenedFor(days)
                ClearKind.OlderThan -> ClearRequest.OlderThan(days)
                ClearKind.All -> ClearRequest.Everything(includePinned)
                ClearKind.OneFile -> document?.let { ClearRequest.OneFile(DocumentKey(it), includePinned) }
                ClearKind.Cache -> null
            }
}

/** Turning "Keep version history" off: first the choice, then (for Delete) the are-you-sure. */
enum class TurnOffStep { Choose, ConfirmDelete }

/** The small pickers on the Storage screen. */
enum class StoragePicker { NotOpened, OlderThan, Limit, Forget }

/** A result line after a clean-up, export or import. */
sealed interface StorageMessage {
    data class Cleared(
        val versions: Int,
        val bytes: Long,
    ) : StorageMessage

    data class Imported(
        val versions: Int,
    ) : StorageMessage

    data object ExportReady : StorageMessage

    data object Failed : StorageMessage
}

/**
 * Settings → Storage and history (ux-spec "Storage"). Sizes are measured
 * off the main thread; the screen shows the last known values at once.
 *
 * @property notOpenedDays the "History of files not opened in" choice.
 * @property olderThanDays the "Versions older than" choice.
 * @property lowStorage under 500 MB free on the device: a warning, never a silent delete.
 */
@Immutable
data class StorageUiState(
    val breakdown: StorageBreakdown? = null,
    val historyFiles: List<FileUsage> = emptyList(),
    val sort: StorageSort = StorageSort.Largest,
    val notOpenedDays: Int = 90,
    val olderThanDays: Int = 180,
    val notOpenedPreview: ClearPreview? = null,
    val olderPreview: ClearPreview? = null,
    val policy: HistoryPolicy = HistoryPolicy(),
    val cleanups: List<CleanupEntry> = emptyList(),
    val showCleanups: Boolean = false,
    val confirm: StorageConfirm? = null,
    val turnOff: TurnOffStep? = null,
    val picker: StoragePicker? = null,
    val fileMenu: String? = null,
    val lowStorage: Boolean = false,
    val message: StorageMessage? = null,
    val busy: Boolean = false,
) {
    /** History by file, in the chosen order. */
    val sortedFiles: List<FileUsage>
        get() =
            when (sort) {
                StorageSort.Largest -> historyFiles.sortedByDescending { it.bytes }
                StorageSort.LastOpened -> historyFiles.sortedByDescending { it.lastOpenedMillis }
            }

    /** The history limit as bytes, or null for "No limit". */
    val limitBytes: Long? get() = (policy.totalLimit as? StorageLimit.Bytes)?.bytes

    companion object {
        /** "History of files not opened in": 30 days · 90 days · 6 months · 1 year. */
        val NOT_OPENED_CHOICES: List<Int> = listOf(30, 90, 180, 365)

        /** "Versions older than": 1 month · 6 months · 1 year. */
        val OLDER_THAN_CHOICES: List<Int> = listOf(30, 180, 365)
    }
}
