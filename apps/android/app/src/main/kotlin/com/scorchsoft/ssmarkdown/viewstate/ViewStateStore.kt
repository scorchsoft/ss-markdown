package com.scorchsoft.ssmarkdown.viewstate

import com.scorchsoft.ssmarkdown.editor.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * How one file was last looked at (research R7; ux-spec "Position
 * continuity"): its view mode, each pane's zoom, the selection and the
 * source line at the top. Restored when the file opens again, even after
 * the process has gone.
 *
 * @property anchorLine the source line at the top of the leading pane.
 */
data class FileViewState(
    val mode: ViewMode? = null,
    val codeZoom: Int? = null,
    val previewZoom: Int? = null,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val anchorLine: Int = 0,
)

/** Where [FileViewState]s live, by document URI. An interface so tests keep them in memory. */
interface ViewStateStore {
    /** What was remembered for [uri], or null. */
    fun read(uri: String): FileViewState?

    /** Remembers [state] for [uri], forgetting the oldest files past the cap. */
    fun write(
        uri: String,
        state: FileViewState,
    )

    /** Writes anything still waiting now, rather than after the debounce: the app is leaving. */
    fun flush() = Unit
}

/** Kept in memory only: tests and previews. */
class InMemoryViewStates : ViewStateStore {
    private val states = LinkedHashMap<String, FileViewState>()

    /** Calls to [write] that changed something: what a file-backed store would have had to persist. */
    var changes: Int = 0
        private set

    override fun read(uri: String): FileViewState? = states[uri]

    override fun write(
        uri: String,
        state: FileViewState,
    ) {
        if (states[uri] == state) return
        states.remove(uri)
        states[uri] = state
        changes++
    }
}

/**
 * View states in one small JSON file in app storage, newest last, capped
 * at [maxFiles]. Rewritten whole (it is a few KB), through a temp file
 * and a rename so a crash never leaves half a file. A damaged file is
 * simply forgotten: losing a scroll position is harmless, failing to open
 * the editor is not.
 *
 * No disk access ever happens on the caller's thread (performance review
 * PERF-B, PERF-C). The file is read ahead on [scope] when the store is
 * built, and [write] only updates memory and schedules one write on
 * [scope] [debounceMillis] later, however many writes arrive meanwhile:
 * a pinch or a burst of mode changes costs one file write, not one per
 * step. [flush] (the app leaving) writes at once, still on [scope].
 *
 * @param scope where the file is read and written: an I/O scope.
 */
class JsonViewStateStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val maxFiles: Int = MAX_FILES,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) : ViewStateStore {
    private val lock = Any()
    private val states: LinkedHashMap<String, FileViewState> by lazy(lock, ::load)
    private var pendingSave: Job? = null
    private val writes = AtomicInteger()

    /** Times the file was written: the PERF-B regression statistic. */
    val diskWrites: Int get() = writes.get()

    init {
        // Read ahead, off the caller's thread; a read before it lands waits for the lock, not the disk.
        scope.launch { synchronized(lock) { states } }
    }

    override fun read(uri: String): FileViewState? = synchronized(lock) { states[uri] }

    override fun write(
        uri: String,
        state: FileViewState,
    ) {
        synchronized(lock) {
            if (states[uri] == state) return
            states.remove(uri)
            states[uri] = state
            while (states.size > maxFiles) states.remove(states.keys.first())
        }
        schedule(debounceMillis)
    }

    override fun flush() {
        if (pendingSave?.isActive == true) schedule(0)
    }

    private fun schedule(delayMillis: Long) {
        pendingSave?.cancel()
        pendingSave =
            scope.launch {
                if (delayMillis > 0) delay(delayMillis)
                save()
            }
    }

    private fun load(): LinkedHashMap<String, FileViewState> {
        val result = LinkedHashMap<String, FileViewState>()
        try {
            if (!file.exists()) return result
            val json = JSONObject(file.readText())
            json.keys().forEach { uri -> json.optJSONObject(uri)?.let { result[uri] = ViewStateJson.decode(it) } }
        } catch (_: IOException) {
            result.clear()
        } catch (_: JSONException) {
            result.clear()
        }
        return result
    }

    private fun save() {
        val json = JSONObject()
        synchronized(lock) { states.forEach { (uri, state) -> json.put(uri, ViewStateJson.encode(state)) } }
        // One writer at a time: a cancelled save may still be mid-write when the next one starts.
        synchronized(file) {
            try {
                file.parentFile?.mkdirs()
                val temp = File(file.path + ".tmp")
                temp.writeText(json.toString())
                if (!temp.renameTo(file)) temp.delete()
                writes.incrementAndGet()
            } catch (_: IOException) {
                // Best effort: the next write tries again.
            }
        }
    }

    private companion object {
        const val MAX_FILES = 200

        /** Long enough to fold a pinch or a burst of taps into one write, short enough to land before a swipe-away. */
        const val DEBOUNCE_MILLIS = 500L
    }
}

/** The JSON shape of [FileViewState]. */
internal object ViewStateJson {
    fun encode(s: FileViewState): JSONObject =
        JSONObject()
            .put("mode", s.mode?.name)
            .put("codeZoom", s.codeZoom ?: 0)
            .put("previewZoom", s.previewZoom ?: 0)
            .put("selStart", s.selectionStart)
            .put("selEnd", s.selectionEnd)
            .put("anchor", s.anchorLine)

    fun decode(o: JSONObject): FileViewState =
        FileViewState(
            mode = ViewMode.entries.firstOrNull { it.name == o.optString("mode") },
            codeZoom = o.optInt("codeZoom").takeIf { it > 0 },
            previewZoom = o.optInt("previewZoom").takeIf { it > 0 },
            selectionStart = o.optInt("selStart").coerceAtLeast(0),
            selectionEnd = o.optInt("selEnd").coerceAtLeast(0),
            anchorLine = o.optInt("anchor").coerceAtLeast(0),
        )
}
