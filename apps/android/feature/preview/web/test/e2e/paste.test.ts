/**
 * Copy and paste in Preview through Chromium's real clipboard: the clip is
 * written with the async Clipboard API, Ctrl+V pastes it, so the page gets
 * the same `beforeinput` (insertFromPaste, with its DataTransfer) a phone's
 * Paste gives it. Each case checks the Markdown source Kotlin receives
 * (docs/ux-spec.md, "Paste").
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import { applyEdit } from "../../src/core/minimalEdit";
import {
    clearReceived,
    loadMarkdown,
    openPreview,
    received,
    startHarness,
    waitForMessage,
    type Harness,
} from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

/** Pages opened by the running test: 79 cases each open one, so each is closed when its test ends. */
const opened: Page[] = [];
afterEach(async () => {
    for (const page of opened.splice(0)) await page.context().close();
});

const SOURCE = [
    "# Heading one",
    "",
    "Para alpha beta.",
    "",
    "- bullet one",
    "- bullet two",
    "",
    "1. first",
    "2. second",
    "3. third",
    "",
    "- [ ] task one",
    "",
    "> quote one",
    "",
    "| A | B |",
    "| - | - |",
    "| cell1 | x |",
    "",
    "```js",
    "code1",
    "```",
    "",
].join("\n");

/** Applies every sourceEdit the page sent, as Kotlin would. */
async function applySent(page: Page, source: string): Promise<string> {
    let text = source;
    for (const m of await received(page)) {
        if (m.type !== "sourceEdit") continue;
        for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
    }
    return text;
}

/** The source after the first [count] sourceEdits only: where Kotlin's undo would stop. */
async function applySentUpTo(page: Page, source: string, count: number): Promise<string> {
    let text = source;
    const sent = (await received(page)).filter((m) => m.type === "sourceEdit").slice(0, count);
    for (const m of sent) for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
    return text;
}

async function open(source = SOURCE): Promise<Page> {
    const page = await openPreview(h);
    opened.push(page);
    await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
    await loadMarkdown(page, source);
    await clearReceived(page);
    return page;
}

/** Taps [word] to start editing, then puts the caret after it (or selects [select] chars before that point). */
async function caretAfter(page: Page, word: string, select = 0): Promise<void> {
    const target = page.getByText(word, { exact: false }).last();
    await target.waitFor();
    const box = await target.boundingBox();
    if (!box) throw new Error(`no ${word}`);
    // The right-hand end: a task's text, never its checkbox.
    await page.mouse.click(box.x + box.width - 3, box.y + box.height / 2);
    await waitForMessage(page, (m) => m.type === "editingChanged");
    await page.evaluate(
        ({ word, select }) => {
            const block = document.querySelector(".editing");
            const walker = document.createTreeWalker(block ?? document.body, NodeFilter.SHOW_TEXT);
            for (let n = walker.nextNode(); n; n = walker.nextNode()) {
                const at = (n.textContent ?? "").indexOf(word);
                if (at < 0) continue;
                const end = at + word.length;
                window.getSelection()?.setBaseAndExtent(n, end - select, n, end);
                return;
            }
            throw new Error(`no ${word} in the block`);
        },
        { word, select },
    );
}

async function writeClip(page: Page, plain: string | null, html: string | null): Promise<void> {
    await page.evaluate(
        async ({ plain, html }) => {
            const parts: Record<string, Blob> = {};
            if (plain !== null) parts["text/plain"] = new Blob([plain], { type: "text/plain" });
            if (html !== null) parts["text/html"] = new Blob([html], { type: "text/html" });
            await navigator.clipboard.write([new ClipboardItem(parts)]);
        },
        { plain, html },
    );
}

async function pasteInto(page: Page, source: string, plain: string): Promise<string> {
    await writeClip(page, plain, null);
    await page.keyboard.press("Control+V");
    await waitForMessage(page, (m) => m.type === "sourceEdit");
    return applySent(page, source);
}

async function paste(page: Page, plain: string | null, html: string | null = null): Promise<string> {
    await writeClip(page, plain, html);
    await page.keyboard.press("Control+V");
    await waitForMessage(page, (m) => m.type === "sourceEdit" || m.type === "editRefused");
    await page.waitForTimeout(50);
    return applySent(page, SOURCE);
}

type BlockName = "paragraph" | "heading" | "bullet" | "numbered" | "task" | "quote" | "cell" | "code";

/** Where each block's caret goes: after this word. */
const AT: Record<BlockName, string> = {
    paragraph: "alpha",
    heading: "Heading",
    bullet: "bullet one",
    numbered: "first",
    task: "task one",
    quote: "quote one",
    cell: "cell1",
    code: "code1",
};

const OWN = '<meta charset="utf-8"><div data-ssmd=""><strong>bold</strong> and <a href="https://e.com">l</a></div>';

/** Clip kinds: plain text and/or HTML, and what each block gets in its source. */
const CLIPS: readonly {
    name: string;
    plain: string | null;
    html: string | null;
    want: (block: BlockName) => string;
}[] = [
    {
        name: "EB-CLIP-06 one line with syntax",
        plain: "x *y* _z_ [w] `v` a_b",
        html: null,
        want: (b) => (b === "code" ? "x *y* _z_ [w] `v` a_b" : "x \\*y\\* \\_z\\_ \\[w\\] \\`v\\` a_b"),
    },
    {
        name: "EB-CLIP-08/09/10/11 several lines",
        plain: "one\ntwo # three\n- four",
        html: null,
        want: (b) =>
            ({
                paragraph: "one\n\ntwo # three\n\n\\- four",
                heading: "one\n\ntwo # three\n\n\\- four",
                bullet: "one\n  two # three\n  \\- four",
                numbered: "one\n   two # three\n   \\- four",
                task: "one\n  two # three\n  \\- four",
                quote: "one\n>\n> two # three\n>\n> \\- four",
                cell: "one<br>two # three<br>- four",
                code: "one\ntwo # three\n- four",
            })[b],
    },
    {
        name: "EB-CLIP-15 CRLF",
        plain: "a\r\nb|c\r",
        html: null,
        want: (b) =>
            ({
                paragraph: "a\n\nb|c",
                heading: "a\n\nb|c",
                bullet: "a\n  b|c",
                numbered: "a\n   b|c",
                task: "a\n  b|c",
                quote: "a\n>\n> b|c",
                cell: "a<br>b\\|c",
                code: "a\nb|c\n",
            })[b],
    },
    {
        name: "EB-CLIP-13 HTML only (plain text until HTML is converted)",
        plain: null,
        html: '<p>Hi <b>there</b><img src="https://example.com/x.png" onerror="bad()"></p><p>Two</p><script>bad()</script>',
        want: (b) =>
            ({
                paragraph: "Hi there\n\nTwo",
                heading: "Hi there\n\nTwo",
                bullet: "Hi there\n  Two",
                numbered: "Hi there\n   Two",
                task: "Hi there\n  Two",
                quote: "Hi there\n>\n> Two",
                cell: "Hi there<br>Two",
                code: "Hi there\nTwo\n",
            })[b],
    },
    {
        name: "EB-CLIP-13 HTML and plain text from another app",
        plain: "Hi *there*",
        html: "<p>Hi <b>there</b></p>",
        want: (b) => (b === "code" ? "Hi *there*" : "Hi \\*there\\*"),
    },
    {
        name: "EB-CLIP-16 emoji, combining marks, right-to-left, NBSP and zero-width",
        plain: "\u{1F469}\u200D\u{1F469}\u200D\u{1F467} مرحبا e\u0301 \u{1F1EC}\u{1F1E7}\u00A0x\u200By",
        html: null,
        want: () => "\u{1F469}\u200D\u{1F469}\u200D\u{1F467} مرحبا e\u0301 \u{1F1EC}\u{1F1E7}\u00A0x\u200By",
    },
    {
        name: "EB-LINK-05 a bare web address with nothing selected",
        plain: "https://example.com/a_b?q=1",
        html: null,
        want: () => "https://example.com/a_b?q=1",
    },
    {
        name: "EB-CLIP-01 Markdown copied from Preview",
        plain: "**bold** and [l](https://e.com)",
        html: OWN,
        want: () => "**bold** and [l](https://e.com)",
    },
];

describe("paste matrix: every clip kind into every block", () => {
    for (const clip of CLIPS) {
        for (const block of Object.keys(AT) as BlockName[]) {
            it(`${clip.name} into a ${block}`, async () => {
                const page = await open();
                await caretAfter(page, AT[block]);
                const after = await paste(page, clip.plain, clip.html);
                const at = SOURCE.indexOf(AT[block]) + AT[block].length;
                expect(after).toBe(SOURCE.slice(0, at) + clip.want(block) + SOURCE.slice(at));
                expect((await received(page)).filter((m) => m.type === "sourceEdit")).toHaveLength(1);
            });
        }
    }
});

describe("paste: selections and edges", () => {
    it("escapes block syntax at the start of a block, not inside a line", async () => {
        const page = await open("Alpha\n\n- item");
        await caretAfter(page, "item", 4);
        await writeClip(page, "# not a heading", null);
        await page.keyboard.press("Control+V");
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        expect(await applySent(page, "Alpha\n\n- item")).toBe("Alpha\n\n- \\# not a heading");
    });

    it("EB-LINK-05 / EB-UNDO-07: links selected text to a pasted web address, the plain paste one step before", async () => {
        const page = await open();
        await caretAfter(page, "beta", 4);
        const after = await paste(page, "https://example.com/(x)");
        expect(after).toBe(SOURCE.replace("alpha beta.", "alpha [beta](https://example.com/(x))."));
        const edits = (await received(page)).filter((m) => m.type === "sourceEdit");
        expect(edits).toHaveLength(2);
        // Kotlin makes each page edit an undo step: the first Undo leaves the plain paste.
        expect(await applySentUpTo(page, SOURCE, 1)).toBe(SOURCE.replace("beta.", "https://example.com/(x)."));
    });

    it("EB-LINK-06: text that is not an address replaces the selection, in one edit", async () => {
        const other = await open("See some words.");
        await caretAfter(other, "words", 5);
        expect(await pasteInto(other, "See some words.", "plain")).toBe("See some plain.");
        expect((await received(other)).filter((m) => m.type === "sourceEdit")).toHaveLength(1);
    });

    it("EB-CLIP-07: a Markdown list pasted into a numbered item becomes its items, the rest renumbered", async () => {
        const page = await open();
        await caretAfter(page, "first");
        const after = await paste(page, "- **a**\n- b\n");
        expect(after).toBe(
            SOURCE.replace("1. first\n2. second\n3. third", "1. first\n2. **a**\n3. b\n4. second\n5. third"),
        );
    });

    it("EB-CLIP-07: splits the item at the caret", async () => {
        const page = await open();
        await caretAfter(page, "bullet");
        const after = await paste(page, "* x\n* y");
        expect(after).toBe(SOURCE.replace("- bullet two", "- bullet\n- x\n- y\n-  two"));
    });

    it("EB-CLIP-19: a trailing line break adds no blank line and keeps the list whole", async () => {
        const page = await open();
        await caretAfter(page, "bullet one");
        expect(await paste(page, "tail\n\n")).toBe(SOURCE.replace("bullet one", "bullet onetail"));
    });

    it("EB-LINK-08 / EB-CODE-24: pastes an address over text in code as text", async () => {
        const page = await open();
        await caretAfter(page, "code1", 5);
        expect(await paste(page, "https://example.com")).toBe(SOURCE.replace("code1", "https://example.com"));
    });

    it("replaces a selection in one block, leaving the blocks around it whole", async () => {
        const page = await open();
        await caretAfter(page, "alpha beta", 10);
        expect(await paste(page, "new\nlines")).toBe(SOURCE.replace("alpha beta.", "new\n\nlines."));
    });

    it("never lands a paste aimed at text it cannot edit somewhere else", async () => {
        const source = "Sum $x^2$ here.";
        const page = await open(source);
        await page.getByText("Sum").waitFor();
        const box = await page.getByText("Sum").boundingBox();
        if (!box) throw new Error("no text");
        await page.mouse.click(box.x + 2, box.y + box.height / 2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await page.evaluate(() => {
            const maths = document.querySelector(".editing [contenteditable=false]");
            if (!maths) throw new Error("no read-only maths");
            window.getSelection()?.selectAllChildren(maths);
        });
        await writeClip(page, "nope", null);
        await page.keyboard.press("Control+V");
        // Chromium offers no paste into read-only text; a beforeinput that did arrive is refused.
        await page.waitForTimeout(400);
        expect(await applySent(page, source)).toBe(source);
    });
});

describe("copy from Preview", () => {
    async function copy(
        page: Page,
        from: string,
        to: string,
        fromEnd = false,
    ): Promise<{ text: string; html: string }> {
        await page.evaluate(
            ({ from, to, fromEnd }) => {
                const find = (word: string): [Node, number] => {
                    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
                        const at = (n.textContent ?? "").indexOf(word);
                        if (at >= 0) return [n, at];
                    }
                    throw new Error(`no ${word}`);
                };
                const [a, ao] = find(from);
                const [b, bo] = find(to);
                window.getSelection()?.setBaseAndExtent(a, fromEnd ? ao + from.length : ao, b, bo + to.length);
            },
            { from, to, fromEnd },
        );
        await page.keyboard.press("Control+C");
        return page.evaluate(async () => {
            const [item] = await navigator.clipboard.read();
            const text = item ? await (await item.getType("text/plain")).text() : "";
            const html = item?.types.includes("text/html") ? await (await item.getType("text/html")).text() : "";
            return { text, html };
        });
    }

    it("EB-CLIP-01: copies whole blocks as their Markdown, and the HTML as Preview's own", async () => {
        const page = await open("Intro **bold** [link](https://e.com)\n\n- one\n- two\n\nEnd");
        const list = await copy(page, "one", "two");
        expect(list.text).toBe("- one\n- two");
        expect(list.html).toContain("data-ssmd");
        expect(list.html).not.toMatch(/data-s=|class=|contenteditable/);
        const para = await copy(page, "Intro", "link");
        expect(para.text).toBe("Intro **bold** [link](https://e.com)");
    });

    it("copies across blocks from the middle of one to the middle of another as the source between", async () => {
        const page = await open("Para alpha beta.\n\n- one two\n- three");
        const both = await copy(page, "alpha", "one", true);
        expect(both.text).toBe(" beta.\n\n- one");
    });

    it("EB-CLIP-03: leaves a copy that would cut bold in half to Chromium: rendered text, never a stray **", async () => {
        const page = await open("Plain **bold words** after.");
        const half = await copy(page, "Plain", "bold");
        expect(half.text).toBe("Plain bold");
        expect(half.html).not.toContain("data-ssmd");
    });

    it("round-trips: a list copied from Preview pastes after a paragraph as a list, bold as bold", async () => {
        const source = "First para.\n\n- **one**\n- two\n\nLast para.";
        const page = await open(source);
        const clip = await copy(page, "one", "two");
        await caretAfter(page, "Last para.");
        await writeClip(page, clip.text, clip.html);
        await page.keyboard.press("Control+V");
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        expect(await applySent(page, source)).toBe(source + "\n\n- **one**\n- two");
        await page.waitForSelector("section.sec ul >> nth=1");
        expect(await page.locator("ul").nth(1).locator("strong").textContent()).toBe("one");
    });

    it("EB-CLIP-02: copies a selection inside a code block as the exact code", async () => {
        const page = await open('Text\n\n```\nif (a < b && c) { x = "q" }\n```');
        const code = await copy(page, "if", "}");
        expect(code.text).toBe('if (a < b && c) { x = "q" }');
    });

    it("cuts the selection's source in one edit while editing", async () => {
        const source = "Keep **this** and that.";
        const page = await open(source);
        await caretAfter(page, "and that", 8);
        await page.keyboard.press("Control+X");
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        expect(await applySent(page, source)).toBe("Keep **this** .");
        const cut = await page.evaluate(() => navigator.clipboard.readText());
        expect(cut).toBe("and that");
    });
});
