import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { PageMessage } from "../../src/protocol";
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

/** 3,000 sections: a heading every 10 paragraphs. Paragraph n sits on line 2n. */
function bigDoc(): string {
    const parts: string[] = [];
    for (let i = 0; i < 3000; i++)
        parts.push(i % 10 === 0 ? `## Chapter ${i / 10}` : `Paragraph ${i} with some text to wrap across the line.`);
    return parts.join("\n\n");
}

describe("large documents", () => {
    it("keeps only sections near the viewport in the DOM", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, bigDoc());
        await page.waitForFunction(() => document.querySelectorAll("section.sec").length === 3000);
        const mounted = await page.locator("section.sec.mounted").count();
        expect(mounted).toBeGreaterThan(5);
        expect(mounted).toBeLessThan(400);
    });

    it("scrolls to a line and reports the top line with its breadcrumb", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, bigDoc());
        await page.waitForFunction(() => document.querySelectorAll("section.sec").length === 3000);
        await clearReceived(page);
        await send(page, { type: "scrollToLine", line: 4000 });
        // The breadcrumb completes once the background render has reached every heading.
        const top = (await waitForMessage(
            page,
            (m) => m.type === "topLine" && m.crumbs.at(-1)?.text === "Chapter 200",
        )) as Extract<PageMessage, { type: "topLine" }>;
        expect(Math.abs(top.line - 4000)).toBeLessThanOrEqual(2);
        expect(top.crumbs.at(-1)).toMatchObject({ level: 2, text: "Chapter 200", line: 4000 });
        const first = await page.evaluate(() => {
            const el = document.elementFromPoint(40, 2);
            return el?.closest<HTMLElement>("[data-line]")?.dataset.line;
        });
        expect(Math.abs(Number(first) - 4000)).toBeLessThanOrEqual(2);
    });

    it("re-renders only the sections an edit touched", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "# A\n\nfirst\n\nsecond\n\nthird");
        await page.evaluate(() =>
            document.querySelectorAll("section.sec").forEach((s, i) => ((s as HTMLElement).dataset.mark = String(i))),
        );
        await clearReceived(page);
        await send(page, {
            type: "edit",
            rev: 2,
            changes: [{ fromLine: 4, toLine: 5, lines: ["second, edited", "", "inserted"] }],
        });
        await waitForMessage(page, (m) => m.type === "rendered");
        // Under load the edited sections can arrive in two batches: wait for the last.
        await page.waitForFunction(() => document.querySelectorAll("section.sec.mounted").length === 5);
        const sections = await page.$$eval("section.sec", (els) =>
            els.map((e) => ({
                mark: (e as HTMLElement).dataset.mark ?? null,
                line: (e as HTMLElement).dataset.line,
                text: e.textContent?.trim(),
            })),
        );
        expect(sections.map((s) => s.text)).toEqual(["A", "first", "second, edited", "inserted", "third"]);
        expect(sections[0]?.mark).toBe("0");
        expect(sections[1]?.mark).toBe("1");
        expect(sections[4]).toMatchObject({ mark: "3", line: "8" });
        expect(await page.locator("p", { hasText: "third" }).getAttribute("data-line")).toBe("8");
        const rendered = (await received(page)).find((m) => m.type === "rendered") as Extract<
            PageMessage,
            { type: "rendered" }
        >;
        expect(rendered.sections).toBeLessThanOrEqual(2);
    });

    it("compresses spacers so the page never exceeds the height cap", async () => {
        const page = await openPreview(h);
        const lines = Array.from({ length: 300_000 }, (_, i) => `Line ${i} of a very long document`);
        // A blank line every 10 lines: 30k sections, about 7.8M px at the estimate.
        for (let i = lines.length - 10; i > 0; i -= 10) lines.splice(i, 0, "");
        await loadMarkdown(page, lines.join("\n"));
        const height = await page.evaluate(() => document.documentElement.scrollHeight);
        expect(height).toBeLessThanOrEqual(7_100_000);
    });
});
