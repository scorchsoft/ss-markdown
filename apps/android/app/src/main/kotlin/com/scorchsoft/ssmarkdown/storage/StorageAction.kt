package com.scorchsoft.ssmarkdown.storage

import com.scorchsoft.ssmarkdown.files.document.SetAsideEdits
import com.scorchsoft.ssmarkdown.history.versions.ClearPreview
import com.scorchsoft.ssmarkdown.history.versions.StorageLimit

/** What the Storage screen can do. */
sealed interface StorageAction {
    data object Back : StorageAction

    data class SetSort(
        val sort: StorageSort,
    ) : StorageAction

    data class OpenPicker(
        val picker: StoragePicker,
    ) : StorageAction

    data object DismissPicker : StorageAction

    data class ChooseNotOpened(
        val days: Int,
    ) : StorageAction

    data class ChooseOlderThan(
        val days: Int,
    ) : StorageAction

    data class AskClear(
        val kind: ClearKind,
        val document: String? = null,
    ) : StorageAction

    data object ToggleExportFirst : StorageAction

    data object ToggleIncludePinned : StorageAction

    data object ConfirmClear : StorageAction

    data object CancelConfirm : StorageAction

    data object ToggleKeepHistory : StorageAction

    data object TurnOffKeep : StorageAction

    data object TurnOffExportThenDelete : StorageAction

    data object TurnOffAskDelete : StorageAction

    data object TurnOffConfirmDelete : StorageAction

    data class SetLimit(
        val limit: StorageLimit,
    ) : StorageAction

    data class SetForget(
        val days: Int?,
    ) : StorageAction

    data class FileMenu(
        val document: String?,
    ) : StorageAction

    data class ExportFile(
        val document: String,
    ) : StorageAction

    data class OpenHistory(
        val document: String,
    ) : StorageAction

    data object ToggleCleanups : StorageAction

    data object ImportHistory : StorageAction

    /** "Open as a copy" on the set-aside edits at [index] (R2-DS-3). */
    data class OpenSetAside(
        val index: Int,
    ) : StorageAction

    data object DismissMessage : StorageAction
}

/** The Storage screen's pure transitions; measuring and clearing run in [StorageViewModel]. */
object StorageReducer {
    /** [state] after [action]. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // A dispatch table: one short branch per action.
    fun reduce(
        state: StorageUiState,
        action: StorageAction,
    ): StorageUiState =
        when (action) {
            is StorageAction.SetSort -> {
                state.copy(sort = action.sort)
            }

            is StorageAction.OpenPicker -> {
                state.copy(picker = action.picker)
            }

            StorageAction.DismissPicker -> {
                state.copy(picker = null)
            }

            is StorageAction.ChooseNotOpened -> {
                state.copy(notOpenedDays = action.days, picker = null, notOpenedPreview = null)
            }

            is StorageAction.ChooseOlderThan -> {
                state.copy(olderThanDays = action.days, picker = null, olderPreview = null)
            }

            StorageAction.ToggleExportFirst -> {
                state.copy(confirm = state.confirm?.let { it.copy(exportFirst = !it.exportFirst) })
            }

            StorageAction.ToggleIncludePinned -> {
                state.copy(confirm = state.confirm?.let { it.copy(includePinned = !it.includePinned) })
            }

            StorageAction.CancelConfirm -> {
                state.copy(confirm = null, turnOff = null)
            }

            StorageAction.ToggleKeepHistory -> {
                if (state.policy.recording) {
                    state.copy(
                        turnOff = TurnOffStep.Choose,
                    )
                } else {
                    state.copy(policy = state.policy.copy(recording = true))
                }
            }

            StorageAction.TurnOffAskDelete -> {
                state.copy(turnOff = TurnOffStep.ConfirmDelete)
            }

            StorageAction.TurnOffKeep, StorageAction.TurnOffExportThenDelete, StorageAction.TurnOffConfirmDelete -> {
                state.copy(turnOff = null, policy = state.policy.copy(recording = false))
            }

            is StorageAction.SetLimit -> {
                state.copy(policy = state.policy.copy(totalLimit = action.limit), picker = null)
            }

            is StorageAction.SetForget -> {
                state.copy(policy = state.policy.copy(forgetUnusedAfterDays = action.days), picker = null)
            }

            is StorageAction.FileMenu -> {
                state.copy(fileMenu = action.document)
            }

            is StorageAction.ExportFile, is StorageAction.OpenHistory -> {
                state.copy(fileMenu = null)
            }

            StorageAction.ToggleCleanups -> {
                state.copy(showCleanups = !state.showCleanups)
            }

            StorageAction.DismissMessage -> {
                state.copy(message = null)
            }

            else -> {
                state
            }
        }

    /** The confirmation for clearing [edits]: how many, how much, and Export first ticked. */
    fun confirmForSetAside(edits: List<SetAsideEdits>): StorageConfirm =
        StorageConfirm(
            kind = ClearKind.SetAside,
            versions = edits.size,
            files = edits.size,
            bytes = edits.sumOf { it.sizeBytes },
            exportFirst = true,
        )

    /** The confirmation for a clear of [kind], from the numbers a preview gave. */
    fun confirmFor(
        kind: ClearKind,
        preview: ClearPreview?,
        days: Int = 0,
        document: String? = null,
    ): StorageConfirm =
        StorageConfirm(
            kind = kind,
            versions = preview?.versions ?: 0,
            files = preview?.files ?: 0,
            bytes = preview?.bytes ?: 0,
            days = days,
            document = document,
            // The cache rebuilds itself; it needs only a simple confirm.
            exportFirst = kind != ClearKind.Cache,
        )
}
