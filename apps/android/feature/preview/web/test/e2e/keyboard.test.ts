/**
 * The line being edited stays above the keyboard (Andrew: "in Preview, when I tap a line near
 * the bottom, the keyboard opens over it and I have to scroll to see it"; ux-spec "Keyboard").
 *
 * What the app really does when the keyboard opens: Compose pads the formatting strip up by the
 * keyboard's inset, frame by frame, and the pane above it shrinks, so the WebView's own viewport
 * gets shorter by the keyboard plus the strip on every frame of the slide. The harness does the
 * same with the viewport, one step per animation frame.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import {
    clearReceived,
    loadMarkdown,
    openPreview,
    received,
    send,
    startHarness,
    waitForMessage,
    type Harness,
} from "./harness";

const WIDTH = 412;
const FULL = 860;
/** The pane once a 300 px keyboard and a 2-row (96 px) strip have taken their room. */
const OPEN = FULL - 300 - 96;
const STEPS = 12;

const SOURCE = Array.from(
    { length: 60 },
    (_, i) => `Paragraph ${i + 1} is long enough to wrap onto a second line at phone width, here.`,
).join("\n\n");

interface Caret {
    top: number;
    bottom: number;
    height: number;
    viewport: number;
    scrollY: number;
}

let h: Harness;
let page: Page;

beforeAll(async () => {
    h = await startHarness();
});
afterAll(async () => h.close());

beforeEach(async () => {
    await page?.close();
    page = await openPreview(h, { width: WIDTH, height: FULL }, { hasTouch: true });
    await loadMarkdown(page, SOURCE);
});

/** The caret's line in the block being edited; fails the test if there is none. */
async function caret(): Promise<Caret> {
    const c = await page.evaluate(() => {
        const s = window.getSelection();
        if (!s || s.rangeCount === 0 || !document.querySelector(".editing")) return null;
        const r = s.getRangeAt(0);
        const rect = r.getClientRects()[0] ?? r.getBoundingClientRect();
        if (rect.height === 0) return null;
        return {
            top: rect.top,
            bottom: rect.bottom,
            height: rect.height,
            viewport: window.innerHeight,
            scrollY: window.scrollY,
        };
    });
    if (c === null) throw new Error("no caret in a block being edited");
    return c;
}

/** Resizes the viewport to [to] in [STEPS] frames, as the keyboard slides; returns the caret after each frame. */
async function slide(to: number): Promise<Caret[]> {
    const from = page.viewportSize()?.height ?? FULL;
    const seen: Caret[] = [];
    for (let i = 1; i <= STEPS; i++) {
        const height = Math.round(from + ((to - from) * i) / STEPS);
        await page.setViewportSize({ width: WIDTH, height });
        await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
        seen.push(await caret());
    }
    return seen;
}

/** A real tap on the paragraph whose middle is nearest [y], on its first line; waits until editing starts. */
async function tapParagraphNear(y: number): Promise<void> {
    const point = await page.evaluate((target) => {
        let best: { x: number; y: number; d: number } | null = null;
        for (const p of document.querySelectorAll("p")) {
            const r = p.getClientRects()[0];
            if (!r || r.bottom > window.innerHeight || r.top < 0) continue;
            const mid = (r.top + r.bottom) / 2;
            const d = Math.abs(mid - target);
            if (!best || d < best.d) best = { x: r.left + 30, y: mid, d };
        }
        return best;
    }, y);
    if (!point) throw new Error("no paragraph on screen");
    await clearReceived(page);
    await page.touchscreen.tap(point.x, point.y);
    await waitForMessage(page, (m) => m.type === "editingChanged" && m.editing);
    await page.waitForFunction(() => {
        const s = window.getSelection();
        return s !== null && s.rangeCount > 0 && document.querySelector(".editing") !== null;
    });
}

/** Scrolls to the very end, waiting out the 500 ms fling guard so a tap edits. */
async function scrollToEnd(): Promise<void> {
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForFunction(() => document.querySelectorAll("section.sec.mounted").length > 0);
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.waitForTimeout(600);
}

describe("the keyboard opens over the caret", () => {
    it("a tap near the bottom: the caret rides up with the keyboard and ends about two lines above the strip", async () => {
        await tapParagraphNear(FULL - 40);
        const before = await caret();
        expect(before.bottom).toBeGreaterThan(OPEN);
        const frames = await slide(OPEN);
        // Never hidden on any frame of the slide: it scrolls with the keyboard, not after it.
        for (const [i, c] of frames.entries()) {
            expect(c.bottom, `frame ${i}: caret under the strip`).toBeLessThanOrEqual(c.viewport + 0.5);
        }
        const after = await caret();
        const gap = after.viewport - after.bottom;
        expect(gap).toBeGreaterThanOrEqual(after.height * 1.5);
        expect(gap).toBeLessThanOrEqual(after.height * 3);
    });

    it("a tap near the top: nothing moves", async () => {
        await page.evaluate(() => window.scrollTo(0, 600));
        await page.waitForTimeout(600);
        await tapParagraphNear(120);
        const before = await caret();
        const frames = await slide(OPEN);
        for (const c of frames) expect(c.scrollY).toBe(before.scrollY);
        expect((await caret()).top).toBeCloseTo(before.top, 0);
    });

    it("the keyboard grows (an emoji panel) over a caret it now covers: re-checked", async () => {
        await tapParagraphNear(FULL - 40);
        await slide(OPEN);
        const settled = await caret();
        // The emoji panel takes another 120 px.
        await slide(OPEN - 120);
        const after = await caret();
        expect(settled.bottom).toBeGreaterThan(OPEN - 120 - settled.height * 1.5);
        expect(after.bottom).toBeLessThanOrEqual(after.viewport - after.height * 1.5);
    });

    it("typing new lines at the bottom keeps the caret above the strip", async () => {
        await tapParagraphNear(FULL - 40);
        await slide(OPEN);
        await page.keyboard.press("End");
        for (let i = 0; i < 8; i++) {
            await clearReceived(page);
            await page.keyboard.press("Enter");
            // The new paragraph has taken the caret before typing goes on (Enter re-renders the block).
            await page.waitForFunction(() => {
                const node = window.getSelection()?.focusNode;
                const el = node instanceof Element ? node : node?.parentElement;
                return el?.closest(".editing.placeholder") != null;
            });
            await page.keyboard.type(`Line ${i}`);
            // Settled: the line is on the page and the caret is in the block being edited.
            await page.waitForFunction((text) => {
                const node = window.getSelection()?.focusNode;
                const el = node instanceof Element ? node : node?.parentElement;
                const typed = [...document.querySelectorAll("p")].some((p) => p.textContent?.includes(text));
                return typed && el?.closest(".editing") != null;
            }, `Line ${i}`);
            const c = await caret();
            expect(c.bottom, `after line ${i}`).toBeLessThanOrEqual(c.viewport + 0.5);
            expect(c.top, `after line ${i}`).toBeGreaterThanOrEqual(0);
        }
        const sent = await received(page);
        expect(sent.some((m) => m.type === "sourceEdit")).toBe(true);
    });

    it("the last line can sit above the strip, and closing the keyboard does not jump the view", async () => {
        await scrollToEnd();
        const lastTop = await page.evaluate(() => {
            const all = [...document.querySelectorAll("p")];
            return all.at(-1)?.getBoundingClientRect().top ?? 0;
        });
        // Push the last line to the bottom edge: the case where the end of the document must have room.
        await page.evaluate((t) => window.scrollBy(0, t - (window.innerHeight - 30)), lastTop);
        await page.waitForTimeout(600);
        await tapParagraphNear(FULL - 20);
        await slide(OPEN);
        const open = await caret();
        expect(open.viewport - open.bottom).toBeGreaterThanOrEqual(open.height * 1.5);
        // The keyboard closes: the view stays where it is, no jump back.
        const closing = await slide(FULL);
        for (const c of closing) expect(c.top).toBeCloseTo(open.top, 0);
    });

    it("a narrow-then-wide resize (rotation) with the keyboard open keeps the caret on screen", async () => {
        await tapParagraphNear(FULL - 40);
        await slide(OPEN);
        await page.setViewportSize({ width: 860, height: 230 });
        await page.waitForFunction(() => {
            const s = window.getSelection();
            if (!s || s.rangeCount === 0) return false;
            const r = s.getRangeAt(0).getClientRects()[0];
            return r !== undefined && r.top >= 0 && r.bottom <= window.innerHeight;
        });
    });

    it("Find next with the keyboard open lands the match above the strip", async () => {
        await tapParagraphNear(120);
        await slide(OPEN);
        const ranges: number[] = [];
        for (const n of [30, 45]) {
            const at = SOURCE.indexOf(`Paragraph ${n} `);
            ranges.push(at, at + `Paragraph ${n}`.length);
        }
        for (const current of [0, 1]) {
            await send(page, { type: "find", rev: 1, ranges, current, reveal: true });
            await page.waitForFunction(() => {
                const registry = (CSS as unknown as { highlights: Map<string, Set<Range>> }).highlights;
                const range = [...(registry.get("ssm-find-current") ?? [])][0];
                if (!range) return false;
                const rect = range.getBoundingClientRect();
                return rect.height > 0 && rect.top >= 0 && rect.bottom <= window.innerHeight;
            });
        }
    });

    it("a caret restored under the keyboard (Undo, a host select) is scrolled above the strip", async () => {
        await tapParagraphNear(120);
        await slide(OPEN);
        // Kotlin's select after an Undo, landing below the screen in a section that is mounted.
        const name = await page.evaluate(() => {
            const below = [...document.querySelectorAll("section.sec.mounted p")].find(
                (p) => p.getBoundingClientRect().top > window.innerHeight + 100,
            );
            return below?.textContent?.match(/^Paragraph \d+ /)?.[0] ?? null;
        });
        if (name === null) throw new Error("no mounted paragraph below the screen");
        const target = SOURCE.indexOf(name);
        await page.evaluate((offset) => {
            const w = window as unknown as { __port: MessagePort; __rev: number };
            w.__port.postMessage(JSON.stringify({ type: "select", rev: w.__rev, from: offset, to: offset }));
        }, target + 4);
        await page.waitForFunction((text) => {
            const s = window.getSelection();
            const node = s?.focusNode;
            const el = node instanceof Element ? node : node?.parentElement;
            return el?.closest(".editing")?.textContent?.includes(text) === true;
        }, name);
        const c = await caret();
        expect(c.top).toBeGreaterThanOrEqual(0);
        expect(c.bottom).toBeLessThanOrEqual(c.viewport - c.height * 1.5);
    });
});
