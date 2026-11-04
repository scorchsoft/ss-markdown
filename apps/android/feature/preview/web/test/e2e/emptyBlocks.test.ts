/**
 * Empty blocks in Preview (Andrew, 2026-11-04): an empty heading (`## `) or list item (`- `) had no
 * text, so it was invisible and only its edge took a tap. It has a line of its own, a tap anywhere
 * along it starts editing, and what is typed lands at its text's start in the source.
 */
import { afterAll, beforeAll, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage } from "../../src/protocol";
import { clearReceived, loadMarkdown, openPreview, startHarness, waitForMessage, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const SOURCE = "# Title\n\n## \n\nText\n\n- a\n- \n- c\n\n1. one\n2. \n\nEnd.";

async function typeInto(
    page: Page,
    selector: string,
    nth: number,
): Promise<Extract<PageMessage, { type: "sourceEdit" }>> {
    const box = await page.locator(selector).nth(nth).boundingBox();
    if (!box) throw new Error(`no ${selector}`);
    await clearReceived(page);
    // Far to the right of the line, nowhere near its start.
    await page.mouse.click(box.x + box.width - 20, box.y + box.height / 2);
    await waitForMessage(page, (m) => m.type === "editingChanged");
    await page.keyboard.type("Hi");
    return (await waitForMessage(page, (m) => m.type === "sourceEdit")) as Extract<PageMessage, { type: "sourceEdit" }>;
}

it("gives an empty heading a visible line", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, SOURCE);
    const height = await page.evaluate(() => document.querySelector("h2")?.getBoundingClientRect().height ?? 0);
    expect(height).toBeGreaterThan(16);
});

for (const [name, selector, nth, at] of [
    ["an empty heading", "h2", 0, SOURCE.indexOf("## ") + 3],
    ["an empty bullet", "ul > li", 1, SOURCE.indexOf("- \n") + 2],
    ["an empty numbered item", "ol > li", 1, SOURCE.indexOf("2. ") + 3],
] as const) {
    it(`takes a tap anywhere on ${name}, and typing lands at its text's start`, async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        const edit = await typeInto(page, selector, nth);
        expect(edit.edits.map((e) => e.from)).toEqual([at]);
        expect(edit.edits.map((e) => e.insert).join("")).toBe("Hi");
    });
}
