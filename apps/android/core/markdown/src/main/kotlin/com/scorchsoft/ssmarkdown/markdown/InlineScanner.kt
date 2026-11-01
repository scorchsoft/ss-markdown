// Scanners bail out the moment a construct fails to match, as CommonMark's
// own reference parsers do; one exit per function would bury the grammar in flags.
@file:Suppress("ReturnCount")

package com.scorchsoft.ssmarkdown.markdown

/**
 * Inline Markdown for one line, painted onto a [TokenBuilder] in three
 * passes that mirror CommonMark's precedence (spec §6):
 *
 * 1. **Atomic spans**, left to right: escapes, code spans, autolinks, raw
 *    HTML, math and bare URLs. Whatever starts first wins, and nothing
 *    inside one is markup. The same pass records brackets, delimiter runs
 *    and table pipes outside those spans.
 * 2. **Links and images**, from the bracket stack ("look for link or image").
 * 3. **Emphasis**, strong and strikethrough, from the delimiter runs
 *    ([EmphasisRuns]).
 *
 * Simplified on purpose: links must close on the same line, a shortcut
 * reference `[foo]` is left as text (whether it resolves is document-wide,
 * and tokens must stay a function of the line and its start state), and
 * emphasis may cross a link boundary. Not thread-safe: one per tokenizer.
 */
@Suppress("TooManyFunctions") // Three passes of a small parser; each function is one rule of the spec.
internal class InlineScanner(
    private val out: TokenBuilder,
) {
    private var text: CharSequence = ""
    private var lineStart = 0
    private var end = 0
    private var base = TokenKind.PLAIN

    private val runs = EmphasisRuns(out)

    private var bracketAt = IntArray(INITIAL)
    private var bracketType = IntArray(INITIAL)
    private var bracketActive = BooleanArray(INITIAL)
    private var bracketCount = 0

    /** Unescaped `|` columns outside code spans, in order: table cells for the tokenizer. */
    var pipes: IntArray = IntArray(INITIAL)
        private set
    var pipeCount: Int = 0
        private set

    private val openers = IntArray(MAX_OPEN_BRACKETS)

    /** Link text ranges as (start, end) pairs, so emphasis inside a link can claim its text. */
    private var linkTexts = IntArray(INITIAL)
    private var linkCount = 0

    /** Paints `[from, to)` of [line] as inline content over [baseKind]. */
    fun scan(
        line: CharSequence,
        from: Int,
        to: Int,
        baseKind: TokenKind,
    ) {
        text = line
        lineStart = from
        end = to
        base = baseKind
        runs.reset(baseKind)
        bracketCount = 0
        pipeCount = 0
        linkCount = 0
        out.paint(from, to, baseKind)
        scanAtomic()
        resolveLinks()
        runs.resolve(linkTexts, linkCount)
    }

    @Suppress("CyclomaticComplexMethod") // A dispatch on the next character, one short branch per construct.
    private fun scanAtomic() {
        var i = lineStart
        while (i < end) {
            i =
                when (text[i]) {
                    '\\' -> escape(i)
                    '`' -> codeSpan(i)
                    '<' -> angle(i)
                    '$' -> math(i)
                    '[' -> bracket(i, OPEN, 1)
                    ']' -> bracket(i, CLOSE, 1)
                    '!' -> if (i + 1 < end && text[i + 1] == '[') bracket(i, IMAGE, 2) else i + 1
                    '*', '_', '~' -> delimiterRun(i)
                    '|' -> pipe(i)
                    'h', 'H', 'w', 'W' -> bareUrl(i)
                    else -> i + 1
                }
        }
    }

    private fun escape(at: Int): Int {
        if (at + 1 < end && InlineSyntax.isAsciiPunctuation(text[at + 1])) {
            out.paint(at, at + 1, TokenKind.ESCAPE)
            return at + 2
        }
        return at + 1
    }

    private fun codeSpan(at: Int): Int {
        val ticks = runOf(at, '`')
        var i = at + ticks
        while (i < end) {
            if (text[i] == '`') {
                val closing = runOf(i, '`')
                if (closing == ticks) {
                    out.paint(at, at + ticks, TokenKind.CODE_SPAN_MARKER)
                    out.paint(at + ticks, i, TokenKind.CODE_SPAN_TEXT)
                    out.paint(i, i + ticks, TokenKind.CODE_SPAN_MARKER)
                    return i + ticks
                }
                i += closing
            } else {
                i++
            }
        }
        return at + ticks
    }

    private fun angle(at: Int): Int {
        val autolink = InlineSyntax.autolinkEnd(text, at, end)
        if (autolink > 0) {
            out.paint(at, autolink, TokenKind.AUTOLINK)
            return autolink
        }
        val tag = InlineSyntax.htmlTagEnd(text, at, end)
        if (tag > 0) {
            out.paint(at, tag, TokenKind.HTML_TAG)
            return tag
        }
        return at + 1
    }

    /** `$x$` (no space just inside, no digit just after) or `$$x$$` on one line. */
    private fun math(at: Int): Int {
        val width = if (at + 1 < end && text[at + 1] == '$') 2 else 1
        val contentStart = at + width
        if (contentStart >= end || (width == 1 && BlockSyntax.isSpace(text[contentStart]))) return contentStart
        var i = contentStart
        while (i < end) {
            when {
                text[i] == '\\' -> {
                    i++
                }

                text[i] == '$' && closesMath(i, width, contentStart) -> {
                    out.paint(at, contentStart, TokenKind.MATH_DELIMITER)
                    out.paint(contentStart, i, TokenKind.MATH_TEXT)
                    out.paint(i, i + width, TokenKind.MATH_DELIMITER)
                    return i + width
                }
            }
            i++
        }
        return contentStart
    }

    private fun closesMath(
        at: Int,
        width: Int,
        contentStart: Int,
    ): Boolean {
        if (at == contentStart) return false
        if (width == 2) return at + 1 < end && text[at + 1] == '$'
        val after = at + 1
        return !BlockSyntax.isSpace(text[at - 1]) && (after >= end || text[after] !in '0'..'9')
    }

    private fun bareUrl(at: Int): Int {
        val urlEnd = InlineSyntax.bareUrlEnd(text, at, lineStart, end)
        if (urlEnd < 0) return at + 1
        out.paint(at, urlEnd, TokenKind.AUTOLINK)
        return urlEnd
    }

    private fun pipe(at: Int): Int {
        if (pipeCount == pipes.size) pipes = pipes.copyOf(pipeCount * 2)
        pipes[pipeCount++] = at
        return at + 1
    }

    private fun bracket(
        at: Int,
        type: Int,
        width: Int,
    ): Int {
        if (bracketCount == bracketAt.size) {
            val size = bracketCount * 2
            bracketAt = bracketAt.copyOf(size)
            bracketType = bracketType.copyOf(size)
            bracketActive = bracketActive.copyOf(size)
        }
        bracketAt[bracketCount] = at
        bracketType[bracketCount] = type
        bracketActive[bracketCount] = true
        bracketCount++
        return at + width
    }

    private fun delimiterRun(at: Int): Int {
        val c = text[at]
        val length = runOf(at, c)
        val after = at + length
        if (c == '~' && length > 2) return after
        val before = if (at > lineStart) text[at - 1] else ' '
        val next = if (after < end) text[after] else ' '
        runs.add(at, length, c, before, next)
        return after
    }

    private fun runOf(
        at: Int,
        c: Char,
    ): Int {
        var i = at
        while (i < end && text[i] == c) i++
        return i - at
    }

    private var openerDepth = 0

    private fun resolveLinks() {
        openerDepth = 0
        var skipUntil = -1
        for (k in 0 until bracketCount) {
            when {
                bracketAt[k] < skipUntil -> Unit
                bracketType[k] != CLOSE -> if (openerDepth < MAX_OPEN_BRACKETS) openers[openerDepth++] = k
                openerDepth > 0 -> skipUntil = maxOf(skipUntil, closeBracket(openers[--openerDepth], bracketAt[k]))
            }
        }
    }

    /** Links may not contain links: every `[` (not `![`) still open becomes text. */
    private fun deactivateOuterLinks() {
        for (d in 0 until openerDepth) if (bracketType[openers[d]] == OPEN) bracketActive[openers[d]] = false
    }

    /** Pairs the `]` at [closeAt] with [opener]; returns where a link formed ends, or -1. */
    private fun closeBracket(
        opener: Int,
        closeAt: Int,
    ): Int {
        if (!bracketActive[opener]) return -1
        val tailEnd = linkTail(closeAt + 1)
        val isLink = bracketType[opener] == OPEN
        when {
            tailEnd > 0 -> {
                paintLink(opener, closeAt, tailEnd)
                if (isLink) deactivateOuterLinks()
            }

            isLink -> {
                footnoteRef(bracketAt[opener], closeAt)
            }
        }
        return tailEnd
    }

    private var urlStart = 0
    private var urlEnd = 0
    private var titleStart = 0
    private var titleEnd = 0
    private var labelTail = false

    /** Parses `(dest "title")`, `[label]` or `[]` at [at]; returns its end or -1, leaving positions in fields. */
    private fun linkTail(at: Int): Int {
        labelTail = at < end && text[at] == '['
        return when {
            at >= end -> -1
            labelTail -> labelTailEnd(at)
            text[at] == '(' -> inlineTailEnd(at)
            else -> -1
        }
    }

    /** `[label]` or the collapsed `[]`. */
    private fun labelTailEnd(at: Int): Int {
        val close = if (at + 1 < end && text[at + 1] == ']') at + 1 else BlockSyntax.labelEnd(text, at, end)
        urlStart = at + 1
        urlEnd = close
        return if (close > 0) close + 1 else -1
    }

    /** `(destination "title")`, either part optional. */
    private fun inlineTailEnd(at: Int): Int {
        urlStart = BlockSyntax.skipSpaces(text, at + 1, end)
        val empty = urlStart < end && text[urlStart] == ')'
        urlEnd = if (empty) urlStart else InlineSyntax.destinationEnd(text, urlStart, end)
        if (urlEnd < 0) return -1
        titleStart = BlockSyntax.skipSpaces(text, urlEnd, end)
        titleEnd = if (titleStart > urlEnd) InlineSyntax.titleEnd(text, titleStart, end) else -1
        if (titleEnd < 0) titleStart = -1
        val close = BlockSyntax.skipSpaces(text, if (titleEnd > 0) titleEnd else urlEnd, end)
        return if (close < end && text[close] == ')') close + 1 else -1
    }

    private fun paintLink(
        opener: Int,
        closeAt: Int,
        tailEnd: Int,
    ) {
        val openAt = bracketAt[opener]
        val textStart = openAt + if (bracketType[opener] == IMAGE) 2 else 1
        if (bracketType[opener] == IMAGE) out.paint(openAt, openAt + 1, TokenKind.IMAGE_MARKER)
        out.paint(textStart - 1, textStart, TokenKind.LINK_PUNCTUATION)
        out.paintOver(textStart, closeAt, TokenKind.LINK_TEXT, base)
        out.addStyle(textStart, closeAt, InlineStyle.LINK)
        if (linkCount * 2 == linkTexts.size) linkTexts = linkTexts.copyOf(linkTexts.size * 2)
        linkTexts[linkCount * 2] = textStart
        linkTexts[linkCount * 2 + 1] = closeAt
        linkCount++
        out.paint(closeAt, tailEnd, TokenKind.LINK_PUNCTUATION)
        out.paint(urlStart, urlEnd, if (labelTail) TokenKind.LINK_LABEL else TokenKind.LINK_URL)
        if (!labelTail && titleStart >= 0) out.paint(titleStart, titleEnd, TokenKind.LINK_TITLE)
        runs.kill(closeAt, tailEnd)
    }

    private fun footnoteRef(
        openAt: Int,
        closeAt: Int,
    ) {
        if (closeAt > openAt + 2 && text[openAt + 1] == '^') {
            out.paint(openAt, closeAt + 1, TokenKind.FOOTNOTE_REF)
            runs.kill(openAt, closeAt)
        }
    }

    private companion object {
        const val INITIAL = 16
        const val MAX_OPEN_BRACKETS = 64
        const val OPEN = 0
        const val IMAGE = 1
        const val CLOSE = 2
    }
}
