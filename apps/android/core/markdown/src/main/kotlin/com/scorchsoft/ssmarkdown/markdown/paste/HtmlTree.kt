package com.scorchsoft.ssmarkdown.markdown.paste

/** A node of pasted HTML: an element with its children, or a run of text (entities decoded). */
internal sealed interface HtmlNode {
    /** A run of text between tags. */
    class Text(
        val text: String,
    ) : HtmlNode

    /** An element; [name] is lower case, [attrs] keep the first of a repeated name. */
    class Element(
        val name: String,
        val attrs: Map<String, String> = emptyMap(),
    ) : HtmlNode {
        val children: MutableList<HtmlNode> = mutableListOf()

        fun attr(name: String): String? = attrs[name]
    }
}

/**
 * A tolerant HTML tree builder for clipboard HTML, written so its tree
 * matches what a browser's `DOMParser` builds for the HTML that browsers,
 * Gmail, Google Docs and Word put on the clipboard: comments and `<!…>`
 * markup go, scripts and styles are skipped whole, void elements take no
 * children, and the implicit end tags the HTML parser applies (a block
 * ends an open `<p>`, an `<li>` ends the item before it, cells and rows end
 * theirs) are applied. Nothing is fetched or run: this only reads text.
 * Preview's twin is `web/src/core/htmlToMarkdown.ts`, which uses the real
 * `DOMParser`; `paste-conversions.txt` keeps the two in step.
 */
internal class HtmlTreeBuilder private constructor(
    source: String,
) {
    private val html = source.replace("\r\n", "\n").replace('\r', '\n')
    private val root = HtmlNode.Element("#root")
    private val stack = mutableListOf(root)
    private val text = StringBuilder()
    private var at = 0

    private fun build(): HtmlNode.Element {
        while (at < html.length) {
            val c = html[at]
            val next = html.getOrNull(at + 1)
            when {
                c != '<' -> text.append(c).also { at++ }
                html.startsWith("<!--", at) -> skipPast("-->", at + COMMENT_OPEN)
                next == '!' || next == '?' -> skipPast(">", at + 1)
                next == '/' && html.getOrNull(at + 2)?.isLetter() == true -> endTag()
                next?.isLetter() == true -> startTag()
                else -> text.append(c).also { at++ }
            }
        }
        flushText()
        return root
    }

    private fun skipPast(
        end: String,
        from: Int,
    ) {
        flushText()
        val found = html.indexOf(end, from)
        at = if (found < 0) html.length else found + end.length
    }

    private fun flushText() {
        if (text.isEmpty()) return
        val decoded = HtmlEntities.decode(text.toString())
        text.clear()
        if (stack.last().name == "head" && decoded.isNotBlank()) popThrough("head")
        stack.last().children += HtmlNode.Text(decoded)
    }

    private fun endTag() {
        flushText()
        val close = html.indexOf('>', at)
        val raw = html.substring(at + 2, if (close < 0) html.length else close)
        at = if (close < 0) html.length else close + 1
        val name = raw.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
        if (name == "br") {
            stack.last().children += HtmlNode.Element("br")
            return
        }
        // As the HTML parser does for a well-formed clip: pop through the element, never out of a cell or table.
        val stops = if (name in TABLE_PARTS) setOf("table") else END_SCOPE
        for (i in stack.indices.reversed()) {
            val open = stack[i].name
            if (open == name) {
                while (stack.size > i) stack.removeAt(stack.lastIndex)
                return
            }
            if (open in stops || open == "#root") return
        }
    }

    private fun startTag() {
        flushText()
        val tag = TagReader(html, at + 1).read()
        at = tag.end
        if (tag.name in HtmlNames.RAW_TEXT) {
            skipRawText(tag.name)
            return
        }
        closeImplied(tag.name)
        val element = HtmlNode.Element(tag.name, tag.attrs)
        if (stack.size > MAX_DEPTH) return
        // A cell straight inside a table gets the row the HTML parser makes for it.
        if ((tag.name == "td" || tag.name == "th") && stack.last().name in ROW_PARENTS) {
            val row = HtmlNode.Element("tr")
            stack.last().children += row
            stack += row
        }
        stack.last().children += element
        if (tag.name in HtmlNames.VOID) return
        stack += element
        // The HTML parser drops one line break straight after these start tags.
        if (tag.name == "pre" || tag.name == "listing") {
            if (html.getOrNull(at) == '\n') at++
        }
    }

    private fun skipRawText(name: String) {
        val close = html.indexOf("</$name", at, ignoreCase = true)
        if (close < 0) {
            at = html.length
            return
        }
        val end = html.indexOf('>', close)
        at = if (end < 0) html.length else end + 1
    }

    /** The end tags the HTML parser implies before [name] starts. */
    private fun closeImplied(name: String) {
        if (stack.last().name == "head" && name !in HtmlNames.HEAD_CONTENT) popThrough("head")
        if (name == "body") popThrough("head")
        if (name in HtmlNames.CLOSES_P) closeInScope(setOf("p"), P_SCOPE)
        when (name) {
            "li" -> closeInScope(setOf("li"), LIST_SCOPE)
            "dd", "dt" -> closeInScope(setOf("dd", "dt"), LIST_SCOPE)
            "td", "th" -> closeInScope(setOf("td", "th"), setOf("tr", "table"))
            "tr" -> closeInScope(setOf("tr"), setOf("table"))
            "thead", "tbody", "tfoot" -> closeInScope(setOf("thead", "tbody", "tfoot"), setOf("table"))
            in HtmlNames.HEADINGS -> if (stack.last().name in HtmlNames.HEADINGS) stack.removeAt(stack.lastIndex)
        }
    }

    /** Pops through the innermost open element named one of [names], unless one of [stops] comes first. */
    private fun closeInScope(
        names: Set<String>,
        stops: Set<String>,
    ) {
        for (i in stack.indices.reversed()) {
            val open = stack[i].name
            if (open in names) {
                while (stack.size > i) stack.removeAt(stack.lastIndex)
                return
            }
            if (open in stops || open == "#root") return
        }
    }

    private fun popThrough(name: String) {
        val i = stack.indexOfLast { it.name == name }
        if (i > 0) while (stack.size > i) stack.removeAt(stack.lastIndex)
    }

    /** One start tag's name and attributes, read from just after its `<`. */
    private class TagReader(
        private val html: String,
        private var at: Int,
    ) {
        class Tag(
            val name: String,
            val attrs: Map<String, String>,
            val end: Int,
        )

        @Suppress("CyclomaticComplexMethod") // One scan of `name key=value key='value' key`, as browsers read it.
        fun read(): Tag {
            val name = word { it.isWhitespace() || it == '/' || it == '>' }.lowercase()
            val attrs = LinkedHashMap<String, String>()
            while (at < html.length && html[at] != '>') {
                if (html[at].isWhitespace() || html[at] == '/') {
                    at++
                    continue
                }
                val key = word { it.isWhitespace() || it == '=' || it == '>' || it == '/' }.lowercase()
                skipSpace()
                var value = ""
                if (html.getOrNull(at) == '=') {
                    at++
                    skipSpace()
                    value = value()
                }
                if (key.isNotEmpty() && key !in attrs) attrs[key] = HtmlEntities.decode(value)
                if (key.isEmpty()) at++
            }
            return Tag(name, attrs, minOf(at + 1, html.length))
        }

        private fun value(): String {
            val quote = html.getOrNull(at)
            if (quote == '"' || quote == '\'') {
                val close = html.indexOf(quote, at + 1)
                val end = if (close < 0) html.length else close
                return html.substring(at + 1, end).also { at = minOf(end + 1, html.length) }
            }
            return word { it.isWhitespace() || it == '>' }
        }

        private inline fun word(stop: (Char) -> Boolean): String {
            val start = at
            while (at < html.length && !stop(html[at])) at++
            return html.substring(start, at)
        }

        private fun skipSpace() {
            while (at < html.length && html[at].isWhitespace()) at++
        }
    }

    companion object {
        private const val COMMENT_OPEN = 4

        /** Deeper start tags are ignored (their text still counts); a browser caps its tree at 512 too. */
        private const val MAX_DEPTH = 256

        /** `</p>` and friends look this far up for their element. */
        private val P_SCOPE = setOf("td", "th", "table", "caption", "button", "html")
        private val LIST_SCOPE = setOf("ul", "ol", "table", "td", "th", "blockquote")

        /** An end tag looks for its element no further out than these. */
        private val END_SCOPE = setOf("td", "th", "table", "caption")
        private val ROW_PARENTS = setOf("table", "thead", "tbody", "tfoot")
        private val TABLE_PARTS = setOf("td", "th", "tr", "thead", "tbody", "tfoot", "table")

        /** The tree of [html]; never throws. */
        fun parse(html: String): HtmlNode.Element = HtmlTreeBuilder(html).build()
    }
}

/** The element names the tree builder and the converter sort by. */
internal object HtmlNames {
    val VOID =
        setOf(
            "area",
            "base",
            "br",
            "col",
            "embed",
            "hr",
            "img",
            "input",
            "link",
            "meta",
            "param",
            "source",
            "track",
            "wbr",
        )

    /** Elements whose content is raw text, skipped whole: none of them pastes. */
    val RAW_TEXT = setOf("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes")

    val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    val HEAD_CONTENT = setOf("meta", "link", "style", "script", "title", "base", "template")

    val BLOCK =
        setOf(
            "address",
            "article",
            "aside",
            "blockquote",
            "body",
            "html",
            "center",
            "dd",
            "details",
            "dir",
            "div",
            "dl",
            "dt",
            "fieldset",
            "figcaption",
            "figure",
            "footer",
            "form",
            "header",
            "hgroup",
            "hr",
            "li",
            "main",
            "menu",
            "nav",
            "ol",
            "p",
            "pre",
            "section",
            "summary",
            "table",
            "caption",
            "thead",
            "tbody",
            "tfoot",
            "tr",
            "td",
            "th",
            "ul",
            "legend",
            "listing",
        ) + HEADINGS

    /** Start tags that end an open paragraph. */
    val CLOSES_P =
        BLOCK - setOf("body", "html", "caption", "thead", "tbody", "tfoot", "tr", "td", "th", "legend")

    /** Elements that never paste: scripts, styles, media, forms, embedded documents. */
    val DROPPED =
        RAW_TEXT +
            setOf(
                "head",
                "template",
                "noscript",
                "object",
                "embed",
                "svg",
                "math",
                "canvas",
                "video",
                "audio",
                "select",
                "option",
                "optgroup",
                "datalist",
                "button",
                "input",
                "meta",
                "link",
                "base",
                "frame",
                "frameset",
                "map",
                "area",
                "param",
                "source",
                "track",
            )
}
