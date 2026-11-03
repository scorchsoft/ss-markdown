package com.scorchsoft.ssmarkdown.markdown.paste

import com.scorchsoft.ssmarkdown.markdown.paste.HtmlNode.Element
import com.scorchsoft.ssmarkdown.markdown.paste.MarkdownText.NBSP

/** Pasted HTML as Markdown, and whether any of it was formatting (not just text in paragraphs). */
public class ConvertedHtml(
    public val markdown: String,
    public val formatted: Boolean,
)

/**
 * Converts clipboard HTML (from a browser, Gmail, Google Docs or Word) to
 * Markdown for a paste (docs/ux-spec.md, "Copy and paste"): headings,
 * paragraphs, line breaks, bold, italic, strikethrough, inline code, code
 * blocks with their language, links (web and mail only), web pictures,
 * nested lists with their start number and task boxes, Word's paragraph
 * lists, quotes, tables (complex ones as rows of text) and rules. Scripts,
 * styles, comments, hidden text, forms and media are dropped; no other
 * address is ever written, so a `javascript:` link keeps only its words.
 * Whitespace collapses as HTML collapses it.
 *
 * Preview's `web/src/core/htmlToMarkdown.ts` is the same walker over the
 * browser's own parse; `web/test/vectors/paste-conversions.txt` holds the
 * cases both must give exactly.
 */
@Suppress("TooManyFunctions") // One walk over the tree: a function per kind of element.
internal class HtmlToMarkdown private constructor() {
    private var formatted = false

    private enum class Mark(
        val delimiter: String,
    ) {
        BOLD("**"),
        ITALIC("*"),
        STRIKE("~~"),
    }

    /** Marks in force ([active]: never wrapped again) and those a block must wrap its text in ([wrap]). */
    private data class Context(
        val active: Set<Mark> = emptySet(),
        val wrap: List<Mark> = emptyList(),
    ) {
        fun with(marks: List<Mark>): Context {
            val added = marks.filter { it !in active }
            return if (added.isEmpty()) this else Context(active + added, wrap + added)
        }
    }

    private fun convert(root: Element): ConvertedHtml {
        val md = blocks(root.children, Context()).filter { it.isNotBlank() }.joinToString("\n\n")
        return ConvertedHtml(md.trim('\n'), formatted)
    }

    // Blocks ----------------------------------------------------------------------------------------------

    private fun blocks(
        nodes: List<HtmlNode>,
        context: Context,
    ): List<String> {
        val out = mutableListOf<String>()
        val run = mutableListOf<HtmlNode>()

        fun flush() {
            if (run.isNotEmpty()) out += paragraph(run, context)
            run.clear()
        }
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            when {
                node !is Element -> {
                    run += node
                }

                hidden(node) -> {
                    Unit
                }

                WordLists.isItem(node) -> {
                    flush()
                    val items = WordLists.gather(nodes, i)
                    out += wordList(items, context)
                    i = nodes.indexOf(items.last())
                }

                node.name in HtmlNames.BLOCK -> {
                    flush()
                    out += block(node, context)
                }

                hasBlock(node) -> {
                    flush()
                    out += blocks(node.children, context.with(marks(node)))
                }

                else -> {
                    run += node
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun block(
        el: Element,
        context: Context,
    ): List<String> =
        when (el.name) {
            in HtmlNames.HEADINGS -> heading(el, context)
            "pre", "listing" -> listOfNotNull(codeBlock(el))
            "blockquote" -> quote(el, context)
            "ul", "ol" -> listOf(list(el, context)).filter { it.isNotEmpty() }
            "li" -> listOf(item(el, "- ", context))
            "table" -> table(el, context)
            "hr" -> listOf("---").also { formatted = true }
            else -> blocks(el.children, context)
        }

    /** Inline [nodes] as paragraphs: `<br>` is a hard break, two in a row end the paragraph. */
    private fun paragraph(
        nodes: List<HtmlNode>,
        context: Context,
    ): List<String> {
        val text = wrapAll(nodes.joinToString("") { inline(it, context) }, context.wrap)
        val paragraphs = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (line in text.split(BR).map { MarkdownText.escapeLineStart(clean(it)) } + "") {
            if (line.isNotEmpty()) {
                lines += line
            } else if (lines.isNotEmpty()) {
                paragraphs += lines.joinToString("\\\n")
                lines.clear()
            }
        }
        return paragraphs
    }

    private fun heading(
        el: Element,
        context: Context,
    ): List<String> {
        // A heading is bold already: bold inside it adds nothing.
        val inner = Context(context.active + Mark.BOLD)
        val text = clean(el.children.joinToString("") { inline(it, inner) }.replace(BR, ' '))
        if (text.isEmpty()) return emptyList()
        formatted = true
        return listOf("#".repeat(el.name[1].digitToInt()) + " " + text)
    }

    private fun quote(
        el: Element,
        context: Context,
    ): List<String> {
        val inner = blocks(el.children, context).filter { it.isNotBlank() }.joinToString("\n\n")
        if (inner.isEmpty()) return emptyList()
        formatted = true
        return listOf(inner.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" })
    }

    private fun codeBlock(el: Element): String? {
        val code = el.children.firstOrNull { it is Element && it.name == "code" } as Element?
        val language = languageOf(el) ?: code?.let(::languageOf) ?: ""
        val text =
            preText(el.children)
                .replace(NBSP, ' ')
                .trimEnd('\n')
                .trimStart('\n')
        if (text.isBlank()) return null
        formatted = true
        val fence = "`".repeat(maxOf(FENCE, MarkdownText.longestRun(text, '`') + 1))
        return "$fence$language\n$text\n$fence"
    }

    // Lists -----------------------------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // One pass over a list's children: blank, hidden, nested list or item.
    private fun list(
        el: Element,
        context: Context,
    ): String {
        val ordered = el.name == "ol"
        var number =
            el
                .attr("start")
                ?.trim()
                ?.toIntOrNull()
                ?.coerceIn(0, MAX_NUMBER) ?: 1
        val items = mutableListOf<String>()
        var width = 2
        for (child in el.children) {
            when {
                child is HtmlNode.Text && child.text.isBlank() -> {
                    Unit
                }

                child is Element && hidden(child) -> {
                    Unit
                }

                child is Element && (child.name == "ul" || child.name == "ol") -> {
                    val nested = list(child, context)
                    if (nested.isEmpty()) continue
                    if (items.isEmpty()) items += nested else items[items.lastIndex] += "\n" + indent(nested, width)
                }

                else -> {
                    val marker = if (ordered) "${number++}. " else "- "
                    width = marker.length
                    items += item(child, marker, context)
                }
            }
        }
        if (items.isNotEmpty()) formatted = true
        return items.joinToString("\n")
    }

    private fun item(
        node: HtmlNode,
        marker: String,
        context: Context,
    ): String {
        val children = if (node is Element && node.name == "li") node.children else listOf(node)
        val box = (node as? Element)?.let(::taskBox).orEmpty()
        val parts = blocks(children, context).filter { it.isNotBlank() }
        if (parts.isEmpty()) return (marker + box).trimEnd()
        val body =
            buildString {
                parts.forEachIndexed { k, part ->
                    if (k > 0) append(if (LIST_START.containsMatchIn(part)) "\n" else "\n\n")
                    append(part)
                }
            }
        return marker + box + indent(body, marker.length).trimStart(' ')
    }

    /** `[x] ` or `[ ] ` for an item with a checkbox first in it (GitHub's task lists). */
    private fun taskBox(li: Element): String? {
        val near =
            li.children +
                li.children
                    .filterIsInstance<Element>()
                    .take(1)
                    .flatMap { it.children }
        val box =
            near.firstOrNull {
                it is Element && it.name == "input" && it.attr("type")?.lowercase() == "checkbox"
            } as Element? ?: return null
        formatted = true
        return if (box.attrs.containsKey("checked")) "[x] " else "[ ] "
    }

    /** Word's lists: paragraphs with a hidden marker span and a `level` in their style. */
    private fun wordList(
        items: List<Element>,
        context: Context,
    ): String {
        val open = ArrayDeque<Pair<Int, String>>()
        val lines = mutableListOf<String>()
        for (p in items) {
            val level = WordLists.level(p)
            while (open.isNotEmpty() && open.last().first >= level) open.removeLast()
            val indent = open.lastOrNull()?.second.orEmpty()
            val marker = WordLists.marker(p)
            val text = paragraph(p.children, context).joinToString(" ")
            lines += "$indent$marker $text".trimEnd()
            open.addLast(level to indent + " ".repeat(marker.length + 1))
        }
        formatted = true
        return lines.joinToString("\n")
    }

    // Tables ----------------------------------------------------------------------------------------------

    private fun table(
        el: Element,
        context: Context,
    ): List<String> {
        val rows = mutableListOf<Element>()
        val captions = mutableListOf<Element>()
        collectRows(el, rows, captions)
        val out = captions.flatMap { blocks(it.children, context) }.toMutableList()
        val cells =
            rows.map { row ->
                row.children.filterIsInstance<Element>().filter {
                    it.name == "td" ||
                        it.name == "th"
                }
            }
        if (cells.all { it.isEmpty() }) return out
        formatted = true
        if (cells.all { row -> row.all(::isSimpleCell) }) {
            val columns = cells.maxOf { it.size }
            val texts = cells.filter { it.isNotEmpty() }.map { row -> row.map { cellText(it, context, "<br>") } }
            val line = { row: List<String> ->
                "| " + (0 until columns).joinToString(" | ") { row.getOrElse(it) { "" } } +
                    " |"
            }
            val divider = "| " + List(columns) { "---" }.joinToString(" | ") + " |"
            out += (listOf(line(texts.first()), divider) + texts.drop(1).map(line)).joinToString("\n")
        } else {
            // Merged or nested cells have no GFM form: each row becomes a line of its cells' words.
            for (row in cells) {
                val text = row.map { cellText(it, context, " ") }.filter { it.isNotEmpty() }.joinToString(" | ")
                if (text.isNotEmpty()) out += MarkdownText.escapeLineStart(text)
            }
        }
        return out
    }

    private fun collectRows(
        el: Element,
        rows: MutableList<Element>,
        captions: MutableList<Element>,
    ) {
        for (child in el.children.filterIsInstance<Element>()) {
            when (child.name) {
                "tr" -> rows += child
                "thead", "tbody", "tfoot" -> collectRows(child, rows, captions)
                "caption" -> captions += child
            }
        }
    }

    private fun isSimpleCell(cell: Element): Boolean {
        val span = { name: String -> cell.attr(name)?.trim()?.toIntOrNull() ?: 1 }
        if (span("colspan") > 1 || span("rowspan") > 1) return false
        val blocks = cell.children.count { it is Element && it.name in HtmlNames.BLOCK && !hidden(it) }
        return blocks <= 1 && !hasDescendant(cell) { it in COMPLEX_IN_CELL }
    }

    /** A cell's words on one line: its lines joined by [lineBreak], pipes escaped. */
    private fun cellText(
        cell: Element,
        context: Context,
        lineBreak: String,
    ): String =
        wrapAll(cell.children.joinToString("") { inline(it, context) }, context.wrap)
            .split(BR)
            .map(::clean)
            .filter { it.isNotEmpty() }
            .joinToString(lineBreak)
            .replace("|", "\\|")

    // Inline ----------------------------------------------------------------------------------------------

    private fun inline(
        node: HtmlNode,
        context: Context,
    ): String {
        if (node is HtmlNode.Text) return MarkdownText.escapeInline(node.text.replace(MarkdownText.collapsible, " "))
        val el = node as Element
        return when {
            hidden(el) -> {
                ""
            }

            el.name == "br" -> {
                BR.toString()
            }

            el.name == "img" -> {
                image(el)
            }

            el.name == "a" -> {
                link(el, context)
            }

            el.name in CODE -> {
                codeSpan(el)
            }

            el.name in HtmlNames.BLOCK -> {
                BR + el.children.joinToString("") { inline(it, context) } + BR
            }

            else -> {
                val marks = marks(el).filter { it !in context.active }
                wrapAll(el.children.joinToString("") { inline(it, context.with(marks)) }, marks)
            }
        }
    }

    private fun link(
        el: Element,
        context: Context,
    ): String {
        val href = el.attr("href")?.trim().orEmpty()
        val inner = el.children.joinToString("") { inline(it, context) }
        if (!SAFE_LINK.containsMatchIn(href)) return inner
        return wrap(inner, "[", "](${destination(href)})")
    }

    private fun image(el: Element): String {
        val src = el.attr("src")?.trim().orEmpty()
        if (!WEB.containsMatchIn(src)) return ""
        formatted = true
        val alt = (el.attr("alt") ?: "").replace(MarkdownText.collapsible, " ").trim()
        return "![${alt.replace(BRACKETS) { "\\" + it.value }}](${destination(src)})"
    }

    private fun codeSpan(el: Element): String {
        val text = flatText(el.children).replace(NBSP, ' ').replace(MarkdownText.collapsible, " ")
        if (text.isBlank()) return text
        val fence = "`".repeat(MarkdownText.longestRun(text, '`') + 1)
        val core = text.trim(' ')
        val pad = if (core.startsWith('`') || core.endsWith('`')) " " else ""
        return wrap(text, fence + pad, pad + fence)
    }

    private fun wrapAll(
        text: String,
        marks: List<Mark>,
    ): String = marks.fold(text) { inner, mark -> wrap(inner, mark.delimiter, mark.delimiter) }

    /** [text] between [open] and [close], its outer spaces and breaks left outside; blank text unwrapped. */
    private fun wrap(
        text: String,
        open: String,
        close: String,
    ): String {
        val start = text.indexOfFirst { it !in EDGE }
        if (start < 0) return text
        val end = text.indexOfLast { it !in EDGE } + 1
        formatted = true
        return text.substring(0, start) + open + text.substring(start, end) + close + text.substring(end)
    }

    // Helpers ---------------------------------------------------------------------------------------------

    private fun hasBlock(el: Element): Boolean = hasDescendant(el) { it in HtmlNames.BLOCK }

    private fun hasDescendant(
        el: Element,
        test: (String) -> Boolean,
    ): Boolean =
        el.children.any { child ->
            child is Element && !hidden(child) && (test(child.name) || hasDescendant(child, test))
        }

    private fun preText(nodes: List<HtmlNode>): String =
        buildString {
            for (node in nodes) {
                when {
                    node is HtmlNode.Text -> {
                        append(node.text)
                    }

                    hidden(node as Element) -> {
                        Unit
                    }

                    node.name == "br" -> {
                        append('\n')
                    }

                    node.name in HtmlNames.BLOCK -> {
                        append(preText(node.children))
                        if (isNotEmpty() && !endsWith('\n')) append('\n')
                    }

                    else -> {
                        append(preText(node.children))
                    }
                }
            }
        }

    private fun flatText(nodes: List<HtmlNode>): String =
        nodes.joinToString("") { node ->
            when {
                node is HtmlNode.Text -> node.text
                hidden(node as Element) -> ""
                node.name == "br" -> " "
                else -> flatText(node.children)
            }
        }

    companion object {
        /** A line break inside inline text, until paragraphs are split on it. */
        private const val BR = '\u0001'
        private const val FENCE = 3
        private const val MAX_NUMBER = 999_999_999
        private val EDGE = setOf(' ', NBSP, BR)
        private val CODE = setOf("code", "kbd", "samp", "tt")
        private val COMPLEX_IN_CELL =
            setOf("table", "ul", "ol", "pre", "listing", "blockquote", "hr", "dl") + HtmlNames.HEADINGS
        private val SAFE_LINK = Regex("^(?:https?:|mailto:)", RegexOption.IGNORE_CASE)
        private val WEB = Regex("^https?://", RegexOption.IGNORE_CASE)
        private val BRACKETS = Regex("""[\\\[\]]""")
        private val LIST_START = Regex("""^(?:[-+*]|[0-9]{1,9}[.)])(?: |$)""")
        private val LANGUAGE = Regex("""(?:^|\s)(?:language|lang)-([A-Za-z0-9_#+.-]+)""")
        private val SPACES = Regex(" {2,}")
        private val FONT_WEIGHT = Regex("""font-weight\s*:\s*([a-z0-9]+)""")
        private val FONT_STYLE = Regex("""font-style\s*:\s*([a-z]+)""")
        private val LINE_THROUGH = Regex("""text-decoration[a-z-]*\s*:[^;]*line-through""")
        private val DISPLAY_NONE = Regex("""display\s*:\s*none|mso-list\s*:\s*ignore""")
        private const val BOLD_WEIGHT = 600

        /** [html] as Markdown; never throws. */
        fun convert(html: String): ConvertedHtml = HtmlToMarkdown().convert(HtmlTreeBuilder.parse(html))

        /** One line of output: spaces collapsed, ends trimmed, no-break spaces made plain. */
        private fun clean(line: String): String = line.replace(SPACES, " ").trim(' ', NBSP).replace(NBSP, ' ')

        private fun indent(
            text: String,
            width: Int,
        ): String = text.lines().joinToString("\n") { if (it.isEmpty()) it else " ".repeat(width) + it }

        private fun languageOf(el: Element): String? = el.attr("class")?.let { LANGUAGE.find(it)?.groupValues?.get(1) }

        private fun hidden(el: Element): Boolean =
            el.name in HtmlNames.DROPPED ||
                el.attrs.containsKey("hidden") ||
                el.attr("style")?.lowercase()?.let { DISPLAY_NONE.containsMatchIn(it) } == true

        private fun marks(el: Element): List<Mark> {
            val style = el.attr("style")?.lowercase().orEmpty()
            val weight = FONT_WEIGHT.find(style)?.groupValues?.get(1)
            val bold =
                if (weight != null) {
                    weight == "bold" || weight == "bolder" || (weight.toIntOrNull() ?: 0) >= BOLD_WEIGHT
                } else {
                    el.name == "b" || el.name == "strong"
                }
            val fontStyle = FONT_STYLE.find(style)?.groupValues?.get(1)
            val italic =
                if (fontStyle !=
                    null
                ) {
                    fontStyle == "italic" || fontStyle == "oblique"
                } else {
                    el.name == "i" || el.name == "em"
                }
            val strike =
                el.name == "s" || el.name == "del" || el.name == "strike" || LINE_THROUGH.containsMatchIn(style)
            return listOfNotNull(
                Mark.BOLD.takeIf { bold },
                Mark.ITALIC.takeIf { italic },
                Mark.STRIKE.takeIf { strike },
            )
        }

        /**
         * A link or picture address for Markdown: in `<>` when spaces, angle
         * brackets or odd parentheses would end it.
         */
        private fun destination(url: String): String {
            val clean = url.replace(Regex("[\\t\\n\\r]"), "")
            val balanced = clean.count { it == '(' } == clean.count { it == ')' }
            if (balanced && clean.none { it in " <>" }) return clean
            return "<" + clean.replace(" ", "%20").replace("<", "%3C").replace(">", "%3E") + ">"
        }
    }
}

/** Word's paragraph lists (`class="MsoListParagraph…"`, `mso-list:l0 level2`). */
internal object WordLists {
    private val listClass = Regex("""(?:^|\s)MsoListParagraph""", RegexOption.IGNORE_CASE)
    private val listStyle = Regex("""mso-list\s*:\s*l[0-9]""", RegexOption.IGNORE_CASE)
    private val ignored = Regex("""mso-list\s*:\s*ignore""", RegexOption.IGNORE_CASE)
    private val levelStyle = Regex("""level([0-9]+)""", RegexOption.IGNORE_CASE)
    private val number = Regex("""^([0-9]{1,9})([.)])$""")

    fun isItem(node: HtmlNode): Boolean =
        node is Element &&
            node.name == "p" &&
            (
                listClass.containsMatchIn(node.attr("class").orEmpty()) ||
                    listStyle.containsMatchIn(node.attr("style").orEmpty())
            )

    /** The run of list paragraphs starting at [from], whitespace between them skipped. */
    fun gather(
        nodes: List<HtmlNode>,
        from: Int,
    ): List<Element> {
        val items = mutableListOf<Element>()
        for (node in nodes.subList(from, nodes.size)) {
            if (isItem(node)) {
                items += node as Element
            } else if (!(node is HtmlNode.Text && node.text.isBlank())) {
                break
            }
        }
        return items
    }

    fun level(p: Element): Int =
        p
            .attr("style")
            ?.let { levelStyle.find(it) }
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 1

    /** `1.` for a numbered item (Word's own number), `-` for a bullet. */
    fun marker(p: Element): String {
        val span = find(p) { el -> ignored.containsMatchIn(el.attr("style").orEmpty()) }
        val text =
            span
                ?.let { text(it) }
                ?.replace('\u00A0', ' ')
                ?.trim()
                .orEmpty()
        return number.find(text)?.let { it.groupValues[1] + it.groupValues[2] } ?: "-"
    }

    private fun find(
        el: Element,
        test: (Element) -> Boolean,
    ): Element? {
        for (child in el.children.filterIsInstance<Element>()) {
            if (test(child)) return child
            find(child, test)?.let { return it }
        }
        return null
    }

    private fun text(el: Element): String =
        el.children.joinToString("") { if (it is HtmlNode.Text) it.text else text(it as Element) }
}
