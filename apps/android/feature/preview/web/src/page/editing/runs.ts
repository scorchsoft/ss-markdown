/**
 * The DOM side of the inline source map: which rendered element holds which
 * source offsets, and how to put the caret at a source offset.
 *
 * Offsets in the DOM are relative to their section (`data-s`, `data-e`); the
 * section's start offset comes from its view (`data-offset` mirrors it). Everything
 * here returns absolute offsets into the whole source.
 */
import { viewOfElement } from "../sectionView";

export interface RunRecord {
    from: number;
    to: number;
    text: string;
}

/** Zero-width space: gives an empty placeholder run somewhere to hold the caret. */
export const ZWSP = "​";

export function sectionOf(node: Node): HTMLElement | null {
    const el = node instanceof Element ? node : node.parentElement;
    return el?.closest<HTMLElement>("section.sec") ?? null;
}

/** The section's start offset: from its view's line index, or `data-offset` for a bare element. */
export function sectionOffset(section: HTMLElement): number {
    return viewOfElement(section)?.startOffset ?? Number(section.dataset.offset ?? 0);
}

export function runText(el: HTMLElement): string {
    return (el.textContent ?? "").split(ZWSP).join("");
}

/** Every run in [block], with absolute offsets. */
export function snapshotRuns(block: HTMLElement): Map<HTMLElement, RunRecord> {
    const out = new Map<HTMLElement, RunRecord>();
    const section = sectionOf(block);
    if (!section) return out;
    const base = sectionOffset(section);
    for (const el of block.querySelectorAll<HTMLElement>("[data-s]")) {
        out.set(el, { from: base + Number(el.dataset.s), to: base + Number(el.dataset.e), text: runText(el) });
    }
    if (block.dataset.s !== undefined) {
        out.set(block, {
            from: base + Number(block.dataset.s),
            to: base + Number(block.dataset.e),
            text: runText(block),
        });
    }
    return out;
}

/** Shifts every run in [section] at or after section-relative [relAt] by [delta]. */
export function shiftRuns(section: HTMLElement, relAt: number, delta: number, edited: HTMLElement | null): void {
    if (delta === 0) return;
    for (const el of section.querySelectorAll<HTMLElement>("[data-s]")) {
        const s = Number(el.dataset.s);
        const e = Number(el.dataset.e);
        if (el === edited) {
            el.dataset.e = String(e + delta);
        } else if (s >= relAt) {
            el.dataset.s = String(s + delta);
            el.dataset.e = String(e + delta);
        }
    }
}

/** Character offset of a DOM point within [run]'s text, ignoring placeholder spaces. */
function offsetWithin(run: HTMLElement, node: Node, offset: number): number {
    const range = document.createRange();
    range.selectNodeContents(run);
    range.setEnd(node, offset);
    return range.toString().split(ZWSP).join("").length;
}

/** Maps a DOM point to an absolute source offset, snapping to the nearest run edge if needed. */
export function pointToSource(block: HTMLElement, node: Node, offset: number): number | null {
    const section = sectionOf(block);
    if (!section) return null;
    const base = sectionOffset(section);
    const el = node instanceof Element ? node : node.parentElement;
    const run = el?.closest<HTMLElement>("[data-s]");
    if (run && block.contains(run)) return base + Number(run.dataset.s) + offsetWithin(run, node, offset);
    const runs = [...block.querySelectorAll<HTMLElement>("[data-s]")];
    const probe = document.createRange();
    probe.setStart(node, offset);
    let previous: HTMLElement | null = null;
    for (const r of runs) {
        const cmp = probe.comparePoint(r, 0);
        if (cmp > 0) return base + Number(r.dataset.s);
        previous = r;
    }
    return previous ? base + Number(previous.dataset.e) : null;
}

/** Puts the caret at absolute [offset] if a run in [root] holds it. */
export function placeCaret(root: HTMLElement, offset: number): HTMLElement | null {
    const section = root.matches("section.sec") ? root : sectionOf(root);
    if (!section) return null;
    const rel = offset - sectionOffset(section);
    let best: HTMLElement | null = null;
    for (const el of section.querySelectorAll<HTMLElement>("[data-s]")) {
        const s = Number(el.dataset.s);
        const e = Number(el.dataset.e);
        if (s <= rel && rel <= e) {
            best = el;
            if (rel < e) break;
        }
    }
    if (!best) return null;
    setCaretInRun(best, rel - Number(best.dataset.s));
    return best;
}

/** Sets a collapsed selection [chars] characters into [run]. */
export function setCaretInRun(run: HTMLElement, chars: number): void {
    const walker = document.createTreeWalker(run, NodeFilter.SHOW_TEXT);
    let left = chars;
    let node = walker.nextNode();
    let last: Node | null = null;
    while (node) {
        const text = node.nodeValue ?? "";
        const len = text.split(ZWSP).join("").length;
        if (left <= len) {
            const sel = window.getSelection();
            sel?.collapse(node, Math.min(text.length, left + (text.startsWith(ZWSP) ? 1 : 0)));
            return;
        }
        left -= len;
        last = node;
        node = walker.nextNode();
    }
    if (last) window.getSelection()?.collapse(last, last.nodeValue?.length ?? 0);
    else window.getSelection()?.collapse(run, run.childNodes.length);
}

/**
 * Inserts an empty run at [offset] so the caret has a home: inside the block
 * that starts on [line] (an empty list item) or as a new paragraph before the
 * first block after it. Used after Enter makes a block with no text yet.
 */
export function placeholderRun(section: HTMLElement, offset: number, line: number): HTMLElement {
    const run = emptyRun(section, offset);
    const host = [...section.querySelectorAll<HTMLElement>("[data-line]")]
        .reverse()
        .find((el) => Number(el.dataset.line) === line && !el.querySelector("[data-line]"));
    if (host && !host.matches("blockquote, .markdown-alert")) {
        host.appendChild(run);
        return run;
    }
    const p = document.createElement("p");
    p.className = "placeholder";
    p.dataset.line = String(line);
    p.dataset.lineEnd = String(line + 1);
    p.appendChild(run);
    if (host) {
        // An empty quote line (`> `): a paragraph inside the quote, where Enter can end it.
        host.appendChild(p);
        return run;
    }
    // Into the quote or alert that holds the line, and through a list that does, so the caret is drawn
    // where the line is, never below the whole block (Andrew, 2026-11-04).
    let parent: HTMLElement = section;
    for (let around = spanning(parent, line); around; around = spanning(parent, line)) {
        if (around.matches("ul, ol")) {
            splitListAt(around, line, p);
            return run;
        }
        parent = around;
    }
    const after = [...parent.children].find((el) => Number((el as HTMLElement).dataset.line) > line);
    parent.insertBefore(p, after ?? null);
    return run;
}

/** The child list, quote or alert of [parent] whose source lines run past [line], if any. */
function spanning(parent: HTMLElement, line: number): HTMLElement | null {
    for (const el of parent.children) {
        if (!(el instanceof HTMLElement) || !el.matches("ul, ol, blockquote, .markdown-alert")) continue;
        const start = Number(el.dataset.line);
        const end = Number(el.dataset.lineEnd);
        if (start < line && line < end) return el;
    }
    return null;
}

/**
 * Draws the empty line [p] stands for where it is: Enter on an empty item in
 * the middle of a list ends the list there (EB-LIST-07), but until something
 * is typed the source is only blank lines, which Markdown reads as one loose
 * list, and the caret went below the whole list (Andrew, 2026-11-04). The list
 * is shown split around [p], as it will render once the line has text; the
 * section re-renders from source when editing ends.
 */
function splitListAt(list: HTMLElement, line: number, p: HTMLElement): void {
    const items = [...list.children].filter((el): el is HTMLElement => el instanceof HTMLElement && el.matches("li"));
    const later = items.filter((li) => Number(li.dataset.line) > line);
    if (later.length === 0) {
        list.after(p);
        return;
    }
    const rest = list.cloneNode(false) as HTMLElement;
    rest.removeAttribute("data-line");
    rest.removeAttribute("data-line-end");
    rest.removeAttribute("data-rl");
    rest.removeAttribute("data-rle");
    if (list instanceof HTMLOListElement && rest instanceof HTMLOListElement) {
        rest.start = list.start + items.length - later.length;
    }
    for (const li of later) rest.appendChild(li);
    list.after(p, rest);
    list.classList.add("split-list");
    rest.classList.add("split-list");
}

/** An empty run at absolute [offset] in [section], holding a zero-width space for the caret. */
export function emptyRun(section: HTMLElement, offset: number): HTMLElement {
    const rel = offset - sectionOffset(section);
    const run = document.createElement("span");
    run.dataset.s = String(rel);
    run.dataset.e = String(rel);
    run.textContent = ZWSP;
    return run;
}
