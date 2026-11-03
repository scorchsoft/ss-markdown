/**
 * How far to scroll so the caret being edited is not left under the soft
 * keyboard (docs/ux-spec.md, "Keyboard"). Pure: the page measures, this
 * decides.
 *
 * The keyboard and the formatting strip take their room from the bottom of
 * the WebView itself (Compose pads the strip up by the keyboard's inset and
 * the pane above shrinks, frame by frame), so "under the keyboard plus the
 * strip" is simply "below the bottom of the page's viewport".
 *
 * All values are CSS px in the viewport's coordinates (a client rect).
 */

/** The caret's line: its top and bottom on screen. */
export interface CaretLine {
    readonly top: number;
    readonly bottom: number;
}

/** Lines of clear text left between the caret and the strip when the page scrolls it up. */
export const COMFORT_LINES = 2;

/**
 * The scroll (positive: down) that puts [caret] about [COMFORT_LINES] lines
 * above the bottom of a [viewport]-high page, when it is within [trigger] px
 * of that bottom or below it; 0 when it is clear of it. A caret above the
 * top is brought down to one line below the top. Never scrolls the caret's
 * own line off the top, however short the page.
 */
export function revealDelta(caret: CaretLine, viewport: number, trigger: number): number {
    const line = Math.max(1, caret.bottom - caret.top);
    if (caret.top < 0) return caret.top - Math.min(line, Math.max(0, viewport - line) / 2);
    if (caret.bottom <= viewport - trigger) return 0;
    const wanted = caret.bottom - (viewport - COMFORT_LINES * line);
    return Math.max(0, Math.min(wanted, caret.top));
}

/**
 * The page shrank from [before] to [after] px high (the keyboard opening or
 * growing, the strip appearing): the scroll that keeps [caret] clear of the
 * new bottom. Only a caret that was on screen before is kept on screen: one
 * the reader had already scrolled away from stays where it is. A caret that
 * stays clear of the strip by [COMFORT_LINES] lines does not move anything,
 * so a tap near the top never scrolls.
 */
export function shrinkDelta(caret: CaretLine, before: number, after: number): number {
    if (after >= before) return 0;
    if (caret.bottom <= 0 || caret.top >= before) return 0;
    const line = Math.max(1, caret.bottom - caret.top);
    return revealDelta(caret, after, COMFORT_LINES * line);
}

/**
 * The bottom padding the page keeps while editing, beyond its own: room for
 * [COMFORT_LINES] lines below the last one, plus the keyboard's height
 * ([tallest] minus [viewport]), so closing the keyboard never has to pull
 * the view back because the page has become too short to stay where it is.
 */
export function editingPad(line: number, tallest: number, viewport: number): number {
    return Math.ceil(COMFORT_LINES * Math.max(1, line) + Math.max(0, tallest - viewport));
}

/**
 * After editing, the padding shrinks only by what is already below the
 * viewport's bottom ([slack]), so the view never moves when it goes.
 */
export function trimmedPad(pad: number, slack: number): number {
    return Math.max(0, pad - Math.max(0, slack));
}
