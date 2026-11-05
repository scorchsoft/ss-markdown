/**
 * Keeps the point under the fingers still while a pinch scales the A4 page
 * (print.css: `.doc { zoom }` in page mode). Kotlin's PinchZoom takes the
 * gesture once it is a pinch, but the page still sees the fingers land, so
 * their midpoint is remembered here; without one (a zoom step from the
 * View sheet) the middle of the screen stays still.
 */
const FRESH_MILLIS = 3000;

export class PinchFocus {
    private x = 0;
    private y = 0;
    private at = -Infinity;

    constructor(target: EventTarget = window) {
        const note = (e: Event): void => {
            const touches = (e as TouchEvent).touches;
            if (!touches || touches.length < 2) return;
            const a = touches[0];
            const b = touches[1];
            if (!a || !b) return;
            this.x = (a.clientX + b.clientX) / 2;
            this.y = (a.clientY + b.clientY) / 2;
            this.at = performance.now();
        };
        target.addEventListener("touchstart", note, { passive: true });
        target.addEventListener("touchmove", note, { passive: true });
    }

    /** After the page grew by [ratio], scrolls so the focus shows the same spot of the page. */
    keep(ratio: number): void {
        const fresh = performance.now() - this.at < FRESH_MILLIS;
        const fx = fresh ? this.x : window.innerWidth / 2;
        const fy = fresh ? this.y : window.innerHeight / 2;
        window.scrollTo({
            left: (window.scrollX + fx) * ratio - fx,
            top: (window.scrollY + fy) * ratio - fy,
            behavior: "instant",
        });
    }
}
