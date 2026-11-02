/**
 * "Copy formatted text" in Chromium, through the bridge as the app asks
 * for it: the HTML has inline styles only, in light colours even under a
 * dark theme; pictures next to the file are embedded within the caps or
 * stand in as alt text; Mermaid is a PNG; maths is text; the plain-text
 * twin has no Markdown symbols; a copy over budget stops and says so.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { Page } from "playwright";
import type { PageMessage, ThemeFonts, ThemeSpec } from "../../src/protocol";
import {
    clearReceived,
    corpus,
    loadMarkdown,
    openPreview,
    send,
    startHarness,
    waitForMessage,
    type Harness,
} from "./harness";

type HtmlResult = Extract<PageMessage, { type: "htmlResult" }>;

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

let nextId = 100;

async function copy(page: Page, fromLine: number, toLine: number, maxChars = 400_000): Promise<HtmlResult> {
    const requestId = nextId++;
    await clearReceived(page);
    await send(page, { type: "exportHtml", requestId, fromLine, toLine, maxChars });
    return (await waitForMessage(
        page,
        (m) => m.type === "htmlResult" && m.requestId === requestId,
        30_000,
    )) as HtmlResult;
}

/** A PNG the page draws itself: [size] pixels square, noise when [noisy] (it does not compress). */
async function png(page: Page, size: number, noisy: boolean): Promise<Buffer> {
    const b64 = await page.evaluate(
        ({ size, noisy }) => {
            const c = document.createElement("canvas");
            c.width = size;
            c.height = size;
            const ctx = c.getContext("2d");
            if (!ctx) return "";
            const img = ctx.createImageData(size, size);
            for (let i = 0; i < img.data.length; i++) img.data[i] = noisy ? Math.floor(Math.random() * 256) : 200;
            ctx.putImageData(img, 0, 0);
            return c.toDataURL("image/png").split(",")[1] ?? "";
        },
        { size, noisy },
    );
    return Buffer.from(b64, "base64");
}

/** Serves [files] at `/doc/<name>`, as the app's document path handler does. */
async function serveDoc(page: Page, files: Record<string, Buffer>): Promise<void> {
    await page.route("**/doc/**", async (route) => {
        const name = decodeURIComponent(new URL(route.request().url()).pathname.replace(/^\/doc\//, ""));
        const body = files[name];
        await (body ? route.fulfill({ status: 200, contentType: "image/png", body }) : route.fulfill({ status: 404 }));
    });
}

const LIGHT = new Set(["#1f2328", "#57606a", "#d0d7de", "#f6f8fa", "#eff1f3", "#0969da", "#fff3a3"]);
const ALERTS = new Set(["#1a7f37", "#8250df", "#9a6700", "#cf222e"]);

describe("copy formatted text", () => {
    it("is inline-styled light HTML under a dark theme, with tasks, maths and footnotes readable", async () => {
        const page = await openPreview(h);
        const themes = JSON.parse(readFileSync(resolve(__dirname, "../vectors/themes.json"), "utf8")) as {
            themes: { theme: ThemeSpec; fonts: ThemeFonts }[];
        };
        const dark = themes.themes[0];
        if (!dark) throw new Error("no theme vector");
        await send(page, { type: "theme", theme: dark.theme, dark: true, fonts: { ...dark.fonts, faces: "" } });
        await page.waitForFunction(() => document.documentElement.dataset.dark === "true");
        const text = corpus("extended.md");
        await loadMarkdown(page, text);
        const result = await copy(page, 0, text.split("\n").length);
        const html = result.html ?? "";
        expect(result.truncated).toBe(false);
        expect(html).not.toMatch(/\bclass=|\bid=|data-|<style|<script|<input|<button|<svg|<math|katex|var\(/);
        for (const colour of html.match(/#[0-9a-f]{6}\b/gi) ?? []) {
            expect(LIGHT.has(colour.toLowerCase()) || ALERTS.has(colour.toLowerCase())).toBe(true);
        }
        expect(html).toContain("☐ Open task");
        expect(html).toContain("☑ Done task");
        expect(html).toContain(">E = mc²</span>");
        expect(html).toContain("∫₀¹x² dx = 1/3");
        expect(html).not.toContain("tags: [test]");
        expect(html).toMatch(/<img(?=[^>]*src="data:image\/png;base64,[^"]+")(?=[^>]*alt="Diagram: flowchart")/);
        const plain = result.text ?? "";
        expect(plain.startsWith("Extended syntax\n\n")).toBe(true);
        expect(plain).toContain("☐ Open task\n☑ Done task");
        expect(plain).toContain("[Diagram: flowchart]");
        expect(plain).not.toMatch(/^#|\*\*|^```/m);
    });

    it("answers for just the asked lines", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "# One\n\nAlpha **bold**.\n\n## Two\n\nBeta.\n");
        const result = await copy(page, 2, 5);
        expect(result.html).toContain("<strong>bold</strong>");
        expect(result.html).toContain("Two");
        expect(result.html).not.toContain("One");
        expect(result.html).not.toContain("Beta");
        expect(result.text).toBe("Alpha bold.\n\nTwo");
    });

    it("embeds pictures next to the file within the caps, else their alt text; web pictures stay links", async () => {
        const page = await openPreview(h);
        await serveDoc(page, { "small.png": await png(page, 40, false), "noise.png": await png(page, 1600, true) });
        await loadMarkdown(
            page,
            "![Small](small.png)\n\n![Noise](noise.png)\n\n![Missing](missing.png)\n\n![Web](https://example.com/a.png)\n",
        );
        const result = await copy(page, 0, 8);
        const html = result.html ?? "";
        expect(html).toMatch(/<img(?=[^>]*src="data:image\/png;base64,[^"]+")(?=[^>]*alt="Small")(?=[^>]*width="40")/);
        // Noise does not compress: it is stepped down to a JPEG that fits, or left as its alt text.
        expect(html).toMatch(/<img(?=[^>]*src="data:image\/jpeg;base64,)(?=[^>]*alt="Noise")|\[Noise\]/);
        for (const src of html.match(/src="data:[^"]+"/g) ?? []) expect(src.length).toBeLessThanOrEqual(200_010);
        expect(html).toContain("[Missing]");
        expect(html).toContain(
            '<a href="https://example.com/a.png" style="color:#0969da;text-decoration:underline">[Web]</a>',
        );
        expect(result.text).toBe("[Small]\n\n[Noise]\n\n[Missing]\n\n[Web] (https://example.com/a.png)");

        const tight = await copy(page, 2, 4, 3_000);
        expect(tight.html).toContain("[Noise]");
        expect(tight.html).not.toContain("data:");
    });

    it("stops a copy over budget, says so, and stays within it", async () => {
        const page = await openPreview(h);
        const text = Array.from({ length: 400 }, (_, i) => `Paragraph ${i} with some words in it.`).join("\n\n");
        await loadMarkdown(page, text);
        const result = await copy(page, 0, 800, 10_000);
        expect(result.truncated).toBe(true);
        expect((result.html ?? "").length + (result.text ?? "").length).toBeLessThanOrEqual(10_000);
        expect(result.html).toContain("Paragraph 0 ");
        expect(result.html).not.toContain("Paragraph 399");
        expect(
            result.text?.endsWith("[Shortened to fit the clipboard. Copy a section, or share the file, for the rest.]"),
        ).toBe(true);
    });
});
