import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import { mkdirSync, writeFileSync } from "node:fs";
import { getDocument } from "pdfjs-dist/legacy/build/pdf.mjs";
import {
    clearReceived,
    loadMarkdown,
    openPreview,
    received,
    send,
    startHarness,
    waitForMessage,
    diagramsSettled,
    type Harness,
} from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

const MM = 72 / 25.4;

interface TextItem {
    readonly str: string;
    readonly x: number;
    readonly y: number;
    readonly width: number;
    readonly size: number;
    readonly page: number;
}

async function printToPdf(
    page: Page,
    save?: string,
): Promise<{ items: TextItem[]; pages: { width: number; height: number }[] }> {
    await send(page, { type: "preparePrint", requestId: 1 });
    await waitForMessage(page, (m) => m.type === "printReady" && m.requestId === 1, 30_000);
    const pdf = await page.pdf({ preferCSSPageSize: true, printBackground: true });
    if (save) {
        // Kept for looking at (pdftoppm), like the theme screenshots; never compared byte for byte.
        mkdirSync("test-results/print", { recursive: true });
        writeFileSync(`test-results/print/${save}.pdf`, pdf);
    }
    const doc = await getDocument({ data: new Uint8Array(pdf), useSystemFonts: false }).promise;
    const items: TextItem[] = [];
    const pages: { width: number; height: number }[] = [];
    for (let p = 1; p <= doc.numPages; p++) {
        const pg = await doc.getPage(p);
        const vp = pg.getViewport({ scale: 1 });
        pages.push({ width: vp.width, height: vp.height });
        const content = await pg.getTextContent();
        for (const it of content.items) {
            if (!("str" in it) || it.str.trim().length === 0) continue;
            const [a, b, , , x, y] = it.transform as number[];
            items.push({
                str: it.str,
                x: x ?? 0,
                y: vp.height - (y ?? 0),
                width: it.width,
                size: Math.hypot(a ?? 0, b ?? 0),
                page: p,
            });
        }
    }
    return { items: joinLines(items), pages };
}

/** pdf.js splits text at font fallbacks; join runs on one baseline into lines. */
function joinLines(items: TextItem[]): TextItem[] {
    const out: TextItem[] = [];
    for (const it of items) {
        const last = out[out.length - 1];
        if (last && last.page === it.page && Math.abs(last.y - it.y) < 0.5 && Math.abs(last.size - it.size) < 0.2) {
            out[out.length - 1] = { ...last, str: last.str + it.str, width: it.x + it.width - last.x };
        } else {
            out.push(it);
        }
    }
    return out;
}

function doc(): string {
    const rows = Array.from({ length: 120 }, (_, i) => `| R${i}C1 | R${i}C2 |`).join("\n");
    const paras = Array.from(
        { length: 12 },
        (_, i) => `Body paragraph ${i} with enough words to fill a printed line or two of text at eleven points.`,
    ).join("\n\n");
    const code = Array.from({ length: 6 }, (_, i) => `const line${i} = "${"x".repeat(150)}";`).join("\n");
    return `# Print title\n\n${paras}\n\n\`\`\`ts\n${code}\n\`\`\`\n\n## Data\n\n| Col one | Col two |\n| --- | --- |\n${rows}\n\n\`\`\`mermaid\ngraph TD\n  A --> B\n\`\`\`\n\nThe end.`;
}

/** Shaped like Andrew's exported transcript: a title, a chapter list, then timed sections of prose. */
function transcript(): string {
    const sentence =
        "Same team, same afternoon's work, almost the same edit, and one customer will not buy it at any price while another thanks us for it.";
    const chapters = Array.from({ length: 12 }, (_, i) => `- ${i}:00 Chapter ${i}`).join("\n");
    const sections = Array.from({ length: 12 }, (_, i) => {
        const paras = Array.from({ length: 3 }, (_, j) => `[${i}:${10 + j}] ${sentence} ${sentence}`).join("\n\n");
        return `## Section ${i} (${i}:00)\n\n${paras}`;
    }).join("\n\n");
    return `# Problem First, a transcript\n\n*Internal training: how we position, price and package what we sell.*\n\n## Chapters\n\n${chapters}\n\n${sections}\n`;
}

describe("print and PDF", () => {
    it("uses the print typography table in physical units, light even in dark mode", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, doc());
        await send(page, { type: "theme", theme: { name: "Clean" }, dark: true });
        await send(page, {
            type: "settings",
            settings: { tapToEdit: true, pageMode: false, zoom: 2, lean: false, readOnly: false },
        });
        await send(page, { type: "preparePrint", requestId: 3 });
        await waitForMessage(page, (m) => m.type === "printReady" && m.requestId === 3, 30_000);
        await page.emulateMedia({ media: "print" });
        const sizes = await page.evaluate(() => {
            const px = (sel: string): string => getComputedStyle(document.querySelector(sel) as Element).fontSize;
            return {
                body: px("body"),
                h1: px("h1"),
                h2: px("h2"),
                td: px("td"),
                code: px("pre.code-block code"),
                lineHeight: getComputedStyle(document.body).lineHeight,
                pMaxWidth: getComputedStyle(document.querySelector("p") as Element).maxWidth,
                pMargin: getComputedStyle(document.querySelector("p") as Element).marginBottom,
                h2Margins: [
                    getComputedStyle(document.querySelector("h2") as Element).marginTop,
                    getComputedStyle(document.querySelector("h2") as Element).marginBottom,
                ],
                bg: getComputedStyle(document.body).backgroundColor,
                text: getComputedStyle(document.body).color,
                tr: getComputedStyle(document.querySelector("tr") as Element).breakInside,
                diagram: getComputedStyle(document.querySelector(".diagram") as Element).breakInside,
                h2Break: getComputedStyle(document.querySelector("h2") as Element).breakAfter,
                afterH2: getComputedStyle(
                    document.querySelector("h2")?.closest("section")?.nextElementSibling as Element,
                ).breakBefore,
                pre: getComputedStyle(document.querySelector("pre.code-block") as Element).breakInside,
                // The page margins (the host's print attributes, or @page in Chromium) are the only margins:
                // nothing on the page adds its own, or the column would sit off centre.
                offsets: ["html", "body", ".doc"].map((sel) => {
                    const s = getComputedStyle(document.querySelector(sel) as Element);
                    return [s.marginLeft, s.marginRight, s.paddingLeft, s.paddingRight].join(" ");
                }),
            };
        });
        const pt = (n: number): string => `${Number(((n * 96) / 72).toFixed(4))}px`;
        expect(sizes.body).toBe(pt(11));
        // A word processor's A4 page (Andrew, 0.2.1): 11 pt at 1.35, 6 pt between paragraphs,
        // headings 18 / 14 / 12 pt with 12 pt above and 4 pt below, the full text width.
        expect(sizes.h1).toBe(pt(18));
        expect(sizes.h2).toBe(pt(14));
        expect(sizes.td).toBe(pt(10));
        expect(sizes.code).toBe(pt(9.5));
        expect(parseFloat(sizes.lineHeight)).toBeCloseTo(11 * 1.35 * (96 / 72), 1);
        expect(sizes.pMaxWidth).not.toMatch(/ch|em/);
        expect(sizes.pMargin).toBe(pt(6));
        expect(parseFloat(sizes.h2Margins[0] ?? "")).toBeCloseTo((12 * 96) / 72, 2);
        expect(parseFloat(sizes.h2Margins[1] ?? "")).toBeCloseTo((4 * 96) / 72, 2);
        expect(sizes.bg).toBe("rgb(255, 255, 255)");
        expect(sizes.text).toBe("rgb(31, 35, 40)");
        expect(sizes.tr).toBe("avoid");
        expect(sizes.diagram).toBe("avoid");
        expect(sizes.h2Break).toBe("avoid-page");
        expect(sizes.afterH2).toBe("avoid-page");
        expect(sizes.pre).toBe("avoid");
        expect(sizes.offsets).toEqual(["0px 0px 0px 0px", "0px 0px 0px 0px", "0px 0px 0px 0px"]);
    });

    it("prints A4 with 11 pt body text, 20 mm margins and no split table rows", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, doc());
        const { items, pages } = await printToPdf(page);
        expect(pages.length).toBeGreaterThan(1);
        for (const p of pages) {
            expect(Math.abs(p.width - 210 * MM)).toBeLessThan(1);
            expect(Math.abs(p.height - 297 * MM)).toBeLessThan(1);
        }
        // pdf.js drops inter-word spaces from Chromium's PDFs, so match without them.
        const body = items.filter((i) => i.str.replace(/\s/g, "").startsWith("Bodyparagraph"));
        expect(body.length).toBeGreaterThan(0);
        for (const i of body) expect(Math.abs(i.size - 11)).toBeLessThan(0.2);
        const left = Math.min(...items.map((i) => i.x));
        expect(Math.abs(left - 20 * MM)).toBeLessThan(1.5);
        // Symmetric: nothing, not even a long code line, runs into the right-hand 20 mm.
        const right = Math.max(...items.map((i) => i.x + i.width));
        expect(right).toBeLessThanOrEqual(190 * MM + 0.5);
        // The top of the tallest glyphs: an ascender is about 0.9 em above the baseline.
        const firstOnPage = Math.min(...items.filter((i) => i.page === 1).map((i) => i.y - 0.9 * i.size));
        expect(firstOnPage).toBeGreaterThanOrEqual(20 * MM - 1);
        for (let r = 0; r < 120; r++) {
            const a = items.find((i) => i.str.includes(`R${r}C1`));
            const b = items.find((i) => i.str.includes(`R${r}C2`));
            expect(a?.page, `row ${r}`).toBe(b?.page);
        }
        const pageNumbers = items.filter((i) => /^\d+$/.test(i.str.trim()) && i.y > 297 * MM - 15 * MM);
        expect(pageNumbers.length).toBe(pages.length);
        for (const n of pageNumbers) expect(Math.abs(n.size - 8)).toBeLessThan(0.2);
    });

    // Andrew, 2026-11-04: a diagram taller than a page was sliced across the page foot, with a
    // blank page before it. Tall blocks of every kind keep to the text box, and no page is empty.
    it("keeps tall blocks inside the margins: a tall diagram fits one page, and no page is blank", async () => {
        const page = await openPreview(h);
        const nodes = Array.from({ length: 30 }, (_, j) => `  N${j} --> N${j + 1}`).join("\n");
        const list = Array.from({ length: 70 }, (_, j) => `- List item ${j}`).join("\n");
        const quote = Array.from({ length: 60 }, (_, j) => `> Quote line ${j}.`).join("\n>\n");
        const code = Array.from({ length: 90 }, (_, j) => `code line ${j}`).join("\n");
        await loadMarkdown(
            page,
            `# Tall\n\n${list}\n\n${quote}\n\n\`\`\`\n${code}\n\`\`\`\n\n\`\`\`mermaid\ngraph TD\n${nodes}\n\`\`\`\n\nAfter.`,
        );
        await diagramsSettled(page);
        const { items, pages } = await printToPdf(page, "tall-blocks");
        const isPageNumber = (i: TextItem): boolean => /^\d+$/.test(i.str.trim()) && Math.abs(i.size - 8) < 0.2;
        const text = items.filter((i) => !isPageNumber(i));
        // Nothing reaches into the 15 mm at the foot (a descender is about 0.25 em below the baseline).
        for (const i of text)
            expect(i.y + 0.25 * i.size, `"${i.str}" on page ${i.page}`).toBeLessThanOrEqual(282 * MM + 0.5);
        // Every page prints something besides its number (the diagram's labels are text too).
        for (let p = 1; p <= pages.length; p++)
            expect(
                text.some((i) => i.page === p),
                `page ${p} is blank`,
            ).toBe(true);
        // The diagram is one picture on one page: its first and last nodes print together.
        const first = text.find((i) => i.str.trim() === "N0");
        const last = text.find((i) => i.str.trim() === "N30");
        if (first && last) expect(first.page).toBe(last.page);
        const svg = await page.evaluate(() => {
            const el = document.querySelector(".diagram-svg svg");
            return el ? getComputedStyle(el).maxHeight : null;
        });
        expect(svg).not.toBeNull();
    });

    // Andrew, 0.2.1: his transcript printed about 12 words a line and 25-30 lines a page, with a
    // quarter of the A4 width empty, against Word's 16 words and 45 lines at 11 pt.
    it("sets a transcript like a word processor: the full width and at least 40 lines a page", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, transcript());
        await send(page, { type: "theme", theme: { name: "Clean" }, dark: true });
        await send(page, {
            type: "settings",
            settings: { tapToEdit: true, pageMode: false, zoom: 1.6, lean: false, readOnly: false },
        });
        const { items } = await printToPdf(page, "transcript");
        const body = items.filter((i) => Math.abs(i.size - 11) < 0.2 && i.str.replace(/\s/g, "").length > 40);
        expect(body.length).toBeGreaterThan(0);
        // Prose runs the whole 170 mm column: the longest lines end within a word of the right margin.
        const right = Math.max(...body.map((i) => i.x + i.width));
        expect(right).toBeGreaterThan(180 * MM);
        expect(right).toBeLessThanOrEqual(190 * MM + 0.5);
        // Most prose lines fill the column, as Word sets 11 pt on A4. Measured as width, not letters, so
        // it holds whatever font the machine renders with: the old 39 em measure capped every line at
        // about 151 mm (11 pt x 39), so none passed 160 mm.
        const wide = body.filter((i) => i.width > 160 * MM).length;
        expect(wide / body.length).toBeGreaterThanOrEqual(0.6);

        // Plain paragraphs fill a page with at least 40 lines (Word: about 45; it was 25-30).
        const plain = Array.from(
            { length: 40 },
            (_, i) => `[${i}:00] ${"Same team, same afternoon, almost the same edit. ".repeat(4)}`,
        );
        await loadMarkdown(page, plain.join("\n\n"));
        const full = await printToPdf(page);
        expect(full.pages.length).toBeGreaterThan(1);
        for (let p = 1; p < full.pages.length; p++) {
            const lines = new Set(
                full.items.filter((i) => i.page === p && Math.abs(i.size - 11) < 0.2).map((i) => Math.round(i.y)),
            );
            expect(lines.size, `page ${p}`).toBeGreaterThanOrEqual(40);
        }
    });

    it("gives the A4 page mode the print typography", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, transcript());
        const settings = { tapToEdit: true, pageMode: true, zoom: 1.6, lean: false, readOnly: false };
        await send(page, { type: "settings", settings });
        await page.waitForSelector("h2");
        const measure = (): Promise<string[]> =>
            page.evaluate(() =>
                ["body", "h1", "h2", "p"].flatMap((sel) => {
                    const s = getComputedStyle(document.querySelector(sel) as Element);
                    return [s.fontSize, s.lineHeight, s.marginTop, s.marginBottom, s.maxWidth];
                }),
            );
        const screen = await measure();
        await send(page, { type: "settings", settings: { ...settings, pageMode: false } });
        await page.emulateMedia({ media: "print" });
        expect(await measure()).toEqual(screen);
    });

    // edge-cases "Export PDF from Code did nothing, then printed the next file": a print
    // prepared for one document is never answered once the page holds another.
    it("abandons a print preparation when another document loads, and answers only the request that asked", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, doc());
        await clearReceived(page);
        await send(page, { type: "preparePrint", requestId: 5 });
        await send(page, { type: "load", rev: 1, text: "# Document B\n\nOnly B." });
        await waitForMessage(page, (m) => m.type === "rendered");
        await send(page, { type: "preparePrint", requestId: 6 });
        await waitForMessage(page, (m) => m.type === "printReady" && m.requestId === 6, 30_000);
        // Give an abandoned preparation every chance to answer late.
        await page.waitForTimeout(500);
        const ready = (await received(page)).filter((m) => m.type === "printReady");
        expect(ready).toEqual([{ type: "printReady", requestId: 6 }]);
        expect(await page.evaluate(() => document.querySelector("h1")?.textContent)).toContain("Document B");
    });

    it("abandons a print preparation when the app finishes printing first", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, doc());
        await clearReceived(page);
        await send(page, { type: "preparePrint", requestId: 7 });
        await send(page, { type: "finishPrint" });
        await page.waitForTimeout(1_500);
        expect((await received(page)).filter((m) => m.type === "printReady")).toEqual([]);
        expect(await page.evaluate(() => document.documentElement.classList.contains("printing"))).toBe(false);
    });
});
