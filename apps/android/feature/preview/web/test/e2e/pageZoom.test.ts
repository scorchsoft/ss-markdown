/**
 * Pinch in A4 page mode (Andrew, 2026-11-04: "I can't pinch zoom … it's showing the pinch zoom
 * functions, but it's not actually doing anything"). The page's type is fixed in print units, so
 * the text-size zoom never reached it; now the zoom scales the whole page in proportion, as a PDF
 * viewer does, keeps the spot under the fingers still, and taps still land on the right text.
 * Print stays at true scale.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PreviewSettings } from "../../src/protocol";
import { applyEdit } from "../../src/core/minimalEdit";
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

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const PAGE: PreviewSettings = { tapToEdit: true, pageMode: true, zoom: 1, lean: false, readOnly: false };
const SOURCE = Array.from({ length: 40 }, (_, i) => `Paragraph ${i} with some words in it.`).join("\n\n");

interface Measure {
    width: number;
    font: number;
    scrollX: number;
    scrollY: number;
}

function measure(page: Page): Promise<Measure> {
    return page.evaluate(() => {
        const doc = document.querySelector(".doc") as HTMLElement;
        const p = document.querySelector(".doc p") as HTMLElement;
        return {
            width: doc.getBoundingClientRect().width,
            font: p.getBoundingClientRect().height,
            scrollX: window.scrollX,
            scrollY: window.scrollY,
        };
    });
}

async function zoomTo(page: Page, zoom: number): Promise<void> {
    await send(page, { type: "settings", settings: { ...PAGE, zoom } });
    await page.waitForTimeout(150);
}

describe("pinch in A4 page mode", () => {
    it("scales the whole page in proportion", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        await zoomTo(page, 1);
        const one = await measure(page);
        await zoomTo(page, 2);
        const two = await measure(page);
        expect(two.width / one.width).toBeCloseTo(2, 1);
        expect(two.font / one.font).toBeCloseTo(2, 1);
        await zoomTo(page, 0.5);
        const half = await measure(page);
        expect(half.width / one.width).toBeCloseTo(0.5, 1);
        // At half size the A4 page fits a phone's width: no sideways panning needed.
        expect(half.width).toBeLessThanOrEqual(412);
    });

    it("keeps the middle of the screen on the same spot of the page", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        await zoomTo(page, 1);
        await page.evaluate(() => window.scrollTo(0, 600));
        await page.waitForTimeout(100);
        const at = (): Promise<string> =>
            page.evaluate(() => {
                const el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
                return el?.closest("p")?.textContent ?? "";
            });
        const before = await at();
        await zoomTo(page, 1.6);
        expect(await at()).toBe(before);
    });

    it("lands a tap on the word under it when zoomed", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        await zoomTo(page, 1.8);
        await clearReceived(page);
        const target = await page.evaluate(() => {
            const p = [...document.querySelectorAll(".doc p")].find((el) => el.textContent?.startsWith("Paragraph 3 "));
            const r = (p as HTMLElement).getBoundingClientRect();
            return { x: r.left + 4, y: r.top + r.height / 2 };
        });
        await page.mouse.click(target.x, target.y);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.keyboard.type("X");
        const want = SOURCE.replace("Paragraph 3 ", "XParagraph 3 ");
        const end = Date.now() + 4000;
        let text = SOURCE;
        while (Date.now() < end) {
            text = SOURCE;
            for (const m of await received(page)) {
                if (m.type !== "sourceEdit") continue;
                for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
            }
            if (text === want) break;
            await page.waitForTimeout(100);
        }
        expect(text).toBe(want);
    });

    it("never zooms the printed page", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        await zoomTo(page, 2);
        await page.emulateMedia({ media: "print" });
        const zoom = await page.evaluate(() => getComputedStyle(document.querySelector(".doc") as Element).zoom);
        expect(zoom).toBe("1");
    });
});

