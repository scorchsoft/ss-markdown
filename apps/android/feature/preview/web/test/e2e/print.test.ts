import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import { getDocument } from "pdfjs-dist/legacy/build/pdf.mjs";
import { loadMarkdown, openPreview, send, startHarness, waitForMessage, type Harness } from "./harness";

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

async function printToPdf(page: Page): Promise<{ items: TextItem[]; pages: { width: number; height: number }[] }> {
    await send(page, { type: "preparePrint" });
    await waitForMessage(page, (m) => m.type === "printReady", 30_000);
    const pdf = await page.pdf({ preferCSSPageSize: true, printBackground: true });
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

describe("print and PDF", () => {
    it("uses the print typography table in physical units, light even in dark mode", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, doc());
        await send(page, { type: "theme", theme: { name: "Clean" }, dark: true });
        await send(page, {
            type: "settings",
            settings: { tapToEdit: true, pageMode: false, zoom: 2, lean: false, readOnly: false },
        });
        await send(page, { type: "preparePrint" });
        await waitForMessage(page, (m) => m.type === "printReady", 30_000);
        await page.emulateMedia({ media: "print" });
        const sizes = await page.evaluate(() => {
            const px = (sel: string): string => getComputedStyle(document.querySelector(sel) as Element).fontSize;
            return {
                body: px("body"),
                h1: px("h1"),
                h2: px("h2"),
                td: px("td"),
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
        expect(sizes.h1).toBe(pt(20));
        expect(sizes.h2).toBe(pt(16));
        expect(sizes.td).toBe(pt(9.5));
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
        const firstOnPage = Math.min(...items.filter((i) => i.page === 1).map((i) => i.y - i.size));
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
});
