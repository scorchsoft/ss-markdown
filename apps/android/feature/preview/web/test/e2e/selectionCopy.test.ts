/**
 * The selection toolbar's "Copy Markdown" / "Copy formatted text" in Preview (Andrew, 2026-11-04):
 * the page answers `copySelection` with the selected source and its formatted copy, a list or a
 * table kept around the parts selected, and a selection inside one block as one paragraph.
 */
import { afterAll, beforeAll, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage } from "../../src/protocol";
import { clearReceived, loadMarkdown, openPreview, send, startHarness, waitForMessage, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

type Answer = Extract<PageMessage, { type: "selectionCopy" }>;

/** Selects from the start of the first element holding [fromText] to the end of the one holding [toText]. */
async function select(page: Page, fromText: string, toText: string, inner = false): Promise<void> {
    await page.evaluate(
        ([a, b, word]) => {
            const runs = [...document.querySelectorAll("[data-s]")];
            const first = runs.find((r) => r.textContent?.includes(a));
            const last = runs.find((r) => r.textContent?.includes(b));
            if (!first?.firstChild || !last?.firstChild) throw new Error("no runs");
            const range = document.createRange();
            const start = word ? (first.textContent ?? "").indexOf(a) : 0;
            const endText = last.textContent ?? "";
            const end = word ? endText.indexOf(b) + b.length : endText.length;
            range.setStart(first.firstChild, start);
            range.setEnd(last.firstChild, end);
            const sel = window.getSelection();
            sel?.removeAllRanges();
            sel?.addRange(range);
        },
        [fromText, toText, inner] as const,
    );
}

async function ask(page: Page, id: number): Promise<Answer> {
    await clearReceived(page);
    await send(page, { type: "copySelection", requestId: id, maxChars: 400_000 });
    return (await waitForMessage(page, (m) => m.type === "selectionCopy" && m.requestId === id)) as Answer;
}

it("copies selected list items as their Markdown and as a styled list", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, "Intro.\n\n- one **bold**\n- two\n- three\n\nAfter.");
    await select(page, "one", "two");
    const a = await ask(page, 1);
    expect(a.markdown).toContain("one **bold**");
    expect(a.markdown).toContain("- two");
    expect(a.html).toMatch(/^<ul[^>]*>.*<li[^>]*>one <(b|strong)[^>]*>bold<\/(b|strong)>.*<li[^>]*>two<\/li><\/ul>$/s);
    expect(a.html).toContain('style="');
    expect(a.text).not.toContain("**");
});

it("copies a few words inside one paragraph as one paragraph", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, "A sentence with some words in it.");
    await select(page, "some", "words", true);
    const a = await ask(page, 2);
    expect(a.markdown).toBe("some words");
    expect(a.html).toMatch(/^<p[^>]*>some words<\/p>$/);
    expect(a.text?.trim()).toBe("some words");
});

it("copies table rows inside their table", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n");
    await select(page, "1", "4");
    const a = await ask(page, 3);
    expect(a.html).toMatch(/^<table/);
    expect(a.html).toContain(">1<");
    expect(a.html).toContain(">4<");
});

it("answers null with nothing selected", async () => {
    const page = await openPreview(h);
    await loadMarkdown(page, "Text.");
    await page.evaluate(() => window.getSelection()?.removeAllRanges());
    const a = await ask(page, 4);
    expect(a.markdown).toBeNull();
    expect(a.html).toBeNull();
});
