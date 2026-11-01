package com.scorchsoft.ssmarkdown.preview.bridge

import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * The version of the message protocol between Kotlin and the preview page.
 * It must equal `PROTOCOL_VERSION` in `web/src/protocol.ts`; bump both for any
 * change that is not purely additive.
 */
const val PROTOCOL_VERSION: Int = 1

/** One replacement of whole lines: lines `[fromLine, toLine)` become [lines]. */
data class LineChange(
    val fromLine: Int,
    val toLine: Int,
    val lines: List<String>,
)

/** Preview behaviour that Kotlin owns (Settings, zoom, renderer health). */
data class PreviewSettings(
    val tapToEdit: Boolean = true,
    val pageMode: Boolean = false,
    val zoom: Float = 1f,
    val lean: Boolean = false,
    val readOnly: Boolean = false,
)

/** Table column alignment, as written in the delimiter row. */
enum class TableAlign(
    internal val wire: String,
) {
    LEFT("left"),
    CENTER("center"),
    RIGHT("right"),
    NONE("none"),
}

/** Table operations from the ⋯ sheet; the page applies them in the table's own style. */
sealed interface TableOp {
    /** Writes this operation's fields. */
    fun toJson(): JSONObject

    data object AddRow : TableOp {
        override fun toJson(): JSONObject = kind("addRow")
    }

    data object AddColumn : TableOp {
        override fun toJson(): JSONObject = kind("addColumn")
    }

    data object DeleteRow : TableOp {
        override fun toJson(): JSONObject = kind("deleteRow")
    }

    data object DeleteColumn : TableOp {
        override fun toJson(): JSONObject = kind("deleteColumn")
    }

    data class Align(
        val align: TableAlign,
    ) : TableOp {
        override fun toJson(): JSONObject = kind("align").put("align", align.wire)
    }

    /** Moves the current row by -1 (up) or +1 (down). */
    data class MoveRow(
        val by: Int,
    ) : TableOp {
        override fun toJson(): JSONObject = kind("moveRow").put("by", by.coerceIn(-1, 1))
    }

    /** Moves the current column by -1 (left) or +1 (right). */
    data class MoveColumn(
        val by: Int,
    ) : TableOp {
        override fun toJson(): JSONObject = kind("moveColumn").put("by", by.coerceIn(-1, 1))
    }
}

private fun kind(name: String): JSONObject = JSONObject().put("kind", name)

private fun editsJson(edits: List<TextEdit>): JSONArray =
    JSONArray().apply {
        for (e in edits) put(JSONObject().put("from", e.from).put("to", e.to).put("insert", e.insert))
    }

/**
 * Messages from Kotlin to the page. Each one knows its own wire form, so the
 * codec needs no central `when` that grows with every message.
 *
 * After the first [Load], the document only ever travels as [Edit]s of the
 * lines that changed (docs/robustness.md, "Preview (WebView)").
 */
sealed interface HostMessage {
    /** The `type` field the page dispatches on. */
    val type: String

    /** Adds this message's fields to [json]. */
    fun writeTo(json: JSONObject)

    /** Opens a document. [anchorLine] is where to land once it renders. */
    data class Load(
        val rev: Int,
        val text: String,
        val anchorLine: Int? = null,
    ) : HostMessage {
        override val type = "load"

        override fun writeTo(json: JSONObject) {
            json.put("rev", rev).put("text", text)
            anchorLine?.let { json.put("anchorLine", it) }
        }
    }

    /**
     * Line ranges that changed, applied in order; [rev] is the revision after
     * them. [ops] is the same change as character edits against revision
     * `rev - 1`, which the page needs to rebase typing still in flight; build
     * both with `PreviewEditSync.onHostEdits`.
     */
    data class Edit(
        val rev: Int,
        val changes: List<LineChange>,
        val ops: List<TextEdit>? = null,
    ) : HostMessage {
        override val type = "edit"

        override fun writeTo(json: JSONObject) {
            val array = JSONArray()
            for (c in changes) {
                array.put(
                    JSONObject()
                        .put("fromLine", c.fromLine)
                        .put("toLine", c.toLine)
                        .put("lines", JSONArray(c.lines)),
                )
            }
            json.put("rev", rev).put("changes", array)
            ops?.let { json.put("ops", editsJson(it)) }
        }
    }

    /** A strip key in Preview: the page answers with a format request for its selection. */
    data class Format(
        val action: FormatAction,
    ) : HostMessage {
        override val type = "format"

        override fun writeTo(json: JSONObject) {
            json.put("action", action.wire)
        }
    }

    /** Puts the page's selection at `[from, to)` in the text at revision [rev]. */
    data class Select(
        val rev: Int,
        val from: Int,
        val to: Int,
    ) : HostMessage {
        override val type = "select"

        override fun writeTo(json: JSONObject) {
            json.put("rev", rev).put("from", from).put("to", to)
        }
    }

    /** Confirms the page's edits were applied and the document is now at [rev]. */
    data class Ack(
        val rev: Int,
    ) : HostMessage {
        override val type = "ack"

        override fun writeTo(json: JSONObject) {
            json.put("rev", rev)
        }
    }

    /** A theme in the JSON shape of docs/ux-spec.md, "Themes". */
    data class Theme(
        val themeJson: String,
        val dark: Boolean,
    ) : HostMessage {
        override val type = "theme"

        override fun writeTo(json: JSONObject) {
            json.put("theme", JSONObject(themeJson)).put("dark", dark)
        }
    }

    data class Settings(
        val settings: PreviewSettings,
    ) : HostMessage {
        override val type = "settings"

        override fun writeTo(json: JSONObject) {
            json.put(
                "settings",
                JSONObject()
                    .put("tapToEdit", settings.tapToEdit)
                    .put("pageMode", settings.pageMode)
                    .put("zoom", settings.zoom.toDouble())
                    .put("lean", settings.lean)
                    .put("readOnly", settings.readOnly),
            )
        }
    }

    data class ScrollToLine(
        val line: Int,
        val smooth: Boolean = false,
    ) : HostMessage {
        override val type = "scrollToLine"

        override fun writeTo(json: JSONObject) {
            json.put("line", line).put("smooth", smooth)
        }
    }

    /** The strip's Prev / Next cell keys. */
    data class TableNav(
        val next: Boolean,
    ) : HostMessage {
        override val type = "tableNav"

        override fun writeTo(json: JSONObject) {
            json.put("direction", if (next) "next" else "prev")
        }
    }

    data class Table(
        val op: TableOp,
    ) : HostMessage {
        override val type = "tableOp"

        override fun writeTo(json: JSONObject) {
            json.put("op", op.toJson())
        }
    }

    /** The strip's Done key: close the keyboard and go back to reading. */
    data object EndEditing : HostMessage {
        override val type = "endEditing"

        override fun writeTo(json: JSONObject) = Unit
    }

    /** Asks for the diagram at [line] as SVG or a PNG at [scale] (1–3). */
    data class ExportImage(
        val requestId: Int,
        val line: Int,
        val png: Boolean,
        val scale: Int = 2,
    ) : HostMessage {
        override val type = "exportImage"

        override fun writeTo(json: JSONObject) {
            json
                .put("requestId", requestId)
                .put("line", line)
                .put("format", if (png) "png" else "svg")
                .put("scale", scale.coerceIn(MIN_SCALE, MAX_SCALE))
        }

        private companion object {
            const val MIN_SCALE = 1
            const val MAX_SCALE = 3
        }
    }

    /** Renders and mounts every block, then the page answers [PageMessage.PrintReady]. */
    data object PreparePrint : HostMessage {
        override val type = "preparePrint"

        override fun writeTo(json: JSONObject) = Unit
    }

    /** Returns the page to its virtualised reading state after printing. */
    data object FinishPrint : HostMessage {
        override val type = "finishPrint"

        override fun writeTo(json: JSONObject) = Unit
    }
}
