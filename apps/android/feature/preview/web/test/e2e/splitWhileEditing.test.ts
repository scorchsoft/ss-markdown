/**
 * A section split while it is being edited (edge-cases "Typed text shown twice after Enter"):
 * Enter at the end of a paragraph, then typing, makes the new paragraph a section of its own.
 * The section being edited keeps its HTML until editing ends, so the new section must not
 * render the same text beside it, and when editing ends each paragraph is still shown once.
 */
import { afterAll, beforeAll, expect, it } from "vitest";
import type { Page } from "playwright";
import { clearReceived, loadMarkdown, openPreview, startHarness, waitForMessage, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const count = (page: Page, word: string): Promise<number> =>
    page.evaluate((w) => (document.querySelector("main")?.textContent ?? "").split(w).length - 1, word);

it("shows a paragraph typed after Enter once, while editing and after", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, "Alpha");
    await clearReceived(page);
    const box = await page.locator("p").first().boundingBox();
    if (!box) throw new Error("no paragraph");
    await page.mouse.click(box.x + box.width - 2, box.y + box.height / 2);
    await waitForMessage(page, (m) => m.type === "editingChanged");
    await page.keyboard.press("End");
    await page.keyboard.press("Enter");
    await page.waitForTimeout(400);
    await page.keyboard.type("Beta");
    await page.waitForTimeout(600);
    expect(await count(page, "Beta")).toBe(1);
    // Editing ends with a tap on the first paragraph: still once each.
    await page.mouse.click(box.x + 4, box.y + box.height / 2);
    await page.waitForTimeout(600);
    expect(await count(page, "Alpha")).toBe(1);
    expect(await count(page, "Beta")).toBe(1);
});
