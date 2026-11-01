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
    fun encode(message: HostMessage): String {
        val json = JSONObject().put("type", message.type)
        message.writeTo(json)
        return json.toString()
    }

    fun decode(raw: String): PageMessage? =
        try {
            val json = JSONObject(raw)
            decoders[json.optString("type")]?.invoke(json)
        } catch (_: JSONException) {
            null
        }

    private val decoders: Map<String, (JSONObject) -> PageMessage> =
        mapOf(
            "ready" to { j -> PageMessage.Ready(j.getInt("version")) },
            "rendered" to { j -> PageMessage.Rendered(j.getInt("rev"), j.getInt("sections"), j.getInt("millis")) },
            "topLine" to { j -> PageMessage.TopLine(j.getInt("line"), crumbs(j.getJSONArray("crumbs"))) },
            "sourceEdit" to { j -> PageMessage.SourceEdit(j.getInt("baseRev"), edits(j.getJSONArray("edits"))) },
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
            "resync" to { j -> PageMessage.Resync(j.optString("reason")) },
            "editRefused" to { j -> PageMessage.EditRefused(j.getInt("line")) },
            "editingChanged" to { j -> PageMessage.EditingChanged(j.getBoolean("editing")) },
            "taskToggle" to
                { j -> PageMessage.TaskToggle(j.getInt("baseRev"), j.getInt("offset"), j.getBoolean("checked")) },
            "linkTapped" to { j -> PageMessage.LinkTapped(j.getString("href"), j.getInt("line")) },
            "headingLongPress" to
                { j -> PageMessage.HeadingLongPress(j.getInt("line"), j.getInt("level"), j.getString("text")) },
            "editInCode" to { j -> PageMessage.EditInCode(j.getInt("line")) },
            "openViewer" to { j -> PageMessage.OpenViewer(kind(j), j.getInt("index"), j.getInt("line")) },
            "mediaLongPress" to { j -> PageMessage.MediaLongPress(kind(j), j.getInt("index"), j.getInt("line")) },
            "tableMenu" to { j -> PageMessage.TableMenu(j.getInt("line"), j.getInt("row"), j.getInt("column")) },
            "exportResult" to
                { j -> PageMessage.ExportResult(j.getInt("requestId"), j.getString("mime"), j.getString("data")) },
            "printReady" to { _ -> PageMessage.PrintReady },
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
            ),
        )
    }

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

    private fun strings(array: JSONArray): Set<String> = List(array.length()) { array.getString(it) }.toSet()

    private fun kind(json: JSONObject): MediaKind =
        when (json.getString("kind")) {
            "diagram" -> MediaKind.DIAGRAM
            "math" -> MediaKind.MATH
            "image" -> MediaKind.IMAGE
            else -> throw JSONException("Unknown media kind")
        }
}
