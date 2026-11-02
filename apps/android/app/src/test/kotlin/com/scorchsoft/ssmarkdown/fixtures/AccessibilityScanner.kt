package com.scorchsoft.ssmarkdown.fixtures

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withTranslation
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.Parameters
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import com.google.android.apps.common.testing.accessibility.framework.utils.contrast.BitmapImage
import com.scorchsoft.ssmarkdown.core.ui.SSDarkColors
import com.scorchsoft.ssmarkdown.core.ui.SSLightColors
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/**
 * Runs Google's Accessibility Test Framework (ATF), the engine inside the
 * Accessibility Scanner app, over every window the app has open: missing
 * labels, touch targets under 48 dp, text and icon contrast, duplicate
 * clickable bounds, traversal order and the rest of the latest preset.
 *
 * Each window is checked against a screenshot of itself drawn over the
 * windows beneath it, so a sheet's contrast is measured on what is really
 * behind it, and the editor behind a sheet is measured without the scrim.
 */
object AccessibilityScanner {
    /**
     * A known false positive, suppressed narrowly: one [check], on the
     * results [matches] accepts, for a written [reason]. Never a whole check.
     */
    data class Suppression(
        val check: Class<out AccessibilityHierarchyCheck>,
        val reason: String,
        val matches: (AccessibilityHierarchyCheckResult) -> Boolean,
    )

    /** One finding, readable in a test failure. */
    data class Finding(
        val check: String,
        val element: String,
        val message: String,
    ) {
        override fun toString(): String = "$check on $element: $message"
    }

    /**
     * The false positives the scan ignores, all in ATF's screenshot estimate
     * of text contrast (Compose draws its own text, so ATF guesses the text
     * colour from pixels). Each is limited to the elements it explains; the
     * colour pairs they stand in for are checked exactly, as numbers, in
     * core/ui's `SSColorsContrastTest`.
     */
    val SUPPRESSIONS: List<Suppression> =
        listOf(
            Suppression(
                TextContrastCheck::class.java,
                "A text field's outline is drawn inside its bounds, and ATF takes that 3:1 " +
                    "outline (SS.colors.inputOutline, WCAG 1.4.11) for the text colour. The text is ink.",
            ) { result -> result.element?.isEditable == true && estimatedForeground(result) in INPUT_OUTLINES },
            Suppression(
                TextContrastCheck::class.java,
                "Code pane lines: the renderer draws them only in theme tokens (CodePaneColouringTest " +
                    "reads the runs), and the estimate mixes in the code-block band, a 1-2 px backtick, " +
                    "or the Navigator panel lying over the line.",
            ) { result -> result.element?.let(::isCodePaneLine) == true },
            Suppression(
                TextContrastCheck::class.java,
                "The mode bar's labels below 2x density (the 1280 dp shot is 240 dpi): 13 sp strokes " +
                    "are 1-2 px, so the estimate is an anti-aliased blend (2.3:1 read for a 6.4:1 pair). " +
                    "The same labels pass at 2x and 2.625x.",
            ) { result -> lowDensity() && result.element?.let(::spokenText) in MODE_LABELS },
        )

    private val INPUT_OUTLINES = setOf(SSLightColors.inputOutline.toArgb(), SSDarkColors.inputOutline.toArgb())
    private val MODE_LABELS = setOf("Code", "Split", "Preview")
    private const val LOW_DENSITY = 2f

    private fun estimatedForeground(result: AccessibilityHierarchyCheckResult): Int? =
        result.metadata?.let { m ->
            if (m.containsKey(
                    TextContrastCheck.KEY_FOREGROUND_COLOR,
                )
            ) {
                m.getInt(TextContrastCheck.KEY_FOREGROUND_COLOR)
            } else {
                null
            }
        }

    /** A visible-line node of the Code pane: a child of the editable, scrollable host node. */
    private fun isCodePaneLine(element: ViewHierarchyElement): Boolean {
        val host = element.parentView ?: return false
        return host.isEditable == true && host.isScrollable == true && host.className?.toString() == EDIT_TEXT
    }

    private fun lowDensity(): Boolean =
        RuntimeEnvironment
            .getApplication()
            .resources.displayMetrics.density < LOW_DENSITY

    private const val EDIT_TEXT = "android.widget.EditText"

    private val checks =
        AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(
            AccessibilityCheckPreset.LATEST,
        )

    /**
     * The findings for [windows] (bottom to top, as the window manager
     * stacks them), less the [suppressions]: see [gated] for which count.
     */
    fun errors(
        windows: List<View>,
        suppressions: List<Suppression> = SUPPRESSIONS,
    ): List<Finding> {
        check(windows.isNotEmpty()) { "no window to scan" }
        val first = windows.first()
        val screen = Bitmap.createBitmap(first.rootView.width, first.rootView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        return windows.flatMap { root ->
            val at = IntArray(2).also(root::getLocationOnScreen)
            canvas.withTranslation(at[0].toFloat(), at[1].toFloat()) { root.draw(this) }
            scan(root, screen.copy(Bitmap.Config.ARGB_8888, false), suppressions)
        }
    }

    private fun scan(
        root: View,
        screenshot: Bitmap,
        suppressions: List<Suppression>,
    ): List<Finding> {
        // Built from the node tree, as the Scanner app sees it, not from the
        // View tree: Compose and the Code pane's lines are virtual nodes, which
        // a View walk never reaches. Querying from the app process lets the
        // node's children resolve in-process, with no accessibility service.
        val node = root.createAccessibilityNodeInfo()
        node.setQueryFromAppProcessEnabled(root, true)
        val hierarchy = AccessibilityHierarchyAndroid.newBuilder(node, root.context).build()
        val parameters = Parameters().apply { putScreenCapture(BitmapImage(screenshot)) }
        return checks
            .flatMap { it.runCheckOnHierarchy(hierarchy, null, parameters) }
            .filter(::gated)
            .filterNot { result -> suppressions.any { it.covers(result) } }
            .map(::finding)
    }

    /**
     * Every ERROR, and the contrast checks' WARNINGs too. Compose draws its
     * own text, so ATF cannot read a text colour and estimates contrast from
     * the screenshot; it then reports even 1.6:1 as a WARNING (unsure), never
     * an ERROR, so without this low contrast would never fail. Other
     * warnings (two items with the same name, and the like) are for the
     * TalkBack pass to judge.
     */
    private fun gated(result: AccessibilityHierarchyCheckResult): Boolean =
        result.type == AccessibilityCheckResultType.ERROR ||
            (result.type == AccessibilityCheckResultType.WARNING && result.sourceCheckClass in CONTRAST_CHECKS)

    private val CONTRAST_CHECKS = setOf(TextContrastCheck::class.java, ImageContrastCheck::class.java)

    private fun Suppression.covers(result: AccessibilityHierarchyCheckResult): Boolean =
        result.sourceCheckClass == check && matches(result)

    private fun finding(result: AccessibilityHierarchyCheckResult): Finding {
        val element = result.element
        val name =
            element?.let {
                listOfNotNull(
                    it.className?.toString()?.substringAfterLast('.'),
                    it.contentDescription?.toString()?.let { d -> "desc=\"$d\"" },
                    spokenText(it)?.take(TEXT_PREVIEW)?.let { t -> "text=\"$t\"" },
                    it.boundsInScreen?.let { b -> "at ${b.left},${b.top}-${b.right},${b.bottom}" },
                ).joinToString(" ")
            } ?: "the screen"
        return Finding(result.sourceCheckClass.simpleName, name, result.getMessage(Locale.ENGLISH).toString())
    }

    /** The element's own text or, for a merged row, the first text inside it, so a finding names its control. */
    private fun spokenText(element: ViewHierarchyElement): String? =
        element.selfAndAllDescendants
            .firstNotNullOfOrNull { e -> (e.text ?: e.contentDescription)?.toString()?.takeIf { it.isNotBlank() } }

    private const val TEXT_PREVIEW = 40
}
