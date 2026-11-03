/**
 * Where a tap goes: which block, and where in it the caret lands.
 *
 * The browser's hit test answers well for a point on text, but not for one in
 * the empty space right of a line's last run, below a block's last line, in a
 * code block's padding (`pre`, around the inline `code`) or between blocks:
 * there it returns the block's container, or the nearest previous node, which
 * can be the line above. So a tap is mapped by the visual lines themselves: the
 * line whose vertical band holds the point (or the nearest), then the point is
 * moved inside that line and hit-tested again, which keeps grapheme clusters
 * and bidi runs whole because the browser still picks the boundary.
 */

/** Space a tap can land in without being on a block: between, beside or around blocks. */
const BETWEEN = "main, section.sec, ul, ol, dl, blockquote, .markdown-alert, table, thead, tbody, tr, pre.code-block";
/** Blocks a gap tap can resolve to; the non-editable ones are there so a tap near one never jumps past it. */
const NEAR = "p, h1, h2, h3, h4, h5, h6, li, td, th, dt, dd, pre, hr, .ro-block, .diagram, .math-block";
/** How far from a block a gap tap still counts as aimed at it, in CSS px. */
const REACH = 48;

interface Box {
    top: number;
    bottom: number;
    left: number;
    right: number;
}

/**
 * The block a tap on [target] at ([x], [y]) edits: the editable block under it, or for a tap in
 * the space between or beside blocks the nearest block, if that one is editable.
 */
export function blockNear(
    target: Element,
    x: number,
    y: number,
    editable: (el: Element) => HTMLElement | null,
): HTMLElement | null {
    const direct = editable(target);
    if (direct || !target.matches(BETWEEN)) return direct;
    let best: Element | null = null;
    let bestDy = REACH;
    let bestDx = Infinity;
    let bestArea = Infinity;
    for (const section of document.querySelectorAll("section.sec.mounted")) {
        if (gap(section.getBoundingClientRect(), x, y)[0] > REACH) continue;
        for (const el of section.querySelectorAll(NEAR)) {
            const r = el.getBoundingClientRect();
            if (r.height === 0) continue;
            // Nearest by vertical gap, then by horizontal, then the innermost (a list item's paragraph).
            const [dy, dx] = gap(r, x, y);
            const area = r.width * r.height;
            const level = Math.abs(dy - bestDy) <= 0.5;
            if ((!level && dy < bestDy) || (level && (dx < bestDx || (dx === bestDx && area < bestArea)))) {
                [best, bestDy, bestDx, bestArea] = [el, dy, dx, area];
            }
        }
    }
    if (best?.matches("pre")) best = best.querySelector(":scope > code[data-s]");
    return best ? editable(best) : null;
}

/** Vertical, then horizontal, distance from ([x], [y]) to [r]; 0 inside it. */
function gap(r: Box, x: number, y: number): [number, number] {
    return [Math.max(r.top - y, y - r.bottom, 0), Math.max(r.left - x, x - r.right, 0)];
}

/** Puts the caret in [block] at the boundary nearest ([x], [y]) on the visual line the point is on. */
export function caretAt(block: HTMLElement, x: number, y: number): void {
    const sel = window.getSelection();
    const hit = rangeAt(x, y);
    if (!sel || (hit && inRun(block, hit.startContainer) && onBand(hit, y))) {
        if (hit) sel?.collapse(hit.startContainer, hit.startOffset);
        return;
    }
    const lines = linesOf(block);
    let line: Box | null = null;
    for (const l of lines) if (!line || gap(l, 0, y)[0] < gap(line, 0, y)[0]) line = l;
    // Below the block's last line (its padding, or the gap after it) is its end; above the first, its start.
    if (line && line === lines[lines.length - 1] && y > line.bottom) x = Infinity;
    else if (line && line === lines[0] && y < line.top) x = -Infinity;
    const mid = line ? (line.top + line.bottom) / 2 : y;
    const r = line ? rangeAt(Math.min(Math.max(x, line.left + 1), line.right - 1), mid) : hit;
    if (!r || !block.contains(r.startContainer)) return;
    const node = r.startContainer;
    let offset = r.startOffset;
    // Past a wrapped line's trailing space is the next line's start: step back so it stays on this one.
    if (!onBand(r, mid) && node instanceof Text && offset > 0 && node.data[offset - 1] === " ") offset--;
    sel.collapse(node, offset);
}

function rangeAt(x: number, y: number): Range | null {
    return document.caretRangeFromPoint?.(x, y) ?? null;
}

function inRun(block: HTMLElement, node: Node): boolean {
    const run = (node instanceof Element ? node : node.parentElement)?.closest("[data-s]");
    return node instanceof Text && !!run && block.contains(run);
}

/** Whether [range]'s caret is drawn on the line band holding [y]. */
function onBand(range: Range, y: number): boolean {
    const r = range.getClientRects()[0];
    return !!r && r.height > 0 && y >= r.top - 1 && y <= r.bottom + 1;
}

/** The visual lines of [block]'s own runs, top to bottom, from their glyph boxes. */
function linesOf(block: HTMLElement): Box[] {
    const lines: Box[] = [];
    const range = document.createRange();
    const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const nested = node.parentElement?.closest("ul, ol, table, blockquote");
        if (!inRun(block, node) || (nested && block.contains(nested))) continue;
        range.selectNodeContents(node);
        for (const r of range.getClientRects()) {
            if (r.height === 0) continue;
            const line = lines.find((l) => r.top < l.bottom && r.bottom > l.top);
            if (!line) lines.push({ top: r.top, bottom: r.bottom, left: r.left, right: r.right });
            else {
                line.top = Math.min(line.top, r.top);
                line.bottom = Math.max(line.bottom, r.bottom);
                line.left = Math.min(line.left, r.left);
                line.right = Math.max(line.right, r.right);
            }
        }
    }
    return lines.sort((p, q) => p.top - q.top);
}
