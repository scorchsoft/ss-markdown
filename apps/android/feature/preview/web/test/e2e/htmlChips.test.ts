/**
 * Raw HTML in Preview, on Andrew's HTML render test file (2026-11-05): a comment's
 * "Edit in Code" chip hung, clipped, out of its empty block, and a lone <hr>'s chip
 * sat over the paragraph below. Every chip now lies inside its own block, and a
 * comment draws nothing at all.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { loadMarkdown, openPreview, startHarness, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const FILE = readFileSync(resolve(__dirname, "fixtures/html-render.md"), "utf8");

describe("Edit in Code chips on raw HTML", () => {
    it("lie inside their own block, and a comment has none", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, FILE);
        // Mount every section: scroll through the page.
        await page.evaluate(async () => {
            for (let y = 0; y < document.documentElement.scrollHeight; y += 400) {
                window.scrollTo(0, y);
                await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
            }
        });
        const report = await page.evaluate(() => {
            const out: string[] = [];
            for (const chip of document.querySelectorAll<HTMLElement>(".ro-block > .edit-in-code")) {
                const parent = chip.parentElement;
                if (!parent) continue;
                const block = parent.getBoundingClientRect(),
                    c = chip.getBoundingClientRect();
                if (c.top < block.top - 0.5 || c.bottom > block.bottom + 0.5)
                    out.push(
                        `${parent.className} line ${chip.dataset.editRl}: chip ${c.top}-${c.bottom}, block ${block.top}-${block.bottom}`,
                    );
            }
            const comments = [...document.querySelectorAll<HTMLElement>(".ro-comment")];
            return {
                out,
                comments: comments.map((e) => ({
                    h: e.getBoundingClientRect().height,
                    chips: e.querySelectorAll(".edit-in-code").length,
                })),
            };
        });
        expect(report.out).toEqual([]);
        expect(report.comments).toEqual([{ h: 0, chips: 0 }]);
        expect(await page.evaluate(() => document.body.innerText.includes("COMMENT TEST"))).toBe(false);
        await page.close();
    });
});
