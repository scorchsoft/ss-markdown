/**
 * Find & replace in Preview (docs/ux-spec.md, "Find & replace"): Kotlin
 * searches the source and sends the matches as source offsets; the page
 * paints them on the rendered text through the inline source map and
 * scrolls the current one into view.
 *
 * Painting uses the CSS Custom Highlight API, so the DOM is never touched:
 * the runs tap-to-edit maps stay exactly as rendered, and a section that
 * mounts or re-renders is simply painted again. A match that no exact run
 * holds (inside raw HTML or an escape) is not painted, but the current one
 * still scrolls into view by its source line.
 */
import type { RenderController } from "./controller";
import { sectionOffset, ZWSP } from "./editing/runs";
import { lineToScroll, scrollToLine } from "./scrollSync";

/** Highlight registry names; preview.css styles `::highlight(...)` for both. */
export const MATCH_HIGHLIGHT = "ssm-find";
export const CURRENT_HIGHLIGHT = "ssm-find-current";

/** A follow-up scroll this soon after a reveal (Split keeping the panes together) keeps the match in view. */
const KEEP_MILLIS = 600;

interface HighlightRegistry {
    set(name: string, highlight: unknown): void;
    delete(name: string): void;
}

/** The registry and constructor, where this WebView has them (Chromium 105+). */
function highlightApi(): { registry: HighlightRegistry; make: (ranges: Range[]) => unknown } | null {
    const css = (globalThis as { CSS?: { highlights?: HighlightRegistry } }).CSS;
    const ctor = (globalThis as { Highlight?: new (...ranges: Range[]) => unknown }).Highlight;
    if (!css?.highlights || !ctor) return null;
    return { registry: css.highlights, make: (ranges) => new ctor(...ranges) };
}

/** The DOM point [chars] characters into [run]'s text, placeholder spaces not counted. */
function pointIn(run: HTMLElement, chars: number, atEnd: boolean): { node: Node; offset: number } | null {
    const walker = document.createTreeWalker(run, NodeFilter.SHOW_TEXT);
    let left = chars;
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const text = node.nodeValue ?? "";
        let visible = 0;
        for (let i = 0; i <= text.length; i++) {
            // An end point stays in this node when it lands on its last character; a start moves on.
            if (visible === left && (atEnd || i < text.length)) return { node, offset: i };
            if (i < text.length && text[i] !== ZWSP) visible++;
        }
        left -= visible;
    }
    return null;
}

export class FindMarks {
    private starts: number[] = [];
    private ends: number[] = [];
    private current = -1;
    /** The current match waits for its section to be rendered before it can be revealed. */
    private pendingReveal = false;
    private revealedAt = -Infinity;
    private readonly api = highlightApi();

    constructor(private readonly controller: RenderController) {}

    /** How many matches are held; tests read it. */
    get count(): number {
        return this.starts.length;
    }

    /** New matches (null keeps the ones held) and the current index; [reveal] scrolls it into view. */
    set(ranges: readonly number[] | null, current: number, reveal: boolean): void {
        if (ranges !== null) {
            const starts: number[] = [];
            const ends: number[] = [];
            for (let i = 0; i + 1 < ranges.length; i += 2) {
                starts.push(ranges[i] ?? 0);
                ends.push(ranges[i + 1] ?? 0);
            }
            this.starts = starts;
            this.ends = ends;
        }
        this.current = current >= 0 && current < this.starts.length ? current : -1;
        this.paint();
        if (reveal) this.reveal();
    }

    /** The find bar closed, or a new document loaded. */
    clear(): void {
        this.starts = [];
        this.ends = [];
        this.current = -1;
        this.pendingReveal = false;
        this.revealedAt = -Infinity;
        this.api?.registry.delete(MATCH_HIGHLIGHT);
        this.api?.registry.delete(CURRENT_HIGHLIGHT);
    }

    /** Sections mounted or re-rendered: paint them, and reveal the current match if it was waiting for one. */
    onRendered(): void {
        if (this.starts.length === 0) return;
        this.paint();
        if (this.pendingReveal) this.reveal();
    }

    /**
     * Kotlin scrolled the page (Split following the Code pane). Just after a
     * reveal, the current match must stay in view: it moves back the least
     * distance that shows it.
     */
    afterHostScroll(): void {
        if (this.current >= 0 && performance.now() - this.revealedAt < KEEP_MILLIS) this.reveal();
    }

    /** The reader touched the page: where it scrolls now is theirs, and a waiting reveal is dropped. */
    onReaderInput(): void {
        this.revealedAt = -Infinity;
        this.pendingReveal = false;
    }

    /** Scrolls the least distance that shows the current match, with a margin; mounts its section first. */
    reveal(): void {
        const start = this.starts[this.current];
        const end = this.ends[this.current];
        if (start === undefined || end === undefined) return;
        const line = this.controller.doc.lineAt(start);
        const view = this.controller.viewAtLine(line);
        if (!view) return;
        if (!view.mounted) {
            // Brings it near, so the virtualiser keeps it mounted; not rendered yet, it is revealed on arrival.
            scrollToLine(this.controller, line);
            if (!view.mount()) {
                this.pendingReveal = true;
                return;
            }
            this.paint();
        }
        this.pendingReveal = false;
        this.revealedAt = performance.now();
        const range = this.rangeIn(view.el, start, end);
        const height = window.innerHeight;
        const margin = Math.min(height / 4, 48);
        let top: number;
        let bottom: number;
        if (range) {
            const rect = range.getBoundingClientRect();
            top = rect.top;
            bottom = rect.bottom;
        } else {
            top = lineToScroll(this.controller, line) - window.scrollY;
            bottom = top + 24;
        }
        if (top >= margin && bottom <= height - margin) return;
        // A third of the way down: the reader sees what comes before and after it.
        window.scrollTo({ top: Math.max(0, window.scrollY + top - height / 3), behavior: "auto" });
    }

    /** Paints every match in the mounted sections, the current one in its own colour. */
    private paint(): void {
        const api = this.api;
        if (!api) return;
        const all: Range[] = [];
        const current: Range[] = [];
        if (this.starts.length > 0) {
            for (const section of document.querySelectorAll<HTMLElement>("section.sec.mounted")) {
                this.collect(section, all, current);
            }
        }
        api.registry.set(MATCH_HIGHLIGHT, api.make(all));
        api.registry.set(CURRENT_HIGHLIGHT, api.make(current));
    }

    /** Ranges for the matches inside [section]'s exact runs. */
    private collect(section: HTMLElement, all: Range[], current: Range[]): void {
        const base = sectionOffset(section);
        for (const run of section.querySelectorAll<HTMLElement>("[data-s]")) {
            // A block that carries offsets around runs of its own is painted through them.
            if (run.querySelector("[data-s]")) continue;
            const from = base + Number(run.dataset.s);
            const to = base + Number(run.dataset.e);
            for (let i = this.firstEndingAfter(from); i < this.starts.length; i++) {
                const s = this.starts[i] ?? 0;
                const e = this.ends[i] ?? 0;
                if (s >= to) break;
                const range = this.rangeInRun(run, Math.max(s, from) - from, Math.min(e, to) - from);
                if (!range) continue;
                (i === this.current ? current : all).push(range);
            }
        }
    }

    /** One DOM range over the current match in [section], from its first run to its last. */
    private rangeIn(section: HTMLElement, start: number, end: number): Range | null {
        const base = sectionOffset(section);
        let first: Range | null = null;
        let last: Range | null = null;
        for (const run of section.querySelectorAll<HTMLElement>("[data-s]")) {
            if (run.querySelector("[data-s]")) continue;
            const from = base + Number(run.dataset.s);
            const to = base + Number(run.dataset.e);
            if (to <= start || from >= Math.max(end, start + 1)) continue;
            const r = this.rangeInRun(run, Math.max(start, from) - from, Math.min(end, to) - from);
            if (!r) continue;
            if (!first) first = r;
            last = r;
        }
        if (!first || !last) return null;
        const range = document.createRange();
        range.setStart(first.startContainer, first.startOffset);
        range.setEnd(last.endContainer, last.endOffset);
        return range;
    }

    private rangeInRun(run: HTMLElement, from: number, to: number): Range | null {
        const a = pointIn(run, from, false);
        const b = pointIn(run, to, true);
        if (!a || !b) return null;
        const range = document.createRange();
        range.setStart(a.node, a.offset);
        range.setEnd(b.node, b.offset);
        return range;
    }

    /** The first match that ends after [offset], by binary search; matches are in document order. */
    private firstEndingAfter(offset: number): number {
        let lo = 0;
        let hi = this.ends.length;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if ((this.ends[mid] ?? 0) <= offset) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
