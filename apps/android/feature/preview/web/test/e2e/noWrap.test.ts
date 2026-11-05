/**
 * Preview's No wrap (Andrew, 2026-11-04: "No wrap still doesn't work on preview mode, it's still
 * wrapping"): as in Code, each source line of text stays one line, never wrapped at the screen's
 * edge, and the page pans sideways. Fluid wraps as before; list items and cells gain no blank
 * lines; typing still lands at the right source offset.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage, PreviewSettings } from "../../src/protocol";
import { clearReceived, loadMarkdown, openPreview, send, startHarness, waitForMessage, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const LONG = "A long line that would wrap many times on a phone screen because it has lots of words in it.";
const SOURCE = [
    "# A heading long enough to wrap on a phone screen easily",
    "",
    LONG,
    "second source line of the same paragraph",
    "",
    `- tight item ${LONG}`,
    "  - nested item",
    "- two",
    "",
    "1. loose",
    "",
    "   a paragraph in the item",
    "",
    "2. second",
    "",
    `> quote ${LONG}`,
    "",
    "| a | b |",
    "|---|---|",
    `| ${LONG} | x |`,
].join("\n");

const BASE: PreviewSettings = { tapToEdit: true, pageMode: false, zoom: 1, lean: false, readOnly: false };

interface Shape {
    pageWidth: number;
    viewport: number;
    heading: number;
    paragraph: number;
    items: number[];
    loose: number[];
    quote: number;
}

/** Line counts of each block, from its height over its line height. */
function shape(page: Page): Promise<Shape> {
    return page.evaluate(() => {
        const lines = (el: Element): number =>
            Math.round(el.getBoundingClientRect().height / parseFloat(getComputedStyle(el).lineHeight));
        const one = (s: string): Element => {
            const el = document.querySelector(s);
            if (!el) throw new Error(`no ${s}`);
            return el;
        };
        return {
            pageWidth: document.documentElement.scrollWidth,
            viewport: innerWidth,
            heading: lines(one("h1")),
            paragraph: lines(one("section.sec > p")),
            items: [...document.querySelectorAll("ul > li")].map(lines),
            loose: [...document.querySelectorAll("ol > li > p")].map(lines),
            quote: lines(one("blockquote p")),
        };
    });
}

describe("Preview No wrap", () => {
    it("wraps everything in Fluid", async () => {
        const page = await openPreview(h, { width: 412, height: 860 });
        await loadMarkdown(page, SOURCE);
        const s = await shape(page);
        expect(s.pageWidth).toBeLessThanOrEqual(s.viewport);
        expect(s.paragraph).toBeGreaterThan(2);
        expect(s.quote).toBeGreaterThan(1);
    });

    it("keeps each source line on one line and pans the page sideways", async () => {
        const page = await openPreview(h, { width: 412, height: 860 });
        await loadMarkdown(page, SOURCE);
        await send(page, { type: "settings", settings: { ...BASE, noWrap: true } });
        await page.waitForFunction(() => document.documentElement.classList.contains("no-wrap"));
        const s = await shape(page);
        expect(s.pageWidth, "the page is wider than the screen").toBeGreaterThan(s.viewport);
        expect(s.heading).toBe(1);
        expect(s.paragraph, "two source lines, two lines").toBe(2);
        // The first item holds its nested list: two lines; no blank lines from the markup between blocks.
        expect(s.items).toEqual([2, 1, 1]);
        expect(s.loose).toEqual([1, 1, 1]);
        expect(s.quote).toBe(1);
    });

    it("leaves A4 page mode laid out as a page", async () => {
        const page = await openPreview(h, { width: 412, height: 860 });
        await loadMarkdown(page, SOURCE);
        await send(page, { type: "settings", settings: { ...BASE, noWrap: true, pageMode: true } });
        await page.waitForFunction(() => document.documentElement.classList.contains("page-mode"));
        expect(await page.evaluate(() => document.documentElement.classList.contains("no-wrap"))).toBe(false);
    });

    it("still takes typing at the right offset", async () => {
        const page = await openPreview(h, { width: 412, height: 860 });
        await loadMarkdown(page, SOURCE);
        await send(page, { type: "settings", settings: { ...BASE, noWrap: true } });
        await page.waitForFunction(() => document.documentElement.classList.contains("no-wrap"));
        await clearReceived(page);
        const run = page.locator("section.sec > p span", { hasText: "second source line" });
        const box = await run.boundingBox();
        if (!box) throw new Error("no run");
        await page.mouse.click(box.x + 4, box.y + box.height / 2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.keyboard.press("End");
        await page.keyboard.type("!");
        const edit = (await waitForMessage(page, (m) => m.type === "sourceEdit")) as Extract<
            PageMessage,
            { type: "sourceEdit" }
        >;
        const at =
            SOURCE.indexOf("second source line of the same paragraph") +
            "second source line of the same paragraph".length;
        expect(edit.edits).toEqual([{ from: at, to: at, insert: "!" }]);
    });
});
