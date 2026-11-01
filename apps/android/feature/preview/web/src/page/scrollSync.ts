/**
 * Anchor-based scroll sync, after VS Code's Markdown preview: every rendered
 * block carries `data-line` / `data-line-end`, and a source line maps to a
 * pixel by binary search and interpolation within the block that holds it.
 * Unmounted sections interpolate across their spacer.
 */
import type { RenderController } from "./controller";
import type { SectionView } from "./sectionView";

interface BlockBox {
    readonly line: number;
    readonly end: number;
    readonly top: number;
    readonly height: number;
}

/** Top-level rendered blocks of a mounted section, with page coordinates. */
function blocksOf(view: SectionView): BlockBox[] {
    const out: BlockBox[] = [];
    const scrollY = window.scrollY;
    for (const node of view.el.querySelectorAll<HTMLElement>(":scope > [data-line]")) {
        const rect = node.getBoundingClientRect();
        out.push({
            line: Number(node.dataset.line),
            end: Number(node.dataset.lineEnd ?? node.dataset.line),
            top: rect.top + scrollY,
            height: rect.height,
        });
    }
    return out;
}

function sectionTop(view: SectionView): number {
    return view.el.getBoundingClientRect().top + window.scrollY;
}

/** The page y (CSS px) where source [line] starts. */
export function lineToScroll(controller: RenderController, line: number): number {
    const view = controller.viewAtLine(line);
    if (!view) return 0;
    const top = sectionTop(view);
    const span = Math.max(1, view.endLine - view.startLine);
    if (view.mounted) {
        const blocks = blocksOf(view);
        let best: BlockBox | null = null;
        for (const b of blocks) if (b.line <= line) best = b;
        if (best) {
            const within = Math.min(1, (line - best.line) / Math.max(1, best.end - best.line));
            return best.top + within * best.height;
        }
    }
    return top + ((line - view.startLine) / span) * view.el.getBoundingClientRect().height;
}

/** The source line at the top of the viewport, fractional within a block. */
export function topLine(controller: RenderController): number {
    const y = window.scrollY;
    const views = controller.views;
    let lo = 0;
    let hi = views.length - 1;
    while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        const v = views[mid];
        if (v && sectionTop(v) <= y) lo = mid;
        else hi = mid - 1;
    }
    const view = views[lo];
    if (!view) return 0;
    if (view.mounted) {
        let best: BlockBox | null = null;
        for (const b of blocksOf(view)) if (b.top <= y) best = b;
        if (best) {
            const frac = best.height > 0 ? Math.min(1, (y - best.top) / best.height) : 0;
            return best.line + frac * (best.end - best.line);
        }
    }
    const height = view.el.getBoundingClientRect().height;
    const frac = height > 0 ? Math.min(1, Math.max(0, (y - sectionTop(view)) / height)) : 0;
    return view.startLine + frac * (view.endLine - view.startLine);
}

/** Scrolls so source [line] sits at the top, mounting its section first so it lands exactly. */
export function scrollToLine(controller: RenderController, line: number, smooth = false): void {
    const view = controller.viewAtLine(line);
    if (view) {
        view.mount();
    }
    window.scrollTo({ top: Math.max(0, lineToScroll(controller, line)), behavior: smooth ? "smooth" : "auto" });
}
