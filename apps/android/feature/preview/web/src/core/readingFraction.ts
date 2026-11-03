/**
 * How far through the page the reader is, for the header's reading line:
 * the scroll position over the scrollable range. 0 at the top, exactly 1
 * at the very bottom, and 0 for a page that fits without scrolling. The
 * source line at the top could never reach 1, because the last screen of
 * a document is never at the top (docs/edge-cases.md, "Reading line stops
 * short of the end").
 *
 * @param scrollY the page's scroll offset, CSS px.
 * @param scrollHeight the page's full height, CSS px.
 * @param viewport the visible height, CSS px.
 */
export function readingFraction(scrollY: number, scrollHeight: number, viewport: number): number {
    const range = scrollHeight - viewport;
    // A sub-pixel range is a page that fits; rounding must not turn it into "at the end".
    if (range < 1) return 0;
    // Browsers stop a fractional pixel short of the end: within a pixel is the bottom.
    if (scrollY >= range - 1) return 1;
    return Math.min(1, Math.max(0, scrollY / range));
}
