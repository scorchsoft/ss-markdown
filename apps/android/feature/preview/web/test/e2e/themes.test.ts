/**
 * Rubric #5 (rendering fidelity) and #7 (reading quality), measured in every
 * built-in theme, light and dark (docs/rubric.md):
 *
 * - the whole rendering corpus renders with no page error, no failed diagram
 *   or maths, and no "unsupported" box except the one the corpus asks for;
 * - body, heading, link and code text meet WCAG AA against what is really
 *   behind them (AAA in Contrast), computed from the page's own styles;
 * - a paragraph holds a comfortable 45–90 characters per line at the default
 *   size on a 390 px phone, and in A4 page mode.
 *
 * Every page is also saved as a screenshot under `test-results/themes/` for
 * the visual check; CI uploads that folder as an artifact. Nothing is committed.
 */
import { mkdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { ThemeFonts, ThemeSpec } from "../../src/protocol";
import {
    corpus,
    diagramsSettled,
    loadMarkdown,
    openPreview,
    pageErrors,
    send,
    startHarness,
    type Harness,
} from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const SHOTS = resolve(__dirname, "../../test-results/themes");

/** Every rendering corpus file; the hostile one is for the security tests. */
const FILES = ["basics.md", "extended.md", "tables.md", "unicode.md", "inline-edges.md", "crlf.md"];

/** WCAG 2.2: AA is 4.5 (3 for large text), AAA is 7 (4.5 for large text). */
const AA = { normal: 4.5, large: 3 };
const AAA = { normal: 7, large: 4.5 };

interface BuiltIn {
    readonly id: string;
    readonly theme: ThemeSpec & { readonly light: { bg: string }; readonly dark: { bg: string } };
    readonly fonts: ThemeFonts;
}

/**
 * The four built-ins exactly as the app sends them, fonts included
 * (`ThemeVectorsTest` in :app keeps this file equal to `BuiltInThemes`).
 */
const BUILT_INS: readonly BuiltIn[] = (
    JSON.parse(readFileSync(resolve(__dirname, "../vectors/themes.json"), "utf8")) as { themes: BuiltIn[] }
).themes;

const THEMES = BUILT_INS.flatMap((b) =>
    [false, true].map((dark) => ({ ...b, dark, label: `${b.theme.name} ${dark ? "dark" : "light"}` })),
);

/**
 * Fenced code in several languages, so every highlight.js colour the palette
 * defines is on the page when contrast is measured, not only the corpus's few.
 */
const SYNTAX = [
    "```kotlin",
    "// A comment",
    '@Suppress("x") fun main(args: Array<String>) { val n = 42; println("hi $n") }',
    "```",
    "",
    "```html",
    '<!doctype html><a href="x" class="y">link</a>',
    "```",
    "",
    "```diff",
    "- removed",
    "+ added",
    "```",
    "",
    "```json",
    '{ "key": true, "n": 1.5, "s": "text", "z": null }',
    "```",
    "",
    "```css",
    ".a > b { color: #fff; }",
    "```",
    "",
    "```python",
    "@decorator",
    "def f(x=None):",
    "    return f'{x!r}' if x else re.compile(r'\\d+')",
    "```",
].join("\n");

/** A long English paragraph: line length is measured on real prose, not on one repeated word. */
const PROSE =
    "Line length decides how comfortable a page is to read. When a line runs too long the eye loses its place on " +
    "the way back to the start of the next one, and when it runs too short the reader is forever jumping down a " +
    "line and the rhythm of the sentence breaks apart. Typographers have settled on roughly forty-five to ninety " +
    "characters as the range that reads well, with about sixty-six as the ideal for a single column of body text. " +
    "This paragraph is long enough to wrap across many lines at any sensible size, so the measurement below " +
    "averages over whole lines and ignores the short last one.";

/**
 * Sends [b] as the app does. The vector's font URLs are origin-relative; the
 * page accepts `@font-face` only from its own origin, written absolutely (the
 * app sends `https://appassets.androidplatform.net/…`), so they are made
 * absolute against the harness's origin here.
 */
async function applyTheme(page: Page, b: BuiltIn, dark: boolean): Promise<void> {
    const origin = new URL(page.url()).origin;
    const fonts = { ...b.fonts, faces: b.fonts.faces.replace(/url\("\//g, `url("${origin}/`) };
    await send(page, { type: "theme", theme: b.theme, dark, fonts });
    await page.waitForFunction((d) => document.documentElement.dataset.dark === String(d), dark);
}

/**
 * Loads the theme's own body face and fails unless it really loaded: a
 * measurement in a fallback font measures the machine, not the theme
 * (`document.fonts.check` is true for a family with no faces at all).
 */
async function fontsReady(page: Page, b: BuiltIn): Promise<void> {
    const family = b.theme.fonts?.body ?? "";
    const loaded = await page.evaluate(async (f) => {
        await document.fonts.load(`16px "${f}"`);
        await document.fonts.ready;
        return [...document.fonts].some((face) => face.family.replace(/"/g, "") === f && face.status === "loaded");
    }, family);
    expect(loaded, `${family} loaded from the bundled file`).toBe(true);
}

interface Sample {
    readonly role: string;
    readonly selector: string;
    readonly ratio: number;
    readonly large: boolean;
    readonly fg: string;
    readonly bg: string;
}

/**
 * The contrast of every text role the reader meets, worst case per role,
 * against the background actually painted behind it (translucent layers
 * composited down to the page).
 */
async function contrasts(page: Page): Promise<Sample[]> {
    return page.evaluate(() => {
        type Rgba = [number, number, number, number];
        const parse = (c: string): Rgba => {
            const n = (c.match(/[\d.]+/g) ?? ["0", "0", "0", "0"]).map(Number);
            return [n[0] ?? 0, n[1] ?? 0, n[2] ?? 0, n[3] ?? 1];
        };
        const over = (top: Rgba, under: Rgba): Rgba => {
            const a = top[3];
            return [0, 1, 2].map((i) => (top[i] ?? 0) * a + (under[i] ?? 0) * (1 - a)).concat(1) as Rgba;
        };
        const backgroundOf = (el: Element): Rgba => {
            const layers: Rgba[] = [];
            for (let e: Element | null = el; e; e = e.parentElement) {
                const c = parse(getComputedStyle(e).backgroundColor);
                if (c[3] > 0) layers.push(c);
                if (c[3] >= 1) break;
            }
            let bg: Rgba = [255, 255, 255, 1];
            for (const layer of layers.reverse()) bg = over(layer, bg);
            return bg;
        };
        const luminance = (c: Rgba): number => {
            const [r, g, b] = [c[0], c[1], c[2]].map((v) => {
                const s = v / 255;
                return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
            });
            return 0.2126 * (r ?? 0) + 0.7152 * (g ?? 0) + 0.0722 * (b ?? 0);
        };
        const ratio = (a: Rgba, b: Rgba): number => {
            const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x);
            return ((l1 ?? 0) + 0.05) / ((l2 ?? 0) + 0.05);
        };
        const roles: Record<string, string> = {
            body: "section.sec p, section.sec li, section.sec td, section.sec dd",
            heading: "section.sec h1, section.sec h2, section.sec h3",
            link: "section.sec a[href]:not(.header-anchor)",
            "inline code": "section.sec :not(pre) > code",
            "code block": "section.sec pre code, section.sec pre code [class^='hljs-']",
            quote: "section.sec blockquote p, section.sec .markdown-alert p",
            "table header": "section.sec th",
            marked: "section.sec mark",
        };
        const out: {
            role: string;
            selector: string;
            ratio: number;
            large: boolean;
            fg: string;
            bg: string;
        }[] = [];
        for (const [role, selector] of Object.entries(roles)) {
            let worst: (typeof out)[number] | null = null;
            for (const el of document.querySelectorAll<HTMLElement>(selector)) {
                if (!(el.textContent ?? "").trim() || el.getClientRects().length === 0) continue;
                const style = getComputedStyle(el);
                const bg = backgroundOf(el);
                const fg = over(parse(style.color), bg);
                const size = parseFloat(style.fontSize);
                const bold = Number(style.fontWeight) >= 700;
                const sample = {
                    role,
                    selector: el.tagName.toLowerCase() + (el.className ? `.${String(el.className).split(" ")[0]}` : ""),
                    ratio: ratio(fg, bg),
                    large: size >= 24 || (bold && size >= 18.66),
                    fg: style.color,
                    bg: `rgb(${bg.slice(0, 3).map(Math.round).join(", ")})`,
                };
                if (!worst || sample.ratio < worst.ratio) worst = sample;
            }
            if (worst) out.push(worst);
        }
        return out;
    });
}

/**
 * Characters per full line of the first paragraph: each character's own box
 * says which line it is on, so the count is exact whatever spans the
 * renderer wraps the text in. The short last line is left out.
 */
async function charactersPerLine(page: Page): Promise<number> {
    return page.evaluate(() => {
        const p = document.querySelector("section.sec p");
        if (!p) return 0;
        const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
        const perLine = new Map<number, number>();
        const range = document.createRange();
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
            const text = node.textContent ?? "";
            for (let i = 0; i < text.length; i++) {
                range.setStart(node, i);
                range.setEnd(node, i + 1);
                const box = range.getClientRects()[0];
                if (!box) continue;
                const top = Math.round(box.top + box.height / 2);
                perLine.set(top, (perLine.get(top) ?? 0) + 1);
            }
        }
        const lines = [...perLine.entries()].sort((a, b) => a[0] - b[0]).map((e) => e[1]);
        const full = lines.slice(0, -1);
        return full.reduce((a, b) => a + b, 0) / Math.max(1, full.length);
    });
}

describe("every built-in theme, light and dark", () => {
    it.each(THEMES)("renders the corpus with no error or unexpected unsupported box: $label", async (b) => {
        const { id, theme, dark } = b;
        const page = await openPreview(h, { width: 412, height: 7000 });
        await applyTheme(page, b, dark);
        await loadMarkdown(page, corpusDocument());
        await diagramsSettled(page);
        await fontsReady(page, b);
        mkdirSync(SHOTS, { recursive: true });
        await page.screenshot({ path: resolve(SHOTS, `${id}-${dark ? "dark" : "light"}.png`), fullPage: true });
        expect(pageErrors(page)).toEqual([]);
        expect(
            await page.locator(".diagram-error, .diagram-failed, .math-error, .render-error, .katex-error").count(),
        ).toBe(0);
        expect(await page.locator(".diagram-svg svg").count()).toBe(1);
        expect(await page.locator(".katex").count()).toBeGreaterThan(1);
        expect(await page.getByText(/aren't supported|unsupported/i).count()).toBe(0);
        // Every built-in theme paints its own page: the dark variants are really dark.
        const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
        expect(bg).toBe(hexToRgb((dark ? theme.dark : theme.light).bg));
        await page.close();
    });

    it.each(THEMES)("meets WCAG AA for body, heading, link and code text (AAA in Contrast): $label", async (b) => {
        const { id, dark } = b;
        const page = await openPreview(h, { width: 412, height: 7000 });
        await applyTheme(page, b, dark);
        await loadMarkdown(page, `${corpusDocument()}\n\n${SYNTAX}`);
        await diagramsSettled(page);
        await fontsReady(page, b);
        const bar = id === "contrast" ? AAA : AA;
        const samples = await contrasts(page);
        const roles = samples.map((s) => s.role);
        for (const role of ["body", "heading", "link", "inline code", "code block"]) expect(roles).toContain(role);
        const failures = samples.filter((s) => s.ratio < (s.large ? bar.large : bar.normal));
        const report = samples.map((s) => `${s.role} ${s.ratio.toFixed(2)} (${s.selector} ${s.fg} on ${s.bg})`);
        expect(failures, report.join("\n")).toEqual([]);
        await page.close();
    });
});

describe("line length", () => {
    it.each(BUILT_INS)("holds 45-90 characters per line at the default size on a 390 px phone: $id", async (b) => {
        const { theme } = b;
        const page = await openPreview(h, { width: 390, height: 844 });
        await applyTheme(page, b, false);
        await loadMarkdown(page, PROSE);
        await fontsReady(page, b);
        const cpl = await charactersPerLine(page);
        console.log(`line length: ${theme.name} ${cpl.toFixed(1)} characters per line`);
        expect(cpl, `${theme.name}: ${cpl.toFixed(1)} characters per line`).toBeGreaterThanOrEqual(45);
        expect(cpl).toBeLessThanOrEqual(90);
        await page.close();
    });

    it.each(BUILT_INS)("holds 45-90 characters per line on the 170 mm A4 column: $id", async (b) => {
        const { theme } = b;
        const page = await openPreview(h, { width: 390, height: 844 });
        await applyTheme(page, b, false);
        await send(page, {
            type: "settings",
            settings: { tapToEdit: true, pageMode: true, zoom: 1, lean: false, readOnly: false },
        });
        await loadMarkdown(page, PROSE);
        await fontsReady(page, b);
        // The text column is A4 less 20 mm margins in every theme: 170 mm. Prose
        // stops short of it at a readable measure; tables and code use all of it.
        const column = await page.evaluate(() => {
            const doc = document.querySelector<HTMLElement>(".doc");
            if (!doc) return 0;
            const style = getComputedStyle(doc);
            return doc.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
        });
        expect(column).toBeCloseTo((170 / 25.4) * 96, 0);
        const cpl = await charactersPerLine(page);
        console.log(`line length: ${theme.name} ${cpl.toFixed(1)} characters per line`);
        expect(cpl, `${theme.name}: ${cpl.toFixed(1)} characters per line`).toBeGreaterThanOrEqual(45);
        expect(cpl, `${theme.name}: ${cpl.toFixed(1)} characters per line`).toBeLessThanOrEqual(90);
        await page.close();
    });
});

/** One document of every rendering corpus file. */
function corpusDocument(): string {
    return FILES.map((f) => corpus(f).replace(/\r\n/g, "\n")).join("\n\n");
}

function hexToRgb(hex: string): string {
    const n = parseInt(hex.replace("#", ""), 16);
    return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}
