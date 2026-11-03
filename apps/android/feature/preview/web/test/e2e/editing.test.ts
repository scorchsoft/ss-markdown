import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage } from "../../src/protocol";
import { applyEdit } from "../../src/core/minimalEdit";
import { transform } from "../../src/core/ot";
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

/**
 * Plays Kotlin's answer to the page's `enter` request: Code's own Enter, which
 * the harness cannot run, is the [insert] at the request's caret (core:markdown's
 * BlockEditingMatrixTest proves the command gives it); then the caret goes after it.
 */
async function answerEnter(page: Page, source: string, insert: string): Promise<string> {
    const request = (await waitForMessage(page, (m) => m.type === "formatRequest")) as Extract<
        PageMessage,
        { type: "formatRequest" }
    >;
    expect(request.action).toBe("enter");
    const before = await applySent(page, source);
    const edit = { from: request.from, to: request.to, insert };
    const after = applyEdit(before, edit);
    const rev = await page.evaluate(() => ++(window as unknown as { __rev: number }).__rev);
    // The caret's line becomes the lines the insert makes of it.
    const line = before.slice(0, request.from).split("\n").length - 1;
    const lines = after.split("\n").slice(line, line + insert.split("\n").length);
    await send(page, { type: "edit", rev, changes: [{ fromLine: line, toLine: line + 1, lines }], ops: [edit] });
    await send(page, { type: "select", rev, from: request.from + insert.length, to: request.from + insert.length });
    return after;
}

/** [kotlin] with the page's typing since the last clear applied, as Kotlin would. */
async function typedOver(page: Page, kotlin: string): Promise<string> {
    let text = kotlin;
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
        // Code's own Enter, from Kotlin: Preview and Code continue a list identically.
        const kotlin = await answerEnter(page, source, "\n+ ");
        await page.keyboard.type("three");
        expect(kotlin).toBe("+ one\n+ two\n+ \n");
        // Typed while the new item renders: kept, then written once the pause after the last key passes.
        const want = "+ one\n+ two\n+ three\n";
        const end = Date.now() + 4000;
        while ((await typedOver(page, kotlin)) !== want && Date.now() < end) await page.waitForTimeout(100);
        expect(await typedOver(page, kotlin)).toBe(want);
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

    it("leaves a task as it is in a file that refuses edits", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "- [ ] open\n- [x] done");
        const settings = { tapToEdit: true, pageMode: false, zoom: 1, lean: false, readOnly: true };
        await send(page, { type: "settings", settings });
        await clearReceived(page);
        const box = page.locator("input.task-list-item-checkbox").first();
        await box.click();
        await page.waitForTimeout(200);
        expect((await received(page)).some((x) => x.type === "taskToggle")).toBe(false);
        expect(await box.isChecked()).toBe(false);
    });

    it("toggles a task from a tap 15 px off the checkbox: a 48 px hit area (UX-12)", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "- [ ] open the box\n- [x] done");
        await clearReceived(page);
        const box = await page.locator("input.task-list-item-checkbox").first().boundingBox();
        if (!box) throw new Error("no checkbox");
        const cx = box.x + box.width / 2;
        const cy = box.y + box.height / 2;
        await page.mouse.click(cx + 15, cy + 6);
        const m = await waitForMessage(page, (x) => x.type === "taskToggle");
        expect(m).toMatchObject({ offset: 3, checked: true });
        expect((await received(page)).some((x) => x.type === "editingChanged")).toBe(false);
        // Far from the box, the text is still text: a tap starts editing (once the tick has re-rendered).
        await page.waitForFunction(() => document.querySelector("input.task-list-item-checkbox[checked]") !== null);
        // The re-render can nudge the scroll position, and a tap within the fling guard only stops a scroll.
        await page.waitForTimeout(600);
        await page.mouse.click(cx + 50, cy);
        await waitForMessage(page, (x) => x.type === "editingChanged");
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

    it("adds a row after typing widened a padded table, without writing over the re-pad", async () => {
        const page = await openPreview(h);
        const source = "| Name | Value |\n| ---- | ----- |\n| one  | 1     |\n| two  | 2     |\n\nAfter";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("td", { hasText: "one" }).first().click();
        await page.keyboard.press("End");
        await page.keyboard.type("-plus");
        await page.waitForTimeout(300);
        await send(page, { type: "tableOp", op: { kind: "addRow" } });
        await page.waitForTimeout(400);
        expect(await applySent(page, source)).toBe(
            "| Name     | Value |\n| -------- | ----- |\n| one-plus | 1     |\n|          |       |\n| two      | 2     |\n\nAfter",
        );
    });

    it("pastes into a cell of a padded table that typing widened, then re-pads on leaving", async () => {
        const page = await openPreview(h);
        const source = "| Name | Value |\n| ---- | ----- |\n| one  | 1     |\n| two  | 2     |\n\nAfter";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("td", { hasText: "one" }).first().click();
        await page.keyboard.press("End");
        await page.keyboard.type("-plus");
        await page.waitForTimeout(300);
        await page.evaluate(() => {
            const data = new DataTransfer();
            data.setData("text/plain", "Z");
            const init = { inputType: "insertFromPaste", dataTransfer: data, bubbles: true, cancelable: true };
            document.querySelector(".editing")?.dispatchEvent(new InputEvent("beforeinput", init));
        });
        await page.waitForTimeout(400);
        await send(page, { type: "endEditing" });
        await page.waitForTimeout(300);
        expect(await applySent(page, source)).toBe(
            "| Name      | Value |\n| --------- | ----- |\n| one-plusZ | 1     |\n| two       | 2     |\n\nAfter",
        );
    });

    it("takes a strip command in a widened padded table without a re-pad racing Kotlin's edit", async () => {
        const page = await openPreview(h);
        const source = "| Name | Value |\n| ---- | ----- |\n| one  | 1     |\n| two  | 2     |\n\nAfter";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("td", { hasText: "one" }).first().click();
        await page.keyboard.press("End");
        await page.keyboard.type("-plus");
        await page.waitForTimeout(300);
        let kotlin = await applySent(page, source);
        await page.evaluate(() => ((window as unknown as { __autoAck: boolean }).__autoAck = false));
        await clearReceived(page);
        await send(page, { type: "format", action: "bold" });
        const request = (await waitForMessage(page, (m) => m.type === "formatRequest")) as Extract<
            PageMessage,
            { type: "formatRequest" }
        >;
        // Kotlin applies Bold at the caret as revision 3 (the typing was revision 2).
        const bold = { from: request.from, to: request.to, insert: "****" };
        kotlin = applyEdit(kotlin, bold);
        await send(page, {
            type: "edit",
            rev: 3,
            changes: [{ fromLine: 2, toLine: 3, lines: [kotlin.split("\n")[2] ?? ""] }],
            ops: [bold],
        });
        await page.evaluate(() => {
            const w = window as unknown as { __autoAck: boolean; __rev: number };
            w.__rev = 3;
            w.__autoAck = true;
        });
        await send(page, { type: "select", rev: 3, from: request.from + 2, to: request.from + 2 });
        await page.waitForTimeout(300);
        await send(page, { type: "endEditing" });
        await page.waitForTimeout(300);
        for (const m of await received(page)) {
            if (m.type !== "sourceEdit") continue;
            // An edit made before the page saw revision 3 is rebased over it, as PreviewEditSync does.
            const edits = m.baseRev < 3 ? transform([bold], m.edits).page : m.edits;
            for (const e of [...edits].sort((a, b) => b.from - a.from)) kotlin = applyEdit(kotlin, e);
        }
        expect(kotlin).toBe(
            "| Name         | Value |\n| ------------ | ----- |\n| one-plus**** | 1     |\n| two          | 2     |\n\nAfter",
        );
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

    it("adds a row, taps into it and deletes it, leaving the table as it was (0.2.0 phone report)", async () => {
        const page = await openPreview(h);
        const source = "Intro\n\n| Lens | Data |\n| --- | --- |\n| one | 1 |\n| two | 2 |\n\nAfter";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("th", { hasText: "Lens" }).click();
        await send(page, { type: "tableOp", op: { kind: "addRow" } });
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        await page.waitForTimeout(300);
        expect(await page.locator("tbody tr").count()).toBe(3);
        await send(page, { type: "endEditing" });
        await page.locator("tbody tr").first().locator("td").first().click();
        await page.waitForTimeout(200);
        await send(page, { type: "tableOp", op: { kind: "deleteRow" } });
        await page.waitForTimeout(400);
        expect(await applySent(page, source)).toBe(source);
        expect(await page.locator("tbody tr").count()).toBe(2);
        expect(await page.locator("p", { hasText: "|" }).count()).toBe(0);
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

describe("edits from both sides at once", () => {
    it("rebases a Kotlin edit over typing in flight, with no reload and no lost keystroke", async () => {
        const page = await openPreview(h);
        const source = "Hello world\n\nSecond para.";
        await loadMarkdown(page, source);
        await page.evaluate(() => ((window as unknown as { __autoAck: boolean }).__autoAck = false));
        await clearReceived(page);
        await tapText(page, "Hello world", 0.99);
        await page.keyboard.press("End");
        await page.keyboard.type("XYZ");
        const typed = (await waitForMessage(page, (m) => m.type === "sourceEdit")) as SourceEditMessage;
        expect(typed.baseRev).toBe(1);

        // Kotlin, meanwhile, made its own change (rev 2) before it saw the page's.
        let kotlin = applyEdit(source, { from: 0, to: 0, insert: "# " });
        await send(page, {
            type: "edit",
            rev: 2,
            changes: [{ fromLine: 0, toLine: 1, lines: ["# Hello world"] }],
            ops: [{ from: 0, to: 0, insert: "# " }],
        });
        // Kotlin rebases the page's edit over its own and acknowledges it as rev 3.
        const rebased = typed.edits.map((e) => ({ ...e, from: e.from + 2, to: e.to + 2 }));
        for (const e of [...rebased].reverse()) kotlin = applyEdit(kotlin, e);
        await send(page, { type: "ack", rev: 3 });
        await page.waitForSelector("h1 .editing, h1.editing");

        await clearReceived(page);
        await page.keyboard.type("Q");
        const more = (await waitForMessage(page, (m) => m.type === "sourceEdit")) as SourceEdit;
        expect(more.baseRev).toBe(3);
        for (const e of [...more.edits].reverse()) kotlin = applyEdit(kotlin, e);
        expect(kotlin).toBe("# Hello worldXYZQ\n\nSecond para.");
        expect((await received(page)).some((m) => m.type === "resync")).toBe(false);
        expect(await page.locator("h1").textContent()).toBe("Hello worldXYZQ");
    });

    it("asks for a resync, rather than guessing, when a revision is missing", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "One.");
        await clearReceived(page);
        await send(page, { type: "edit", rev: 5, changes: [{ fromLine: 0, toLine: 1, lines: ["Two."] }] });
        expect(await waitForMessage(page, (m) => m.type === "resync")).toMatchObject({ type: "resync" });
    });
});

type SourceEdit = SourceEditMessage;

describe("formatting from the strip", () => {
    it("sends the selection's source range and restores the selection after Kotlin's edit", async () => {
        const page = await openPreview(h);
        const source = "Make this bold please.";
        await loadMarkdown(page, source);
        await tapText(page, "Make this", 0.3);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.evaluate(() => {
            const node = document.querySelector(".editing [data-s]")?.firstChild as Text;
            window.getSelection()?.setBaseAndExtent(node, 5, node, 9);
        });
        await clearReceived(page);
        await send(page, { type: "format", action: "bold" });
        const request = await waitForMessage(page, (m) => m.type === "formatRequest");
        expect(request).toEqual({ type: "formatRequest", action: "bold", from: 5, to: 9, baseRev: 1, inTable: false });

        // Kotlin runs EditCommand.ToggleInline(Bold) and answers with the minimal edits and the selection.
        await send(page, {
            type: "edit",
            rev: 2,
            changes: [{ fromLine: 0, toLine: 1, lines: ["Make **this** bold please."] }],
            ops: [
                { from: 5, to: 5, insert: "**" },
                { from: 9, to: 9, insert: "**" },
            ],
        });
        await send(page, { type: "select", rev: 2, from: 7, to: 11 });
        await page.waitForSelector("p strong");
        await page.waitForFunction(() => window.getSelection()?.toString() === "this");
        expect(await page.locator(".editing").count()).toBe(1);
    });

    it("keeps an editable element focused through Kotlin's edit, so the keyboard never drops (Bold, ¶)", async () => {
        // Andrew, 0.2.1: every strip command took contenteditable off the focused block while it
        // re-rendered; Android's WebView hid the keyboard and showed it again, and the ¶ menu went with it.
        const page = await openPreview(h);
        await loadMarkdown(page, "Make this a heading.\n\nNext.");
        await tapText(page, "Make this", 0.3);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.evaluate(() => {
            const w = window as unknown as { __lostFocus: string[] };
            w.__lostFocus = [];
            const check = (why: string): void => {
                const active = document.activeElement as HTMLElement | null;
                if (!active?.isContentEditable) w.__lostFocus.push(`${why}: ${active?.tagName ?? "none"}`);
            };
            new MutationObserver(() => check("mutation")).observe(document.body, {
                subtree: true,
                childList: true,
                attributes: true,
                attributeFilter: ["contenteditable"],
            });
            document.addEventListener("focusout", () => queueMicrotask(() => check("focusout")), true);
        });
        await clearReceived(page);
        await send(page, { type: "format", action: "heading1" });
        await waitForMessage(page, (m) => m.type === "formatRequest");
        await send(page, {
            type: "edit",
            rev: 2,
            changes: [{ fromLine: 0, toLine: 1, lines: ["# Make this a heading."] }],
            ops: [{ from: 0, to: 0, insert: "# " }],
        });
        await send(page, { type: "select", rev: 2, from: 7, to: 7 });
        await page.waitForSelector("h1.editing");
        await page.keyboard.type("X");
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        expect(await page.evaluate(() => (window as unknown as { __lostFocus: string[] }).__lostFocus)).toEqual([]);
        // Kotlin is never told editing stopped, so Split's strip does not flip to Code for a moment.
        expect((await received(page)).filter((m) => m.type === "editingChanged")).toEqual([]);
        expect(await page.locator("h1").textContent()).toBe("Make Xthis a heading.");
    });

    it("keeps what was typed while a slow re-render held the keyboard, and writes it at the caret", async () => {
        const page = await openPreview(h);
        const source = "- one\n\nNext.";
        await loadMarkdown(page, source);
        await tapText(page, "one", 0.9);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.keyboard.press("End");
        await clearReceived(page);
        // A worker busy with a long document: the edited block's new HTML takes 2.5 s.
        await page.evaluate(() => {
            const proto = Worker.prototype as unknown as { postMessage: (...a: unknown[]) => void };
            const post = proto.postMessage;
            proto.postMessage = function (this: Worker, ...args: unknown[]) {
                setTimeout(() => post.apply(this, args), 2500);
            };
        });
        await page.keyboard.press("Enter");
        await page.keyboard.type("two");
        const kotlin = await answerEnter(page, source, "\n- ");
        await page.waitForTimeout(4000);
        expect(await typedOver(page, kotlin)).toBe("- one\n- two\n\nNext.");
    });

    it("Enter continues a list, and Enter on the new empty item asks Kotlin to end the list", async () => {
        const page = await openPreview(h);
        const source = "- one";
        await loadMarkdown(page, source);
        await tapText(page, "one", 0.9);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.keyboard.press("End");
        await clearReceived(page);
        await page.keyboard.press("Enter");
        expect(await answerEnter(page, source, "\n- ")).toBe("- one\n- ");
        await page.waitForTimeout(300);
        await clearReceived(page);
        await page.keyboard.press("Enter");
        // The same command as Enter in Code (EditCommand.Newline), run by Kotlin on the empty item.
        expect(await waitForMessage(page, (m) => m.type === "formatRequest")).toMatchObject({
            action: "enter",
            from: 8,
            to: 8,
        });
        expect((await received(page)).filter((m) => m.type === "sourceEdit")).toEqual([]);
    });

    it("turns a keyboard shortcut into the same request", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "Some words.");
        await tapText(page, "Some words", 0.2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await clearReceived(page);
        await page.evaluate(() => {
            const block = document.querySelector(".editing");
            block?.dispatchEvent(
                new InputEvent("beforeinput", { inputType: "formatItalic", bubbles: true, cancelable: true }),
            );
        });
        expect(await waitForMessage(page, (m) => m.type === "formatRequest")).toMatchObject({ action: "italic" });
    });
});
