package com.scorchsoft.ssmarkdown.preview.bridge

import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Encodes [HostMessage]s and decodes [PageMessage]s as the JSON strings that
 * travel over the WebMessagePort.
 *
 * Decoding never throws: a malformed or unknown message from the page yields
 * null, because a page bug must never crash the app.
 */
object BridgeCodec {
    /**
     * Above this many characters a [HostMessage.Load] travels as a JSON header
     * plus raw text frames of at most this size, never as one JSON string the
     * size of the document (PERF-12).
     */
    const val FRAME_CHARS: Int = 256 * 1024

    /** One message as one JSON string. A large [HostMessage.Load] must go through [frames] instead. */
    fun encode(message: HostMessage): String {
        val json = JSONObject().put("type", message.type)
        message.writeTo(json)
        return json.toString()
    }

    /**
     * The frames to post for [message], in order. Everything but a large
     * load is one JSON frame. A load whose text is longer than [frameChars]
     * is a header (`"chunks": n`, no text) followed by n raw text frames,
     * which the page joins without parsing: no quoted 40 MB copy is built
     * here and none is parsed there. A frame never ends between the two
     * halves of a surrogate pair, which a WebMessage could not carry.
     */
    fun frames(
        message: HostMessage,
        frameChars: Int = FRAME_CHARS,
    ): List<String> {
        if (message !is HostMessage.Load || message.text.length <= frameChars) return listOf(encode(message))
        val text = message.text
        val parts = ArrayList<String>()
        var at = 0
        while (at < text.length) {
            var end = minOf(text.length, at + frameChars)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            parts += text.subSequence(at, end).toString()
            at = end
        }
        val header = JSONObject().put("type", message.type).put("rev", message.rev).put("chunks", parts.size)
        message.anchorLine?.let { header.put("anchorLine", it) }
        if (message.epoch != 0) header.put("epoch", message.epoch)
        return listOf(header.toString()) + parts
    }

    fun decode(raw: String): PageMessage? =
        try {
            val json = JSONObject(raw)
            decoders[json.optString("type")]?.invoke(json)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null // An export whose base64 does not decode: dropped here, never thrown on the main thread.
        }

    /** The reading fraction, clamped to 0..1; null when the page did not send one or sent junk. */
    private fun fraction(j: JSONObject): Float? {
        if (!j.has("fraction")) return null
        val value = j.optDouble("fraction", Double.NaN)
        return if (value.isNaN()) null else value.toFloat().coerceIn(0f, 1f)
    }

    /** How much of the page shows, clamped to 0..1; null when absent or junk. */
    private fun visible(j: JSONObject): Float? {
        if (!j.has("visible")) return null
        val value = j.optDouble("visible", Double.NaN)
        return if (value.isNaN()) null else value.toFloat().coerceIn(0f, 1f)
    }

    private val decoders: Map<String, (JSONObject) -> PageMessage> =
        mapOf(
            "ready" to { j -> PageMessage.Ready(j.getInt("version")) },
            "rendered" to { j -> PageMessage.Rendered(j.getInt("rev"), j.getInt("sections"), j.getInt("millis")) },
            "topLine" to { j ->
                PageMessage.TopLine(j.getInt("line"), crumbs(j.getJSONArray("crumbs")), fraction(j), visible(j))
            },
            "sourceEdit" to
                { j -> PageMessage.SourceEdit(j.getInt("baseRev"), edits(j.getJSONArray("edits")), epoch(j)) },
            "selection" to { j ->
                PageMessage.Selection(
                    j.getInt("from"),
                    j.getInt("to"),
                    strings(j.getJSONArray("marks")),
                    j.getBoolean("inTable"),
                    j.optInt("baseRev", 0),
                )
            },
            "formatRequest" to { j -> formatRequest(j) },
            "resync" to { j -> PageMessage.Resync(j.optString("reason"), epoch(j)) },
            "editRefused" to { j -> PageMessage.EditRefused(j.getInt("line")) },
            "editingChanged" to { j -> PageMessage.EditingChanged(j.getBoolean("editing")) },
            "taskToggle" to
                { j ->
                    PageMessage.TaskToggle(j.getInt("baseRev"), j.getInt("offset"), j.getBoolean("checked"), epoch(j))
                },
            "linkTapped" to { j -> PageMessage.LinkTapped(j.getString("href"), j.getInt("line")) },
            "headingLongPress" to
                { j -> PageMessage.HeadingLongPress(j.getInt("line"), j.getInt("level"), j.getString("text")) },
            "editInCode" to { j -> PageMessage.EditInCode(j.getInt("line")) },
            "openViewer" to { j -> PageMessage.OpenViewer(kind(j), j.getInt("index"), j.getInt("line")) },
            "mediaLongPress" to { j -> PageMessage.MediaLongPress(kind(j), j.getInt("index"), j.getInt("line")) },
            "tableMenu" to { j -> PageMessage.TableMenu(j.getInt("line"), j.getInt("row"), j.getInt("column")) },
            "exportResult" to
                { j -> PageMessage.ExportResult.of(j.getInt("requestId"), j.getString("mime"), j.getString("data")) },
            "printReady" to { j -> PageMessage.PrintReady(j.getInt("requestId")) },
            "htmlResult" to { j ->
                PageMessage.HtmlResult(
                    j.getInt("requestId"),
                    if (j.isNull("html")) null else j.getString("html"),
                    if (j.isNull("text")) null else j.optString("text"),
                    j.optBoolean("truncated", false),
                )
            },
            "imageQuery" to { j ->
                // At most [MAX_IMAGE_PATHS] short keys: a hostile page cannot make the host resolve thousands.
                val paths = j.getJSONArray("paths")
                val keys = List(minOf(paths.length(), MAX_IMAGE_PATHS)) { paths.getString(it) }
                PageMessage.ImageQuery(j.getInt("id"), keys.filter { it.length <= MAX_IMAGE_PATH_CHARS })
            },
            "allowFolder" to { _ -> PageMessage.AllowFolder },
            "error" to { j -> PageMessage.Error(j.getString("message")) },
        )

    /** An action this app does not know decodes as no message, never as a guess. */
    private fun formatRequest(j: JSONObject): PageMessage {
        val action = FormatAction.fromWire(j.getString("action")) ?: throw JSONException("unknown format action")
        return PageMessage.FormatRequest(
            PreviewFormatRequest(
                action,
                j.getInt("from"),
                j.getInt("to"),
                j.getInt("baseRev"),
                j.optBoolean("inTable"),
                epoch(j),
            ),
        )
    }

    /** The load a message was made in; [PageMessage.UNSTAMPED] from a page that does not say. */
    private fun epoch(j: JSONObject): Int = j.optInt("epoch", PageMessage.UNSTAMPED)

    private fun crumbs(array: JSONArray): List<Crumb> =
        List(array.length()) { i ->
            val c = array.getJSONObject(i)
            Crumb(c.getInt("level"), c.getString("text"), c.getInt("line"))
        }

    private fun edits(array: JSONArray): List<TextEdit> =
        List(array.length()) { i ->
            val e = array.getJSONObject(i)
            TextEdit(e.getInt("from"), e.getInt("to"), e.getString("insert"))
        }

    private const val MAX_IMAGE_PATHS = 200
    private const val MAX_IMAGE_PATH_CHARS = 1024

    private fun strings(array: JSONArray): Set<String> = List(array.length()) { array.getString(it) }.toSet()

    private fun kind(json: JSONObject): MediaKind =
        when (json.getString("kind")) {
            "diagram" -> MediaKind.DIAGRAM
            "math" -> MediaKind.MATH
            "image" -> MediaKind.IMAGE
            else -> throw JSONException("Unknown media kind")
        }
}
