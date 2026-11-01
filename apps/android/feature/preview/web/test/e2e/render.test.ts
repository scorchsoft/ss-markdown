import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import {
    corpus,
    diagramsSettled,
    loadMarkdown,
    openPreview,
    pageErrors,
    send,
    startHarness,
    waitForMessage,
    type Harness,
} from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

async function count(page: Page, selector: string): Promise<number> {
    return page.locator(selector).count();
}

describe("rendering the corpus", () => {
    it("renders CommonMark and GFM basics", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, corpus("basics.md"));
        expect(await page.locator("h1").first().textContent()).toBe("Basics heading");
        for (const sel of [
            "strong",
            "em",
            "s",
            "p > code",
            "a[href='https://example.com']",
            "a[href='https://scorchsoft.com']",
            "ul ul",
            "ol",
            "blockquote",
            "hr",
            "br",
            "th",
            "td",
        ]) {
            expect(await count(page, sel), sel).toBeGreaterThan(0);
        }
        expect(await count(page, "pre code.hljs.language-kotlin .hljs-keyword")).toBeGreaterThan(0);
        expect(await page.locator("img").getAttribute("src")).toBe("/doc/pictures/cat.png");
        expect(await page.locator("h2#setext-heading").count()).toBe(1);
        expect(pageErrors(page)).toEqual([]);
    });

    it("renders every extension", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, corpus("extended.md"));
        await diagramsSettled(page);
        expect(await count(page, ".ro-front-matter")).toBe(1);
        expect(await count(page, "nav.toc a")).toBe(9);
        expect(await page.locator(".footnote-ref a").allTextContents()).toEqual(["1", "2"]);
        expect(await count(page, ".footnote-def")).toBe(2);
        expect(await page.locator("a[title='Reference']").getAttribute("href")).toBe("https://example.com/ref");
        for (const sel of [
            "sub",
            "sup",
            "mark",
            "abbr[title='Hyper Text Markup Language']",
            "dl dt",
            "dl dd",
            ".markdown-alert-note",
            ".markdown-alert-warning",
            ".math-inline .katex",
            ".math-block .katex-display",
            "details summary",
        ]) {
            expect(await count(page, sel), sel).toBeGreaterThan(0);
        }
        expect(await page.locator("p", { hasText: "marked" }).first().textContent()).toContain("😄");
        expect(await count(page, "input.task-list-item-checkbox")).toBe(2);
        expect(await count(page, "input.task-list-item-checkbox:checked")).toBe(1);
        expect(await count(page, ".diagram-svg svg")).toBe(1);
        expect(await page.locator(".diagram-svg svg text, .diagram-svg svg tspan").allTextContents()).toContain(
            "Start",
        );
        expect(pageErrors(page)).toEqual([]);
    });

    it("anchors every block to its source line", async () => {
        for (const file of ["basics.md", "extended.md", "tables.md", "unicode.md"]) {
            const text = corpus(file);
            const lines = text.split("\n");
            const page = await openPreview(h);
            await loadMarkdown(page, text);
            const blocks = await page.$$eval("section.sec.mounted > [data-line]", (els) =>
                els.map((e) => ({
                    tag: e.tagName,
                    line: Number((e as HTMLElement).dataset.line),
                    end: Number((e as HTMLElement).dataset.lineEnd),
                    text: e.textContent ?? "",
                })),
            );
            expect(blocks.length).toBeGreaterThan(3);
            for (const b of blocks) {
                expect(Number.isInteger(b.line), `${file} ${b.tag}`).toBe(true);
                expect(b.end).toBeGreaterThanOrEqual(b.line);
                if (/^H[1-6]$/.test(b.tag)) {
                    const src = lines[b.line] ?? "";
                    expect(
                        src.includes(b.text.trim().slice(0, 4)) || (lines[b.line + 1] ?? "").startsWith("-"),
                        `${file}:${b.line} ${b.text}`,
                    ).toBe(true);
                }
                if (b.tag === "TABLE") expect(lines[b.line]).toContain("|");
            }
            await page.close();
        }
    });

    it("keeps offsets right for CRLF files", async () => {
        const page = await openPreview(h);
        const text = corpus("crlf.md");
        await loadMarkdown(page, text);
        const runs = await page.$$eval("section.sec [data-s]", (els) =>
            els.map((e) => ({
                abs: Number(e.closest<HTMLElement>("section")?.dataset.offset) + Number((e as HTMLElement).dataset.s),
                len: Number((e as HTMLElement).dataset.e) - Number((e as HTMLElement).dataset.s),
                text: e.textContent ?? "",
            })),
        );
        for (const r of runs) expect(text.slice(r.abs, r.abs + r.len)).toBe(r.text);
    });

    it("caps hostile diagrams with an inline error and keeps the page", async () => {
        const page = await openPreview(h);
        const edges = `  ${Array.from({ length: 600 }, (_, i) => `N${i}`).join(" --> ")}`;
        await loadMarkdown(
            page,
            `# Diagrams\n\n\`\`\`mermaid\ngraph TD\n${edges}\n\`\`\`\n\n\`\`\`mermaid\nmindmap\n  root\n    a\n\`\`\`\n\n\`\`\`mermaid\nnot a diagram at all\n\`\`\`\n\nStill here.`,
        );
        await diagramsSettled(page);
        expect(await count(page, ".diagram-error")).toBe(3);
        expect(await page.locator(".diagram-error").first().textContent()).toContain("too many connections");
        expect(await page.locator("p", { hasText: "Still here." }).count()).toBe(1);
    });

    it("exports a diagram as SVG and as a 2x PNG", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "# D\n\n```mermaid\ngraph LR\n  A --> B\n```\n");
        await diagramsSettled(page);
        await send(page, { type: "exportImage", requestId: 7, line: 2, format: "svg", scale: 1 });
        const svg = await waitForMessage(page, (m) => m.type === "exportResult" && m.requestId === 7);
        expect(svg).toMatchObject({ mime: "image/svg+xml" });
        expect((svg as { data: string }).data).toMatch(/^<svg[^>]+xmlns="http:\/\/www.w3.org\/2000\/svg"/);
        await send(page, { type: "exportImage", requestId: 8, line: 2, format: "png", scale: 2 });
        const png = await waitForMessage(page, (m) => m.type === "exportResult" && m.requestId === 8);
        const bytes = Buffer.from((png as { data: string }).data, "base64");
        expect(bytes.subarray(1, 4).toString()).toBe("PNG");
        const width = bytes.readUInt32BE(16);
        const cssWidth = await page.locator(".diagram-svg svg").evaluate((e) => e.getBoundingClientRect().width);
        expect(Math.abs(width - Math.round(cssWidth * 2))).toBeLessThanOrEqual(1);
    });

    it("applies a theme and its dark variant", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "# Theme\n\nText.");
        await page.evaluate(() =>
            (window as unknown as { __port: MessagePort }).__port.postMessage(
                JSON.stringify({ type: "theme", theme: { name: "Reader", base: "reader" }, dark: true }),
            ),
        );
        await page.waitForFunction(() => document.documentElement.dataset.dark === "true");
        const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
        expect(bg).toBe("rgb(30, 27, 23)");
        const font = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
        expect(font).toContain("Lora");
    });
});
