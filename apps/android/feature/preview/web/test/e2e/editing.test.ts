import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage } from "../../src/protocol";
import { applyEdit } from "../../src/core/minimalEdit";
import {
    clearReceived,
    corpus,
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

type SourceEditMessage = Extract<PageMessage, { type: "sourceEdit" }>;

/** Applies every sourceEdit the page sent, as Kotlin would (descending offsets). */
async function applySent(page: Page, source: string): Promise<string> {
    let text = source;
    for (const m of await received(page)) {
        if (m.type !== "sourceEdit") continue;
        for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
    }
    return text;
}

async function tapText(page: Page, text: string, at = 0.5): Promise<void> {
    const box = await page.getByText(text, { exact: false }).first().boundingBox();
    if (!box) throw new Error(`no ${text}`);
    await page.mouse.click(box.x + box.width * at, box.y + box.height / 2);
}

describe("tap to edit", () => {
    it("writes a typo fix back as a minimal edit", async () => {
        const page = await openPreview(h);
        const source = "# Title\n\nThe quick brwn fox **jumps** over.\n\nLast.";
        await loadMarkdown(page, source);
        await clearReceived(page);
        const word = page.locator("span", { hasText: "The quick brwn fox" });
        const box = await word.boundingBox();
        if (!box) throw new Error("no run");
        // Click just after "br" by measuring the text.
        const x = await word.evaluate((el) => {
            const range = document.createRange();
            const node = el.firstChild as Text;
            range.setStart(node, 0);
            range.setEnd(node, "The quick br".length);
            return range.getBoundingClientRect().right;
        });
        await page.mouse.click(x, box.y + box.height / 2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.keyboard.type("o");
        const m = (await waitForMessage(page, (x) => x.type === "sourceEdit")) as SourceEditMessage;
        expect(m.edits).toEqual([{ from: source.indexOf("brwn") + 2, to: source.indexOf("brwn") + 2, insert: "o" }]);
        expect(await applySent(page, source)).toBe(source.replace("brwn", "brown"));
    });

    it("escapes typed syntax and edits inside bold without touching markers", async () => {
        const page = await openPreview(h);
        const source = "Para with **bold** text.";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await tapText(page, "bold", 0.99);
        await page.keyboard.press("End");
        await page.keyboard.type("*");
        await waitForMessage(page, (x) => x.type === "sourceEdit");
        const after = await applySent(page, source);
        expect(after.startsWith("Para with **bold**")).toBe(true);
        expect(after).toContain("\\*");
    });

    it("continues a list on Enter in the document's style", async () => {
        const page = await openPreview(h);
        const source = "+ one\n+ two\n";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await tapText(page, "two", 0.99);
        await page.keyboard.press("End");
        await page.keyboard.press("Enter");
        await waitForMessage(page, (x) => x.type === "sourceEdit");
        await page.keyboard.type("three");
        await page.waitForTimeout(400);
        expect(await applySent(page, source)).toBe("+ one\n+ two\n+ three\n");
    });

    it("ignores a tap that stops a fling", async () => {
        const page = await openPreview(h, { width: 412, height: 400 });
        await loadMarkdown(page, Array.from({ length: 60 }, (_, i) => `Paragraph ${i}.`).join("\n\n"));
        await clearReceived(page);
        await page.mouse.wheel(0, 600);
        await page.waitForTimeout(50);
        await page.mouse.click(40, 200);
        await page.waitForTimeout(100);
        expect((await received(page)).some((m) => m.type === "editingChanged")).toBe(false);
        await page.waitForTimeout(600);
        await page.mouse.click(40, 200);
        await waitForMessage(page, (m) => m.type === "editingChanged");
    });

    it("toggles a task by one character", async () => {
        const page = await openPreview(h);
        const source = "- [ ] open\n- [x] done";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("input.task-list-item-checkbox").first().click();
        const m = await waitForMessage(page, (x) => x.type === "taskToggle");
        expect(m).toMatchObject({ offset: 3, checked: true });
    });

    it("routes links, heading long-press and Edit in Code to Kotlin", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "# Head\n\nA [link](https://example.com).\n\n$$\nx^2\n$$\n");
        await clearReceived(page);
        await page.locator("a[href='https://example.com']").click();
        expect(await waitForMessage(page, (m) => m.type === "linkTapped")).toMatchObject({
            href: "https://example.com",
            line: 2,
        });
        await page.locator("h1").click({ button: "right" });
        expect(await waitForMessage(page, (m) => m.type === "headingLongPress")).toMatchObject({
            line: 0,
            level: 1,
            text: "Head",
        });
        await page.locator(".edit-in-code").click();
        expect(await waitForMessage(page, (m) => m.type === "editInCode")).toMatchObject({ line: 4 });
        await page.locator(".math-block").click();
        expect(await waitForMessage(page, (m) => m.type === "openViewer")).toMatchObject({ kind: "math", line: 4 });
    });
});

describe("tables", () => {
    it("edits a padded table cell and re-pads only the changed column on leaving", async () => {
        const page = await openPreview(h);
        const source = corpus("tables.md");
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("td", { hasText: "one" }).first().click();
        await page.keyboard.press("End");
        await page.keyboard.type("-plus");
        await page.waitForTimeout(300);
        await send(page, { type: "endEditing" });
        await page.waitForTimeout(300);
        const after = await applySent(page, source);
        const lines = after.split("\n");
        expect(lines.slice(2, 6)).toEqual([
            "| Name     | Value |",
            "| -------- | ----- |",
            "| one-plus | 1     |",
            "| two      | 2     |",
        ]);
        expect(lines.slice(6)).toEqual(source.split("\n").slice(6));
    });

    it("moves to the next cell and adds a row from the last one", async () => {
        const page = await openPreview(h);
        const source = "|a|b|\n|-|-|\n|1|2|\n";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("td", { hasText: "2" }).click();
        await send(page, { type: "tableNav", direction: "next" });
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        await page.waitForTimeout(300);
        await page.keyboard.type("3");
        await page.waitForTimeout(400);
        expect(await applySent(page, source)).toBe("|a|b|\n|-|-|\n|1|2|\n|3||\n");
    });

    it("aligns a column from the toolbar, changing only the delimiter row", async () => {
        const page = await openPreview(h);
        const source = "| a | b |\n| --- | --- |\n| 1 | 2 |";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("td", { hasText: "2" }).click();
        await page.locator(".table-toolbar button[aria-label='Align right']").click();
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        expect(await applySent(page, source)).toBe("| a | b |\n| --- | --: |\n| 1 | 2 |");
    });
});
