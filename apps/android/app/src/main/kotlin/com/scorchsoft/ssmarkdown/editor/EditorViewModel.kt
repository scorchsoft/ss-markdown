package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.document.ActiveDocument
import com.scorchsoft.ssmarkdown.document.ActiveDocumentListener
import com.scorchsoft.ssmarkdown.document.DocumentThreads
import com.scorchsoft.ssmarkdown.editor.preview.EditorPreviewHost
import com.scorchsoft.ssmarkdown.editor.preview.PreviewSession
import com.scorchsoft.ssmarkdown.editor.preview.ScrollLink
import com.scorchsoft.ssmarkdown.files.document.CheckReason
import com.scorchsoft.ssmarkdown.files.document.DocumentEvent
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.files.document.OpenOutcome
import com.scorchsoft.ssmarkdown.files.memory.Budget
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.markdown.Heading
import com.scorchsoft.ssmarkdown.media.ImageExports
import com.scorchsoft.ssmarkdown.media.MediaController
import com.scorchsoft.ssmarkdown.media.ViewerViewModel
import com.scorchsoft.ssmarkdown.settings.LocationLabel
import com.scorchsoft.ssmarkdown.settings.OnLaunch
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.settings.withRecent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The editor screen's view model: one [EditorUiState], one action entry
 * point ([onAction]), and the effects that touch files. The pure part of
 * every action is [EditorReducer]; the effects are split by area into
 * controllers that reach shared state through [EditorHost].
 *
 * Process-scoped (built by the AppGraph), like the store whose buffers it
 * shows, so no configuration change can lose it.
 */
@Suppress("TooManyFunctions") // The host contract plus the store and editor callbacks it bridges.
class EditorViewModel(
    override val deps: EditorDependencies,
) : EditorHost,
    ActiveDocumentListener {
    private val ui = MutableStateFlow(initialState())
    private val eventChannel = Channel<EditorEvent>(Channel.BUFFERED)
    private val activeFlow = MutableStateFlow<ActiveDocument?>(null)
    private val anchorFlow = MutableStateFlow(0)
    private val metas = HashMap<String, DocumentMeta>()
    private var shownCrumbs: List<Heading> = emptyList()
    private var shownTexts: List<String> = emptyList()
    private var started = false
    private var explicitOpen = false

    /** The screen state. */
    val uiState: StateFlow<EditorUiState> = ui.asStateFlow()

    /** One-shot effects for the Activity. */
    val events: Flow<EditorEvent> = eventChannel.receiveAsFlow()

    /** The document on screen; the panes bind to its buffer. */
    val activeDocument: StateFlow<ActiveDocument?> = activeFlow.asStateFlow()

    /** The source line at the top of the leading pane: the anchor Preview follows. */
    val anchorLine: StateFlow<Int> = anchorFlow.asStateFlow()

    val saves = SaveController(this)
    val conflicts = ConflictController(this)
    val recovery = RecoveryController(this)
    val strip = StripController(this)
    val find = FindController(this)
    val navigator = NavigatorController(this)
    val folder = FolderController(this)
    val views = ViewMemory(this)
    val previewHost = EditorPreviewHost(this)
    override val preview = PreviewSession(previewHost)
    val media = MediaController(this)
    val imageExports = ImageExports(this, media)
    val viewer = ViewerViewModel(media, deps.scope, ::emit)
    val scrollLink = ScrollLink()

    override val active: ActiveDocument? get() = activeFlow.value
    override val state: EditorUiState get() = ui.value

    init {
        deps.store.addListener(::onStoreEvent)
    }

    /**
     * The launch sequence, once per process: recover unsaved edits first,
     * then open what was asked for, else the last file, else a draft.
     */
    fun start(request: LaunchRequest? = null) {
        if (request != null) explicitOpen = true
        if (started) {
            if (request != null) {
                openRequested(request)
            } else {
                rebindIfOrphaned()
            }
            return
        }
        started = true
        deps.scope.launch {
            val recovered = recovery.recoverOnLaunch()
            val settings = deps.settings.settings.value
            when {
                request != null -> {
                    openRequested(request)
                }

                // A file asked for while recovery ran (Share → Open, a second VIEW intent) wins over the default.
                recovered || explicitOpen -> {
                    Unit
                }

                settings.onLaunch == OnLaunch.ReopenLast && reopenable(settings.lastFile) -> {
                    openDocument(settings.lastFile.orEmpty())
                }

                else -> {
                    newDraft()
                }
            }
        }
    }

    /**
     * A last document worth reopening: any file (opening reports its own
     * failure), or a draft that still exists. A draft saved as a file or
     * deleted since is simply gone, which is not worth an error message.
     */
    private fun reopenable(uri: String?): Boolean {
        if (uri == null) return false
        val draft = DocumentUri(uri)
        return !draft.isDraft || deps.drafts.resolve(draft)?.isFile == true
    }

    private fun openRequested(request: LaunchRequest) =
        openDocument(request.uri, fromAnotherApp = request.fromAnotherApp, readOnlySource = request.source)

    /** The single entry point for user actions. */
    fun onAction(action: EditorAction) {
        val before = ui.value
        ui.update { EditorReducer.reduce(it, action) }
        // Back or a mode change closed the find bar: its highlights go with it.
        if (before.find != null && ui.value.find == null) active?.clearHighlights()
        EditorEffects.run(this, action, before)
    }

    /**
     * `onPause`: make the journal durable now and let autosave write
     * before we may die. The journal is written on its own thread; this
     * waits for it, briefly, because a backgrounded process may be killed
     * next (docs/robustness.md §1).
     */
    fun onPause() {
        views.capture(now = true)
        // Preview typing still in its 150 ms pause is asked for now; it arrives
        // after this returns and is made durable by [persistNow] (DS-14).
        preview.onPause()
        persistNow()
    }

    /** Makes the journal durable now, waiting for it briefly, and lets autosave write. */
    fun persistNow() {
        deps.store.flushJournals()
        deps.store.awaitJournals(PAUSE_JOURNAL_WAIT_MILLIS)
        active?.open?.uri?.let(deps.autosave::onLeave)
    }

    /**
     * The activity came back to a view model whose document has left the
     * store ("Don't save", then Exit, with the process kept alive). That
     * buffer can be neither saved nor journalled, so it must not be shown
     * (DS-10): open the file again from disk, or a fresh draft.
     */
    private fun rebindIfOrphaned() {
        val open = active?.open ?: return
        if (deps.store[open.uri] === open) return
        active?.release()
        activeFlow.value = null
        if (open.uri.isDraft) newDraft() else openDocument(open.uri.value)
    }

    /** `onResume`: the disk is the truth; look for an outside change. */
    fun onResume() {
        preview.onResume()
        val uri = active?.open?.uri ?: return
        if (uri.isDraft) return
        deps.scope.launch { deps.store.checkOutside(uri, CheckReason.Resume) }
    }

    /** The Code view is on screen: it takes the document's remembered cursor and scroll, once. */
    fun attach(view: MarkdownCodeView) {
        val doc = active ?: return
        doc.attach(view)
        // A file's remembered position wins once; after that the Code pane lands on the shared anchor.
        if (!views.applyPendingTo(doc)) doc.surface.scrollToLine(anchorFlow.value)
    }

    /** The Code view left the composition. */
    fun detach(view: MarkdownCodeView) = active?.detach(view)

    override fun update(change: (EditorUiState) -> EditorUiState) = ui.update(change)

    override fun emit(event: EditorEvent) {
        eventChannel.trySend(event)
    }

    override fun meta(uri: String): DocumentMeta = metas.getOrPut(uri) { DocumentMeta(openedAtMillis = deps.clock()) }

    override fun updateMeta(
        uri: String,
        change: (DocumentMeta) -> DocumentMeta,
    ) {
        metas[uri] = change(meta(uri))
    }

    override fun message(
        kind: MessageKind,
        argument: String,
        undo: Boolean,
    ) = ui.update { it.copy(message = UserMessage(kind, argument, undo, id = deps.clock())) }

    override fun openDocument(
        uri: String,
        fromAnotherApp: Boolean,
        readOnlySource: String?,
    ) {
        val current = active?.open
        if (current != null && current.uri.value == uri && deps.store[current.uri] === current) {
            // Already on screen: joining the entry again would count a second window that never closes (CC-12).
            updateMeta(uri) { it.copy(fromAnotherApp = fromAnotherApp, readOnlySource = readOnlySource) }
            refresh()
            return
        }
        deps.scope.launch {
            ui.update { it.copy(loading = true, tooLarge = null) }
            val outcome = deps.store.open(DocumentUri(uri))
            ui.update { it.copy(loading = false) }
            when (outcome) {
                is OpenOutcome.Opened -> {
                    updateMeta(uri) { it.copy(fromAnotherApp = fromAnotherApp, readOnlySource = readOnlySource) }
                    bind(outcome.document)
                    outcome.recovery?.let { recovery.onRecovered(it) }
                }

                is OpenOutcome.Failed -> {
                    // Unsaved edits whose file is out of reach come back as a copy, never as an empty draft (R2-DS-2).
                    if (outcome.hasUnsavedEdits && recovery.openUnreachableAsCopy(DocumentUri(uri))) return@launch
                    message(MessageKind.ActionFailed)
                    if (active == null) newDraft()
                }

                is OpenOutcome.TooLarge -> {
                    val a = outcome.admission
                    ui.update { it.copy(tooLarge = TooLargeInfo(a.fileBytes, a.neededBytes, a.allowedBytes)) }
                }

                else -> {
                    message(MessageKind.ActionFailed)
                    if (active == null) newDraft()
                }
            }
        }
    }

    /**
     * "New": reuses an empty, untouched draft rather than piling up empty
     * ones, else creates one. A draft that is empty on disk but holds
     * typing not yet autosaved is not untouched: reusing it would make New
     * do nothing, or show that typing again as if it were new.
     */
    override fun newDraft() {
        val reusable =
            deps.drafts
                .list()
                .firstOrNull { it.sizeBytes == 0L && deps.store[it.uri]?.isDirty != true }
                ?.uri
        val uri = reusable ?: deps.drafts.create()
        if (uri == null) {
            message(MessageKind.ActionFailed)
            return
        }
        openDocument(uri.value)
    }

    /**
     * Shows [open], an entry the store has already opened for this window
     * (a recovered copy), with [banner]. Opening it again would count a
     * second window that never closes (CC-12).
     */
    fun show(
        open: OpenDocument,
        banner: EditorBanner,
    ) {
        bind(open)
        ui.update { it.copy(banner = banner) }
    }

    /** "Discard anyway" / "Merge anyway": [step] again, accepting that History kept no copy (R2-DS-1). */
    fun goOnWithoutCopy(step: NotKeptStep) {
        when (step) {
            NotKeptStep.KeepTheirs -> conflicts.keepTheirs(evenIfNotKept = true)
            NotKeptStep.DiscardRecovered -> recovery.discard(evenIfNotKept = true)
            is NotKeptStep.FinishMerge -> conflicts.applyMerge(step.text, step.madeFromVersion, evenIfNotKept = true)
            is NotKeptStep.DontSave -> saves.leave(step.leave, discard = true, evenIfNotKept = true)
        }
    }

    override fun rebind() {
        val open = active?.open ?: return
        if (open.document !== active?.text) bind(open, switching = false)
    }

    override fun refresh() {
        val doc = active ?: return
        val open = doc.open
        val uri = open.uri.value
        val meta = meta(uri)
        val settings = deps.settings.settings.value
        val facts =
            SaveFacts(
                isDraft = open.uri.isDraft,
                isDirty = open.isDirty,
                readOnly = open.readOnly != null,
                saving = meta.saving,
                autosave = settings.autosaves(uri, open.uri.isDraft),
                lastFailure = meta.lastFailure,
                autosavePausedBy = deps.autosave.pausedBy(open.uri)?.let(SaveProblem::of),
                lastSavedAtMillis = meta.lastSavedAtMillis,
                lastSaveWasAutosave = meta.lastSaveWasAutosave,
                openedAtMillis = meta.openedAtMillis,
                readOnlySource = meta.readOnlySource,
            )
        ui.update {
            it.copy(
                title = if (open.uri.isDraft) "" else open.displayName.orEmpty(),
                save = SaveStateMachine.present(facts),
                bigFile = open.budget == Budget.Lean,
                isMarkdown = open.displayName?.endsWith(".txt", ignoreCase = true) != true,
                canUndo = doc.text.canUndo,
                historyOn = settings.history.recording,
            )
        }
        updateBreadcrumb()
    }

    private fun bind(
        open: OpenDocument,
        switching: Boolean = true,
    ) {
        val previous = active
        if (switching && previous != null && previous.open.uri != open.uri) {
            views.capture()
            val uri = previous.open.uri
            deps.autosave.onLeave(uri)
            deps.scope.launch { deps.store.close(uri) }
        }
        previous?.release()
        val document = ActiveDocument(open, this, DocumentThreads(deps.outlineWork, deps.scope))
        activeFlow.value = document
        preview.bind(document)
        val uri = open.uri.value
        val meta = meta(uri)
        val settings = deps.settings.settings.value
        val saved = if (switching) views.restore(document) else null
        ui.update {
            it.copy(
                mode = meta.mode ?: saved?.mode ?: settings.firstViewFor(meta.fromAnotherApp),
                codeZoom = meta.codeZoom ?: saved?.codeZoom ?: settings.defaultZoomCode,
                previewZoom = meta.previewZoom ?: saved?.previewZoom ?: settings.defaultZoomPreview,
                defaultCodeZoom = settings.defaultZoomCode,
                defaultPreviewZoom = settings.defaultZoomPreview,
                codeWrap = settings.codeWrap,
                find = null,
                navigator = null,
            )
        }
        if (switching) anchorFlow.value = saved?.anchorLine ?: 0
        rememberRecent(open)
        refresh()
    }

    /**
     * [open] becomes the document reopened on launch. A file also joins the
     * recent list; a draft does not (the Drafts group lists it), but it is
     * still what the user was writing, so it is what comes back.
     */
    private fun rememberRecent(open: OpenDocument) {
        if (open.uri.isDraft) {
            deps.settings.update { it.copy(lastFile = open.uri.value) }
            return
        }
        val name = open.displayName ?: return
        deps.settings.update {
            it.withRecent(
                RecentFile(open.uri.value, name, LocationLabel.of(open.uri.value), openedAtMillis = deps.clock()),
            )
        }
    }

    @Suppress("CyclomaticComplexMethod") // One small branch per store event.
    private fun onStoreEvent(event: DocumentEvent) {
        val activeUri = active?.open?.uri
        when (event) {
            is DocumentEvent.Edited -> {
                if (event.uri == activeUri) refresh() else folder.refreshDots()
            }

            is DocumentEvent.SaveFinished -> {
                saves.onSaveFinished(event.uri.value, event.outcome)
            }

            is DocumentEvent.Reloaded -> {
                if (event.uri == activeUri) rebind()
            }

            is DocumentEvent.ConflictDetected -> {
                if (event.uri == activeUri) conflicts.onDetected()
            }

            is DocumentEvent.FileMissing -> {
                if (event.uri ==
                    activeUri
                ) {
                    ui.update { it.copy(banner = EditorBanner.FileMissing) }
                }
            }

            is DocumentEvent.Moved -> {
                metas[event.from.value]?.let { metas[event.uri.value] = it }
                if (event.uri == activeUri) {
                    // Save as: the file is now the one on screen, so it is recent and reopens on launch.
                    active?.open?.let(::rememberRecent)
                    refresh()
                }
            }

            is DocumentEvent.JournalHealthChanged -> {
                if (event.uri == activeUri) onJournalHealth()
            }
        }
    }

    override fun onEdited() {
        refresh()
        strip.afterTyped()
        find.onTextChanged()
    }

    override fun onOutlineChanged(document: ActiveDocument) {
        if (document !== active) return
        updateBreadcrumb()
        navigator.onOutlineChanged()
    }

    override fun onSelectionChanged(
        start: Int,
        end: Int,
    ) = strip.onSelectionChanged(start, end)

    override fun onAnchorLineChanged(line: Int) = onPaneTopLine(Pane.Code, line)

    /**
     * [pane] scrolled so [line] is at its top. Only the leading pane moves
     * the anchor and the breadcrumb; in Split, with "Scroll panes together"
     * on, the other pane follows (ux-spec "Position continuity").
     */
    fun onPaneTopLine(
        pane: Pane,
        line: Int,
    ) {
        val linked = deps.settings.settings.value.scrollPanesTogether
        val follow = scrollLink.onTopLine(pane, ui.value.mode, linked) as? ScrollLink.Follow.Anchor ?: return
        anchorFlow.value = line
        updateBreadcrumb()
        when (follow.follower) {
            Pane.Preview -> preview.scrollTo(line)
            Pane.Code -> active?.surface?.scrollToLine(line)
            null -> Unit
        }
    }

    override fun onZoomChanged(
        percent: Int,
        pinching: Boolean,
    ) {
        onAction(EditorAction.ZoomChanged(Pane.Code, percent, pinching))
        if (pinching) return
        active
            ?.open
            ?.uri
            ?.value
            ?.let { uri -> updateMeta(uri) { it.copy(codeZoom = percent) } }
    }

    override fun onCopyTooLarge(chars: Int) = message(MessageKind.CopyTooLarge)

    override fun onShortcut(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
    ): Boolean {
        val action = Shortcuts.actionFor(keyCode, ctrl, shift) ?: return false
        onAction(action)
        return true
    }

    /** Moves the anchor (Navigator jumps, Preview scrolling) and scrolls the Code pane to it. */
    fun jumpToLine(line: Int) {
        anchorFlow.value = line
        active?.surface?.scrollToLine(line)
        preview.scrollTo(line)
        updateBreadcrumb()
    }

    /**
     * The breadcrumb and reading line for the anchor. Called for nearly
     * every frame of a fling, so it emits only when what is drawn changes
     * (performance review PERF-13): the crumbs are compared as headings,
     * and the fraction moves in steps of [READING_STEPS], about a pixel of
     * the reading line on a phone. An emission recomposes the whole screen.
     */
    private fun updateBreadcrumb() {
        val doc = active ?: return
        val line = anchorFlow.value
        // The breadcrumb appears only once the first heading has scrolled past the top (ux-spec "Where am I?").
        val crumbs = if (line == 0) emptyList() else doc.outline.breadcrumbAt(line)
        val total = doc.outline.lineCount.coerceAtLeast(1)
        val fraction = ((line.toLong() * READING_STEPS / total).toFloat() / READING_STEPS).coerceIn(0f, 1f)
        val current = ui.value
        if (crumbs == shownCrumbs && fraction == current.readingFraction && current.breadcrumb === shownTexts) return
        if (crumbs != shownCrumbs) {
            shownCrumbs = crumbs
            shownTexts = crumbs.map { it.text }
        }
        ui.update { it.copy(breadcrumb = shownTexts, readingFraction = fraction) }
    }

    private fun initialState(): EditorUiState {
        val settings = deps.settings.settings.value
        return EditorUiState(
            defaultCodeZoom = settings.defaultZoomCode,
            defaultPreviewZoom = settings.defaultZoomPreview,
            codeZoom = settings.defaultZoomCode,
            previewZoom = settings.defaultZoomPreview,
            codeWrap = settings.codeWrap,
        )
    }

    /** Recovery copies stopped (or came back): say so, never fail silently (robustness §1). */
    private fun onJournalHealth() {
        val healthy = active?.open?.journalHealthy ?: return
        ui.update {
            when {
                !healthy && it.banner == null -> it.copy(banner = EditorBanner.RecoveryCopiesStopped)
                healthy && it.banner == EditorBanner.RecoveryCopiesStopped -> it.copy(banner = null)
                else -> it
            }
        }
        refresh()
    }

    private companion object {
        /** The reading line moves in thousandths: a pixel or so on a phone, never once per line of a long file. */
        const val READING_STEPS = 1_000L

        /** Long enough for an `fsync` under load, short enough never to feel like a hang on leaving. */
        const val PAUSE_JOURNAL_WAIT_MILLIS = 300L
    }
}

/**
 * What another app (or our own share sheet) asked us to open.
 *
 * @property source the sending app's name, for "Read-only · from Gmail".
 * @property fromAnotherApp first open shows Preview; false for our own share sheet's "Open".
 */
data class LaunchRequest(
    val uri: String,
    val writable: Boolean,
    val source: String?,
    val fromAnotherApp: Boolean = true,
)
