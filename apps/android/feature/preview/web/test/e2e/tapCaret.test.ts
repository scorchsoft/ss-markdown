/**
 * Where a tap puts the caret, for every editable block (Andrew's 0.2.2 report: a tap in the
 * space right of `)` in a code block put the caret on the line above). Taps are real touches
 * at computed points, on a phone (412 × 915 at 2.6×) in the dark Clean theme, and the answer
 * is read as Kotlin reads it: the `selection` message's source offset, plus the visual line
 * the caret is drawn on.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage, ThemeSpec } from "../../src/protocol";
import { clearReceived, loadMarkdown, openPreview, received, send, startHarness, type Harness } from "./harness";

const SOURCE = [
    "## Heading long enough to wrap across two lines on a phone",
    "",
    "A paragraph with **bold** words that is long enough to wrap onto a second visual line here.",
    "",
    "- A list item that is long enough to wrap onto a second visual line at phone width.",
    "",
    "***",
    "",
    "- [ ] A task item that is long enough to wrap onto a second visual line at phone width.",
    "",
    "> A quoted line that is long enough to wrap onto a second visual line at phone width.",
    "",
    "| a | b |",
    "|---|---|",
    "| x | A table cell long enough to wrap onto a second line. |",
    "",
    "```python",
    "def f(x):",
    "    return (",
    "        x",
    "",
    "    )",
    "```",
    "",
    "> [!NOTE]",
    "> Read-only first line.",
    ">",
    "> An alert paragraph long enough to wrap onto a second visual line at phone width.",
    "",
    "Family 👨‍👩‍👧 and flag 🇬🇧 then שלום עולם at the end of a line that wraps 👍🏽",
    "",
    "Last paragraph.",
].join("\n");

/** Each editable block under test: the first element matching `sel` whose text holds `has`. */
const BLOCKS: readonly { name: string; sel: string; has: string }[] = [
    { name: "heading", sel: "h2", has: "Heading" },
    { name: "paragraph", sel: "p", has: "A paragraph" },
    { name: "list item", sel: "li", has: "A list item" },
    { name: "task item", sel: "li", has: "A task item" },
    { name: "quote", sel: "blockquote > p", has: "A quoted" },
    { name: "table cell", sel: "td", has: "A table cell" },
    { name: "code block", sel: "pre.code-block > code", has: "def" },
    { name: "alert", sel: ".markdown-alert > p", has: "An alert" },
    { name: "graphemes and RTL", sel: "p", has: "Family" },
];

const SPOTS = [
    "right of the end",
    "left of the start",
    "in a wrapped line's trailing space",
    "below the last line",
    "between characters",
    "in the gap below",
    "in the gap above",
] as const;

interface Box {
    top: number;
    bottom: number;
    left: number;
    right: number;
}

interface Line extends Box {
    /** Source offsets of the line's first character and just after its last non-space one. */
    start: number;
    end: number;
    /** Every grapheme boundary on the line: its source offset and x. */
    stops: { at: number; x: number }[];
}

interface Block {
    box: Box;
    lines: Line[];
}

let h: Harness;
let page: Page;

beforeAll(async () => {
    h = await startHarness();
    page = await openPreview(h, { width: 412, height: 915 }, { deviceScaleFactor: 2.6, hasTouch: true });
    const vectors = JSON.parse(readFileSync(resolve(__dirname, "../vectors/themes.json"), "utf8")) as {
        themes: { id: string; theme: ThemeSpec }[];
    };
    const clean = vectors.themes.find((t) => t.id === "clean");
    if (!clean) throw new Error("no Clean theme");
    await send(page, { type: "theme", theme: clean.theme, dark: true });
    await page.waitForFunction(() => document.documentElement.dataset.dark === "true");
    await loadMarkdown(page, SOURCE);
    await page.evaluate(() => document.fonts.ready);
    // The measuring function, from the glyphs themselves (one Range per grapheme).
    await page.evaluate(() => {
        const seg = new Intl.Segmenter(undefined, { granularity: "grapheme" });
        (window as unknown as { __measure: (el: Element) => Block }).__measure = (el) => {
            const base = Number(el.closest<HTMLElement>("section.sec")?.dataset.offset ?? 0);
            const lines: Line[] = [];
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                const run = node.parentElement?.closest<HTMLElement>("[data-s]");
                if (!run || !el.contains(run) || !(node instanceof Text)) continue;
                const before = document.createRange();
                before.setStart(run, 0);
                before.setEnd(node, 0);
                const at0 = base + Number(run.dataset.s) + before.toString().length;
                for (const g of seg.segment(node.data)) {
                    if (g.segment === "\n") continue;
                    const r = document.createRange();
                    r.setStart(node, g.index);
                    r.setEnd(node, g.index + g.segment.length);
                    const rect = [...r.getClientRects()].find((c) => c.width > 0);
                    if (!rect) continue;
                    const from = at0 + g.index;
                    const to = from + g.segment.length;
                    const mid = (rect.top + rect.bottom) / 2;
                    let line = lines.find((l) => mid > l.top && mid < l.bottom);
                    if (!line) {
                        line = { ...rect.toJSON(), start: from, end: from, stops: [] } as Line;
                        lines.push(line);
                    }
                    line.top = Math.min(line.top, rect.top);
                    line.bottom = Math.max(line.bottom, rect.bottom);
                    line.left = Math.min(line.left, rect.left);
                    line.right = Math.max(line.right, rect.right);
                    line.start = Math.min(line.start, from);
                    if (!/^\s$/.test(g.segment)) line.end = Math.max(line.end, to);
                    line.stops.push({ at: from, x: rect.left }, { at: to, x: rect.right });
                }
            }
            lines.sort((a, b) => a.top - b.top);
            const b = (el.matches("pre > code") ? (el.parentElement ?? el) : el).getBoundingClientRect();
            return { lines, box: { top: b.top, bottom: b.bottom, left: b.left, right: b.right } };
        };
    });
});
afterAll(async () => h.close());

/** Scrolls the block into view (waiting out the fling guard), then measures it. */
async function measure(sel: string, has: string): Promise<Block> {
    const moved = await page.evaluate(
        ([s, text]) => {
            const el = [...document.querySelectorAll(s)].find((e) => (e.textContent ?? "").includes(text));
            if (!el) throw new Error(`no ${s} with ${text}`);
            const y = window.scrollY;
            el.scrollIntoView({ block: "center" });
            return window.scrollY !== y;
        },
        [sel, has] as const,
    );
    // A tap within 500 ms of a scroll only stops the scroll (FLING_GUARD_MS).
    if (moved) await page.waitForTimeout(600);
    return page.evaluate(
        ([s, text]) => {
            const el = [...document.querySelectorAll(s)].find((e) => (e.textContent ?? "").includes(text));
            if (!el) throw new Error(`no ${s} with ${text}`);
            return (window as unknown as { __measure: (el: Element) => Block }).__measure(el);
        },
        [sel, has] as const,
    );
}

/** Every block on the page a gap tap could go to, with its lines (none: not editable). */
async function everyBlock(): Promise<Block[]> {
    return page.evaluate(() =>
        [...document.querySelectorAll("p, h1, h2, h3, h4, h5, h6, li, td, th, pre, hr")].map((el) => {
            const code = el.matches("pre.code-block") ? el.querySelector(":scope > code") : null;
            return (window as unknown as { __measure: (el: Element) => Block }).__measure(code ?? el);
        }),
    );
}

/** Ends any session, then taps ([x], [y]) for real and reads the caret as Kotlin hears of it. */
async function tapAt(x: number, y: number, fresh = true): Promise<{ offset: number | null; caretY: number | null }> {
    if (fresh) {
        await page.evaluate(() => {
            document.querySelector("hr")?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
            window.getSelection()?.removeAllRanges();
        });
        await page.waitForTimeout(80);
    }
    await clearReceived(page);
    await page.touchscreen.tap(x, y);
    let offset: number | null = null;
    for (const start = Date.now(); Date.now() - start < 1000; await page.waitForTimeout(25)) {
        const sel = (await received(page)).find(
            (m): m is Extract<PageMessage, { type: "selection" }> => m.type === "selection",
        );
        if (sel) {
            expect(sel.to).toBe(sel.from);
            offset = sel.from;
            break;
        }
    }
    const caretY = await page.evaluate(() => {
        const s = window.getSelection();
        if (!s || s.rangeCount === 0 || !document.querySelector(".editing")) return null;
        const r = s.getRangeAt(0);
        const rect = r.getClientRects()[0] ?? r.getBoundingClientRect();
        return (rect.top + rect.bottom) / 2;
    });
    return { offset, caretY };
}

async function expectCaret(x: number, y: number, line: Line | null, offset: number | null, fresh = true) {
    const got = await tapAt(x, y, fresh);
    expect(got.offset, `tap at ${x.toFixed(1)},${y.toFixed(1)}`).toBe(offset);
    if (!line) return;
    expect(got.caretY, "caret drawn on the tapped line").toBeGreaterThanOrEqual(line.top - 1);
    expect(got.caretY, "caret drawn on the tapped line").toBeLessThanOrEqual(line.bottom + 1);
}

const mid = (l: Box): number => (l.top + l.bottom) / 2;
const first = (b: Block): Line => b.lines[0] as Line;
const last = (b: Block): Line => b.lines[b.lines.length - 1] as Line;

/** The block nearest ([x], [y]): by vertical gap, then horizontal, then the innermost. */
function nearest(blocks: readonly Block[], x: number, y: number): Block {
    const key = (b: Block): number[] => [
        Math.max(b.box.top - y, y - b.box.bottom, 0),
        Math.max(b.box.left - x, x - b.box.right, 0),
        (b.box.right - b.box.left) * (b.box.bottom - b.box.top),
    ];
    const sorted = [...blocks].sort((a, b) => {
        const [ka, kb] = [key(a), key(b)];
        for (let i = 0; i < 3; i++) if (Math.abs((ka[i] ?? 0) - (kb[i] ?? 0)) > 0.5) return (ka[i] ?? 0) - (kb[i] ?? 0);
        return 0;
    });
    return sorted[0] as Block;
}

/** The line of [b] nearest [y]. */
function lineNear(b: Block, y: number): Line {
    const d = (l: Line): number => Math.max(l.top - y, y - l.bottom, 0);
    return [...b.lines].sort((p, q) => d(p) - d(q))[0] as Line;
}

describe("a tap puts the caret on the visual line it hit", () => {
    for (const block of BLOCKS) {
        for (const spot of SPOTS) {
            it(`${block.name}: ${spot}`, async () => {
                const b = await measure(block.sel, block.has);
                expect(b.lines.length, "the block wraps").toBeGreaterThanOrEqual(2);
                const farRight = b.box.right - 3;
                // Left of a task item's text is its checkbox's 48 dp hit area (that tap ticks the box).
                const farLeft = block.name === "task item" ? farRight : first(b).left - 6;
                switch (spot) {
                    case "right of the end":
                        return expectCaret(farRight, mid(last(b)), last(b), last(b).end);
                    case "left of the start":
                        if (block.name === "task item") return;
                        return expectCaret(farLeft, mid(first(b)), first(b), first(b).start);
                    case "in a wrapped line's trailing space":
                        return expectCaret(farRight, mid(first(b)), first(b), first(b).end);
                    case "below the last line": {
                        const l = last(b);
                        const y = b.box.bottom - l.bottom >= 4 ? (l.bottom + b.box.bottom) / 2 : l.bottom + 2;
                        return expectCaret((b.box.left + b.box.right) / 2, y, l, l.end);
                    }
                    case "between characters": {
                        const stop = first(b).stops[7];
                        if (!stop) throw new Error("short line");
                        return expectCaret(stop.x + 1, mid(first(b)), first(b), stop.at);
                    }
                    case "in the gap below":
                    case "in the gap above": {
                        // A point in the margin outside the block's outermost box, nearer this block's side.
                        const outer = await page.evaluate(
                            ([s, text]) => {
                                const el = [...document.querySelectorAll(s)].find((e) =>
                                    (e.textContent ?? "").includes(text),
                                );
                                const o = el?.closest("table, ul, ol, blockquote, .markdown-alert, pre, p, h2") ?? el;
                                if (!o) throw new Error("no block");
                                const r = o.getBoundingClientRect();
                                const cs = getComputedStyle(o);
                                const mt = parseFloat(cs.marginTop);
                                const mb = parseFloat(cs.marginBottom);
                                return { top: r.top - Math.max(2, mt * 0.3), bottom: r.bottom + Math.max(2, mb * 0.3) };
                            },
                            [block.sel, block.has] as const,
                        );
                        const below = spot === "in the gap below";
                        const x = block.name === "task item" ? farRight : (b.box.left + b.box.right) / 2;
                        const y = below ? outer.bottom : outer.top;
                        const target = nearest(await everyBlock(), x, y);
                        if (target.lines.length === 0) return expectCaret(x, y, null, null);
                        const line = lineNear(target, y);
                        return expectCaret(x, y, line, y > target.box.bottom ? line.end : line.start);
                    }
                }
            });
        }
    }

    it("code block: right of `)` puts the caret after it (Andrew's report), also while editing", async () => {
        const b = await measure("pre.code-block > code", "def");
        const at = SOURCE.indexOf("    )\n```") + 5;
        const line = b.lines.find((l) => l.end === at);
        if (!line) throw new Error("no ) line");
        await expectCaret(b.box.right - 3, mid(line), line, at);
        await expectCaret(line.right + 12, mid(line), line, at);
        // Already editing, caret elsewhere in the block: the tap still moves it there.
        const top = first(b);
        await expectCaret(top.left + 10, mid(top), top, top.start + 1);
        await expectCaret(line.right + 12, mid(line), line, at, false);
    });

    it("code block: a tap on an empty line goes to that line", async () => {
        const b = await measure("pre.code-block > code", "def");
        const at = SOURCE.indexOf("        x\n\n") + "        x\n".length;
        const above = b.lines.find((l) => l.end === at - 1);
        const below = b.lines.find((l) => l.start === at + 1);
        if (!above || !below) throw new Error("no empty line");
        for (const x of [b.box.left + 4, b.box.left + 60, b.box.right - 3]) {
            const got = await tapAt(x, (above.bottom + below.top) / 2);
            // A caret on an empty line has no client rect; its offset is the only one on that line.
            expect(got.offset).toBe(at);
        }
    });

    it("a tap beside a read-only line never starts editing it", async () => {
        const b = await measure(".markdown-alert > p", "Read-only");
        expect(b.lines).toEqual([]);
        await expectCaret(b.box.right - 3, mid(b.box), null, null);
    });

    it("never splits a grapheme cluster or an RTL run", async () => {
        const b = await measure("p", "Family");
        const bounds = new Set([...new Intl.Segmenter().segment(SOURCE)].map((g) => g.index));
        for (const line of b.lines) {
            for (const stop of line.stops.filter((_, i) => i % 2 === 0)) {
                const got = await tapAt(stop.x + 2, mid(line));
                expect(got.offset).not.toBeNull();
                expect(bounds.has(got.offset ?? -1), `offset ${got.offset} splits a cluster`).toBe(true);
                expect(got.caretY).toBeGreaterThanOrEqual(line.top - 1);
                expect(got.caretY).toBeLessThanOrEqual(line.bottom + 1);
            }
        }
    }, 240_000);
    it("a code block being edited keeps one background, in light and dark", async () => {
        const theme = JSON.parse(readFileSync(resolve(__dirname, "../vectors/themes.json"), "utf8")) as {
            themes: { id: string; theme: ThemeSpec }[];
        };
        for (const t of theme.themes) {
            for (const dark of [true, false]) {
                await send(page, { type: "theme", theme: t.theme, dark });
                await page.waitForFunction((d) => document.documentElement.dataset.dark === String(d), dark);
                const b = await measure("pre.code-block > code", "def");
                await tapAt(first(b).left + 10, mid(first(b)));
                // Before: the editing tint sat on the inline `code`, a box behind every line fragment.
                const painted = await page.evaluate(() =>
                    [...document.querySelectorAll("pre.code-block *")]
                        .map((el) => getComputedStyle(el).backgroundColor)
                        .filter((c) => !/^rgba\(0, 0, 0, 0\)$|^transparent$/.test(c)),
                );
                expect(painted, `${t.id} ${dark ? "dark" : "light"}`).toEqual([]);
                expect(await page.locator("pre.code-block > code.editing").count()).toBe(1);
            }
        }
    });
});
