/**
 * One top-level section's place in the page: a `<section>` element that holds
 * the rendered blocks while it is near the viewport and is an empty spacer of
 * the right height when it is not.
 *
 * The worker renders line numbers relative to the section (`data-rl`), so a
 * section that only moved does not need re-rendering: on mount, and whenever
 * its start line changes, the absolute `data-line` / `data-line-end` that
 * scroll sync reads are recomputed here.
 */
import type { HeadingInfo } from "../protocol";
import { sanitizeSection } from "./sanitize";

/** An unmeasured section is estimated at this many pixels per source line. */
const ESTIMATED_LINE_PX = 26;

/**
 * What a section needs from its controller: offsets from the document's line
 * index, and the set of mounted sections, the only ones whose DOM attributes
 * are kept current. A spacer's attributes are written when it mounts, so an
 * edit never touches the DOM of the thousands of sections far away (PERF-11).
 */
export interface SectionHost {
    lineStart(line: number): number;
    readonly mounted: Set<SectionView>;
}

const VIEWS = new WeakMap<Element, SectionView>();

/** The view that owns a `<section>` element, for code that starts from the DOM. */
export function viewOfElement(el: Element): SectionView | undefined {
    return VIEWS.get(el);
}

export class SectionView {
    readonly el: HTMLElement;
    html: string | null = null;
    headings: readonly HeadingInfo[] = [];
    measured: number | null = null;
    mounted = false;
    /** The key changed (document-wide definitions did) but the old HTML is still shown until the new arrives. */
    stale = false;

    constructor(
        public key: string,
        /** The section text's hash, without the definitions part of [key]. */
        public hash: string,
        public startLine: number,
        public endLine: number,
        private readonly host: SectionHost,
    ) {
        this.el = document.createElement("section");
        this.el.className = "sec";
        VIEWS.set(this.el, this);
    }

    /** Offset of the section's first character, from the document's line index. */
    get startOffset(): number {
        return this.host.lineStart(this.startLine);
    }

    /** True while the worker owes this section HTML for its current key. */
    get needsRender(): boolean {
        return this.html === null || this.stale;
    }

    /** Estimated or measured height in CSS pixels. */
    get height(): number {
        return this.measured ?? Math.max(1, this.endLine - this.startLine) * ESTIMATED_LINE_PX;
    }

    moveTo(startLine: number, endLine: number): void {
        const moved = startLine !== this.startLine;
        this.startLine = startLine;
        this.endLine = endLine;
        if (this.mounted) {
            this.updateAttrs();
            if (moved) this.relabelLines();
        }
    }

    /** Moves the section by [delta] lines; its text is unchanged. */
    shift(delta: number): void {
        if (delta !== 0) this.moveTo(this.startLine + delta, this.endLine + delta);
    }

    /** Rewrites the DOM attributes of a mounted section whose offset may have moved. */
    syncAttrs(): void {
        if (this.mounted) this.updateAttrs();
    }

    /** The section left the document. */
    dispose(): void {
        this.host.mounted.delete(this);
    }

    /** Puts the rendered HTML into the DOM. Returns false if it has not been rendered yet. */
    mount(): boolean {
        if (this.html === null) return false;
        if (this.mounted) return true;
        this.el.replaceChildren(sanitizeSection(this.html));
        this.el.style.height = "";
        this.el.classList.add("mounted");
        this.mounted = true;
        this.host.mounted.add(this);
        this.updateAttrs();
        this.relabelLines();
        return true;
    }

    /** Replaces the content with a spacer, remembering the real height. */
    unmount(scale: number): void {
        if (this.mounted) {
            const h = this.el.getBoundingClientRect().height;
            if (h > 0) this.measured = h;
            this.el.replaceChildren();
            this.el.classList.remove("mounted");
            this.mounted = false;
            this.host.mounted.delete(this);
        }
        this.el.style.height = `${Math.max(1, this.height * scale)}px`;
    }

    /** New HTML for this section. A mounted section swaps its content in place. */
    setHtml(html: string, headings: readonly HeadingInfo[]): void {
        this.html = html;
        this.stale = false;
        this.headings = headings;
        if (this.mounted) {
            this.mounted = false;
            this.mount();
        }
    }

    private updateAttrs(): void {
        const d = this.el.dataset;
        const line = String(this.startLine);
        const end = String(this.endLine);
        const offset = String(this.startOffset);
        if (d.line !== line) d.line = line;
        if (d.lineEnd !== end) d.lineEnd = end;
        if (d.offset !== offset) d.offset = offset;
        this.el.style.setProperty("contain-intrinsic-size", `auto ${Math.round(this.height)}px`);
    }

    private relabelLines(): void {
        const base = this.startLine;
        for (const node of this.el.querySelectorAll<HTMLElement>("[data-rl]")) {
            const rl = Number(node.dataset.rl);
            const rle = Number(node.dataset.rle ?? node.dataset.rl);
            node.dataset.line = String(base + rl);
            node.dataset.lineEnd = String(base + rle);
        }
    }
}
