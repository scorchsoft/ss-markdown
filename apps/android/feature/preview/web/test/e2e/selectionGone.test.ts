/**
 * Kotlin hides Preview's fast-scroll thumb while text is selected, so it never sits under a
 * selection handle. A selection that went away without collapsing inside the block being edited
 * (cleared, or moved out of it) was never reported, so the thumb stayed hidden until editing ended
 * (Andrew, 2026-11-04: the thumb was missing until a switch to Code and back). The page now reports
 * such a selection as a caret.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { PageMessage } from "../../src/protocol";
import { clearReceived, loadMarkdown, openPreview, startHarness, waitForMessage, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

type Selection = Extract<PageMessage, { type: "selection" }>;

describe("a selection that goes away is reported", () => {
    it("reports a caret when the selection is cleared", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "# Title\n\nAlpha beta gamma delta.\n\nSecond paragraph.");
        const box = await page.getByText("Alpha beta", { exact: false }).first().boundingBox();
        if (!box) throw new Error("no paragraph");
        await page.mouse.click(box.x + 60, box.y + box.height / 2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await clearReceived(page);
        await page.mouse.dblclick(box.x + 60, box.y + box.height / 2);
        const selected = (await waitForMessage(page, (m) => m.type === "selection" && m.from !== m.to)) as Selection;
        await clearReceived(page);
        await page.evaluate(() => window.getSelection()?.removeAllRanges());
        const gone = (await waitForMessage(page, (m) => m.type === "selection")) as Selection;
        expect(gone.from).toBe(gone.to);
        expect(gone.from).toBe(selected.to);
    });
});
