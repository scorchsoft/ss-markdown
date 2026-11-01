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

export class SectionView {
    readonly el: HTMLElement;
    html: string | null = null;
    headings: readonly HeadingInfo[] = [];
    measured: number | null = null;
    mounted = false;

    constructor(
        public key: string,
        public startLine: number,
        public endLine: number,
        public startOffset: number,
    ) {
        this.el = document.createElement("section");
        this.el.className = "sec";
        this.updateAttrs();
    }

    /** Estimated or measured height in CSS pixels. */
    get height(): number {
        return this.measured ?? Math.max(1, this.endLine - this.startLine) * ESTIMATED_LINE_PX;
    }

    moveTo(startLine: number, endLine: number, startOffset: number): void {
        const moved = startLine !== this.startLine;
        this.startLine = startLine;
        this.endLine = endLine;
        this.startOffset = startOffset;
        this.updateAttrs();
        if (moved && this.mounted) this.relabelLines();
    }

    /** Puts the rendered HTML into the DOM. Returns false if it has not been rendered yet. */
    mount(): boolean {
        if (this.html === null) return false;
        if (this.mounted) return true;
        this.el.replaceChildren(sanitizeSection(this.html));
        this.el.style.height = "";
        this.el.classList.add("mounted");
        this.mounted = true;
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
        }
        this.el.style.height = `${Math.max(1, this.height * scale)}px`;
    }

    /** New HTML for this section. A mounted section swaps its content in place. */
    setHtml(html: string, headings: readonly HeadingInfo[]): void {
        this.html = html;
        this.headings = headings;
        if (this.mounted) {
            this.mounted = false;
            this.mount();
        }
    }

    private updateAttrs(): void {
        const d = this.el.dataset;
        d.line = String(this.startLine);
        d.lineEnd = String(this.endLine);
        d.offset = String(this.startOffset);
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
