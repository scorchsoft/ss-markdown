/**
 * The block-editing matrix in Preview: every row of core:markdown's
 * BlockEditingMatrix marked for Preview (test/vectors/block-editing.json),
 * driven through real key presses on the built page.
 *
 * The harness plays Kotlin. When the page asks for Code's own command
 * (`formatRequest`: enter, backspace, indent…), it checks the request names
 * the row's action at the row's caret, and answers with the row's expected
 * text as an `edit` and its caret as a `select`; Kotlin's half (that the
 * command gives that text) is BlockEditingMatrixTest's job. What this proves
 * is the page's half: the key reaches the right rule with the right offsets,
 * page-side rules give the right text, the result renders as a fresh load
 * of it would, and the caret lands where the row says (typing goes there).
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { HostMessage } from "../../src/protocol";
import { applyEdit } from "../../src/core/minimalEdit";
import {
    clearReceived,
    loadMarkdown,
    openPreview,
    received,
    send,
    startHarness,
    takeReceived,
    waitForMessage,
    type Harness,
} from "./harness";

interface Row {
    readonly id: string;
    readonly action: string;
    readonly before: string;
    readonly after: string;
}

const ROWS = (JSON.parse(readFileSync(resolve(__dirname, "../vectors/block-editing.json"), "utf8")) as { rows: Row[] })
    .rows;

const CARET = "‸";

/** A marked string: the text, and its selection (`‸` a caret, `«…»` a selection). */
function marked(s: string): { text: string; from: number; to: number } {
    const caret = s.indexOf(CARET);
    if (caret >= 0) return { text: s.replace(CARET, ""), from: caret, to: caret };
    const from = s.indexOf("«");
    const to = s.indexOf("»") - 1;
    return { text: s.replace("«", "").replace("»", ""), from, to };
}

/** The change from [a] to [b] as one edit, common start and end trimmed. */
function diff(a: string, b: string): { from: number; to: number; insert: string } {
    let head = 0;
    while (head < a.length && head < b.length && a[head] === b[head]) head++;
    let tail = 0;
    while (tail < a.length - head && tail < b.length - head && a[a.length - 1 - tail] === b[b.length - 1 - tail])
        tail++;
    return { from: head, to: a.length - tail, insert: b.slice(head, b.length - tail) };
}

/** The same change as whole lines, as Kotlin's `edit` carries it. */
function lineChange(a: string, b: string): { fromLine: number; toLine: number; lines: string[] } {
    const x = a.split("\n");
    const y = b.split("\n");
    let head = 0;
    while (head < x.length && head < y.length && x[head] === y[head]) head++;
    let tail = 0;
    while (tail < x.length - head && tail < y.length - head && x[x.length - 1 - tail] === y[y.length - 1 - tail])
        tail++;
    return { fromLine: head, toLine: x.length - tail, lines: y.slice(head, y.length - tail) };
}

const KEYS: Readonly<Record<string, string>> = {
    enter: "Enter",
    backspace: "Backspace",
    indent: "Tab",
    outdent: "Shift+Tab",
};

let h: Harness;
let page: Page;
let fresh: Page | undefined;
let rev = 0;
beforeAll(async () => {
    h = await startHarness();
}, 120_000);
afterAll(async () => h.close());
// Pages per row: one page loaded a hundred times over crashed Chromium.
beforeEach(async () => {
    page = await openPreview(h);
    // BE_THROTTLE=4 runs the page on a CPU four times slower, as a busy CI runner or a phone is.
    const rate = Number(process.env.BE_THROTTLE ?? 0);
    if (rate > 1) await (await page.context().newCDPSession(page)).send("Emulation.setCPUThrottlingRate", { rate });
}, 60_000);
afterEach(async () => {
    await page.close();
    await fresh?.close();
    fresh = undefined;
});

/** Polls [read] until it gives [want] or [ms] pass, and returns the last value (a busy machine renders late). */
async function settle<T>(read: () => Promise<T>, want: T, ms = 4000): Promise<T> {
    const end = Date.now() + ms;
    let value = await read();
    while (value !== want && Date.now() < end) {
        await new Promise((r) => setTimeout(r, 100));
        value = await read();
    }
    return value;
}

/** The page's text as a reader sees it, placeholder spaces and spacing aside. */
async function screenText(p: Page): Promise<string> {
    const text = await p.evaluate(() =>
        [...document.querySelectorAll("section.sec")].map((s) => (s as HTMLElement).innerText).join("\n"),
    );
    return text.split("\u200b").join("").replace(/\s+/g, " ").trim();
}

/** Runs [row] and returns the source text the page and the harness ended with. */
async function run(row: Row): Promise<{ source: string; problems: string[] }> {
    const before = marked(row.before);
    const after = marked(row.after);
    const problems: string[] = [];
    rev += 10;
    await loadMarkdown(page, before.text, rev);
    // The page being edited has focus: a page opened after it (the fresh one, below) is only opened once
    // this row's keys are done, so no key or focus ever goes to the wrong page.
    await page.bringToFront();
    await page.evaluate(() => ((window as unknown as { __autoAck: boolean }).__autoAck = false));
    await send(page, { type: "select", rev, from: before.from, to: before.to });
    await page.waitForFunction(() => document.querySelector(".editing") !== null, undefined, { timeout: 10_000 });
    // The page reports the caret once it is placed; keys before that could land before the caret is restored.
    await waitForMessage(page, (m) => m.type === "selection", 10_000).catch(() => undefined);
    await clearReceived(page);
    const key = KEYS[row.action];
    if (row.action === "type}") await page.keyboard.type("}");
    else if (key) await page.keyboard.press(key);
    else await send(page, { type: "format", action: row.action } as HostMessage);
    // Play Kotlin: ack page edits, answer a format request with the row's result.
    let source = before.text;
    // Until the page has answered (a source edit or a request), then a quiet moment for anything after it.
    const quiet = before.text === after.text ? 1500 : 10_000;
    let deadline = Date.now() + quiet;
    let answered = false;
    while (Date.now() < deadline) {
        // Read and clear in one step: a message arriving between the two was lost (the page "sent nothing").
        const messages = await takeReceived(page);
        if (process.env.BE_DEBUG) for (const m of messages) console.log("MSG", JSON.stringify(m));
        for (const m of messages) {
            if ((m.type === "sourceEdit" || m.type === "formatRequest") && !answered) {
                answered = true;
                deadline = Date.now() + 600;
            }
            if (m.type === "sourceEdit") {
                for (const e of [...m.edits].sort((a, b) => b.from - a.from)) source = applyEdit(source, e);
                rev++;
                await send(page, { type: "ack", rev });
            } else if (m.type === "formatRequest") {
                if (m.action !== row.action) problems.push(`asked for ${m.action}, not ${row.action}`);
                if (m.from !== before.from || m.to !== before.to) {
                    problems.push(`asked at ${m.from}..${m.to}, not ${before.from}..${before.to}`);
                }
                const op = diff(source, after.text);
                rev++;
                await send(page, { type: "edit", rev, changes: [lineChange(source, after.text)], ops: [op] });
                source = after.text;
                await send(page, { type: "select", rev, from: after.from, to: after.to });
            }
        }
        await page.waitForTimeout(50);
    }
    if (!answered && before.text !== after.text) {
        // Say where the caret was, so a dropped key or strip action is diagnosable from CI's log.
        const where = await page.evaluate(() => {
            const sel = window.getSelection();
            const node = sel?.anchorNode;
            const el = node instanceof Element ? node : node?.parentElement;
            return `active=${document.activeElement?.tagName}.${document.activeElement?.className} hasFocus=${document.hasFocus()} editing=${document.querySelectorAll(".editing").length} anchor=${el?.outerHTML.slice(0, 80) ?? "none"}`;
        });
        problems.push(`the page sent nothing (${where})`);
    }
    return { source, problems };
}

describe("block-editing matrix in Preview", () => {
    for (const row of ROWS) {
        it(row.id, async () => {
            const after = marked(row.after);
            const { source, problems } = await run(row);
            expect(problems).toEqual([]);
            expect(source).toBe(after.text);
            // The screen is what a fresh load of the result shows.
            rev++;
            const reference = await openPreview(h);
            fresh = reference;
            await loadMarkdown(reference, after.text, rev);
            await page.bringToFront();
            // A fresh page can show nothing for a moment after its first render on a busy machine.
            const want =
                (await screenText(reference)) || (await settle(() => screenText(reference), "(nothing yet)", 1500));
            expect(await settle(() => screenText(page), want)).toBe(want);
            // The caret is where the row puts it: typing lands there.
            if (after.from === after.to) {
                await page.keyboard.type("Z");
                const typed = async (): Promise<string> => {
                    let text = source;
                    for (const m of await received(page)) {
                        if (m.type !== "sourceEdit") continue;
                        for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
                    }
                    return text;
                };
                const want = after.text.slice(0, after.from) + "Z" + after.text.slice(after.from);
                expect(await settle(typed, want)).toBe(want);
            }
        });
    }
});
