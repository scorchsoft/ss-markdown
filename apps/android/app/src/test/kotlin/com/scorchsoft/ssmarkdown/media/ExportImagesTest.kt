package com.scorchsoft.ssmarkdown.media

import com.scorchsoft.ssmarkdown.settings.DiagramFormat
import com.scorchsoft.ssmarkdown.settings.ExportDefaults
import com.scorchsoft.ssmarkdown.settings.ExportDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Export images sheet's choices, the file names and what counts as an image. */
class ExportImagesTest {
    private val doc =
        """
        # Release notes

        ```mermaid
        %% the save flow
        flowchart LR
          A --> B
        ```

        ![Architecture](img/arch.png) and ![](shots/one.JPG)

        ```
        ![not a picture](x.png)
        ```

        ~~~mermaid
        sequenceDiagram
        title: Save
        ~~~
        """.trimIndent()

    private val items = MediaScanner.scan(doc)

    @Test
    fun `diagrams and pictures are found in order, code blocks ignored`() {
        assertEquals(
            listOf(
                MediaItem(MediaType.Diagram, 2, "flowchart LR"),
                MediaItem(MediaType.Picture, 8, "Architecture", "img/arch.png"),
                MediaItem(MediaType.Picture, 8, "one.JPG", "shots/one.JPG"),
                MediaItem(MediaType.Diagram, 14, "Save"),
            ),
            items,
        )
    }

    @Test
    fun `the sheet opens with everything selected and last time's choices`() {
        val defaults = ExportDefaults(DiagramFormat.Both, 3, ExportDestination.Zip)
        val state = ExportImagesState.of("release-notes", items, defaults)
        assertEquals(4, state.count)
        assertEquals(2, state.diagrams)
        assertEquals(DiagramFormat.Both, state.format)
        assertEquals(ExportDestination.Zip, state.destination)
        assertEquals(defaults.copy(zipFolder = "content://f"), state.defaults("content://f"))
    }

    @Test
    fun `select none, toggle one, then select all`() {
        var state = ExportImagesState.of("d", items, ExportDefaults())
        state = ExportImagesReducer.reduce(state, ExportImagesAction.ToggleAll)
        assertEquals(0, state.count)
        state = ExportImagesReducer.reduce(state, ExportImagesAction.Toggle(1))
        assertEquals(listOf(items[1]), state.chosen)
        state = ExportImagesReducer.reduce(state, ExportImagesAction.Toggle(9))
        assertEquals(1, state.count)
        state =
            ExportImagesReducer.reduce(
                ExportImagesReducer.reduce(state, ExportImagesAction.Toggle(1)),
                ExportImagesAction.ToggleAll,
            )
        assertEquals(4, state.count)
    }

    @Test
    fun `scale stays within 1 to 3`() {
        val state = ExportImagesState.of("d", items, ExportDefaults())
        assertEquals(3, ExportImagesReducer.reduce(state, ExportImagesAction.SetScale(7)).scale)
        assertEquals(1, ExportImagesReducer.reduce(state, ExportImagesAction.SetScale(0)).scale)
    }

    @Test
    fun `files are numbered in document order with safe names`() {
        assertEquals("01-save-flow.png", ExportNames.name(1, 5, "Save flow", "png"))
        assertEquals("010-image.svg", ExportNames.name(10, 120, "/../", "svg"))
        assertEquals("release-notes", ExportNames.docStem("release-notes.md"))
        assertTrue(ExportNames.slug("A".repeat(80)).length <= 40)
        assertEquals("jpg" to "image/jpeg", ExportNames.typeOf(items[2], svg = false))
        assertEquals("svg" to "image/svg+xml", ExportNames.typeOf(items[0], svg = true))
    }
}
