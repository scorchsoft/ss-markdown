package com.scorchsoft.ssmarkdown.fixtures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SS
import com.scorchsoft.ssmarkdown.editor.ConflictSummary
import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.SaveButton
import com.scorchsoft.ssmarkdown.editor.SaveFacts
import com.scorchsoft.ssmarkdown.editor.SaveProblem
import com.scorchsoft.ssmarkdown.editor.SaveStateMachine
import com.scorchsoft.ssmarkdown.editor.panes.PreviewHostState

/** Editor states from the mockups, for render and screenshot tests. */
object Samples {
    private val clean =
        SaveFacts(
            isDraft = false,
            isDirty = false,
            readOnly = false,
            saving = false,
            autosave = false,
            lastSavedAtMillis = 0,
        )

    val saved = EditorUiState(title = "release-notes.md", save = SaveStateMachine.present(clean))
    val unsaved = saved.copy(save = SaveStateMachine.present(clean.copy(isDirty = true)))
    val draft = EditorUiState(title = "", save = SaveStateMachine.present(clean.copy(isDraft = true)))
    val readOnly =
        saved.copy(
            title = "invoice-notes.md",
            save = SaveStateMachine.present(clean.copy(readOnly = true, isDirty = true, readOnlySource = "Gmail")),
        )
    val failed =
        unsaved.copy(
            title = "roadmap.md",
            save = SaveStateMachine.present(clean.copy(isDirty = true, lastFailure = SaveProblem.StorageFull)),
            banner = EditorBanner.SaveFailed(SaveProblem.StorageFull),
        )
    val recovered = unsaved.copy(title = "roadmap.md", banner = EditorBanner.Recovered(0, closedBySwipe = false))
    val section =
        unsaved.copy(
            title = "handbook.md",
            breadcrumb = listOf("How we work", "Code review"),
            readingFraction = 0.4f,
        )
    val conflict =
        unsaved.copy(
            title = "roadmap.md",
            banner = EditorBanner.ChangedOutside,
            overlay = EditorOverlay.Conflict(ConflictSummary(18, 2, 4, 3, 1, canMerge = true)),
        )
    val zoomed = saved.copy(codeZoom = 120)

    /** A read-only file without edits: outline "Save as…" (SaveAsReadOnly). */
    val readOnlyClean =
        saved.copy(
            title = "invoice-notes.md",
            save = SaveStateMachine.present(clean.copy(readOnly = true, readOnlySource = "Gmail")),
        )

    /** A save in flight: the disabled pill with a spinner. */
    val saving = unsaved.copy(save = SaveStateMachine.present(clean.copy(isDirty = true, saving = true)))

    /** Autosave on, paused by a full disk. */
    val autosavePaused =
        unsaved.copy(
            title = "roadmap.md",
            save =
                SaveStateMachine.present(
                    clean.copy(isDirty = true, autosave = true, autosavePausedBy = SaveProblem.StorageFull),
                ),
        )

    /** Big-file mode with a breadcrumb: the chip sits beside the title. */
    val bigFile = section.copy(title = "server-log-export.md", bigFile = true)

    init {
        check(unsaved.save.button == SaveButton.Save)
        check(readOnly.save.button == SaveButton.SaveAsRequired)
        check(readOnlyClean.save.button == SaveButton.SaveAsReadOnly)
        check(saving.save.button == SaveButton.Saving)
        check(failed.save.button == SaveButton.Retry)
    }

    /** The preview slot's state with nothing to render. */
    val previewState = PreviewHostState(null, 0, { 0 }, 100, true, {}, { _, _ -> }, { _, _, _ -> }, {})

    val sampleLines =
        listOf(
            "# Release notes",
            "",
            "SS Markdown 1.0",
            "",
            "## Checklist",
            "- [x] Code, Split and Preview",
            "- [x] Copy and Save always visible",
        )

    /**
     * What the real Code pane shows in screenshots: [sampleLines], then a
     * line of every inline kind and a fenced block, so a shot shows
     * headings, muted markers, links, code chips and the code band.
     */
    val codeSample: String =
        (
            sampleLines +
                listOf(
                    "- [ ] Mermaid and themes",
                    "",
                    "A **calm**, *fast* editor with `inline code`.",
                    "See [the docs](https://example.com/docs) for more.",
                    "",
                    "```kotlin",
                    "fun main() = println(\"Hello\")",
                    "```",
                )
        ).joinToString("\n")

    /** Fenced code in five languages, for the Code view's highlighting shot. */
    val highlightedCode: String =
        listOf(
            "# Code samples",
            "```python",
            "@dataclass",
            "class Point:",
            "    def length(self) -> float:",
            "        # Pythagoras",
            "        return (self.x ** 2 + self.y ** 2) ** 0.5",
            "```",
            "```ts",
            "interface User { name: string; age?: number }",
            "const greet = (u: User) => `Hi \${u.name}`; // ok",
            "```",
            "```php",
            "<?php",
            "function total(\$items) { return count(\$items) * 1.2; }",
            "```",
            "```c",
            "#include <stdio.h>",
            "/* entry point */",
            "int main(void) { printf(\"%d\\n\", 42); return 0; }",
            "```",
            "```java",
            "@Override",
            "public List<String> names() { return List.of(\"a\"); }",
            "```",
        ).joinToString("\n")
}

/** A stand-in for the Code pane: line numbers and text, so screens render without the view. */
@Composable
fun FakeCodePane(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(SS.colors.bg).padding(top = 12.dp)) {
        Samples.sampleLines.forEachIndexed { i, line ->
            Row {
                Text(
                    "${i + 1}",
                    style = SS.type.caption,
                    color = SS.colors.gutter,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp).padding(end = 10.dp),
                )
                Text(line, style = SS.type.code, color = SS.colors.ink)
            }
        }
    }
}
