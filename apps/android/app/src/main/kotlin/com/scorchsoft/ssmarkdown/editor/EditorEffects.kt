package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.editor.preview.PreviewEffects
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.nav.AppScreen

/**
 * The side effects of each [EditorAction], after [EditorReducer] has
 * applied its pure part. A dispatch table: each branch hands off to the
 * controller that owns the area.
 */
internal object EditorEffects {
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One line per action; splitting would only hide the table.
    fun run(
        vm: EditorViewModel,
        action: EditorAction,
        before: EditorUiState,
    ) {
        when (action) {
            is EditorAction.SelectMode -> {
                vm.rememberView { it.copy(mode = action.mode) }.also { vm.emit(EditorEvent.Tick) }
                // The pane coming into view lands on the anchor (the Code pane does so as it attaches).
                if (action.mode != ViewMode.Code) vm.preview.scrollTo(vm.anchorLine.value)
                if (action.mode != ViewMode.Split) vm.update { it.copy(preview = it.preview.copy(editing = false)) }
                vm.onReadingPaneChanged()
                vm.find.onModeChanged()
            }

            EditorAction.CopyTapped -> {
                vm.copies.openChooser()
            }

            EditorAction.CopyLongPressed -> {
                vm.copies.copyDocument(vm.deps.settings.settings.value.copyFormat)
            }

            is EditorAction.CopyAs -> {
                vm.copies.copyDocument(action.format)
            }

            EditorAction.SaveTapped -> {
                vm.saves.tap()
            }

            EditorAction.SaveLongPressed -> {
                vm.saves.openSaveMenu()
            }

            EditorAction.ToggleAutosaveThisFile -> {
                vm.saves.toggleAutosaveThisFile()
            }

            is EditorAction.ChooseMenuItem -> {
                menu(vm, action.item)
            }

            EditorAction.OpenMenu -> {
                // The menu needs the room: the keyboard's word is committed first (R6), then the
                // keyboard goes. Closing the menu does not bring it back; tapping the text does.
                vm.active?.surface?.flushComposing()
                vm.emit(EditorEvent.HideKeyboard)
            }

            is EditorAction.MenuHistory -> {
                vm.active?.surface?.let { if (action.redo) it.redo() else it.undo() }
                vm.refresh()
            }

            EditorAction.TitleTapped -> {
                vm.navigator.toggle()
            }

            is EditorAction.ResetZoom, is EditorAction.StepZoom, is EditorAction.ZoomChanged -> {
                // Mid-pinch zoom changes every frame: remember only where the fingers let go (PERF-B).
                if (action !is EditorAction.ZoomChanged || !action.pinching) {
                    vm.rememberView { it.copy(codeZoom = vm.state.codeZoom, previewZoom = vm.state.previewZoom) }
                }
            }

            is EditorAction.SetAppearance -> {
                vm.deps.settings.update { it.copy(appearance = action.appearance) }
            }

            is EditorAction.SetCodeWrap -> {
                vm.deps.settings.update { it.copy(codeWrap = action.wrap) }
            }

            EditorAction.RecoveryCompare -> {
                vm.recovery.compare()
            }

            EditorAction.RecoveryDiscard -> {
                vm.recovery.discard()
            }

            EditorAction.UndoDiscard -> {
                vm.recovery.undoDiscard()
            }

            EditorAction.MessageUndo -> {
                if (before.message?.kind ==
                    MessageKind.DraftDeleted
                ) {
                    vm.navigator.undoDeleteDraft()
                } else {
                    vm.recovery.undoDiscard()
                }
            }

            EditorAction.DiscardKeptEdits -> {
                vm.recovery.discardKept()
            }

            EditorAction.OpenKeptAsCopy -> {
                vm.recovery.openKeptAsCopy()
            }

            EditorAction.ConfirmNotKept -> {
                (before.overlay as? EditorOverlay.NotKept)?.let { vm.goOnWithoutCopy(it.step) }
            }

            EditorAction.ReviewConflict -> {
                val skipped = (before.banner as? EditorBanner.SaveAllResult)?.skipped.orEmpty()
                val changed = skipped.firstOrNull { it.problem == SaveProblem.ChangedOutside }
                // From Save all's banner, Review means the skipped file, which may not be the one on screen (J7).
                if (changed == null || vm.active
                        ?.open
                        ?.uri
                        ?.value == changed.uri
                ) {
                    vm.conflicts.review()
                } else {
                    vm.openForReview(changed.uri)
                }
            }

            EditorAction.MergeMine -> {
                vm.conflicts.merge()
            }

            EditorAction.CompareConflict -> {
                vm.conflicts.compare()
            }

            EditorAction.KeepTheirs -> {
                vm.conflicts.keepTheirs()
            }

            EditorAction.ConfirmUseMine -> {
                vm.conflicts.useMine()
            }

            EditorAction.SaveAsFromBanner, EditorAction.RecreateFile -> {
                vm.saves.saveAs()
            }

            EditorAction.OpenAnotherFile -> {
                vm.emit(EditorEvent.PickFileToOpen)
            }

            EditorAction.RetryOpen -> {
                (before.banner as? EditorBanner.OpenFailed)?.let { vm.openDocument(it.uri) }
            }

            EditorAction.BackPressed -> {
                if (EditorReducer.backLeaves(before)) vm.saves.leave(PendingLeave.Exit, discard = false)
            }

            EditorAction.LeaveSave -> {
                (before.overlay as? EditorOverlay.DirtyPrompt)?.let { vm.saves.saveThenLeave(it.leave) }
            }

            EditorAction.LeaveDiscard -> {
                // The sheet said "can't be undone" when History was off: the user has accepted no copy.
                (before.overlay as? EditorOverlay.DirtyPrompt)?.let {
                    vm.saves.leave(it.leave, discard = true, evenIfNotKept = !before.historyOn)
                }
            }

            is EditorAction.StripKeyTapped -> {
                vm.strip.onKey(action.key)
            }

            is EditorAction.SymbolTapped -> {
                vm.strip.onSymbol(action.text)
            }

            is EditorAction.CursorStepped -> {
                vm.strip.onCursor(action.step)
            }

            is EditorAction.BlockChosen -> {
                vm.strip.onBlock(action.choice)
            }

            is EditorAction.LinkDone -> {
                (before.overlay as? EditorOverlay.LinkPrompt)?.let {
                    vm.strip.links.onDone(
                        it,
                        action.text,
                        action.url,
                    )
                    vm.emit(EditorEvent.ReturnKeyboard)
                }
            }

            EditorAction.LinkRemove -> {
                (before.overlay as? EditorOverlay.LinkPrompt)?.let {
                    vm.strip.links.onRemove(it)
                    vm.emit(EditorEvent.ReturnKeyboard)
                }
            }

            EditorAction.DismissOverlay -> {
                // Cancelling the Link sheet hands the keyboard back as Done does; other overlays never had it.
                if (before.overlay is EditorOverlay.LinkPrompt) vm.emit(EditorEvent.ReturnKeyboard)
            }

            is EditorAction.Find -> {
                // Find moves the Code pane to each match and, in Split, the Preview with it: Code leads,
                // so the Preview following its scroll can never pull the Code pane off the match.
                if (vm.uiState.value.mode == ViewMode.Split) vm.scrollLink.touched(Pane.Code)
                vm.find.onAction(action.action)
            }

            is EditorAction.Navigator -> {
                vm.navigator.onAction(action.action)
            }

            is EditorAction.Folder -> {
                vm.folder.onAction(action.action)
            }

            is EditorAction.PreviewAction -> {
                PreviewEffects.run(vm, action, before)
            }

            // The reducer does all there is to do for these.
            is EditorAction.AnchorChanged,
            EditorAction.RecoveryGotIt,
            EditorAction.ShowFailureDetails,
            EditorAction.DismissBanner,
            EditorAction.AskUseMine,
            EditorAction.KeepEditing,
            is EditorAction.ImeVisibilityChanged,
            EditorAction.BigFileChipTapped,
            EditorAction.MessageShown,
            -> {
                Unit
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per menu row.
    private fun menu(
        vm: EditorViewModel,
        item: MenuItem,
    ) {
        vm.update { if (it.overlay == EditorOverlay.Menu) it.copy(overlay = null) else it }
        when (item) {
            MenuItem.New -> vm.saves.requestLeave(PendingLeave.NewDocument)
            MenuItem.OpenFile -> vm.emit(EditorEvent.PickFileToOpen)
            MenuItem.OpenFolder -> vm.emit(EditorEvent.PickFolder)
            MenuItem.SaveAs -> vm.saves.saveAs()
            MenuItem.SaveAll -> vm.onAction(EditorAction.Folder(FolderAction.SaveAll))
            MenuItem.FindReplace -> vm.onAction(EditorAction.Find(com.scorchsoft.ssmarkdown.find.FindAction.Open))
            MenuItem.VersionHistory -> vm.emit(EditorEvent.Open(AppScreen.History))
            MenuItem.ExportPdf -> PreviewEffects.exportPdf(vm)
            MenuItem.ExportImages -> vm.imageExports.openExport()
            MenuItem.Share -> vm.saves.share(richText = false)
            MenuItem.CopyAs -> vm.copies.openChooser()
            MenuItem.Display -> vm.update { it.copy(overlay = EditorOverlay.Display) }
            MenuItem.Settings -> vm.emit(EditorEvent.Open(AppScreen.Settings))
            MenuItem.Copy -> vm.copies.openChooser()
        }
    }
}

/** Stores the on-screen document's view choices (mode, zoom) so it reopens the same way. */
internal fun EditorViewModel.rememberView(change: (DocumentMeta) -> DocumentMeta) {
    val uri = active?.open?.uri?.value ?: return
    val now = state
    updateMeta(uri) { change(it.copy(mode = now.mode, codeZoom = now.codeZoom, previewZoom = now.previewZoom)) }
    views.capture()
}
