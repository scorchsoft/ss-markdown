/**
 * Keeps the soft keyboard up while the block being edited is swapped for its
 * re-rendered twin (a strip command, Enter, a paste, a table move).
 *
 * Ending a session takes `contenteditable` off the focused block. Android's
 * WebView sees focus leave an editable element and hides the keyboard; when
 * the new block takes focus a moment later the keyboard comes back. On a
 * phone that reads as the keyboard flashing away on every Bold, and it made
 * the ¶ menu impossible to use (Andrew, 0.2.1). Moving focus from one
 * editable element to another keeps the keyboard, so for the gap focus sits
 * here: a 1 px, invisible, editable box outside the document.
 *
 * Anything typed into it during the gap is handed back by [release], so it
 * reaches the source instead of vanishing.
 */
export class KeyboardKeeper {
    private el: HTMLElement | null = null;
    private timer: ReturnType<typeof setTimeout> | null = null;

    /** How long focus may wait here for the re-render before the keyboard is let go. */
    static readonly HOLD_LIMIT_MS = 1500;

    /** True while focus is parked here. */
    get holding(): boolean {
        return this.el !== null && document.activeElement === this.el;
    }

    /**
     * Parks focus here if an editable element in the page has it now, and
     * calls [onTimeout] if nothing takes it back within [HOLD_LIMIT_MS].
     * Returns whether it took focus.
     */
    hold(onTimeout: () => void): boolean {
        const active = document.activeElement as HTMLElement | null;
        if (!active?.isContentEditable) return false;
        const el = this.element();
        el.textContent = "";
        el.focus({ preventScroll: true });
        if (document.activeElement !== el) return false;
        this.clearTimer();
        this.timer = setTimeout(() => {
            this.timer = null;
            if (!this.holding) return;
            // The keyboard goes, but what was typed stays for the block when it does come back
            // ([release]); a tap elsewhere or the end of editing ([drop]) discards it.
            el.blur();
            onTimeout();
        }, KeyboardKeeper.HOLD_LIMIT_MS);
        return true;
    }

    /** Stops holding (focus has moved on, or is about to) and returns what was typed meanwhile. */
    release(): string {
        this.clearTimer();
        const el = this.el;
        if (!el) return "";
        const typed = el.textContent ?? "";
        el.textContent = "";
        return typed;
    }

    /** Lets the keyboard go: used when editing really ends. */
    drop(): void {
        const wasHolding = this.holding;
        this.release();
        if (wasHolding) this.el?.blur();
    }

    private clearTimer(): void {
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = null;
    }

    private element(): HTMLElement {
        if (this.el) return this.el;
        const el = document.createElement("div");
        el.className = "keyboard-keeper";
        el.contentEditable = "true";
        el.tabIndex = -1;
        el.setAttribute("aria-hidden", "true");
        // Off the document flow and invisible, but still focusable: opacity, not display or visibility.
        Object.assign(el.style, {
            position: "fixed",
            left: "0",
            bottom: "0",
            width: "1px",
            height: "1px",
            overflow: "hidden",
            opacity: "0",
            pointerEvents: "none",
            caretColor: "transparent",
            whiteSpace: "pre",
        });
        document.body.appendChild(el);
        this.el = el;
        return el;
    }
}
