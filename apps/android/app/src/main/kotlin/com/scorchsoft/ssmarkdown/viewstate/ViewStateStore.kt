package com.scorchsoft.ssmarkdown.viewstate

import com.scorchsoft.ssmarkdown.editor.ViewMode
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
}

/** Kept in memory only: tests and previews. */
class InMemoryViewStates : ViewStateStore {
    private val states = LinkedHashMap<String, FileViewState>()

    override fun read(uri: String): FileViewState? = states[uri]

    override fun write(
        uri: String,
        state: FileViewState,
    ) {
        states.remove(uri)
        states[uri] = state
    }
}

/**
 * View states in one small JSON file in app storage, newest last, capped
 * at [maxFiles]. Read lazily on first use and rewritten whole (it is a
 * few KB), through a temp file and a rename so a crash never leaves half
 * a file. A damaged file is simply forgotten: losing a scroll position
 * is harmless, failing to open the editor is not.
 */
class JsonViewStateStore(
    private val file: File,
    private val maxFiles: Int = MAX_FILES,
) : ViewStateStore {
    private val states: LinkedHashMap<String, FileViewState> by lazy(::load)

    override fun read(uri: String): FileViewState? = states[uri]

    override fun write(
        uri: String,
        state: FileViewState,
    ) {
        if (states[uri] == state) return
        states.remove(uri)
        states[uri] = state
        while (states.size > maxFiles) states.remove(states.keys.first())
        save()
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
        states.forEach { (uri, state) -> json.put(uri, ViewStateJson.encode(state)) }
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: IOException) {
            // Best effort: the next write tries again.
        }
    }

    private companion object {
        const val MAX_FILES = 200
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
