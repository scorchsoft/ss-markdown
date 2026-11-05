/**
 * Bold in Preview flashed (Andrew, 2026-11-04: "a small flash where some text is over the text
 * that I've just edited, and then it's back in the right position"). The edit gave the paragraph's
 * section a new view, an empty spacer until the worker's render came back, so for a frame or more
 * the paragraph vanished and the text below jumped. Now the old view keeps its HTML on screen until
 * the new render swaps in place: no state between shows the paragraph missing or anything moved.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { PageMessage } from "../../src/protocol";
import { applyEdit } from "../../src/core/minimalEdit";
import { clearReceived, loadMarkdown, openPreview, send, startHarness, waitForMessage, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

interface Snapshot {
    edited: string;
    below: number;
}

describe("an edit from the strip re-renders in place", () => {
    for (const [action, wrap] of [
        ["bold", "**"],
        ["italic", "_"],
        ["strike", "~~"],
    ] as const) {
        it(`${action}: the paragraph never disappears and nothing below moves`, async () => {
            const page = await openPreview(h);
            const source = "# Title\n\nAlpha beta gamma delta epsilon.\n\nSecond paragraph here.\n\nThird one.";
            await loadMarkdown(page, source);
            const box = await page.getByText("Alpha beta", { exact: false }).first().boundingBox();
            if (!box) throw new Error("no paragraph");
            await page.mouse.click(box.x + 60, box.y + box.height / 2);
            await waitForMessage(page, (m) => m.type === "editingChanged");
            await page.mouse.dblclick(box.x + 60, box.y + box.height / 2);
            await page.waitForTimeout(200);
            await clearReceived(page);
            // Every DOM change and every frame from here on: the edited line's text and where the next
            // paragraph is.
            await page.evaluate(() => {
                const w = window as unknown as { __snaps: Snapshot[] };
                w.__snaps = [];
                const snap = (): void => {
                    const edited = (document.querySelector('.doc p[data-line="2"]')?.textContent ?? "").trim();
                    const next = document.querySelector('.doc [data-line="4"]');
                    w.__snaps.push({ edited, below: Math.round(next?.getBoundingClientRect().top ?? -1) });
                };
                new MutationObserver(snap).observe(document.body, {
                    subtree: true,
                    childList: true,
                    characterData: true,
                });
                let n = 0;
                const tick = (): void => {
                    snap();
                    if (++n < 40) requestAnimationFrame(tick);
                };
                requestAnimationFrame(tick);
            });
            const before = await page.evaluate(() =>
                Math.round(document.querySelector('.doc [data-line="4"]')?.getBoundingClientRect().top ?? -1),
            );
            await send(page, { type: "format", action });
            const req = (await waitForMessage(page, (m) => m.type === "formatRequest")) as Extract<
                PageMessage,
                { type: "formatRequest" }
            >;
            const edit = { from: req.from, to: req.to, insert: `${wrap}${source.slice(req.from, req.to)}${wrap}` };
            const after = applyEdit(source, edit);
            const rev = await page.evaluate(() => ++(window as unknown as { __rev: number }).__rev);
            await send(page, {
                type: "edit",
                rev,
                changes: [{ fromLine: 2, toLine: 3, lines: [after.split("\n")[2] ?? ""] }],
                ops: [edit],
            });
            await send(page, { type: "select", rev, from: req.from + wrap.length, to: req.to + wrap.length });
            await page.waitForTimeout(700);
            const snaps = await page.evaluate(() => (window as unknown as { __snaps: Snapshot[] }).__snaps);
            expect(snaps.length).toBeGreaterThan(10);
            for (const s of snaps) {
                expect(s.edited).toBe("Alpha beta gamma delta epsilon.");
                expect(s.below).toBe(before);
            }
            expect(await page.locator(`.doc p[data-line="2"] :is(strong, em, s, del)`).count()).toBe(1);
        });
    }
});
