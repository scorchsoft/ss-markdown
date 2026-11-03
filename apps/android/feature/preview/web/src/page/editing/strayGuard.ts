/**
 * The safety net under tap-to-edit: every change the browser makes to the
 * block being edited either becomes a source edit or is undone on screen. It
 * is never left on screen without reaching the source (docs/ux-spec.md,
 * "Editing in Preview"; docs/edge-cases.md).
 *
 * Most typing lands inside a source-mapped run and is diffed there. This
 * guard watches for everything else: an IME composition, which Chrome does
 * not let `beforeinput` cancel, can put text next to an emoji or a line
 * break, or remove a whole run. A MutationObserver records such changes, and
 * at flush time:
 *
 * - text *added* at the very edge of unmapped content, with nothing but
 *   empty markup between it and a run, belongs to that run's edge, and is
 *   written there;
 * - anything else (text added in the middle of unmapped content, unmapped
 *   text deleted, a run removed) cannot be mapped 1:1 and is refused: the
 *   caller re-renders the block from source and tells the user.
 */
import { diffText } from "../../core/minimalEdit";
import { sectionOf, sectionOffset, ZWSP } from "./runs";

/** A stray insertion that maps to one source offset. */
export interface StrayInsert {
    readonly at: number;
    readonly text: string;
}

export interface StrayReport {
    readonly inserts: readonly StrayInsert[];
    /** True when some change could not be mapped and must be undone. */
    readonly refused: boolean;
}

/** Page chrome added inside a block (a picture's placeholder): never typed, never content. */
const CHROME = ".img-ph";

function isChrome(node: Node): boolean {
    const el = node instanceof Element ? node : node.parentElement;
    return el?.closest(CHROME) != null;
}

const REPLACED = "img, input, br, hr, svg, video, audio, canvas, iframe, object, embed, math, .katex";

function runOf(node: Node, block: HTMLElement): HTMLElement | null {
    const el = node instanceof Element ? node : node.parentElement;
    const run = el?.closest<HTMLElement>("[data-s]") ?? null;
    return run && block.contains(run) ? run : null;
}

function visibleText(node: Node): string {
    return (node.textContent ?? "").split(ZWSP).join("");
}

/** True when the DOM between two points holds no text and nothing drawn. */
function emptyBetween(range: Range): boolean {
    const fragment = range.cloneContents();
    if (visibleText(fragment).length > 0) return false;
    return fragment.querySelector(REPLACED) === null;
}

/** How deep the closest common ancestor of [a] and [b] is. */
function depthShared(a: Node, b: Node): number {
    const ancestors = new Set<Node>();
    for (let n: Node | null = a; n; n = n.parentNode) ancestors.add(n);
    let common: Node | null = b;
    while (common && !ancestors.has(common)) common = common.parentNode;
    let depth = 0;
    for (let n = common; n; n = n.parentNode) depth++;
    return depth;
}

export class StrayGuard {
    private readonly observer: MutationObserver;
    /** Text nodes outside runs that changed, with their value before the first change. */
    private readonly before = new Map<Text, string>();
    private refused = false;

    constructor(private readonly block: HTMLElement) {
        this.observer = new MutationObserver((records) => this.note(records));
    }

    start(): void {
        this.observer.observe(this.block, {
            subtree: true,
            childList: true,
            characterData: true,
            characterDataOldValue: true,
        });
    }

    stop(): void {
        this.note(this.observer.takeRecords());
        this.observer.disconnect();
    }

    /** True when something outside the runs changed since the last [take]. */
    get dirty(): boolean {
        this.note(this.observer.takeRecords());
        return this.refused || this.before.size > 0;
    }

    /** What changed outside runs since the last call, mapped where it can be. */
    take(): StrayReport {
        this.note(this.observer.takeRecords());
        const inserts: StrayInsert[] = [];
        let refused = this.refused;
        for (const [node, old] of this.before) {
            if (!node.isConnected || !this.block.contains(node)) {
                if (old.split(ZWSP).join("").trim().length > 0) refused = true;
                continue;
            }
            if (runOf(node, this.block)) continue; // became part of a run: the run diff has it
            const now = node.data;
            const diff = diffText(old, now);
            if (!diff) continue;
            const inserted = diff.insert.split(ZWSP).join("");
            const removed = old.slice(diff.start, diff.oldEnd).split(ZWSP).join("");
            if (removed.length > 0) {
                refused = true;
                continue;
            }
            if (inserted.length === 0) continue;
            const at = this.edgeOffset(node, diff.start, diff.start + diff.insert.length);
            if (at === null) refused = true;
            else inserts.push({ at, text: inserted });
        }
        this.before.clear();
        this.refused = false;
        return { inserts, refused };
    }

    private note(records: readonly MutationRecord[]): void {
        for (const r of records) {
            if (r.type === "characterData") {
                const node = r.target as Text;
                if (runOf(node, this.block)) continue;
                if (!this.before.has(node)) this.before.set(node, r.oldValue ?? "");
                continue;
            }
            if (isChrome(r.target)) continue;
            const insideRun = runOf(r.target, this.block) !== null;
            for (const n of r.removedNodes) {
                if (n instanceof Element && n.matches(CHROME)) continue;
                const lostRun = n instanceof Element && (n.matches("[data-s]") || n.querySelector("[data-s]"));
                if (lostRun) {
                    this.refused = true;
                } else if (
                    !insideRun &&
                    (visibleText(n).trim().length > 0 || (n instanceof Element && n.matches(REPLACED)))
                ) {
                    // Unmapped content (an emoji, a footnote mark, raw HTML) went away.
                    this.refused = true;
                }
            }
            if (insideRun) continue;
            for (const n of r.addedNodes) {
                if (n instanceof Element && n.matches(CHROME)) continue;
                if (n instanceof Text) {
                    if (!this.before.has(n)) this.before.set(n, "");
                } else if (n instanceof Element && !n.matches("[data-s]") && visibleText(n).length > 0) {
                    // A new element with text and no run (Chrome splitting a span): can't map it.
                    this.refused = true;
                }
            }
        }
    }

    /**
     * The source offset of text inserted at [start, end) in an unmapped text
     * node: the end of the run before it or the start of the run after it,
     * when only empty markup lies between. Null when it is ambiguous.
     */
    private edgeOffset(node: Text, start: number, end: number): number | null {
        const section = sectionOf(this.block);
        if (!section) return null;
        const base = sectionOffset(section);
        const runs = [...this.block.querySelectorAll<HTMLElement>("[data-s]")];
        const point = document.createRange();
        point.setStart(node, start);
        let previous: HTMLElement | null = null;
        let next: HTMLElement | null = null;
        for (const r of runs) {
            if (point.comparePoint(r, 0) > 0) {
                next = r;
                break;
            }
            previous = r;
        }
        let before: number | null = null;
        let after: number | null = null;
        if (previous) {
            const gap = document.createRange();
            gap.setStartAfter(previous);
            gap.setEnd(node, start);
            if (emptyBetween(gap)) before = base + Number(previous.dataset.e);
        }
        if (next) {
            const gap = document.createRange();
            gap.setStart(node, end);
            gap.setEndBefore(next);
            if (emptyBetween(gap)) after = base + Number(next.dataset.s);
        }
        if (before !== null && after !== null && previous && next) {
            // Between `**a**` and ` b`: the text belongs with whichever run shares
            // more of its formatting (it sits inside the <strong>, or outside it).
            return depthShared(node, previous) > depthShared(node, next) ? before : after;
        }
        return before ?? after;
    }
}
