/**
 * A tap where there is no block to tap (Andrew, 2026-11-04: a new file is empty, so in Preview
 * there was nothing to tap and no way to start typing). In a blank document a tap anywhere
 * starts editing at its end; below the last text block, a tap edits that block at its end; a
 * tap below a final table, code block or read-only block never jumps into or above it.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
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

/** [source] with every sourceEdit the page sent applied, as Kotlin would. */
async function applySent(page: Page, source: string): Promise<string> {
    let text = source;
    for (const m of await received(page)) {
        if (m.type !== "sourceEdit") continue;
        for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
    }
    return text;
}

/** Waits until the page's typing has been written back as [want]. */
async function expectSource(page: Page, source: string, want: string): Promise<void> {
    const end = Date.now() + 4000;
    while ((await applySent(page, source)) !== want && Date.now() < end) await page.waitForTimeout(100);
    expect(await applySent(page, source)).toBe(want);
}

async function selection(page: Page): Promise<Extract<PageMessage, { type: "selection" }>> {
    return (await waitForMessage(page, (m) => m.type === "selection")) as Extract<PageMessage, { type: "selection" }>;
}

describe("tap where there is no block", () => {
    for (const [name, source] of [
        ["an empty file", ""],
        ["a file of blank lines", "\n\n"],
    ] as const) {
        it(`starts editing ${name}, and what is typed is written`, async () => {
            const page = await openPreview(h);
            await loadMarkdown(page, source);
            await clearReceived(page);
            await page.mouse.click(200, 300);
            const editing = await waitForMessage(page, (m) => m.type === "editingChanged");
            expect(editing).toMatchObject({ editing: true });
            expect((await selection(page)).from).toBe(source.length);
            await page.keyboard.type("Hello");
            await expectSource(page, source, `${source}Hello`);
            // A second tap in the same space keeps the session (and the caret) where it is.
            await clearReceived(page);
            await page.mouse.click(200, 600);
            await page.waitForTimeout(200);
            expect((await received(page)).some((m) => m.type === "editingChanged")).toBe(false);
        });
    }

    it("puts the caret at the end of the last block for a tap far below it", async () => {
        const page = await openPreview(h);
        const source = "First paragraph.\n\nLast paragraph.";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.mouse.click(60, 700);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        expect((await selection(page)).from).toBe(source.length);
        await page.keyboard.type("!");
        await expectSource(page, source, `${source}!`);
    });

    it("edits the last list item below a list", async () => {
        const page = await openPreview(h);
        const source = "- one\n- two\n";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.mouse.click(60, 700);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        expect((await selection(page)).from).toBe(source.indexOf("two") + 3);
    });

    it("never jumps above a read-only block that ends the document", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "Text.\n\n<div>raw html</div>\n");
        await clearReceived(page);
        await page.mouse.click(60, 700);
        await page.waitForTimeout(300);
        expect((await received(page)).some((m) => m.type === "editingChanged")).toBe(false);
    });

    for (const [name, source] of [
        ["table", "Text.\n\n| a | b |\n|---|---|\n| 1 | 2 |\n"],
        ["code block", "Text.\n\n```\ncode\n```\n"],
    ] as const) {
        it(`leaves a tap far below a final ${name} alone (EB-CARET-04 wants a new paragraph there)`, async () => {
            const page = await openPreview(h);
            await loadMarkdown(page, source);
            await clearReceived(page);
            await page.mouse.click(60, 700);
            await page.waitForTimeout(300);
            expect((await received(page)).some((m) => m.type === "editingChanged")).toBe(false);
        });
    }

    it("leaves a tap in the gap between blocks to the nearest block", async () => {
        const page = await openPreview(h);
        const source = "Para.\n\n".repeat(30);
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.mouse.click(60, 400);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        // Somewhere in the middle of the page, not the end of the document.
        expect((await selection(page)).from).toBeLessThan(source.length - 10);
    });
});
