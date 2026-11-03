package com.scorchsoft.ssmarkdown.markdown.paste

/**
 * Markdown as the words it shows, for "Paste as plain text" (docs/ux-spec.md,
 * "Copy and paste"): no `#`, `*`, `_`, `` ` ``, `>`, brackets, list markers,
 * task boxes or fences. A code block's lines stay exactly as written; each
 * list item is a line of its own; a table is one row per line with its
 * cells separated by a TAB (no pipes, no divider row); a link keeps its
 * text and a picture its alt text; blocks are separated by one blank line.
 *
 * Preview's twin is `web/src/core/plainWords.ts`; the vectors in
 * `web/test/vectors/paste-conversions.txt` keep the two identical.
 */
public object MarkdownPlainText {
    private val squash = Regex("[ \\t\\n\\r\\f\\u000B\\u00A0]+")

    /** The words [markdown] shows, without its syntax. */
    public fun of(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        return PlainBlocks.blocks(lines).joinToString("\n\n") { it.joinToString("\n") }
    }

    /** True when [text] holds Markdown syntax: its words differ from the text once spacing is ignored. */
    public fun hasMarkdown(text: String): Boolean = squash.replace(of(text), "") != squash.replace(text, "")
}

/** The block structure of Markdown, read line by line, as plain lines. */
internal object PlainBlocks {
    private val fence = Regex("""^( {0,3})(`{3,}|~{3,})(.*)$""")
    private val setext = Regex("""^ {0,3}(?:=+|-+)[ \t]*$""")
    private val heading = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$""")
    private val closingHashes = Regex("""(?:^|[ \t]+)#+$""")
    private val rule = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
    private val divider = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
    private val quote = Regex("""^ {0,3}>""")
    private val quoteMarker = Regex("""^ {0,3}>[ \t]?""")
    private val item = Regex("""^( {0,3})([-+*]|[0-9]{1,9}[.)])([ \t]+|$)""")
    private val task = Regex("""^\[[ xX]\][ \t]+""")
    private val definition = Regex("""^ {0,3}\[(?!\^)[^\]]+\]:[ \t]*\S""")
    private val hardBreak = Regex("""(?:\\| {2,})$""")
    private val pipe = Regex("""(?<!\\)\|""")
    private val brTag = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private const val MAX_ITEM_SPACES = 4

    @Suppress("CyclomaticComplexMethod") // The table of block starts, one line each, in CommonMark's order.
    fun blocks(lines: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        val paragraph = mutableListOf<String>()

        fun flush() {
            val words =
                paragraph
                    .map {
                        PlainInline
                            .of(
                                it.replace(hardBreak, "").trim(),
                            ).trim()
                    }.filter { it.isNotEmpty() }
            if (words.isNotEmpty()) out += words
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            when {
                line.isBlank() -> {
                    flush().also { i++ }
                }

                fence.matches(line) && !isBadFence(line) -> {
                    flush()
                    i = fenced(lines, i, out)
                }

                paragraph.isNotEmpty() && setext.matches(line) -> {
                    flush().also { i++ }
                }

                heading.matches(line) -> {
                    flush()
                    headingText(line).takeIf { it.isNotEmpty() }?.let { out += listOf(it) }
                    i++
                }

                rule.matches(line) -> {
                    flush().also { i++ }
                }

                next != null && isTableStart(line, next) -> {
                    flush()
                    i = table(lines, i, out)
                }

                quote.containsMatchIn(line) -> {
                    flush()
                    var end = i
                    while (end < lines.size && quote.containsMatchIn(lines[end])) end++
                    out += blocks(lines.subList(i, end).map { it.replaceFirst(quoteMarker, "") })
                    i = end
                }

                item.containsMatchIn(line) -> {
                    flush()
                    i = list(lines, i, out)
                }

                paragraph.isEmpty() && definition.containsMatchIn(line) -> {
                    i++
                }

                else -> {
                    paragraph += line
                    i++
                }
            }
        }
        flush()
        return out
    }

    private fun isBadFence(line: String): Boolean {
        val (_, marker, info) = fence.find(line)?.destructured ?: return true
        return marker.startsWith('`') && '`' in info
    }

    private fun headingText(line: String): String {
        val text =
            heading
                .find(line)
                ?.groupValues
                ?.get(2)
                .orEmpty()
        return PlainInline.of(text.replace(closingHashes, "")).trim()
    }

    /** A fenced block from [start]: its lines verbatim (less the fence's indent); returns the line after it. */
    private fun fenced(
        lines: List<String>,
        start: Int,
        out: MutableList<List<String>>,
    ): Int {
        val m = fence.find(lines[start])!!
        val indent = m.groupValues[1].length
        val marker = m.groupValues[2]
        val close = Regex("^ {0,3}" + Regex.escape(marker[0].toString()) + "{" + marker.length + ",}[ \\t]*$")
        val code = mutableListOf<String>()
        var i = start + 1
        while (i < lines.size && !close.matches(lines[i])) {
            val line = lines[i]
            val strip = line.takeWhile { it == ' ' }.length.coerceAtMost(indent)
            code += line.substring(strip)
            i++
        }
        if (code.any { it.isNotBlank() }) out += code
        return minOf(i + 1, lines.size)
    }

    private fun cells(row: String): List<String> {
        var text = row.trim()
        if (text.startsWith('|')) text = text.substring(1)
        if (text.endsWith('|') && !text.endsWith("\\|")) text = text.dropLast(1)
        return text.split(pipe)
    }

    private fun isTableStart(
        line: String,
        next: String,
    ): Boolean =
        '|' in line &&
            divider.matches(next) &&
            '-' in next &&
            cells(line).size == cells(next).size

    private fun table(
        lines: List<String>,
        start: Int,
        out: MutableList<List<String>>,
    ): Int {
        val rows = mutableListOf(row(lines[start]))
        var i = start + 2
        while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) rows += row(lines[i++])
        out += rows
        return i
    }

    private fun row(line: String): String =
        cells(line).joinToString("\t") { cell ->
            PlainInline.of(cell.trim().replace(brTag, " ")).trim()
        }

    /**
     * A list from [start]: each item's words, every line of them, with no
     * blank lines; nested lists follow their item. Returns the line after it.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // One pass sorting each line.
    private fun list(
        lines: List<String>,
        start: Int,
        out: MutableList<List<String>>,
    ): Int {
        val items = mutableListOf<MutableList<String>>()
        var contentIndent = 0
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            val indent = line.takeWhile { it == ' ' }.length
            val m = item.find(line)
            when {
                line.isBlank() -> {
                    val next = lines.subList(i, lines.size).firstOrNull { it.isNotBlank() } ?: break
                    val nextIndent = next.takeWhile { it == ' ' }.length
                    if (nextIndent < contentIndent && !item.containsMatchIn(next)) break
                    items.last() += ""
                }

                rule.matches(line) && indent < contentIndent -> {
                    break
                }

                m != null && (items.isEmpty() || indent < contentIndent) -> {
                    val (lead, marker, gap) = m.destructured
                    val width = lead.length + marker.length
                    val spaces = gap.length
                    val fits = spaces in 1..MAX_ITEM_SPACES
                    contentIndent = width + if (fits) spaces else 1
                    val textStart = width + if (fits) spaces else minOf(spaces, 1)
                    items += mutableListOf(line.substring(textStart).replaceFirst(task, ""))
                }

                indent >= contentIndent -> {
                    items.last() += line.substring(contentIndent)
                }

                lines[i - 1].isNotBlank() && !startsBlock(line) -> {
                    items.last() += line.trimStart()
                }

                else -> {
                    break
                }
            }
            i++
        }
        val words = items.flatMap { content -> blocks(content).flatten().filter { it.isNotBlank() } }
        if (words.isNotEmpty()) out += words
        return i
    }

    private fun startsBlock(line: String): Boolean =
        heading.matches(line) || fence.matches(line) || quote.containsMatchIn(line) || rule.matches(line) ||
            item.containsMatchIn(line)
}
