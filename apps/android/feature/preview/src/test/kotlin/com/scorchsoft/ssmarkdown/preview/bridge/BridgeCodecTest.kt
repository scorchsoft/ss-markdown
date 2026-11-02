package com.scorchsoft.ssmarkdown.preview.bridge

import com.scorchsoft.ssmarkdown.preview.FormattedCopy
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The wire format both ends agree on. The JSON strings below are exactly what
 * `web/src/protocol.ts` produces and expects; a change here is a protocol change.
 */
@RunWith(RobolectricTestRunner::class)
class BridgeCodecTest {
    private fun encoded(message: HostMessage): JSONObject = JSONObject(BridgeCodec.encode(message))

    @Test
    fun `load carries the text, revision and anchor`() {
        val json = encoded(HostMessage.Load(rev = 3, text = "# Hi\n", anchorLine = 12))
        assertEquals("load", json.getString("type"))
        assertEquals(3, json.getInt("rev"))
        assertEquals("# Hi\n", json.getString("text"))
        assertEquals(12, json.getInt("anchorLine"))
        assertTrue(!encoded(HostMessage.Load(1, "x")).has("anchorLine"))
    }

    @Test
    fun `edits travel as line ranges, never the whole document`() {
        val json = encoded(HostMessage.Edit(rev = 9, changes = listOf(LineChange(4, 5, listOf("a", "")))))
        val change = json.getJSONArray("changes").getJSONObject(0)
        assertEquals(4, change.getInt("fromLine"))
        assertEquals(5, change.getInt("toLine"))
        assertEquals(2, change.getJSONArray("lines").length())
        assertTrue(!json.has("text"))
    }

    @Test
    fun `edits carry character ops for rebasing, and format and select match the page's shapes`() {
        val json = encoded(HostMessage.Edit(3, listOf(LineChange(0, 1, listOf("# a"))), listOf(TextEdit(0, 0, "# "))))
        val op = json.getJSONArray("ops").getJSONObject(0)
        assertEquals(0, op.getInt("from"))
        assertEquals("# ", op.getString("insert"))
        assertTrue(!encoded(HostMessage.Edit(3, emptyList())).has("ops"))
        assertEquals(
            """{"type":"format","action":"codeBlock"}""",
            BridgeCodec.encode(HostMessage.Format(FormatAction.CODE_BLOCK)),
        )
        assertEquals("""{"type":"select","rev":4,"from":2,"to":6}""", BridgeCodec.encode(HostMessage.Select(4, 2, 6)))
    }

    @Test
    fun `theme embeds the theme JSON as an object`() {
        val json = encoded(HostMessage.Theme("""{"name":"Reader","base":"reader"}""", dark = true))
        assertEquals("Reader", json.getJSONObject("theme").getString("name"))
        assertTrue(json.getBoolean("dark"))
    }

    @Test
    fun `settings, table and export messages match the page's shapes`() {
        val settings =
            encoded(
                HostMessage.Settings(PreviewSettings(tapToEdit = false, zoom = 1.5f)),
            ).getJSONObject("settings")
        assertEquals(false, settings.getBoolean("tapToEdit"))
        assertEquals(1.5, settings.getDouble("zoom"), 0.0)
        assertEquals("next", encoded(HostMessage.TableNav(next = true)).getString("direction"))
        val op = encoded(HostMessage.Table(TableOp.Align(TableAlign.RIGHT))).getJSONObject("op")
        assertEquals("align", op.getString("kind"))
        assertEquals("right", op.getString("align"))
        assertEquals(1, encoded(HostMessage.Table(TableOp.MoveRow(5))).getJSONObject("op").getInt("by"))
        // The kinds the page's tableOpEdits handles (web/src/protocol.ts, TableOp).
        val kinds =
            listOf(
                TableOp.AddRow,
                TableOp.AddRowAbove,
                TableOp.AddColumn,
                TableOp.AddColumnLeft,
                TableOp.DeleteRow,
                TableOp.DeleteColumn,
                TableOp.DeleteTable,
            ).map { encoded(HostMessage.Table(it)).getJSONObject("op").getString("kind") }
        assertEquals(
            listOf("addRow", "addRowAbove", "addColumn", "addColumnLeft", "deleteRow", "deleteColumn", "deleteTable"),
            kinds,
        )
        val export = encoded(HostMessage.ExportImage(requestId = 2, line = 7, png = true, scale = 9))
        assertEquals("png", export.getString("format"))
        assertEquals(3, export.getInt("scale"))
        assertEquals("""{"type":"preparePrint","requestId":4}""", BridgeCodec.encode(HostMessage.PreparePrint(4)))
    }

    @Test
    fun `decodes every page message`() {
        assertEquals(PageMessage.Ready(PROTOCOL_VERSION), BridgeCodec.decode("""{"type":"ready","version":1}"""))
        assertEquals(
            PageMessage.SourceEdit(4, listOf(TextEdit(10, 12, "ab"), TextEdit(20, 20, "\\*"))),
            BridgeCodec.decode(
                """{"type":"sourceEdit","baseRev":4,"edits":""" +
                    """[{"from":10,"to":12,"insert":"ab"},{"from":20,"to":20,"insert":"\\*"}]}""",
            ),
        )
        assertEquals(
            PageMessage.TopLine(40, listOf(Crumb(1, "Intro", 0), Crumb(2, "Setup", 30))),
            BridgeCodec.decode(
                """{"type":"topLine","line":40,"crumbs":""" +
                    """[{"level":1,"text":"Intro","line":0},{"level":2,"text":"Setup","line":30}]}""",
            ),
        )
        // The reading fraction rides on the same report; junk is clamped or dropped, never thrown.
        assertEquals(
            PageMessage.TopLine(90, emptyList(), 1f),
            BridgeCodec.decode("""{"type":"topLine","line":90,"crumbs":[],"fraction":1}"""),
        )
        assertEquals(
            PageMessage.TopLine(0, emptyList(), 1f),
            BridgeCodec.decode("""{"type":"topLine","line":0,"crumbs":[],"fraction":7.5}"""),
        )
        assertEquals(
            PageMessage.TopLine(0, emptyList(), null),
            BridgeCodec.decode("""{"type":"topLine","line":0,"crumbs":[],"fraction":"x"}"""),
        )
        assertEquals(
            PageMessage.Selection(1, 5, setOf("bold", "link"), false, 7),
            BridgeCodec.decode(
                """{"type":"selection","from":1,"to":5,"marks":["bold","link"],"inTable":false,"baseRev":7}""",
            ),
        )
        assertEquals(
            PageMessage.FormatRequest(PreviewFormatRequest(FormatAction.HEADING_2, 4, 9, 6, true)),
            BridgeCodec.decode(
                """{"type":"formatRequest","action":"heading2","from":4,"to":9,"baseRev":6,"inTable":true}""",
            ),
        )
        assertEquals(PageMessage.Resync("gap"), BridgeCodec.decode("""{"type":"resync","reason":"gap"}"""))
        assertEquals(PageMessage.EditRefused(8), BridgeCodec.decode("""{"type":"editRefused","line":8}"""))
        assertEquals(
            PageMessage.TaskToggle(2, 3, true),
            BridgeCodec.decode("""{"type":"taskToggle","baseRev":2,"offset":3,"checked":true}"""),
        )
        assertEquals(
            PageMessage.OpenViewer(MediaKind.DIAGRAM, 0, 12),
            BridgeCodec.decode("""{"type":"openViewer","kind":"diagram","index":0,"line":12}"""),
        )
        assertEquals(PageMessage.PrintReady(4), BridgeCodec.decode("""{"type":"printReady","requestId":4}"""))
        assertEquals(
            PageMessage.TableMenu(3, 1, 0),
            BridgeCodec.decode("""{"type":"tableMenu","line":3,"row":1,"column":0}"""),
        )
    }

    @Test
    fun `a formatted copy carries its budget out and its HTML, plain text and truncation back`() {
        val ask = encoded(HostMessage.ExportHtml(4, 0, 12, 400_000))
        assertEquals(400_000, ask.getInt("maxChars"))
        val answer =
            BridgeCodec.decode(
                """{"type":"htmlResult","requestId":4,"html":"<p>x</p>","text":"x","truncated":true}""",
            ) as PageMessage.HtmlResult
        assertEquals(FormattedCopy("<p>x</p>", "x", truncated = true), answer.copy)
        val none =
            BridgeCodec.decode("""{"type":"htmlResult","requestId":5,"html":null,"text":null,"truncated":false}""")
                as PageMessage.HtmlResult
        assertNull(none.copy)
    }

    @Test
    fun `malformed or unknown messages decode to null instead of throwing`() {
        assertNull(BridgeCodec.decode("not json"))
        assertNull(BridgeCodec.decode("""{"type":"launchMissiles"}"""))
        assertNull(BridgeCodec.decode("""{"type":"sourceEdit","baseRev":"x"}"""))
        assertNull(BridgeCodec.decode("""{"type":"openViewer","kind":"video","index":0,"line":1}"""))
        assertNull(
            BridgeCodec.decode("""{"type":"formatRequest","action":"blink","from":0,"to":1,"baseRev":1}"""),
        )
    }
}
