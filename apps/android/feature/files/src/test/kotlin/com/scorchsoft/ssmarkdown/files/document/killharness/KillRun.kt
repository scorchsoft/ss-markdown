package com.scorchsoft.ssmarkdown.files.document.killharness

import com.scorchsoft.ssmarkdown.files.document.CheckReason
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.document.OpenDocument
import com.scorchsoft.ssmarkdown.files.document.OpenOutcome
import com.scorchsoft.ssmarkdown.files.document.OutsideChange
import com.scorchsoft.ssmarkdown.files.document.Recovery
import com.scorchsoft.ssmarkdown.files.document.ResolveOutcome
import com.scorchsoft.ssmarkdown.files.fakes.FakeDocumentProvider
import com.scorchsoft.ssmarkdown.files.fakes.FakeMemorySource
import com.scorchsoft.ssmarkdown.files.fakes.InterleavingDispatcher
import com.scorchsoft.ssmarkdown.files.memory.MemoryGovernor
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.history.journal.IdleFlushPolicy
import com.scorchsoft.ssmarkdown.history.journal.JournalClock
import com.scorchsoft.ssmarkdown.history.journal.JournalStore
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.HistoryListing
import com.scorchsoft.ssmarkdown.history.versions.ReadResult
import com.scorchsoft.ssmarkdown.history.versions.VersionClock
import com.scorchsoft.ssmarkdown.history.versions.VersionStore
import com.scorchsoft.ssmarkdown.text.DecodeResult
import com.scorchsoft.ssmarkdown.text.LineEnding
import com.scorchsoft.ssmarkdown.text.LineEndings
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextFileFormat
import com.scorchsoft.ssmarkdown.text.Utf8Decoder
import com.scorchsoft.ssmarkdown.text.Utf8Encoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.random.Random

/** What one run came to; [failure] is null when every invariant held. */
internal data class RunResult(
    val seed: Long,
    val killedAt: String,
    val lossMillis: Long,
    val lostEdits: Int,
    val events: Int,
    val actions: Int,
    val tornFile: Boolean,
    val tornJournal: Boolean,
    val outsideAfterKill: Boolean,
    val failure: String?,
)

/**
 * One randomised life of a document, killed once at a random point and
 * recovered by a fresh store over the same storage (docs/robustness.md
 * Part 3, the kill test on the host).
 *
 * Everything real except the file provider and the clock: the
 * [DocumentStore], the [JournalStore] on real files, the save pipeline,
 * the base copies and the [VersionStore]. The provider is the
 * fault-injecting [FakeDocumentProvider], whose writes land byte by byte,
 * so a kill part way through a save really leaves a torn file.
 *
 * The run is executed twice from the same seed: a dry run counts the
 * points where the process could die (every main-thread task, every
 * journal-thread task, every disk hop and every provider write), then the
 * real run dies at one of them, chosen uniformly. A kill inside a write
 * picks a byte; a kill right after a journal write may tear that write at
 * a random byte, as a dying `write(2)` or a power cut can.
 */
@Suppress("LargeClass", "TooManyFunctions") // One scenario, its model and its checks; split, it reads worse.
internal class KillRun(
    private val seed: Long,
    private val root: File,
    private val killAtEvent: Int?,
) {
    private val rnd = Random(seed)
    private val killRnd = Random(seed * 7_919 + 13)
    private val clock = ManualClock()
    private val provider = FakeDocumentProvider()
    private val trail = ArrayList<String>()

    /** Every text the document has had, with the model time it appeared. */
    private val states = ArrayList<Pair<Long, String>>()

    /** The independent model of typed edits: checked against the buffer after each one. */
    private var model = StringBuilder()
    private var format = TextFileFormat.DEFAULT

    private var current = DocumentUri("content://docs/note.md")
    private val uris = LinkedHashSet<DocumentUri>()

    /** Per file: the whole contents it may hold after recovery. */
    private val allowed = HashMap<DocumentUri, MutableSet<String>>()

    /** Outside edits the store has not yet seen and answered; overwriting one is the bug. */
    private val unacknowledged = HashMap<DocumentUri, ByteArray>()
    private var outsideCount = 0
    private var violation: String? = null

    // ---- The process. ----
    private var events = 0
    private var dead = false
    private var killDescription = "never"
    private var killAtByte: Int? = null
    private var bytesThisWrite = 0
    private var lastJournalGrowth: Triple<File, Long, Long>? = null
    private var tornFile = false
    private var tornJournal = false
    private var recovering = false
    private var actionStates = 0
    private var actionFormat = TextFileFormat.DEFAULT
    private lateinit var session: Session

    private inner class Session {
        val main = ManualMain()
        val thread = ManualJournalThread(clock)
        val io = InterleavingDispatcher()
        val journals = JournalStore(File(root, "journal"), thread, JournalClock { clock.now }, IdleFlushPolicy())
        val versions = VersionStore(File(root, "history"), VersionClock { clock.now })
        val store =
            DocumentStore(
                provider,
                journals,
                BaseCopyStore(File(root, "base")),
                MemoryGovernor(FakeMemorySource()),
                io,
                InlineVersionHook(versions),
                journalThread = thread,
            )
        val scope = CoroutineScope(main + Job())
    }

    /** Runs the scenario; returns the result, or the event count when [killAtEvent] is null (the dry run). */
    fun execute(): RunResult {
        setUp()
        session = Session()
        wireHooks()
        val actions = ACTIONS_MIN + rnd.nextInt(ACTIONS_SPAN)
        var done = 0
        try {
            act("open") { opened() }
            while (done < actions) {
                step()
                done++
            }
            killDescription = "after the last action"
            die()
        } catch (_: Killed) {
            // The process is gone; whatever it left on disk is what the next launch finds.
        }
        if (killAtEvent == null) return result(done, failure = null, loss = 0, lost = 0)
        val killTime = clock.now
        afterDeath()
        return recoverAndCheck(done, killTime)
    }

    // ---- Set-up. ----

    private fun setUp() {
        val lines = 3 + rnd.nextInt(12)
        val text = (0 until lines).joinToString("\n") { "line $it ${word()}" } + if (rnd.nextBoolean()) "\n" else ""
        val endings = if (rnd.nextInt(4) == 0) LineEnding.CRLF else LineEnding.LF
        val bom = rnd.nextInt(6) == 0
        val bytes = encode(text, TextFileFormat(LineEndings.Uniform(endings), bom))
        provider.put(current, bytes)
        uris += current
        allowed[current] = mutableSetOf(hex(bytes))
        model = StringBuilder(text)
        states += clock.now to text
    }

    private fun wireHooks() {
        provider.onOpenOutput = { uri ->
            unacknowledged[uri]?.let { theirs ->
                if (provider.bytes(uri).contentEquals(theirs) && violation == null) {
                    violation = "an outside edit to $uri was overwritten (${trail.takeLast(TRAIL)})"
                }
            }
            event("write-open $uri")
            bytesThisWrite = 0
        }
        provider.onWriteByte = {
            val at = killAtByte
            if (at != null && bytesThisWrite >= at) {
                tornFile = true
                killDescription += " at byte $bytesThisWrite"
                dead = true
                throw Killed()
            }
            bytesThisWrite++
        }
        session.io.afterEveryHop = { hop ->
            if (!dead) {
                event("hop $hop")
                if (!dead && !recovering && rnd.nextInt(HOP_TYPING_ODDS) == 0) typeAtHop()
            }
        }
        session.thread.afterTask = { if (!dead) event("journal task") }
    }

    // ---- The process dies at one event. ----

    private fun event(what: String) {
        events++
        if (events != killAtEvent || dead) return
        killDescription = "$what (event $events)"
        if (what.startsWith("write-open")) {
            // Somewhere in this write: a byte, or just after the last one.
            killAtByte = killRnd.nextInt(0, (model.length * 2).coerceAtLeast(1) + 2)
            return
        }
        dead = true
    }

    private fun die() {
        dead = true
        throw Killed()
    }

    private fun checkAlive() {
        if (dead) throw Killed()
    }

    /** Before each journal task: which journal file it grows, so a kill right after can tear that write. */
    private fun journalLengths(): Map<File, Pair<Any?, Long>> =
        File(root, "journal")
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .associateWith { inode(it) to it.length() }

    private fun afterDeath() {
        // A write the process died in may have completed: its bytes are whole contents too.
        val inFlight = candidatesSince(actionStates - 1, actionFormat)
        uris.forEach { allowed.getValue(it) += inFlight }
        if (killDescription.startsWith("journal task") && killRnd.nextInt(3) != 0) tearLastJournalWrite()
        if (killRnd.nextInt(5) == 0) leaveHalfWrittenTemporaries()
        if (killRnd.nextInt(OUTSIDE_AFTER_KILL_ODDS) == 0 && provider.exists(current)) outsideEditAfterKill()
    }

    /** The process died inside the journal's last `write`: only a prefix of it reached the disk. */
    private fun tearLastJournalWrite() {
        val (file, from, to) = lastJournalGrowth ?: return
        if (to <= from || !file.exists()) return
        val cut = from + killRnd.nextLong(to - from)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(cut)
            // A power cut may leave the rest zero-filled rather than missing.
            if (killRnd.nextBoolean()) raf.setLength(to)
        }
        tornJournal = true
    }

    /** Another app writes the file while we are dead: the next launch must not overwrite it. */
    private fun outsideEditAfterKill() {
        val theirs = outsideBytes()
        provider.externalWrite(current, theirs)
        unacknowledged[current] = theirs
        allowed[current] = mutableSetOf(hex(theirs))
        // A clean document reopens as their text; that is a state, not a loss.
        states += clock.now to requireNotNull(decode(theirs))
        trail += "outside edit after the kill"
    }

    /** What a kill in the middle of an atomic replace leaves: a partial `.new` beside the real file. */
    private fun leaveHalfWrittenTemporaries() {
        val junk = ByteArray(killRnd.nextInt(1, 64)) { killRnd.nextInt().toByte() }
        File(root, "journal").listFiles().orEmpty().filter { it.isFile }.forEach {
            File(it.path + ".new").writeBytes(junk)
        }
        File(root, "base").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".base") }.forEach {
            File(it.path + ".new").writeBytes(junk)
        }
        File(root, "history").walkTopDown().filter { it.name == "index.ssv" }.forEach {
            File(it.path + ".new").writeBytes(junk)
        }
    }

    // ---- Driving the main thread and the journal thread. ----

    /** Runs [block] as one main-thread action, interleaving the journal thread, until it ends or the process dies. */
    private fun act(
        name: String,
        block: suspend () -> Unit,
    ) {
        checkAlive()
        trail += name
        var finished = false
        val before = HashMap(allowed.mapValues { it.value.toSet() })
        val startFormat = format
        val statesBefore = states.size
        actionStates = statesBefore
        actionFormat = startFormat
        session.scope.launch {
            try {
                block()
            } catch (_: Killed) {
                dead = true
            }
            finished = true
        }
        while (!finished) {
            checkAlive()
            val preferThread = !session.thread.isEmpty && rnd.nextInt(THREAD_FIRST_ODDS) == 0
            val ran = if (preferThread) runJournalTask() || runMain() else runMain() || runJournalTask()
            checkAlive()
            check(ran) { "action $name is stuck (seed $seed)" }
        }
        checkAlive()
        // A fault armed for a write that never came must not hit a later one (or recovery's restore).
        provider.failNextWriteAfter = null
        provider.failNextClose = false
        provider.corruptNextWrite = false
        settleDisk(before, startFormat, statesBefore)
        if (violation != null) throw Killed()
    }

    private fun runMain(): Boolean {
        val ran = session.main.runOne()
        if (ran && !dead) event("main task")
        return ran
    }

    private fun runJournalTask(): Boolean {
        if (session.thread.isEmpty) return false
        val before = journalLengths()
        session.thread.runOne()
        lastJournalGrowth =
            journalLengths().entries.firstNotNullOfOrNull { (file, now) ->
                val was = before[file]
                if (was != null && was.first == now.first && now.second > was.second) {
                    Triple(file, was.second, now.second)
                } else {
                    null
                }
            }
        return true
    }

    /** Model time passes: the journal thread keeps up, and each flush timer fires at its own time. */
    private fun advance(millis: Long) {
        val target = clock.now + millis
        while (true) {
            while (runJournalTask()) checkAlive()
            val timer = session.thread.takeDueTimer(target) ?: break
            session.thread.execute(timer)
        }
        clock.now = target
    }

    /**
     * After an action that completed: each file must hold whole contents,
     * the old or one the action meant to write, never a mixture.
     */
    private fun settleDisk(
        before: Map<DocumentUri, Set<String>>,
        startFormat: TextFileFormat,
        statesBefore: Int,
    ) {
        val candidates = candidatesSince(statesBefore - 1, startFormat)
        for (uri in uris) {
            if (!provider.exists(uri)) continue
            val now = hex(provider.bytes(uri))
            val ok = now in (before[uri] ?: emptySet()) || now in candidates || now in allowed.getValue(uri)
            if (!ok && violation == null) violation = "$uri holds bytes nobody wrote whole after ${trail.last()}"
            allowed[uri] = mutableSetOf(now)
        }
    }

    private fun candidatesSince(
        from: Int,
        startFormat: TextFileFormat,
    ): Set<String> =
        states
            .drop(from.coerceAtLeast(0))
            .flatMap { (_, text) -> listOf(hex(encode(text, startFormat)), hex(encode(text, format))) }
            .toSet()

    // ---- The scenario. ----

    private fun doc(): OpenDocument? = session.store[current]

    private suspend fun opened() {
        val outcome = session.store.open(current)
        val doc = (outcome as OpenOutcome.Opened).document
        // Opened clean, the store has read the disk; opened in conflict, it must still ask before writing.
        if (doc.conflict == null) unacknowledged.remove(current)
        format = doc.document.format
        syncModel(doc)
    }

    private fun syncModel(doc: OpenDocument) {
        val live = doc.document.text.toString()
        model = StringBuilder(live)
        format = doc.document.format
        if (states.last().second != live) states += clock.now to live
    }

    private fun recordEdit(doc: OpenDocument) {
        val live = doc.document.text.toString()
        check(live == model.toString()) { "buffer and model diverged (seed $seed): ${trail.takeLast(TRAIL)}" }
        format = doc.document.format
        states += clock.now to live
    }

    @Suppress("CyclomaticComplexMethod") // A weighted menu of actions.
    private fun step() {
        val doc = doc()
        if (doc == null) {
            act("reopen") { opened() }
            return
        }
        if (doc.conflict != null && rnd.nextInt(2) == 0) {
            resolve()
            return
        }
        when (rnd.nextInt(TOTAL_WEIGHT)) {
            in 0 until 50 -> {
                act("type") { type(doc, word(1 + rnd.nextInt(3))) }
            }

            in 50 until 54 -> {
                act("paste") { type(doc, paragraph()) }
            }

            in 54 until 62 -> {
                act("delete") { delete(doc) }
            }

            in 62 until 66 -> {
                act("undo") { doc.undo()?.let { syncAfterUndo(doc) } }
            }

            in 66 until 68 -> {
                act("redo") { doc.redo()?.let { syncAfterUndo(doc) } }
            }

            in 68 until 72 -> {
                act("pause") { session.store.flushJournals() }
            }

            in 72 until 78 -> {
                advance(AUTOSAVE_DELAY)
                maybeFault()
                act("autosave") { session.store.save(current) }
            }

            in 78 until 84 -> {
                maybeFault()
                act("save") { session.store.save(current) }
            }

            in 84 until 86 -> {
                saveAs()
            }

            in 86 until 90 -> {
                outsideEdit()
            }

            in 90 until 92 -> {
                act("don't save") { dontSave() }
            }

            else -> {
                advance(IDLE_MIN + rnd.nextLong(IDLE_SPAN))
            }
        }
        advance(GAP_MIN + rnd.nextLong(GAP_SPAN))
    }

    private fun type(
        doc: OpenDocument,
        text: String,
    ) {
        val at = boundary(doc.document.text.length)
        doc.replace(at, 0, text)
        model.insert(at, text)
        recordEdit(doc)
    }

    private fun delete(doc: OpenDocument) {
        val length = doc.document.text.length
        if (length == 0) return
        val from = boundary(length)
        val to = boundary(length).coerceAtLeast(from).coerceAtMost(from + DELETE_MAX)
        val end = snap(to)
        if (end <= from) return
        doc.replace(from, end - from, "")
        model.delete(from, end)
        recordEdit(doc)
    }

    private fun syncAfterUndo(doc: OpenDocument) {
        model = StringBuilder(doc.document.text.toString())
        recordEdit(doc)
    }

    /** A keystroke that lands while the store waits on the disk (the never-lose-work races). */
    private fun typeAtHop() {
        val doc = doc() ?: return
        if (doc.readOnly != null) return
        // The store may have swapped the buffer in this action (a reload or a resolution): that is a state too.
        if (doc.document.text.toString() != model.toString()) syncModel(doc)
        type(doc, word(1))
    }

    private fun maybeFault() {
        when (rnd.nextInt(FAULT_ODDS)) {
            0 -> provider.failNextWriteAfter = rnd.nextInt(model.length + 1)
            1 -> provider.failNextClose = true
            2 -> provider.corruptNextWrite = true
            else -> Unit
        }
    }

    private fun saveAs() {
        val target = DocumentUri("content://docs/copy-${uris.size}.md")
        provider.put(target, ByteArray(0)) // What the system picker leaves for "Save as…".
        uris += target
        allowed[target] = mutableSetOf(hex(ByteArray(0)))
        act("save as") {
            session.store.saveAs(current, target)
            if (session.store[target] != null) current = target
        }
    }

    private fun outsideEdit() {
        if (!provider.exists(current)) return
        val theirs = outsideBytes()
        provider.externalWrite(current, theirs)
        unacknowledged[current] = theirs
        allowed.getValue(current) += hex(theirs)
        // If the document is clean, their text is what it holds from now on, reloaded or not yet.
        if (doc()?.isDirty == false) states += clock.now to decode(theirs)!!
        act("outside edit, then the periodic check") {
            when (session.store.checkOutside(current, CheckReason.Periodic)) {
                OutsideChange.Reloaded -> {
                    unacknowledged.remove(current)
                    doc()?.let(::syncModel)
                }

                OutsideChange.Conflicted -> {
                    Unit
                }

                // A same-size change with an unchanged time can slip the cheap check; the save's hash catches it.
                else -> {
                    Unit
                }
            }
        }
    }

    private fun resolve() {
        val choice = rnd.nextInt(3)
        act(listOf("use mine", "keep theirs", "clean merge")[choice]) {
            val outcome =
                when (choice) {
                    0 -> session.store.useMine(current)
                    1 -> session.store.keepTheirs(current)
                    else -> session.store.useCleanMerge(current)
                }
            if (outcome is ResolveOutcome.Resolved) {
                unacknowledged.remove(current)
                doc()?.let(::syncModel)
            }
        }
    }

    private suspend fun dontSave() {
        session.store.close(current, discardUnsaved = true)
        if (session.store[current] == null) {
            val disk = provider.bytes(current)
            val text = decode(disk) ?: return
            model = StringBuilder(text)
            if (states.last().second != text) states += clock.now to text
        }
    }

    // ---- Recovery and the invariants. ----

    private fun recoverAndCheck(
        actions: Int,
        killTime: Long,
    ): RunResult {
        violation?.let { return result(actions, it, 0, 0) }
        dead = false
        recovering = true
        killAtByte = null
        session = Session()
        wireHooks()
        val (recovered, reopenFailure) = relaunch()
        session.thread.drain(0)
        var failure = reopenFailure ?: violation ?: checkDisk() ?: checkHistory()
        val best = recovered.maxOfOrNull { text -> states.indexOfLast { it.second == text } } ?: -1
        if (failure == null && best < 0) failure = "recovered text matches no state the document ever had"
        val lost = if (best < 0) 0 else states.size - 1 - best
        val loss = if (best < 0 || lost == 0) 0 else killTime - states[best + 1].first
        return result(actions, failure, loss, lost)
    }

    /** The next launch: the real scan, then every offered document and the one that was open. */
    private fun relaunch(): Pair<List<String>, String?> {
        val recovered = ArrayList<String>()
        var failure: String? = null
        var finished = false
        session.scope.launch {
            val scan = session.store.scanForRecovery()
            val toOpen = LinkedHashSet<DocumentUri>()
            toOpen += scan.offers.map { it.uri }
            toOpen += current
            for (uri in toOpen.filter(provider::exists)) {
                val outcome = session.store.open(uri)
                if (outcome !is OpenOutcome.Opened) {
                    failure = "could not reopen $uri: $outcome"
                    continue
                }
                if (outcome.recovery is Recovery.Kept) failure = "recovery kept $uri aside: ${outcome.recovery}"
                recovered +=
                    outcome.document.document.text
                        .toString()
            }
            finished = true
        }
        while (!finished) check(session.main.runOne() || session.thread.runOne()) { "recovery stuck" }
        return recovered to failure
    }

    private fun checkDisk(): String? {
        for (uri in uris) {
            if (!provider.exists(uri)) continue
            val now = hex(provider.bytes(uri))
            if (now !in allowed.getValue(uri)) return "$uri is torn after recovery (killed at $killDescription)"
        }
        return null
    }

    private fun checkHistory(): String? =
        uris.firstNotNullOfOrNull { uri ->
            val key = DocumentKey(uri.value)
            when (val listing = session.versions.list(key)) {
                is HistoryListing.Listed -> {
                    listing.versions.firstNotNullOfOrNull { version ->
                        val read = session.versions.read(key, version.id)
                        if (read is ReadResult.Text) null else "history of $uri: version ${version.id} reads $read"
                    }
                }

                HistoryListing.Empty -> {
                    null
                }

                else -> {
                    "history of $uri is $listing"
                }
            }
        }

    private fun result(
        actions: Int,
        failure: String?,
        loss: Long,
        lost: Int,
    ) = RunResult(
        seed = seed,
        killedAt = killDescription,
        lossMillis = loss,
        lostEdits = lost,
        events = events,
        actions = actions,
        tornFile = tornFile,
        tornJournal = tornJournal,
        outsideAfterKill = trail.lastOrNull() == "outside edit after the kill",
        failure = failure?.let { "$it\n  trail: ${trail.takeLast(TRAIL)}" },
    )

    // ---- Text helpers. ----

    private fun word(count: Int = 1): String =
        (0 until count).joinToString(" ") {
            val pick = rnd.nextInt(WORDS.size + 2)
            when (pick) {
                WORDS.size -> "\n"
                WORDS.size + 1 -> "😀"
                else -> WORDS[pick]
            }
        } + " "

    private fun paragraph(): String =
        (0 until 2 + rnd.nextInt(PASTE_LINES)).joinToString("\n", postfix = "\n") { word(4) }

    private fun outsideBytes(): ByteArray {
        outsideCount++
        val text = "~~outside-$seed-$outsideCount~~\n" + (0 until 2 + rnd.nextInt(4)).joinToString("\n") { word(3) }
        return encode(text, TextFileFormat.DEFAULT)
    }

    /** A random offset that never splits a surrogate pair. */
    private fun boundary(length: Int): Int = snap(rnd.nextInt(length + 1))

    private fun snap(offset: Int): Int {
        val text = model
        return if (offset in 1 until text.length && text[offset].isLowSurrogate()) offset - 1 else offset
    }

    private fun inode(file: File): Any? =
        runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey() }.getOrNull()

    private companion object {
        const val ACTIONS_MIN = 40
        const val ACTIONS_SPAN = 80
        const val TOTAL_WEIGHT = 96
        const val HOP_TYPING_ODDS = 4
        const val THREAD_FIRST_ODDS = 3
        const val FAULT_ODDS = 25
        const val OUTSIDE_AFTER_KILL_ODDS = 8
        const val AUTOSAVE_DELAY = 2_000L
        const val GAP_MIN = 40L
        const val GAP_SPAN = 360L
        const val IDLE_MIN = 1_200L
        const val IDLE_SPAN = 3_000L
        const val DELETE_MAX = 12
        const val PASTE_LINES = 6
        const val TRAIL = 8
        val WORDS = listOf("alpha", "beta", "gamma", "délta", "# head", "- item", "`code`", "**b**", "x", "ünï")

        fun encode(
            text: String,
            format: TextFileFormat,
        ): ByteArray = Utf8Encoder.encode(PieceTree.of(text).snapshot(), format)

        fun decode(bytes: ByteArray): String? = (Utf8Decoder.decode(bytes) as? DecodeResult.Decoded)?.text?.toString()

        fun hex(bytes: ByteArray): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) } + ":" + bytes.size
    }
}
