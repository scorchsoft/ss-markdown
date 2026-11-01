package com.scorchsoft.ssmarkdown.fixtures

import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fontpicker.FontOption
import com.scorchsoft.ssmarkdown.fontpicker.FontOrigin
import com.scorchsoft.ssmarkdown.fontpicker.FontPickerUiState
import com.scorchsoft.ssmarkdown.fonts.FontCategory
import com.scorchsoft.ssmarkdown.media.ExportImagesState
import com.scorchsoft.ssmarkdown.media.MediaItem
import com.scorchsoft.ssmarkdown.media.MediaType
import com.scorchsoft.ssmarkdown.media.ViewerImage
import com.scorchsoft.ssmarkdown.media.ViewerUiState
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.preview.bridge.MediaKind
import com.scorchsoft.ssmarkdown.settings.ExportDefaults
import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.theme.PaletteKey
import com.scorchsoft.ssmarkdown.themes.FontRole
import com.scorchsoft.ssmarkdown.themes.ThemeEditorAction
import com.scorchsoft.ssmarkdown.themes.ThemeEditorReducer
import com.scorchsoft.ssmarkdown.themes.ThemeTab

/** States for the Preview-side screens and sheets, as the mockups show them. */
object PreviewSamples {
    val images =
        listOf(
            MediaItem(MediaType.Diagram, 4, "Save flow"),
            MediaItem(MediaType.Diagram, 20, "Architecture"),
            MediaItem(MediaType.Picture, 31, "Screenshot", "img/shot.png"),
            MediaItem(MediaType.Diagram, 44, "Sequence"),
            MediaItem(MediaType.Picture, 60, "Logo", "img/logo.png"),
        )

    val viewer =
        ViewerUiState("release-notes", images, 1, mapOf(0 to ViewerImage.Failed, 1 to ViewerImage.Loading))

    val export = ExportImagesState.of("release-notes", images, ExportDefaults())

    val exportSheet = Samples.saved.copy(mode = ViewMode.Preview, overlay = EditorOverlay.ExportImages(export))

    val mermaidSheet =
        Samples.saved.copy(
            mode = ViewMode.Preview,
            overlay = EditorOverlay.MediaMenu(MediaKind.DIAGRAM, 4, "Save flow", 2),
        )

    val linkChip =
        Samples.saved.copy(
            mode = ViewMode.Preview,
            overlay = EditorOverlay.LinkChip(LinkTarget.External("https://www.scorchsoft.com"), 3),
        )

    val display = Samples.zoomed.copy(overlay = EditorOverlay.Display)

    val tableMenu = Samples.saved.copy(mode = ViewMode.Preview, overlay = EditorOverlay.TableMenu(8))

    private val themeStart = ThemeEditorReducer.start(ThemeEditorReducer.copyOf(BuiltInThemes.READER), null)

    val themeType = themeStart

    val themeColours =
        ThemeEditorReducer.reduce(
            ThemeEditorReducer.reduce(themeStart, ThemeEditorAction.SelectTab(ThemeTab.Colours)),
            ThemeEditorAction.SetColor(PaletteKey.LINK, "#E8C9B8"),
        )

    val themeJson = ThemeEditorReducer.reduce(themeStart, ThemeEditorAction.SelectTab(ThemeTab.Json))

    val fonts =
        FontPickerUiState(
            role = FontRole.Body,
            themeName = "Studio Serif",
            current = "Source Serif 4",
            onPhone =
                listOf(
                    FontOption("Source Serif 4", FontCategory.SERIF, FontOrigin.BuiltIn),
                    FontOption("Inter", FontCategory.SANS, FontOrigin.BuiltIn),
                ),
            google =
                listOf(
                    FontOption("Lora", FontCategory.SERIF, FontOrigin.GoogleFonts, 4, 430),
                    FontOption("Merriweather", FontCategory.SERIF, FontOrigin.GoogleFonts, 8, 310),
                ),
            downloading = mapOf("Merriweather" to 0.4f),
            problem = "Couldn't download Lora — you're offline. Using Source Serif 4 until then.",
        )
}
