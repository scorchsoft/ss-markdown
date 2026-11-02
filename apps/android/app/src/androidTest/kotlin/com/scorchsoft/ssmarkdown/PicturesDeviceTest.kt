package com.scorchsoft.ssmarkdown

import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.ImageStatus
import com.scorchsoft.ssmarkdown.preview.PreviewListener
import com.scorchsoft.ssmarkdown.text.EditOp
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * docs/ux-spec.md "Pictures" on a real WebView: a picture beside the
 * document loads through the `/doc/` path handler, and one that is missing
 * is asked about in one query and answered "Not found" by the app's own
 * resolver. A draft is used because its folder needs no system picker; the
 * folder-grant flow itself is covered on the host (`InsertImageTest`,
 * `PictureFoldersTest`), since the system picker cannot be driven here.
 */
@RunWith(AndroidJUnit4::class)
class PicturesDeviceTest {
    @get:Rule(order = 0)
    val leaks = DetectLeaksAfterTestSuccess()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aPictureBesideADraftLoadsAndAMissingOneIsNotFound() {
        val graph = rule.activity.graph()
        val editor = graph.editor
        rule.waitUntil(TIMEOUT_MILLIS) { editor.active != null }
        instrumentation.runOnMainSync {
            if (editor.active
                    ?.open
                    ?.uri
                    ?.isDraft != true
            ) {
                editor.newDraft()
            }
        }
        rule.waitUntil(TIMEOUT_MILLIS) {
            editor.active
                ?.open
                ?.uri
                ?.isDraft == true
        }
        File(graph.drafts.root, "images").apply { mkdirs() }.resolve("dot.png").writeBytes(PNG)

        // The Preview pane first, so its session is the view's listener before we wrap it.
        instrumentation.runOnMainSync { editor.onAction(EditorAction.SelectMode(ViewMode.Preview)) }
        rule.waitUntil(TIMEOUT_MILLIS) {
            var ready = false
            instrumentation.runOnMainSync { ready = rule.activity.previewView.isOnScreen }
            ready
        }
        val asked = ConcurrentHashMap<String, ImageStatus>()
        instrumentation.runOnMainSync {
            val view = rule.activity.previewView
            val session = view.listener ?: error("no preview session")
            view.listener =
                object : PreviewListener by session {
                    override fun onImageStatuses(statuses: Map<String, ImageStatus>) {
                        asked.putAll(statuses)
                        session.onImageStatuses(statuses)
                    }
                }
            val doc = editor.active ?: error("nothing open")
            val text = "# Pictures\n\n![dot](images/dot.png)\n\n![gone](images/gone.png)\n"
            doc.surface.apply(listOf(EditOp(0, doc.text.snapshot().toString(), text)), TextSelection(0, 0))
        }
        rule.waitUntil(TIMEOUT_MILLIS) { asked.isNotEmpty() }
        // Only the missing one is asked about: the other loaded through the path handler.
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(mapOf("0/images/gone.png" to ImageStatus.NOT_FOUND), asked.toMap())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val SETTLE_MILLIS = 1_000L

        /** A 1×1 transparent PNG. */
        val PNG: ByteArray =
            Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
                Base64.DEFAULT,
            )
    }
}
