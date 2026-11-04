/**
 * Enter on an empty item in the middle of a list (Andrew, 2026-11-04): the list ends there
 * (EB-LIST-07), and Preview must show it as it will be, two lists with the caret between them,
 * not one spaced-out loose list with the caret below it.
 */
import { afterAll, beforeAll, expect, it } from "vitest";
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

for (const [name, source, list] of [
    ["bullet", "- a\n- b\n\n\n- d\n- e", "ul"],
    ["numbered", "1. a\n2. b\n\n\n3. d\n4. e", "ol"],
] as const) {
    it(`shows a ${name} list split around the new empty line, caret on it`, async () => {
        const page = await openPreview(h);
        // What Kotlin sends after Enter on the empty third item: the list ends, the caret on line 3.
        await loadMarkdown(page, source);
        const at = source.indexOf("\n\n\n") + 2;
        await send(page, { type: "select", rev: 1, from: at, to: at });
        await page.waitForSelector(".placeholder.editing");
        const shape = await page.evaluate((tag) => {
            const sec = document.querySelector("section.sec");
            const kids = [...(sec?.children ?? [])].map((el) =>
                el.matches(tag)
                    ? `${tag}:${el.querySelectorAll("li").length}`
                    : el.matches(".placeholder")
                      ? "p"
                      : el.tagName,
            );
            const sel = window.getSelection();
            const caretIn = sel?.anchorNode?.parentElement?.closest(".placeholder") !== null;
            const gaps = [...document.querySelectorAll("li > p")].map((p) => getComputedStyle(p).marginBottom);
            const second = sec?.querySelectorAll(tag)[1] as HTMLOListElement | undefined;
            return { kids, caretIn, gaps, start: second?.start ?? null };
        }, list);
        expect(shape.kids).toEqual([`${list}:2`, "p", `${list}:2`]);
        expect(shape.caretIn).toBe(true);
        for (const g of shape.gaps) expect(g).toBe("0px");
        if (list === "ol") expect(shape.start, "the second list goes on counting").toBe(3);

        await clearReceived(page);
        await page.keyboard.type("x");
        const edit = (await waitForMessage(page, (m) => m.type === "sourceEdit")) as Extract<
            PageMessage,
            { type: "sourceEdit" }
        >;
        expect(edit.edits).toEqual([{ from: at, to: at, insert: "x" }]);
    });
}

it("keeps the caret inside a quote when a quoted list is split", async () => {
    const page = await openPreview(h);
    const source = "> - a\n> - b\n>\n> \n> - d\n\nAfter.";
    await loadMarkdown(page, source);
    const at = source.indexOf(">\n> \n") + 4;
    await send(page, { type: "select", rev: 1, from: at, to: at });
    await page.waitForSelector(".placeholder.editing");
    const kids = await page.evaluate(() =>
        [...(document.querySelector("blockquote")?.children ?? [])].map((el) =>
            el.matches("ul") ? `ul:${el.querySelectorAll("li").length}` : el.matches(".placeholder") ? "p" : el.tagName,
        ),
    );
    expect(kids).toEqual(["ul:2", "p", "ul:1"]);
});

/** [source] with the page's edits applied, as Kotlin would. */
async function sourceNow(page: import("playwright").Page, source: string): Promise<string> {
    let text = source;
    for (const m of await received(page)) {
        if (m.type !== "sourceEdit") continue;
        for (const e of [...m.edits].sort((a, b) => b.from - a.from)) {
            text = text.slice(0, e.from) + e.insert + text.slice(e.to);
        }
    }
    return text;
}

for (const [name, source, want] of [
    ["bullet", "- a\n- b\n\n\n- d\n- e", "- a\n- b\n- d\n- e"],
    ["numbered", "1. a\n2. b\n\n\n\n3. d", "1. a\n2. b\n3. d"],
] as const) {
    it(`Backspace on the empty line rejoins a split ${name} list, the caret at the item's end`, async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, source);
        const at = source.indexOf("\n\n\n") + 2;
        await send(page, { type: "select", rev: 1, from: at, to: at });
        await page.waitForSelector(".placeholder.editing");
        await clearReceived(page);
        await page.keyboard.press("Backspace");
        const end = Date.now() + 4000;
        while ((await sourceNow(page, source)) !== want && Date.now() < end) await page.waitForTimeout(100);
        expect(await sourceNow(page, source)).toBe(want);
        const sel = (await waitForMessage(page, (m) => m.type === "selection")) as Extract<
            PageMessage,
            { type: "selection" }
        >;
        expect(sel.from).toBe(want.indexOf(" b") + 2);
    });
}

it("Enter on an empty line adds a line each time, and the caret is drawn lower", async () => {
    const page = await openPreview(h);
    const source = "Para\n\n";
    await loadMarkdown(page, source);
    await send(page, { type: "select", rev: 1, from: source.length, to: source.length });
    await page.waitForSelector(".placeholder.editing");
    const caretY = (): Promise<number> =>
        page.evaluate(() => document.querySelector(".placeholder.editing")?.getBoundingClientRect().top ?? 0);
    const first = await caretY();
    await clearReceived(page);
    await page.keyboard.press("Enter");
    await waitForMessage(page, (m) => m.type === "sourceEdit");
    await page.waitForSelector(".placeholder.editing");
    await page.waitForTimeout(200);
    expect(await sourceNow(page, source)).toBe("Para\n\n\n");
    expect(await caretY()).toBeGreaterThan(first + 10);
    await page.keyboard.press("Backspace");
    await page.waitForTimeout(300);
    expect(await sourceNow(page, source)).toBe("Para\n\n");
});
