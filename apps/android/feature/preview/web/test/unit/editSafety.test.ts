/**
 * Data safety of tap-to-edit, checked on the renderer's output without a
 * browser (the Playwright twin in e2e/editSafety.test.ts types for real):
 *
 * 1. Every visible character in an editable block is either inside an exact
 *    run, which writes back 1:1, or inside a `contenteditable="false"`
 *    element, where no caret can go. Nothing in between can take typing that
 *    would then have nowhere to go.
 * 2. Property: typing a letter anywhere inside an exact run, written back as
 *    the one-character splice Preview would send, re-renders to exactly the
 *    old text with that letter added. The edit is never lost, moved or
 *    reinterpreted.
 */
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { JSDOM } from "jsdom";
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { splitSections } from "../../src/core/sections";
import { EDITABLE_BLOCK } from "../../src/page/editing/editor";
import { createMarkdown, renderSection } from "../../src/render/markdown";
import { Random } from "./random";

const CORPUS = resolve(__dirname, "../corpus");
const md = createMarkdown();
const MARK = "Ж";

const host = new JSDOM("").window.document;

function dom(html: string): Document {
    const doc = host.implementation.createHTMLDocument("");
    doc.body.innerHTML = html;
    return doc;
}

/** Text nodes in editable blocks that could take a caret but map to no source. */
function unguarded(doc: Document): string[] {
    const out: string[] = [];
    const walker = doc.createTreeWalker(doc.body, 4 /* NodeFilter.SHOW_TEXT */);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const text = node.nodeValue ?? "";
        if (text.trim() === "") continue;
        const el = node.parentElement;
        if (!el) continue;
        if (el.closest(".ro-block, [contenteditable=false]")) continue;
        const block = el.closest(EDITABLE_BLOCK);
        if (!block) continue;
        if (el.closest("[data-s]")) continue;
        out.push(`${block.tagName}: ${JSON.stringify(text)}`);
    }
    return out;
}

function render(src: string, defs = collectDefinitions(src.split("\n"))): string {
    return renderSection(md, src, defs).html;
}

/** Plain text of the rendered blocks, the way a reader sees it. */
function visible(html: string): string {
    return dom(html).body.textContent ?? "";
}

/** Each exact run with its source offsets and where its text starts in [visible]. */
function exactRuns(html: string): { s: number; e: number; at: number }[] {
    const doc = dom(html);
    const out: { s: number; e: number; at: number }[] = [];
    let at = 0;
    const walker = doc.createTreeWalker(doc.body, 4);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const run = node.parentElement?.closest<HTMLElement>("[data-s]");
        if (run && run.firstChild === node && !run.closest("pre")) {
            out.push({ s: Number(run.dataset.s), e: Number(run.dataset.e), at });
        }
        at += node.nodeValue?.length ?? 0;
    }
    return out;
}

const FRAGMENTS = [
    "plain words",
    "**bold**",
    "*em*",
    "_under_",
    "~~gone~~",
    "`code`",
    "&amp;",
    "AT&amp;T",
    "&copy;",
    "&#169;",
    "\\*",
    "\\_x\\_",
    "<kbd>Ctrl</kbd>",
    '<span title="t">tip</span>',
    "<b></b>",
    "<br>",
    "<https://example.org/a>",
    "www.example.com",
    "me@example.com",
    ":smile:",
    "[link](https://x.y/z)",
    "![img](a.png)",
    "==mark==",
    "H~2~O",
    "x^2^",
    "$x^2$",
    "[^1]",
    "\n",
];

function fuzzParagraph(rnd: Random): string {
    const parts = Array.from({ length: 2 + rnd.int(6) }, () => rnd.pick(FRAGMENTS));
    return parts.join(rnd.pick([" ", " ", "", ", "])).replace(/^\s+|\s+$/g, "") || "x";
}

describe("nothing editable maps nowhere", () => {
    for (const file of readdirSync(CORPUS).filter((f) => f.endsWith(".md"))) {
        it(`${file}: every visible character is in an exact run or read-only`, () => {
            const lines = readFileSync(resolve(CORPUS, file), "utf8").split("\n");
            const defs = collectDefinitions(lines);
            for (const section of splitSections(lines)) {
                const src = lines.slice(section.startLine, section.endLine).join("\n");
                const { html } = renderSection(md, src, defs, section.startLine === 0);
                expect(unguarded(dom(html)), `${file}:${section.startLine}`).toEqual([]);
            }
        });
    }

    it("locks raw inline HTML, entities at a run's edge, escapes, autolinks and emoji", () => {
        const html = render("Raw <kbd>Ctrl</kbd>, &amp;, \\*x, <https://example.org>, www.example.com, :smile: end");
        const doc = dom(html);
        expect(doc.querySelector("kbd")?.getAttribute("contenteditable")).toBe("false");
        expect(doc.querySelector("kbd [data-s]")).toBeNull();
        expect(doc.querySelector("a[href='https://example.org'] [data-s]")).toBeNull();
        expect(doc.querySelector("a[href='http://www.example.com'] [data-s]")).toBeNull();
        expect(doc.querySelector(".emoji")?.getAttribute("contenteditable")).toBe("false");
        for (const run of doc.querySelectorAll("[data-s]")) {
            expect(run.textContent).not.toMatch(/^[&*]/);
        }
    });

    it("property: fuzzed inline Markdown has no unguarded text", () => {
        const rnd = new Random(31);
        for (let i = 0; i < 600; i++) {
            const src = fuzzParagraph(rnd);
            expect(unguarded(dom(render(src))), src).toEqual([]);
        }
    }, 60_000);

    it("property: a letter typed inside any exact run reappears exactly there after write-back", () => {
        const rnd = new Random(77);
        let checked = 0;
        for (let i = 0; i < 200; i++) {
            const src = fuzzParagraph(rnd);
            const html = render(src);
            const before = visible(html);
            for (const run of exactRuns(html)) {
                if (run.e - run.s < 2) continue;
                const k = 1 + rnd.int(run.e - run.s - 1); // strictly inside: the run's edges touch syntax
                const edited = src.slice(0, run.s + k) + MARK + src.slice(run.s + k);
                const want = before.slice(0, run.at + k) + MARK + before.slice(run.at + k);
                expect(visible(render(edited)), `${JSON.stringify(src)} @${run.s + k}`).toBe(want);
                checked++;
            }
        }
        expect(checked).toBeGreaterThan(150);
    }, 60_000);
});
