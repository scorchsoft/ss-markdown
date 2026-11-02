/**
 * The plain-text twin of a formatted copy, for apps that paste text only:
 * the rendered words with no Markdown symbols. Headings are lines of their
 * own, lists keep "•" or "1." markers, tasks their ☐ and ☑, quotes are
 * indented, tables are tab-separated (a spreadsheet pastes them into cells),
 * pictures are their alt text in brackets and links add their address.
 */
import { BLOCK_TAGS } from "./formatted";
import { subscript, superscript } from "./mathText";

const QUOTE_INDENT = "    ";
const RULE = "──────────";

/** The plain text of formatted nodes (the children of a [Formatter]'s root). */
export function plainText(nodes: Iterable<Node>): string {
    return blocks(nodes)
        .map((lines) => lines.join("\n"))
        .join("\n\n");
}

function isBlock(node: Node): node is Element {
    return node.nodeType === 1 && BLOCK_TAGS.has((node as Element).localName.toLowerCase());
}

/** Each block's lines, in order; blocks are separated by a blank line. */
function blocks(nodes: Iterable<Node>): string[][] {
    const out: string[][] = [];
    let inline: Node[] = [];
    const flush = (): void => {
        const lines = inlineLines(inline);
        if (lines.length > 0) out.push(lines);
        inline = [];
    };
    for (const node of nodes) {
        if (!isBlock(node)) {
            inline.push(node);
            continue;
        }
        flush();
        out.push(...block(node));
    }
    flush();
    return out;
}

function block(el: Element): string[][] {
    const tag = el.localName.toLowerCase();
    switch (tag) {
        case "pre":
            return [(el.textContent ?? "").split("\n")];
        case "hr":
            return [[RULE]];
        case "blockquote":
            return blocks(el.childNodes).map((lines) => lines.map((l) => QUOTE_INDENT + l));
        case "div":
            return (el as HTMLElement).style?.marginLeft
                ? blocks(el.childNodes).map((lines) => lines.map((l) => QUOTE_INDENT + l))
                : blocks(el.childNodes);
        case "ul":
        case "ol":
            return [list(el, tag === "ol")];
        case "li":
            return [item(el, "• ")];
        case "table":
            return [table(el)];
        default: {
            const lines = inlineLines([...el.childNodes]);
            return lines.length > 0 ? [lines] : [];
        }
    }
}

function list(el: Element, ordered: boolean): string[] {
    let n = Number(el.getAttribute("start") ?? "1");
    if (!Number.isFinite(n)) n = 1;
    const lines: string[] = [];
    for (const child of el.children) {
        if (child.localName.toLowerCase() !== "li") continue;
        lines.push(...item(child, ordered ? `${n++}. ` : "• "));
    }
    return lines;
}

/** One list item: its marker on the first line, the rest (and nested lists) indented under it. */
function item(li: Element, marker: string): string[] {
    const lines = blocks(li.childNodes).flat();
    const first = lines[0] ?? "";
    // A task's ☐ or ☑ is its marker.
    const mark = /^[☐☑] /.test(first) ? "" : marker;
    const pad = " ".repeat([...mark].length);
    return lines.map((l, i) => (i === 0 ? mark + l : pad + l));
}

function table(el: Element): string[] {
    return [...el.querySelectorAll("tr")].map((tr) =>
        [...tr.children].map((cell) => inlineLines([...cell.childNodes]).join(" ")).join("\t"),
    );
}

/** Inline content as lines: whitespace collapsed as a browser would, `<br>` a new line. */
function inlineLines(nodes: readonly Node[]): string[] {
    const text = nodes.map(inline).join("");
    return text
        .split("\n")
        .map((l) => l.replace(/[ \t\r\f]+/g, " ").trim())
        .filter((l, i, all) => l !== "" || (i > 0 && i < all.length - 1));
}

function inline(node: Node): string {
    if (node.nodeType === 3) return (node.textContent ?? "").replace(/\s+/g, " ");
    if (node.nodeType !== 1) return "";
    const el = node as Element;
    const tag = el.localName.toLowerCase();
    const inner = (): string => [...el.childNodes].map(inline).join("");
    switch (tag) {
        case "br":
            return "\n";
        case "img":
            return `[${el.getAttribute("alt") ?? "Image"}]`;
        case "sup":
            return superscript(inner());
        case "sub":
            return subscript(inner());
        case "code":
            return el.textContent ?? "";
        case "a": {
            const text = inner();
            const href = el.getAttribute("href") ?? "";
            const bare = href.replace(/^mailto:|^tel:/i, "");
            return text.trim() === "" || text.trim() === href || text.trim() === bare ? href : `${text} (${href})`;
        }
        default:
            // A block inside inline content (a list in a table cell, say) still reads as words.
            return BLOCK_TAGS.has(tag) ? ` ${inner()} ` : inner();
    }
}
