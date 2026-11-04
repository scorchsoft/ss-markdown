/**
 * Copy and cut of a selection in Preview (docs/ux-spec.md, "Paste"): the
 * plain text is the Markdown source the selection covers, and the HTML its
 * rendering, marked [OWN_HTML] so a paste back into Preview takes the plain
 * text as Markdown and keeps bold, links and lists. An end at the start (or
 * end) of a block takes in its whole line, block markup and all. A
 * selection with an end inside bold, a link or code whose other end is
 * outside it would cut the syntax in half: Chromium copies that one as
 * rendered text, which pastes back as plain text.
 */
import type { SourceDoc } from "../../core/sourceDoc";
import { pointToSource, sectionOf } from "./runs";

/** On the HTML of a copy from Preview: its plain text is Markdown. */
export const OWN_HTML = "data-ssmd";

/** Puts the selection's source and rendering on [e]'s clipboard; false leaves the copy to Chromium. */
export function copySelection(e: ClipboardEvent, doc: SourceDoc): boolean {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || !e.clipboardData) return false;
    const range = sel.getRangeAt(0);
    const md = selectionSource(range, doc);
    if (md === null) return false;
    const box = document.createElement("div");
    box.append(range.cloneContents());
    // Only the structure and link addresses travel: no classes, offsets or editing state.
    for (const el of box.querySelectorAll("*")) {
        for (const a of [...el.attributes]) if (a.name !== "href") el.removeAttribute(a.name);
    }
    e.clipboardData.setData("text/plain", md);
    e.clipboardData.setData("text/html", `<div ${OWN_HTML}>${box.innerHTML}</div>`);
    e.preventDefault();
    return true;
}

/** The Markdown source [range] covers, or null when an end cannot be had whole. */
export function selectionSource(range: Range, doc: SourceDoc): string | null {
    const from = edge(range, doc, true);
    const to = edge(range, doc, false);
    if (from === null || to === null || to <= from) return null;
    const first = doc.lineAt(from);
    const base = doc.lineStart(first);
    return doc.lines
        .slice(first, doc.lineAt(to) + 1)
        .join("\n")
        .slice(from - base, to - base);
}

/** The source offset of one end of [range]; null when it cannot be had whole. */
function edge(range: Range, doc: SourceDoc, start: boolean): number | null {
    const node = start ? range.startContainer : range.endContainer;
    const section = sectionOf(node);
    const at = section && pointToSource(section, node, start ? range.startOffset : range.endOffset);
    const el = node instanceof Element ? node : node.parentElement;
    if (at == null) return null;
    const block = el?.closest("p, h1, h2, h3, h4, h5, h6, li, dt, dd, pre");
    const rest = document.createRange();
    if (block) rest.selectNodeContents(block);
    if (start) rest.setEnd(node, range.startOffset);
    else rest.setStart(node, range.endOffset);
    if (!block || rest.toString().trim()) {
        const mark = el?.closest("strong, em, a, code, s, del, mark, sub, sup");
        return mark && !mark.contains(start ? range.endContainer : range.startContainer) ? null : at;
    }
    const line = doc.lineAt(at);
    return doc.lineStart(line) + (start ? 0 : (doc.lines[line] ?? "").length);
}
