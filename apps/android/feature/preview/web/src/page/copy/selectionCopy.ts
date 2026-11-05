/**
 * The selection toolbar's "Copy Markdown" and "Copy formatted text" in
 * Preview (Andrew, 2026-11-04: copy just the selected part, either way).
 * Markdown is the source the selection covers, as Copy gives it
 * (editing/clip.ts). Formatted is the selection's own rendering through
 * the same converter as "Copy formatted text" (copy/formatted.ts): inline
 * light styles, pictures and diagrams embedded within the budget, and a
 * plain-text twin. A selection across list items or table rows keeps its
 * list or table around it; one inside a single block is a paragraph.
 */
import type { SourceDoc } from "../../core/sourceDoc";
import { selectionSource } from "../editing/clip";
import { MAX_IMAGE_CHARS, MAX_IMAGES_CHARS } from "../exportHtml";
import { Formatter } from "./formatted";
import { embedAll } from "./media";
import { plainText } from "./plainText";

/** Both copies of the selection; [html] and [text] are null when the formatted one is over the budget. */
export interface SelectionCopy {
    readonly markdown: string;
    readonly html: string | null;
    readonly text: string | null;
}

/** A selected fragment that holds these needs the container they live in. */
const PARTS = "li, tr, thead, tbody, dt, dd";
/** Containers a fragment of [PARTS] is put back into, innermost first. */
const CONTAINERS = "ul, ol, table, thead, tbody, tr, dl, blockquote, .markdown-alert";
/** Blocks: a fragment with none is inline text and becomes one paragraph. */
const BLOCKS = "p, h1, h2, h3, h4, h5, h6, ul, ol, li, table, tr, pre, blockquote, hr, dl, div, figure";
/** How long pictures and diagrams may take. */
const MEDIA_MILLIS = 8_000;

/** The current selection's copies, or null with nothing selected (or nothing in the document selected). */
export async function selectionCopy(
    doc: SourceDoc,
    maxChars: number,
    drawDiagram: (source: string) => Promise<string | null>,
): Promise<SelectionCopy | null> {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return null;
    const range = sel.getRangeAt(0);
    const root = document.getElementById("doc");
    if (!root?.contains(range.commonAncestorContainer)) return null;
    const markdown = selectionSource(range, doc) ?? sel.toString();
    const fragment = fragmentOf(range);
    const formatter = new Formatter(document);
    for (const node of [...fragment.childNodes]) formatter.add(node as Element);
    formatter.finish();
    const size = (): number => formatter.root.innerHTML.length + plainText(formatter.root.childNodes).length;
    if (markdown.length + size() > maxChars) return { markdown, html: null, text: null };
    const left = Math.max(0, maxChars - markdown.length - size());
    await embedAll(
        formatter.media,
        { perItem: MAX_IMAGE_CHARS, total: Math.min(MAX_IMAGES_CHARS, left) },
        Date.now() + MEDIA_MILLIS,
        drawDiagram,
    );
    return { markdown, html: formatter.root.innerHTML, text: plainText(formatter.root.childNodes) };
}

/** The selected nodes, put back into their list or table when they are parts of one. */
function fragmentOf(range: Range): HTMLElement {
    const box = document.createElement("div");
    box.append(range.cloneContents());
    const children = [...box.children];
    const inline = children.every((el) => !el.matches(BLOCKS));
    if (inline) {
        const p = document.createElement("p");
        p.append(...box.childNodes);
        box.append(p);
        return box;
    }
    if (!children.some((el) => el.matches(PARTS))) return box;
    // Rebuild the containers between the selected parts and their common ancestor.
    let wrapped: Node[] = [...box.childNodes];
    for (
        let anc: Element | null =
            range.commonAncestorContainer instanceof Element
                ? range.commonAncestorContainer
                : range.commonAncestorContainer.parentElement;
        anc?.matches(CONTAINERS);
        anc = anc.parentElement
    ) {
        const shell = anc.cloneNode(false) as Element;
        shell.append(...wrapped);
        wrapped = [shell];
    }
    const out = document.createElement("div");
    out.append(...wrapped);
    return out;
}
