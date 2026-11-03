/**
 * The never-lose-work promise for Preview editing, typed for real: at caret
 * positions all over the corpus, type, delete, and inject IME-style changes
 * that `beforeinput` cannot cancel. After each, what the page shows must be
 * exactly what a fresh render of the source Kotlin now holds shows. So every
 * change on screen reached the source, or was visibly undone; none is
 * silently dropped. A refused change must also have been reported.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import { applyEdit } from "../../src/core/minimalEdit";
import {
    clearReceived,
    corpus,
    loadMarkdown,
    openPreview,
    received,
    send,
    startHarness,
    type Harness,
} from "./harness";
import { Random } from "../unit/random";

let h: Harness;
let page: Page;
let reference: Page;
const VIEWPORT = { width: 412, height: 6000 };
const MARK = "Ж";

beforeAll(async () => {
    h = await startHarness();
    page = await openPreview(h, VIEWPORT);
    reference = await openPreview(h, VIEWPORT);
});
afterAll(async () => h.close());

type Action = "type" | "backspace" | "ime" | "imeStray" | "imeDelete" | "typePaste";
const ACTIONS: readonly Action[] = ["type", "backspace", "ime", "imeStray", "imeDelete", "typePaste"];

/** Kotlin's copy: every edit the page sent, applied in order. */
async function kotlinText(source: string): Promise<string> {
    let text = source;
    for (const m of await received(page)) {
        if (m.type === "sourceEdit") {
            for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
        } else if (m.type === "taskToggle") {
            text = applyEdit(text, { from: m.offset, to: m.offset + 1, insert: m.checked ? "x" : " " });
        }
    }
    return text;
}

/** What a reader sees, diagrams aside (they draw asynchronously). */
async function shown(p: Page): Promise<string> {
    return p.evaluate(() => {
        const copy = document.getElementById("doc")?.cloneNode(true) as HTMLElement | undefined;
        copy?.querySelectorAll(".diagram, .table-toolbar").forEach((e) => e.remove());
        return (copy?.textContent ?? "")
            .replace(/\u200b/g, "")
            .replace(/\s+/g, " ")
            .trim();
    });
}

/** Every caret position worth trying: inside runs and in unmapped text next to them. */
async function candidates(p: Page): Promise<{ x: number; y: number }[]> {
    return p.evaluate(() => {
        const out: { x: number; y: number }[] = [];
        const blocks = document.querySelectorAll<HTMLElement>("p, li, h1, h2, h3, td, th, dt, dd");
        for (const block of blocks) {
            if (block.closest(".ro-block")) continue;
            const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
            for (let n = walker.nextNode(); n; n = walker.nextNode()) {
                const text = n.nodeValue ?? "";
                if (text.trim() === "") continue;
                for (const at of [0, Math.floor(text.length / 2), text.length]) {
                    const r = document.createRange();
                    r.setStart(n, Math.max(0, at - (at === text.length ? 1 : 0)));
                    r.setEnd(n, Math.min(text.length, Math.max(1, at + (at === text.length ? 0 : 1))));
                    const box = r.getBoundingClientRect();
                    if (box.width === 0 && box.height === 0) continue;
                    out.push({ x: at === text.length ? box.right - 0.5 : box.left + 0.5, y: box.top + box.height / 2 });
                }
            }
        }
        return out;
    });
}

/** A change Chrome makes without a cancelable beforeinput, as an IME composition does. */
async function injectIme(p: Page, kind: "ime" | "imeStray" | "imeDelete"): Promise<void> {
    await p.evaluate(
        ({ kind, mark }) => {
            const block = document.querySelector<HTMLElement>(".editing");
            if (!block) return;
            const texts: Text[] = [];
            const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
            for (let n = walker.nextNode(); n; n = walker.nextNode()) {
                if (!n.parentElement?.closest("[contenteditable=false]")) texts.push(n as Text);
            }
            const sel = window.getSelection();
            let node: Text | undefined;
            let offset = 0;
            if (kind === "imeStray") {
                node = texts.find((t) => !t.parentElement?.closest("[data-s]")) ?? texts[0];
                offset = node ? Math.floor(node.length / 2) : 0;
            } else if (sel?.anchorNode instanceof Text && block.contains(sel.anchorNode)) {
                node = sel.anchorNode;
                offset = sel.anchorOffset;
            } else {
                node = texts[0];
            }
            if (!node) return;
            if (kind === "imeDelete") {
                if (node.length === 0) return;
                node.deleteData(Math.min(offset, node.length - 1), 1);
            } else {
                node.insertData(Math.min(offset, node.length), mark);
            }
            block.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertCompositionText" }));
        },
        { kind, mark: MARK },
    );
}

/** A paste, as Chrome reports it: a cancelable `beforeinput` the session turns into a source edit. */
async function paste(p: Page, text: string): Promise<void> {
    await p.evaluate((text) => {
        const data = new DataTransfer();
        data.setData("text/plain", text);
        const init = { inputType: "insertFromPaste", dataTransfer: data, bubbles: true, cancelable: true };
        document.querySelector(".editing")?.dispatchEvent(new InputEvent("beforeinput", init));
    }, text);
}

/** Rows of every table on screen: typing into a cell must never add, lose or break one. */
async function tableRows(p: Page): Promise<number> {
    return p.evaluate(() => document.querySelectorAll("#doc table tr").length);
}

async function act(action: Action): Promise<void> {
    if (action === "type") await page.keyboard.type(MARK);
    else if (action === "typePaste") {
        // Typing first, written back, then a paste: in a padded table the typing widens a cell.
        await page.keyboard.type(MARK);
        await page.waitForTimeout(200);
        await paste(page, MARK);
    } else if (action === "backspace") await page.keyboard.press("Backspace");
    else await injectIme(page, action);
}

async function runCase(source: string, at: { x: number; y: number }, action: Action, label: string): Promise<void> {
    await loadMarkdown(page, source);
    await clearReceived(page);
    const rows = await tableRows(page);
    await page.mouse.click(at.x, at.y);
    await page.waitForTimeout(30);
    await act(action);
    await page.waitForTimeout(250);
    await send(page, { type: "endEditing" });
    await page.waitForTimeout(250);
    const after = await kotlinText(source);
    await loadMarkdown(reference, after);
    const refused = (await received(page)).some((m) => m.type === "editRefused");
    const screen = await shown(page);
    expect(screen, `${label}: screen differs from the source Kotlin holds`).toBe(await shown(reference));
    if (action !== "backspace" && action !== "imeDelete" && screen.includes(MARK)) {
        expect(after.includes(MARK), `${label}: typed text on screen but not in the source`).toBe(true);
    }
    if (refused) expect(screen.includes(MARK), `${label}: refused but still shown`).toBe(false);
    if (action !== "backspace" && action !== "imeDelete") {
        expect(await tableRows(page), `${label}: a table gained or lost rows`).toBe(rows);
    }
}

describe("no keystroke is ever silently discarded", () => {
    for (const [file, cases] of [
        ["inline-edges.md", 24],
        ["basics.md", 8],
        ["extended.md", 8],
        ["unicode.md", 6],
        ["tables.md", 12],
    ] as const) {
        it(`${file}: every change on screen reaches the source or is undone`, async () => {
            const source = corpus(file);
            await loadMarkdown(page, source);
            const points = await candidates(page);
            expect(points.length).toBeGreaterThan(5);
            const rnd = new Random(file.length * 101 + cases);
            for (let i = 0; i < cases; i++) {
                const at = rnd.pick(points);
                const action = ACTIONS[i % ACTIONS.length] ?? "type";
                await runCase(source, at, action, `${file} #${i} ${action} @${Math.round(at.x)},${Math.round(at.y)}`);
            }
        }, 240_000);
    }

    it("maps IME text typed beside an emoji to the right side of its shortcode", async () => {
        const source = "Go :rocket: now";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("span", { hasText: "now" }).first().click();
        await page.evaluate((mark) => {
            const emoji = document.querySelector(".emoji");
            const text = document.createTextNode(mark);
            emoji?.after(text);
            text.parentElement?.dispatchEvent(new InputEvent("input", { bubbles: true }));
        }, MARK);
        await page.waitForTimeout(300);
        expect(await kotlinText(source)).toBe(`Go :rocket:${MARK} now`);
    });

    it("writes typing in a run once when the same pause also has text beside an emoji", async () => {
        const source = "Go :rocket: now";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("span", { hasText: "now" }).first().click();
        await page.keyboard.press("End");
        await page.keyboard.type("X");
        await page.evaluate((mark) => {
            const emoji = document.querySelector(".emoji");
            const text = document.createTextNode(mark);
            emoji?.after(text);
            text.parentElement?.dispatchEvent(new InputEvent("input", { bubbles: true }));
        }, MARK);
        await page.waitForTimeout(400);
        expect(await kotlinText(source)).toBe(`Go :rocket:${MARK} nowX`);
    });

    it("writes typing in a run once when the same pause also deleted unmapped text", async () => {
        const source = "Go :rocket: now";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("span", { hasText: "now" }).first().click();
        await page.keyboard.press("End");
        await page.keyboard.type("X");
        await page.evaluate(() => {
            const emoji = document.querySelector(".emoji");
            emoji?.parentElement?.removeChild(emoji);
            document.querySelector(".editing")?.dispatchEvent(new InputEvent("input", { bubbles: true }));
        });
        await page.waitForTimeout(400);
        expect(await kotlinText(source)).toBe(`${source}X`);
    });

    it("undoes and reports a deletion inside unmapped text", async () => {
        const source = "Go :rocket: now";
        await loadMarkdown(page, source);
        await clearReceived(page);
        await page.locator("span", { hasText: "now" }).first().click();
        await page.evaluate(() => {
            const emoji = document.querySelector(".emoji");
            emoji?.parentElement?.removeChild(emoji);
            document.querySelector(".editing")?.dispatchEvent(new InputEvent("input", { bubbles: true }));
        });
        await page.waitForTimeout(400);
        expect((await received(page)).some((m) => m.type === "editRefused")).toBe(true);
        expect(await kotlinText(source)).toBe(source);
        expect(await page.locator(".emoji").count()).toBe(1);
    });
});
