package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.LineChange
import com.scorchsoft.ssmarkdown.preview.bridge.PROTOCOL_VERSION
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewSettings
import com.scorchsoft.ssmarkdown.preview.bridge.TableAlign
import com.scorchsoft.ssmarkdown.preview.bridge.TableOp
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import com.scorchsoft.ssmarkdown.preview.bridge.ThemeFonts
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.preview.editing.TextTransform
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The golden vectors in `web/test/vectors/`, which the page's Vitest suite
 * runs too (CC-20): the two halves of the bridge and of the edit protocol
 * are written once per language, and these prove they agree.
 */
@RunWith(RobolectricTestRunner::class)
class SharedVectorsTest {
    private val vectors = File("web/test/vectors")

    private fun json(name: String) = JSONObject(File(vectors, name).readText())

    @Test
    fun `the protocol version matches protocol ts`() {
        val ts = File("web/src/protocol.ts").readText()
        val declared =
            Regex("""export const PROTOCOL_VERSION = (\d+);""")
                .find(ts)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        assertEquals(PROTOCOL_VERSION, declared)
        assertEquals(PROTOCOL_VERSION, json("messages.json").getInt("protocolVersion"))
    }

    @Test
    fun `transform and mapOffset give the answers the page's implementation recorded`() {
        val ot = json("ot.json")
        val cases = ot.getJSONArray("transform")
        assertTrue(cases.length() > 100)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val t = TextTransform.transform(edits(c.getJSONArray("host")), edits(c.getJSONArray("page")))
            assertEquals(c.toString(), edits(c.getJSONArray("hostPrime")), t.host)
            assertEquals(c.toString(), edits(c.getJSONArray("pagePrime")), t.page)
            val viaPage =
                TextTransform.apply(
                    TextTransform.apply(c.getString("base"), edits(c.getJSONArray("page"))),
                    t.host,
                )
            assertEquals(c.toString(), c.getString("result"), viaPage)
        }
        val maps = ot.getJSONArray("mapOffset")
        for (i in 0 until maps.length()) {
            val m = maps.getJSONObject(i)
            val at = TextTransform.mapOffset(m.getInt("offset"), edits(m.getJSONArray("edits")), m.getBoolean("after"))
            assertEquals(m.toString(), m.getInt("expected"), at)
        }
    }

    @Test
    fun `every host message encodes exactly as the page expects`() {
        val host = json("messages.json").getJSONObject("host")
        val names = host.keys().asSequence().toSet()
        assertEquals(names, hostMessages.keys)
        for ((name, message) in hostMessages) {
            val frames = BridgeCodec.frames(message, frameChars = 8)
            assertEquals(name, canonical(host.getJSONObject(name)), canonical(JSONObject(frames.first())))
        }
    }

    @Test
    fun `every page message decodes to its class`() {
        val page = json("messages.json").getJSONArray("page")
        for (i in 0 until page.length()) {
            val v = page.getJSONObject(i)
            val decoded = BridgeCodec.decode(v.getJSONObject("json").toString())
            assertNotNull(v.toString(), decoded)
            assertEquals(v.toString(), v.getString("kotlin"), decoded?.let { it::class.simpleName })
        }
    }

    /** JSON as plain Kotlin values, numbers as doubles, so two encodings compare by content. */
    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }
            is JSONArray -> List(value.length()) { canonical(value.get(it)) }
            is Number -> value.toDouble()
            JSONObject.NULL -> null
            else -> value
        }

    private fun edits(array: JSONArray): List<TextEdit> =
        List(array.length()) { i ->
            val e = array.getJSONObject(i)
            TextEdit(e.getInt("from"), e.getInt("to"), e.getString("insert"))
        }

    private companion object {
        const val INTER = "\"Inter\", sans-serif"
    }

    /** The Kotlin twin of each named vector in messages.json. */
    private val hostMessages: Map<String, HostMessage> =
        mapOf(
            "load" to HostMessage.Load(3, "# Hi\n", 12, epoch = 2),
            "loadChunked" to HostMessage.Load(4, "0123456789"),
            "edit" to HostMessage.Edit(9, listOf(LineChange(4, 5, listOf("a", ""))), listOf(TextEdit(10, 11, "a\n"))),
            "ack" to HostMessage.Ack(5),
            "format" to HostMessage.Format(FormatAction.BOLD),
            "select" to HostMessage.Select(2, 1, 4),
            "theme" to
                HostMessage.Theme(
                    """{"name":"Clean"}""",
                    dark = true,
                    fonts =
                        ThemeFonts(
                            "@font-face { }",
                            "\"Inter\", sans-serif",
                            "\"Inter\", sans-serif",
                            "\"JetBrains Mono\", monospace",
                        ),
                ),
            "settings" to HostMessage.Settings(PreviewSettings(zoom = 1.5f, readOnly = true, noWrap = true)),
            "scrollToLine" to HostMessage.ScrollToLine(40, smooth = true),
            "tableNav" to HostMessage.TableNav(next = true),
            "tableOp" to HostMessage.Table(TableOp.Align(TableAlign.CENTER)),
            "endEditing" to HostMessage.EndEditing,
            "exportImage" to HostMessage.ExportImage(7, 3, png = true, scale = 2),
            "preparePrint" to HostMessage.PreparePrint(4),
            "finishPrint" to HostMessage.FinishPrint,
            "exportHtml" to HostMessage.ExportHtml(8, 2, 9, 400_000),
            "flush" to HostMessage.Flush,
            "find" to HostMessage.Find(6, intArrayOf(3, 20), intArrayOf(9, 26), current = 1, reveal = true),
            "findKeep" to HostMessage.Find(6, null, null, current = 0, reveal = true),
        )
}
