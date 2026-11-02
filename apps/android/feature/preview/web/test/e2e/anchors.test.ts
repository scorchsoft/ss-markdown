/**
 * Rubric #3, position continuity: switching Code ↔ Preview lands within ±1
 * block of the anchor at ≥ 95% of corpus positions (docs/rubric.md).
 *
 * Every source line of the corpus is an anchor. For each one the test plays
 * both directions through the real protocol, exactly as the app does:
 *
 * - Code → Preview: Kotlin sends `scrollToLine`; the block at the top of the
 *   viewport must be the anchor's block or a neighbour.
 * - Preview → Code: the page reports `topLine` for that scroll position; the
 *   line Code would land on must also sit in the anchor's block or a neighbour.
 *
 * A "block" is a top-level rendered element with `data-line` (a paragraph, a
 * heading, a whole list, a table, a fence), the unit scroll sync works in. The
 * anchor's block is the last one starting at or before the anchor line, so a
 * blank line between blocks belongs to the block above it, as Code shows it.
 *
 * Near the end of the document the page cannot scroll the anchor to the top.
 * There, landing counts when the anchor's block is on screen at the bottom of
 * the scroll range; those positions are counted and reported separately.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import {
    clearReceived,
    corpus,
    diagramsSettled,
    loadMarkdown,
    openPreview,
    pageErrors,
    received,
    send,
    startHarness,
    type Harness,
} from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

/** The rubric's bar. */
const PASS_RATE = 0.95;

/** Every rendering corpus file (the hostile one is for security, not reading). */
const FILES = ["basics.md", "extended.md", "tables.md", "unicode.md", "inline-edges.md", "crlf.md"];

interface Landing {
    readonly anchorIdx: number;
    readonly landedIdx: number;
    readonly backIdx: number;
    readonly clamped: boolean;
    readonly visible: boolean;
}

/** Two frames: one for the scroll to apply, one for the page's rAF-throttled `topLine` report. */
async function frames(page: Page): Promise<void> {
    await page.evaluate(() => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r()))));
}

/** The page's last reported top line, or null if it has reported none since the last clear. */
async function lastTopLine(page: Page): Promise<number | null> {
    const tops = (await received(page)).filter((m) => m.type === "topLine");
    const last = tops[tops.length - 1];
    return last && last.type === "topLine" ? last.line : null;
}

async function land(page: Page, line: number, previousTop: number): Promise<Landing & { top: number }> {
    await send(page, { type: "scrollToLine", line });
    await frames(page);
    // A scroll that keeps the same top line reports nothing new: the earlier report still holds.
    const top = (await lastTopLine(page)) ?? previousTop;
    const facts = await page.evaluate(
        ({ anchor, top }) => {
            const blocks = [...document.querySelectorAll<HTMLElement>("section.sec.mounted > [data-line]")]
                .map((e) => {
                    const r = e.getBoundingClientRect();
                    return {
                        line: Number(e.dataset.line),
                        top: r.top + window.scrollY,
                        bottom: r.bottom + window.scrollY,
                    };
                })
                .sort((a, b) => a.line - b.line);
            const indexAt = (l: number): number => {
                let found = -1;
                blocks.forEach((b, i) => {
                    if (b.line <= l) found = i;
                });
                return found;
            };
            const y = window.scrollY;
            let landedIdx = -1;
            blocks.forEach((b, i) => {
                if (b.top <= y + 0.5) landedIdx = i;
            });
            const anchorIdx = indexAt(anchor);
            const maxScroll = document.documentElement.scrollHeight - window.innerHeight;
            const a = blocks[anchorIdx];
            return {
                anchorIdx,
                landedIdx,
                backIdx: indexAt(top),
                clamped: y >= maxScroll - 1,
                visible: a !== undefined && a.bottom > y && a.top < y + window.innerHeight,
            };
        },
        { anchor: line, top },
    );
    return { ...facts, top };
}

/** One document of every corpus file, so most anchors can reach the top of the screen. */
function corpusDocument(): string {
    return FILES.map((f) => corpus(f).replace(/\r\n/g, "\n")).join("\n\n");
}

/** A phone in portrait, a phone on its side, and a tablet. */
const VIEWPORTS = [
    { width: 412, height: 860 },
    { width: 860, height: 380 },
    { width: 1024, height: 1280 },
];

describe("Code ↔ Preview anchor mapping over the corpus", () => {
    it.each(VIEWPORTS)(
        `lands within ±1 block of the anchor at ≥ ${PASS_RATE * 100}% of positions, both ways, at $width×$height`,
        async (viewport) => {
            const text = corpusDocument();
            const lines = text.split("\n").length;
            const page = await openPreview(h, viewport);
            await loadMarkdown(page, text);
            await diagramsSettled(page);
            await clearReceived(page);

            let pass = 0;
            let exact = 0;
            let atEnd = 0;
            let top = 0;
            const misses: string[] = [];
            for (let line = 0; line < lines; line++) {
                const l = await land(page, line, top);
                top = l.top;
                if (l.anchorIdx < 0) {
                    // Before the first block (front matter's opening fence): the top of the page is right.
                    pass++;
                    continue;
                }
                const there = Math.abs(l.landedIdx - l.anchorIdx) <= 1;
                const back = Math.abs(l.backIdx - l.anchorIdx) <= 1;
                if (l.landedIdx === l.anchorIdx && l.backIdx === l.anchorIdx) exact++;
                if (there && back) {
                    pass++;
                } else if (l.clamped && l.visible && back) {
                    pass++;
                    atEnd++;
                } else {
                    misses.push(`line ${line}: anchor block ${l.anchorIdx}, landed ${l.landedIdx}, back ${l.backIdx}`);
                }
            }
            const rate = pass / lines;
            console.log(
                `anchor mapping at ${viewport.width}×${viewport.height}: ${pass}/${lines} positions ` +
                    `(${(rate * 100).toFixed(1)}%) within ±1 block both ways, ${exact} on the exact block; ` +
                    `${atEnd} at the end of the scroll range`,
            );
            if (misses.length > 0) console.log(misses.slice(0, 20).join("\n"));
            expect(rate, misses.join("\n")).toBeGreaterThanOrEqual(PASS_RATE);
            expect(pageErrors(page)).toEqual([]);
        },
    );

    it("keeps reporting the asked-for line at the end of the page until the reader scrolls", async () => {
        const text = corpusDocument();
        const last = text.split("\n").length - 2;
        const page = await openPreview(h);
        await loadMarkdown(page, text);
        await diagramsSettled(page);
        await clearReceived(page);
        await send(page, { type: "scrollToLine", line: last });
        await frames(page);
        expect(await lastTopLine(page)).toBe(last);
        // The reader's own scroll ends the pin: the page reports what is really at the top again.
        await page.mouse.move(200, 400);
        await page.mouse.wheel(0, -300);
        await page.waitForTimeout(100);
        await frames(page);
        const top = await lastTopLine(page);
        expect(top).not.toBeNull();
        expect(top as number).toBeLessThan(last);
    });
});
