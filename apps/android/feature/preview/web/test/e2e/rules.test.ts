/**
 * Horizontal rules in Preview (Andrew, 2026-11-04: a rule could not be tapped, nor a caret put before
 * or after it). A tap on or just beside the rule's lower half puts the caret at the start of the block
 * after it, its upper half at the end of the block above, and neither changes the file (a rule that
 * ends the document gets a line added). Backspace at the start of the block after a rule removes the
 * rule as one unit (EB-BREAK-08).
 */
import { afterAll, beforeAll, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage } from "../../src/protocol";
import { applyEdit } from "../../src/core/minimalEdit";
import {
    clearReceived,
    loadMarkdown,
    openPreview,
    received,
    startHarness,
    waitForMessage,
    type Harness,
} from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const SOURCE = "Above.\n\n---\n\nBelow.";

async function sourceAfter(page: Page, source: string): Promise<string> {
    let text = source;
    for (const m of await received(page)) {
        if (m.type !== "sourceEdit") continue;
        for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
    }
    return text;
}

async function ruleBox(page: Page): Promise<{ x: number; y: number; width: number; height: number }> {
    const box = await page.locator("hr").boundingBox();
    if (!box) throw new Error("no rule");
    return box;
}

it("a tap just below the rule puts the caret at the start of the block after it, and changes nothing", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, SOURCE);
    await clearReceived(page);
    const r = await ruleBox(page);
    await page.mouse.click(r.x + r.width / 2, r.y + 8);
    const sel = (await waitForMessage(page, (m) => m.type === "selection")) as Extract<
        PageMessage,
        { type: "selection" }
    >;
    expect(sel.from).toBe(SOURCE.indexOf("Below."));
    expect((await received(page)).some((m) => m.type === "sourceEdit")).toBe(false);
});

it("a tap below a rule that ends the document opens a line to type on", async () => {
    const page = await openPreview(h);
    const source = "Above.\n\n---";
    await loadMarkdown(page, source);
    await clearReceived(page);
    const r = await ruleBox(page);
    await page.mouse.click(r.x + r.width / 2, r.y + 8);
    await waitForMessage(page, (m) => m.type === "editingChanged");
    await page.keyboard.type("New");
    const want = "Above.\n\n---\nNew";
    const end = Date.now() + 4000;
    while ((await sourceAfter(page, source)) !== want && Date.now() < end) await page.waitForTimeout(100);
    expect(await sourceAfter(page, source)).toBe(want);
});

it("a tap just above the rule puts the caret at the end of the block above", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, SOURCE);
    await clearReceived(page);
    const r = await ruleBox(page);
    await page.mouse.click(r.x + r.width / 2, r.y - 8);
    const sel = (await waitForMessage(page, (m) => m.type === "selection")) as Extract<
        PageMessage,
        { type: "selection" }
    >;
    expect(sel.from).toBe("Above.".length);
});

it("Backspace at the start of the paragraph after a rule removes the rule", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, SOURCE);
    await clearReceived(page);
    const run = await page.locator("span", { hasText: "Below." }).boundingBox();
    if (!run) throw new Error("no run");
    await page.mouse.click(run.x + 1, run.y + run.height / 2);
    await waitForMessage(page, (m) => m.type === "editingChanged");
    await page.keyboard.press("Home");
    await page.keyboard.press("Backspace");
    await waitForMessage(page, (m) => m.type === "sourceEdit");
    expect(await sourceAfter(page, SOURCE)).toBe("Above.\n\nBelow.");
});
