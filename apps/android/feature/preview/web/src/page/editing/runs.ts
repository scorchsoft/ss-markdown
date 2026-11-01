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
    if (host) {
        host.appendChild(run);
        return run;
    }
    const p = document.createElement("p");
    p.className = "placeholder";
    p.dataset.line = String(line);
    p.dataset.lineEnd = String(line + 1);
    p.appendChild(run);
    const after = [...section.children].find((el) => Number((el as HTMLElement).dataset.line) > line);
    section.insertBefore(p, after ?? null);
    return run;
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
