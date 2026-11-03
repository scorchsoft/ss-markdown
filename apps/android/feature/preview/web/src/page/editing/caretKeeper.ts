/**
 * Keeps the caret being edited above the soft keyboard and the formatting
 * strip (docs/ux-spec.md, "Keyboard"; Andrew: "the keyboard opens over the
 * line I tapped"). Code does the same in `MarkdownCodeView.onSizeChanged`.
 *
 * The keyboard reaches the page as its own viewport getting shorter: Compose
 * pads the strip up by the keyboard's inset on every frame of its slide and
 * the pane above shrinks with it. So each `resize` is one frame of the
 * keyboard, and scrolling there moves the caret with the keyboard rather
 * than after it. Chromium's own "scroll the focused field into view" runs
 * once, when the keyboard says it is shown, before the WebView has shrunk,
 * so on its own it found nothing to do.
 *
 * - The page shrinks (the keyboard opens, an emoji panel or suggestion bar
 *   grows): a caret that was on screen and would now be within two lines of
 *   the strip is scrolled to about two lines above it. Anything higher does
 *   not move. A floating keyboard takes no room, so nothing scrolls.
 * - The page grows (the keyboard closes): nothing scrolls.
 * - Typing, and a caret put back after a re-render (Enter, a strip key,
 *   Undo, Kotlin's `select`): a caret below the bottom or above the top is
 *   brought back, two lines clear of the strip.
 * - The width changes (rotation): the caret is brought back if it is off screen.
 * - While editing, an invisible spacer below the document gives its last
 *   line that room, plus the keyboard's height, so closing the keyboard at
 *   the very end never pulls the view down. After editing it shrinks only
 *   by what is below the viewport, so it never moves the view either.
 */
import { editingPad, revealDelta, shrinkDelta, trimmedPad, type CaretLine } from "../../core/caretReveal";

export class CaretKeeper {
    private height = window.innerHeight;
    private width = window.innerWidth;
    /** The tallest the page has been at this width: its height without the keyboard. */
    private tallest = window.innerHeight;
    private pad = 0;
    private room: HTMLElement | null = null;
    private widthFrame = 0;

    constructor(private readonly editing: () => boolean) {
        window.addEventListener("resize", () => this.onResize());
        window.addEventListener("scroll", () => this.trim(), { passive: true });
        document.addEventListener("input", () => this.reveal(), true);
    }

    /** Editing started: make room below the document's last line. */
    begin(): void {
        const caret = caretLine();
        this.makeRoom(caret ? caret.bottom - caret.top : null);
    }

    /** Brings a caret that is off screen (or under the strip) back, about two lines above the strip. */
    reveal(): void {
        if (!this.editing()) return;
        const caret = caretLine();
        if (!caret) return;
        this.makeRoom(caret.bottom - caret.top);
        const delta = revealDelta(caret, window.innerHeight, 0);
        if (delta !== 0) window.scrollBy(0, delta);
    }

    private onResize(): void {
        const height = window.innerHeight;
        const width = window.innerWidth;
        const before = this.height;
        const turned = width !== this.width;
        this.height = height;
        this.width = width;
        this.tallest = turned ? height : Math.max(this.tallest, height);
        if (!this.editing()) {
            this.trim();
            return;
        }
        const caret = caretLine();
        this.makeRoom(caret ? caret.bottom - caret.top : null);
        if (turned) {
            // Reflowed at the new width: look again once layout has settled.
            cancelAnimationFrame(this.widthFrame);
            this.widthFrame = requestAnimationFrame(() => this.reveal());
            return;
        }
        if (!caret) return;
        const delta = shrinkDelta(caret, before, height);
        if (delta !== 0) window.scrollBy(0, delta);
    }

    private makeRoom(line: number | null): void {
        const wanted = editingPad(line ?? lineHeight(document.body), this.tallest, window.innerHeight);
        this.setPad(Math.max(this.pad, wanted));
    }

    /** Not editing: lets the room go by what is below the viewport, keeping enough to close the keyboard into. */
    private trim(): void {
        if (this.pad === 0 || this.editing()) return;
        const h = window.innerHeight;
        const slack = document.documentElement.scrollHeight - window.scrollY - h;
        this.setPad(trimmedPad(this.pad, slack - Math.max(0, this.tallest - h)));
    }

    private setPad(px: number): void {
        if (px === this.pad) return;
        this.pad = px;
        const room = this.element();
        room.style.height = `${px}px`;
    }

    private element(): HTMLElement {
        if (this.room?.isConnected) return this.room;
        const el = document.createElement("div");
        el.className = "keyboard-room";
        el.setAttribute("aria-hidden", "true");
        document.body.appendChild(el);
        this.room = el;
        return el;
    }
}

/** The line the caret (the selection's moving end) is on, if it is in the block being edited. */
function caretLine(): CaretLine | null {
    const sel = window.getSelection();
    const node = sel?.focusNode;
    if (!sel || !node) return null;
    const el = node instanceof Element ? node : node.parentElement;
    if (!el?.closest(".editing")) return null;
    const range = document.createRange();
    try {
        range.setStart(node, sel.focusOffset);
    } catch {
        return null;
    }
    range.collapse(true);
    const rect = range.getClientRects()[0];
    if (rect && rect.height > 0) return { top: rect.top, bottom: rect.bottom };
    // An empty text node or a caret between elements has no rect of its own: use its element's first line.
    const box = el.getBoundingClientRect();
    return { top: box.top, bottom: box.top + Math.min(box.height || Infinity, lineHeight(el)) };
}

function lineHeight(el: Element): number {
    const style = getComputedStyle(el);
    const line = parseFloat(style.lineHeight);
    return Number.isFinite(line) && line > 0 ? line : parseFloat(style.fontSize) * 1.5 || 24;
}
