/**
 * Malformed Markdown (Andrew, 2026-11-04: "files that have invalid syntax"): unclosed and
 * unbalanced constructs, ragged tables, pathological runs and nesting, and characters that
 * are not text. Each document renders without a page error and quickly, keeps its last
 * paragraph visible, and that paragraph can still be tapped and typed into, with the edit
 * landing at the right source offset. `corpus/malformed.md` is also run by the unit suites
 * (exact source runs, edit safety, formatted copy).
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage } from "../../src/protocol";
import {
    clearReceived,
    corpus,
    loadMarkdown,
    openPreview,
    pageErrors,
    startHarness,
    waitForMessage,
    type Harness,
} from "./harness";

let h: Harness;
let page: Page;
beforeAll(async () => {
    h = await startHarness();
    page = await openPreview(h, { width: 412, height: 860 });
});
afterAll(async () => h.close());

const END = "Sentinel paragraph at the end.";
const repeat = (s: string, n: number): string => Array.from({ length: n }, () => s).join("");

/** Each case ends in [END] as a paragraph of its own, which must survive whatever came before. */
const CASES: readonly { name: string; text: string }[] = [
    { name: "unclosed emphasis", text: "**bold _it ~~strike `code" },
    { name: "ten thousand stars", text: repeat("*", 10_000) },
    { name: "alternating delimiters", text: repeat("*_", 5_000) },
    { name: "unclosed brackets", text: repeat("[", 5_000) + " text " + repeat("](", 2_000) },
    { name: "nested links", text: repeat("[a", 500) + repeat("](b)", 500) },
    {
        name: "backtick runs of every length",
        text: Array.from({ length: 200 }, (_, i) => "`".repeat(i + 1)).join(" x "),
    },
    { name: "unclosed angle brackets", text: repeat("<a ", 3_000) },
    { name: "entity soup", text: repeat("&#x; &#; &amp &; &#99999999; ", 500) },
    { name: "deep quotes", text: repeat(">", 1_000) + " deep" },
    { name: "deep lists", text: Array.from({ length: 300 }, (_, i) => `${"  ".repeat(i)}- level ${i}`).join("\n") },
    {
        name: "deep mixed nesting",
        text: Array.from({ length: 200 }, (_, i) => `${"> ".repeat(i % 20)}${"  ".repeat(i % 7)}- ${i}`).join("\n"),
    },
    { name: "ragged table", text: "| a | b |\n|---|\n| 1 | 2 | 3 | 4 | 5 |\n| x\n|\n||||\n| --- |" },
    {
        name: "huge table row",
        text: `| ${repeat("c | ", 2_000)}\n| ${repeat("--- | ", 2_000)}\n| ${repeat("1 | ", 2_000)}`,
    },
    { name: "a thousand-character word", text: repeat("abcdefghij", 1_000) },
    { name: "a 200 KB line", text: repeat("word ", 40_000) },
    { name: "lone CRs and NULs", text: "one\rtwo\r\rthree\u0000four\u0000" },
    { name: "a byte-order mark mid-file", text: "before﻿after\n\n﻿# heading" },
    { name: "lone surrogates", text: "high \uD83D alone, low \uDE00 alone, swapped \uDE00\uD83D" },
    { name: "control characters", text: Array.from({ length: 31 }, (_, i) => String.fromCharCode(i + 1)).join("x") },
    { name: "combining marks", text: "e" + repeat("́", 2_000) + " zalgo" },
    { name: "right-to-left overrides", text: "abc‮evil‬ def ⁦iso⁩" },
    { name: "tabs everywhere", text: "\t-\titem\n\t\t>\tquote\n\t```\n\tcode" },
    { name: "fence closed by a shorter fence", text: "````\ncode\n```\nstill code\n````" },
    { name: "fence with a backtick info string", text: "``` a`b\nnot a fence" },
    { name: "HTML block that never closes", text: "<div>\n<span>\n<table><tr><td>cell" },
    { name: "maths that never closes", text: "$x and $$y and \\(z" },
    { name: "footnotes gone wrong", text: "[^a] [^] [^ b]\n\n[^a]:\n[^a]: twice\n[^]: empty" },
    { name: "reference definitions gone wrong", text: "[a]: \n[b]: <unclosed\n[c]: /u 'bad\n[a][] [b] [c]" },
    { name: "headings gone wrong", text: "#\n####### seven\n#no space\n# trailing #####\n=\n--" },
    { name: "a mermaid block with a syntax error", text: "```mermaid\ngraph TD\n  A --> \n  ((( \n```" },
    { name: "a mermaid block that never closes", text: "```mermaid\ngraph TD\n  A --> B" },
];

describe("malformed Markdown renders and stays editable", () => {
    for (const c of CASES) {
        it(c.name, async () => {
            // An unclosed fence or diagram swallows the rest, as CommonMark says: those end with
            // the sentinel inside them, so only the others are expected to keep it as a paragraph.
            const swallows = /never closes$/.test(c.name) && /mermaid|HTML/.test(c.name);
            const source = `${c.text}\n\n${END}`;
            const started = Date.now();
            await loadMarkdown(page, source);
            expect(Date.now() - started, "render time").toBeLessThan(10_000);
            expect(pageErrors(page)).toEqual([]);
            if (swallows) return;
            // Long cases are virtualised: go to the end and wait for its section to mount.
            const box = await page.evaluate(async (end) => {
                for (let i = 0; i < 40; i++) {
                    window.scrollTo(0, document.documentElement.scrollHeight);
                    await new Promise((r) => setTimeout(r, 100));
                    const p = [...document.querySelectorAll("main p")].find((e) => e.textContent === end);
                    if (p) {
                        p.scrollIntoView({ block: "center" });
                        await new Promise((r) => setTimeout(r, 700)); // past the fling guard
                        const r = p.getBoundingClientRect();
                        return { x: r.x, y: r.y, width: r.width, height: r.height };
                    }
                }
                return null;
            }, END);
            if (!box) throw new Error("the sentinel paragraph is not shown");
            await clearReceived(page);
            await page.mouse.click(box.x + box.width - 1, box.y + box.height / 2);
            await waitForMessage(page, (m) => m.type === "editingChanged");
            await page.keyboard.press("End");
            await page.keyboard.type("!");
            const edit = (await waitForMessage(page, (m) => m.type === "sourceEdit")) as Extract<
                PageMessage,
                { type: "sourceEdit" }
            >;
            const at = source.length;
            expect(edit.edits).toEqual([{ from: at, to: at, insert: "!" }]);
            await page.mouse.click(5, 5); // ends the session before the next case loads
        });
    }

    it("the malformed corpus renders whole, every character kept", async () => {
        const text = corpus("malformed.md");
        await loadMarkdown(page, text);
        expect(pageErrors(page)).toEqual([]);
        const shown = await page.locator("main").innerText();
        expect(shown).toContain("Last paragraph after everything.");
        expect(shown).toContain("10000000000. too many digits");
        expect(shown).toContain("#No space heading");
    });
});
