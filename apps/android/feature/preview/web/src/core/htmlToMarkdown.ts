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
 * The HTML is parsed with `DOMParser` into a detached document: it is never
 * put in the page, so nothing in it runs or loads. Kotlin's twin
 * (`core/markdown/…/paste/HtmlToMarkdown.kt`) walks its own tolerant parse
 * the same way; `test/vectors/paste-conversions.txt` holds the cases both
 * must give exactly.
 */
import { COLLAPSIBLE, NBSP, escapeBlockStart, escapeInline, longestRun } from "./markdownText";

export interface ConvertedHtml {
    readonly markdown: string;
    /** True when any of it was formatting, not just text in paragraphs. */
    readonly formatted: boolean;
}

const HEADINGS = new Set(["h1", "h2", "h3", "h4", "h5", "h6"]);

const BLOCK = new Set([
    ..."address article aside blockquote body html center dd details dir div dl dt fieldset figcaption figure footer form header hgroup hr li main menu nav ol p pre section summary table caption thead tbody tfoot tr td th ul legend listing".split(
        " ",
    ),
    ...HEADINGS,
]);

const DROPPED = new Set(
    "script style textarea title xmp iframe noembed noframes head template noscript object embed svg math canvas video audio select option optgroup datalist button input meta link base frame frameset map area param source track".split(
        " ",
    ),
);

const CODE = new Set(["code", "kbd", "samp", "tt"]);
const COMPLEX_IN_CELL = new Set(["table", "ul", "ol", "pre", "listing", "blockquote", "hr", "dl", ...HEADINGS]);

/** A line break inside inline text, until paragraphs are split on it. */
const BR = "\u0001";
const EDGE = new Set([" ", NBSP, BR]);
const FENCE = 3;
const MAX_NUMBER = 999_999_999;
const BOLD_WEIGHT = 600;

const SAFE_LINK = /^(?:https?:|mailto:)/i;
const WEB = /^https?:\/\//i;
const LIST_START = /^(?:[-+*]|[0-9]{1,9}[.)])(?: |$)/;
const LANGUAGE = /(?:^|\s)(?:language|lang)-([A-Za-z0-9_#+.-]+)/;
const FONT_WEIGHT = /font-weight\s*:\s*([a-z0-9]+)/;
const FONT_STYLE = /font-style\s*:\s*([a-z]+)/;
const LINE_THROUGH = /text-decoration[a-z-]*\s*:[^;]*line-through/;
const DISPLAY_NONE = /display\s*:\s*none|mso-list\s*:\s*ignore/;
const WORD_CLASS = /(?:^|\s)MsoListParagraph/i;
const WORD_STYLE = /mso-list\s*:\s*l[0-9]/i;
const WORD_IGNORE = /mso-list\s*:\s*ignore/i;
const WORD_LEVEL = /level([0-9]+)/i;
const WORD_NUMBER = /^([0-9]{1,9})([.)])$/;

type Mark = "**" | "*" | "~~";

/** Marks in force (`active`: never wrapped again) and those a block must wrap its text in (`wrap`). */
interface Context {
    readonly active: ReadonlySet<Mark>;
    readonly wrap: readonly Mark[];
}

const ROOT: Context = { active: new Set(), wrap: [] };

function withMarks(ctx: Context, marks: readonly Mark[]): Context {
    const added = marks.filter((m) => !ctx.active.has(m));
    return added.length === 0 ? ctx : { active: new Set([...ctx.active, ...added]), wrap: [...ctx.wrap, ...added] };
}

/** Parses [html] inert: a document of its own, never attached to the page. */
export function parseHtml(html: string): Document {
    return new DOMParser().parseFromString(html, "text/html");
}

/** [html] as Markdown; never throws. [parse] is the browser's own parser, or jsdom's in unit tests. */
export function htmlToMarkdown(html: string, parse: (html: string) => Document = parseHtml): ConvertedHtml {
    const walker = new Walker();
    const md = walker
        .blocks([...parse(html).body.childNodes], ROOT)
        .filter((b) => !isBlank(b))
        .join("\n\n");
    return { markdown: md.replace(/^\n+|\n+$/g, ""), formatted: walker.formatted };
}

function isElement(node: Node): node is Element {
    return node.nodeType === 1;
}

function isText(node: Node): boolean {
    return node.nodeType === 3;
}

function isBlank(s: string): boolean {
    return s.trim() === "";
}

function name(el: Element): string {
    return el.localName.toLowerCase();
}

function children(node: Node): Node[] {
    return [...node.childNodes].filter((n) => isElement(n) || isText(n));
}

function hidden(el: Element): boolean {
    return (
        DROPPED.has(name(el)) ||
        el.hasAttribute("hidden") ||
        DISPLAY_NONE.test((el.getAttribute("style") ?? "").toLowerCase())
    );
}

/** One line of output: spaces collapsed, ends trimmed, no-break spaces made plain. */
function clean(line: string): string {
    return line
        .replace(/ {2,}/g, " ")
        .replace(/^[ \u00a0]+|[ \u00a0]+$/g, "")
        .split(NBSP)
        .join(" ");
}

function indent(text: string, width: number): string {
    return text
        .split("\n")
        .map((l) => (l === "" ? l : " ".repeat(width) + l))
        .join("\n");
}

function languageOf(el: Element): string | null {
    return LANGUAGE.exec(el.getAttribute("class") ?? "")?.[1] ?? null;
}

function marks(el: Element): Mark[] {
    const style = (el.getAttribute("style") ?? "").toLowerCase();
    const tag = name(el);
    const weight = FONT_WEIGHT.exec(style)?.[1];
    const bold =
        weight !== undefined
            ? weight === "bold" || weight === "bolder" || (Number.parseInt(weight, 10) || 0) >= BOLD_WEIGHT
            : tag === "b" || tag === "strong";
    const fontStyle = FONT_STYLE.exec(style)?.[1];
    const italic =
        fontStyle !== undefined ? fontStyle === "italic" || fontStyle === "oblique" : tag === "i" || tag === "em";
    const strike = tag === "s" || tag === "del" || tag === "strike" || LINE_THROUGH.test(style);
    const out: Mark[] = [];
    if (bold) out.push("**");
    if (italic) out.push("*");
    if (strike) out.push("~~");
    return out;
}

/** A link or picture address for Markdown: in `<>` when spaces, angle brackets or odd parentheses would end it. */
function destination(url: string): string {
    const clean = url.replace(/[\t\n\r]/g, "");
    const balanced = clean.split("(").length === clean.split(")").length;
    if (balanced && !/[ <>]/.test(clean)) return clean;
    return `<${clean.replace(/ /g, "%20").replace(/</g, "%3C").replace(/>/g, "%3E")}>`;
}

function intOr(value: string | null, fallback: number): number {
    const v = value?.trim() ?? "";
    return /^[+-]?[0-9]+$/.test(v) ? Number.parseInt(v, 10) : fallback;
}

function isWordItem(node: Node): node is Element {
    return (
        isElement(node) &&
        name(node) === "p" &&
        (WORD_CLASS.test(node.getAttribute("class") ?? "") || WORD_STYLE.test(node.getAttribute("style") ?? ""))
    );
}

class Walker {
    formatted = false;

    blocks(nodes: readonly Node[], ctx: Context): string[] {
        const out: string[] = [];
        let run: Node[] = [];
        const flush = (): void => {
            if (run.length > 0) out.push(...this.paragraph(run, ctx));
            run = [];
        };
        for (let i = 0; i < nodes.length; i++) {
            const node = nodes[i] as Node;
            if (!isElement(node)) {
                if (isText(node)) run.push(node);
            } else if (hidden(node)) {
                continue;
            } else if (isWordItem(node)) {
                flush();
                const items: Element[] = [];
                let j = i;
                for (; j < nodes.length; j++) {
                    const n = nodes[j] as Node;
                    if (isWordItem(n)) items.push(n);
                    else if (!(isText(n) && isBlank(n.textContent ?? ""))) break;
                }
                out.push(this.wordList(items, ctx));
                i = nodes.indexOf(items[items.length - 1] as Node);
            } else if (BLOCK.has(name(node))) {
                flush();
                out.push(...this.block(node, ctx));
            } else if (this.hasDescendant(node, (n) => BLOCK.has(n))) {
                flush();
                out.push(...this.blocks(children(node), withMarks(ctx, marks(node))));
            } else {
                run.push(node);
            }
        }
        flush();
        return out;
    }

    private block(el: Element, ctx: Context): string[] {
        const tag = name(el);
        if (HEADINGS.has(tag)) return this.heading(el, ctx);
        switch (tag) {
            case "pre":
            case "listing": {
                const code = this.codeBlock(el);
                return code === null ? [] : [code];
            }
            case "blockquote":
                return this.quote(el, ctx);
            case "ul":
            case "ol": {
                const list = this.list(el, ctx);
                return list === "" ? [] : [list];
            }
            case "li":
                return [this.item(el, "- ", ctx)];
            case "table":
                return this.table(el, ctx);
            case "hr":
                this.formatted = true;
                return ["---"];
            default:
                return this.blocks(children(el), ctx);
        }
    }

    /** Inline [nodes] as paragraphs: `<br>` is a hard break, two in a row end the paragraph. */
    private paragraph(nodes: readonly Node[], ctx: Context): string[] {
        const text = this.wrapAll(nodes.map((n) => this.inline(n, ctx)).join(""), ctx.wrap);
        const paragraphs: string[] = [];
        let lines: string[] = [];
        for (const line of [...text.split(BR).map((l) => escapeBlockStart(clean(l))), ""]) {
            if (line !== "") {
                lines.push(line);
            } else if (lines.length > 0) {
                paragraphs.push(lines.join("\\\n"));
                lines = [];
            }
        }
        return paragraphs;
    }

    private heading(el: Element, ctx: Context): string[] {
        // A heading is bold already: bold inside it adds nothing.
        const inner: Context = { active: new Set([...ctx.active, "**"]), wrap: [] };
        const text = clean(
            children(el)
                .map((n) => this.inline(n, inner))
                .join("")
                .split(BR)
                .join(" "),
        );
        if (text === "") return [];
        this.formatted = true;
        return ["#".repeat(Number(name(el)[1])) + " " + text];
    }

    private quote(el: Element, ctx: Context): string[] {
        const inner = this.blocks(children(el), ctx)
            .filter((b) => !isBlank(b))
            .join("\n\n");
        if (inner === "") return [];
        this.formatted = true;
        return [
            inner
                .split("\n")
                .map((l) => (l === "" ? ">" : `> ${l}`))
                .join("\n"),
        ];
    }

    private codeBlock(el: Element): string | null {
        const code = children(el).find((n): n is Element => isElement(n) && name(n) === "code");
        const language = languageOf(el) ?? (code ? languageOf(code) : null) ?? "";
        const text = this.preText(children(el)).split(NBSP).join(" ").replace(/\n+$/, "").replace(/^\n+/, "");
        if (isBlank(text)) return null;
        this.formatted = true;
        const fence = "`".repeat(Math.max(FENCE, longestRun(text, "`") + 1));
        return `${fence}${language}\n${text}\n${fence}`;
    }

    private list(el: Element, ctx: Context): string {
        const ordered = name(el) === "ol";
        let number = Math.min(Math.max(intOr(el.getAttribute("start"), 1), 0), MAX_NUMBER);
        const items: string[] = [];
        let width = 2;
        for (const child of children(el)) {
            if (isText(child) && isBlank(child.textContent ?? "")) continue;
            if (isElement(child) && hidden(child)) continue;
            if (isElement(child) && (name(child) === "ul" || name(child) === "ol")) {
                const nested = this.list(child, ctx);
                if (nested === "") continue;
                if (items.length === 0) items.push(nested);
                else items[items.length - 1] += "\n" + indent(nested, width);
                continue;
            }
            const marker = ordered ? `${number++}. ` : "- ";
            width = marker.length;
            items.push(this.item(child, marker, ctx));
        }
        if (items.length > 0) this.formatted = true;
        return items.join("\n");
    }

    private item(node: Node, marker: string, ctx: Context): string {
        const nodes = isElement(node) && name(node) === "li" ? children(node) : [node];
        const box = (isElement(node) ? this.taskBox(node) : null) ?? "";
        const parts = this.blocks(nodes, ctx).filter((b) => !isBlank(b));
        if (parts.length === 0) return (marker + box).trimEnd();
        let body = "";
        parts.forEach((part, k) => {
            if (k > 0) body += LIST_START.test(part) ? "\n" : "\n\n";
            body += part;
        });
        return marker + box + indent(body, marker.length).replace(/^ +/, "");
    }

    /** `[x] ` or `[ ] ` for an item with a checkbox first in it (GitHub's task lists). */
    private taskBox(li: Element): string | null {
        const first = children(li).find(isElement);
        const near = [...children(li), ...(first ? children(first) : [])];
        const box = near.find(
            (n): n is Element =>
                isElement(n) && name(n) === "input" && (n.getAttribute("type") ?? "").toLowerCase() === "checkbox",
        );
        if (!box) return null;
        this.formatted = true;
        return box.hasAttribute("checked") ? "[x] " : "[ ] ";
    }

    /** Word's lists: paragraphs with a hidden marker span and a `level` in their style. */
    private wordList(items: readonly Element[], ctx: Context): string {
        const open: [number, string][] = [];
        const lines: string[] = [];
        for (const p of items) {
            const level = intOr(WORD_LEVEL.exec(p.getAttribute("style") ?? "")?.[1] ?? null, 1);
            while (open.length > 0 && (open[open.length - 1] as [number, string])[0] >= level) open.pop();
            const pad = open[open.length - 1]?.[1] ?? "";
            const marker = wordMarker(p);
            const text = this.paragraph(children(p), ctx).join(" ");
            lines.push(`${pad}${marker} ${text}`.trimEnd());
            open.push([level, pad + " ".repeat(marker.length + 1)]);
        }
        this.formatted = true;
        return lines.join("\n");
    }

    private table(el: Element, ctx: Context): string[] {
        const rows: Element[] = [];
        const captions: Element[] = [];
        collectRows(el, rows, captions);
        const out = captions.flatMap((c) => this.blocks(children(c), ctx));
        const cells = rows.map((row) =>
            children(row).filter((n): n is Element => isElement(n) && /^t[dh]$/.test(name(n))),
        );
        if (cells.every((r) => r.length === 0)) return out;
        this.formatted = true;
        if (cells.every((row) => row.every((c) => this.isSimpleCell(c)))) {
            const columns = Math.max(...cells.map((r) => r.length));
            const texts = cells.filter((r) => r.length > 0).map((row) => row.map((c) => this.cellText(c, ctx, "<br>")));
            const line = (row: readonly string[]): string =>
                "| " + Array.from({ length: columns }, (_, i) => row[i] ?? "").join(" | ") + " |";
            const divider = "| " + Array.from({ length: columns }, () => "---").join(" | ") + " |";
            out.push([line(texts[0] ?? []), divider, ...texts.slice(1).map(line)].join("\n"));
        } else {
            // Merged or nested cells have no GFM form: each row becomes a line of its cells' words.
            for (const row of cells) {
                const text = row
                    .map((c) => this.cellText(c, ctx, " "))
                    .filter((t) => t !== "")
                    .join(" | ");
                if (text !== "") out.push(escapeBlockStart(text));
            }
        }
        return out;
    }

    private isSimpleCell(cell: Element): boolean {
        if (intOr(cell.getAttribute("colspan"), 1) > 1 || intOr(cell.getAttribute("rowspan"), 1) > 1) return false;
        const blocks = children(cell).filter((n) => isElement(n) && BLOCK.has(name(n)) && !hidden(n)).length;
        return blocks <= 1 && !this.hasDescendant(cell, (n) => COMPLEX_IN_CELL.has(n));
    }

    /** A cell's words on one line: its lines joined by [lineBreak], pipes escaped. */
    private cellText(cell: Element, ctx: Context, lineBreak: string): string {
        return this.wrapAll(
            children(cell)
                .map((n) => this.inline(n, ctx))
                .join(""),
            ctx.wrap,
        )
            .split(BR)
            .map(clean)
            .filter((l) => l !== "")
            .join(lineBreak)
            .replace(/\|/g, "\\|");
    }

    private inline(node: Node, ctx: Context): string {
        if (!isElement(node)) return escapeInline((node.textContent ?? "").replace(COLLAPSIBLE, " "));
        const tag = name(node);
        if (hidden(node)) return "";
        if (tag === "br") return BR;
        if (tag === "img") return this.image(node);
        if (tag === "a") return this.link(node, ctx);
        if (CODE.has(tag)) return this.codeSpan(node);
        const inner = (c: Context): string =>
            children(node)
                .map((n) => this.inline(n, c))
                .join("");
        if (BLOCK.has(tag)) return BR + inner(ctx) + BR;
        const added = marks(node).filter((m) => !ctx.active.has(m));
        return this.wrapAll(inner(withMarks(ctx, added)), added);
    }

    private link(el: Element, ctx: Context): string {
        const href = (el.getAttribute("href") ?? "").trim();
        const inner = children(el)
            .map((n) => this.inline(n, ctx))
            .join("");
        if (!SAFE_LINK.test(href)) return inner;
        return this.wrap(inner, "[", `](${destination(href)})`);
    }

    private image(el: Element): string {
        const src = (el.getAttribute("src") ?? "").trim();
        if (!WEB.test(src)) return "";
        this.formatted = true;
        const alt = (el.getAttribute("alt") ?? "").replace(COLLAPSIBLE, " ").trim();
        return `![${alt.replace(/[\\[\]]/g, "\\$&")}](${destination(src)})`;
    }

    private codeSpan(el: Element): string {
        const text = flatText(children(el)).split(NBSP).join(" ").replace(COLLAPSIBLE, " ");
        if (isBlank(text)) return text;
        const fence = "`".repeat(longestRun(text, "`") + 1);
        const core = text.replace(/^ +| +$/g, "");
        const pad = core.startsWith("`") || core.endsWith("`") ? " " : "";
        return this.wrap(text, fence + pad, pad + fence);
    }

    private wrapAll(text: string, marks: readonly Mark[]): string {
        return marks.reduce((inner, mark) => this.wrap(inner, mark, mark), text);
    }

    /** [text] between [open] and [close], its outer spaces and breaks left outside; blank text unwrapped. */
    private wrap(text: string, open: string, close: string): string {
        const chars = [...text];
        const start = chars.findIndex((c) => !EDGE.has(c));
        if (start < 0) return text;
        let end = chars.length;
        while (end > start && EDGE.has(chars[end - 1] as string)) end--;
        this.formatted = true;
        return (
            chars.slice(0, start).join("") + open + chars.slice(start, end).join("") + close + chars.slice(end).join("")
        );
    }

    hasDescendant(el: Element, test: (name: string) => boolean): boolean {
        return children(el).some((c) => isElement(c) && !hidden(c) && (test(name(c)) || this.hasDescendant(c, test)));
    }

    private preText(nodes: readonly Node[]): string {
        let out = "";
        for (const node of nodes) {
            if (!isElement(node)) {
                out += node.textContent ?? "";
            } else if (hidden(node)) {
                continue;
            } else if (name(node) === "br") {
                out += "\n";
            } else if (BLOCK.has(name(node))) {
                out += this.preText(children(node));
                if (out !== "" && !out.endsWith("\n")) out += "\n";
            } else {
                out += this.preText(children(node));
            }
        }
        return out;
    }
}

function collectRows(el: Element, rows: Element[], captions: Element[]): void {
    for (const child of children(el)) {
        if (!isElement(child)) continue;
        const tag = name(child);
        if (tag === "tr") rows.push(child);
        else if (tag === "thead" || tag === "tbody" || tag === "tfoot") collectRows(child, rows, captions);
        else if (tag === "caption") captions.push(child);
    }
}

function flatText(nodes: readonly Node[]): string {
    return nodes
        .map((n) => {
            if (!isElement(n)) return n.textContent ?? "";
            if (hidden(n)) return "";
            if (name(n) === "br") return " ";
            return flatText(children(n));
        })
        .join("");
}

/** `1.` for a numbered Word item (Word's own number), `-` for a bullet. */
function wordMarker(p: Element): string {
    const span = findElement(p, (el) => WORD_IGNORE.test(el.getAttribute("style") ?? ""));
    const text = (span?.textContent ?? "").split(NBSP).join(" ").trim();
    const m = WORD_NUMBER.exec(text);
    return m ? `${m[1]}${m[2]}` : "-";
}

function findElement(el: Element, test: (el: Element) => boolean): Element | null {
    for (const child of children(el)) {
        if (!isElement(child)) continue;
        if (test(child)) return child;
        const found = findElement(child, test);
        if (found) return found;
    }
    return null;
}
