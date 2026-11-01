import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { splitSections } from "../../src/core/sections";
import { CrMap, isMarkupOnly, matchRun } from "../../src/core/sourceMap";
import { createMarkdown, renderSection } from "../../src/render/markdown";

const CORPUS = resolve(__dirname, "../corpus");
const md = createMarkdown();

/** Every `<span data-s data-e>` or `<code data-s data-e>` and its decoded text. */
function runs(html: string): { s: number; e: number; text: string }[] {
    const out: { s: number; e: number; text: string }[] = [];
    const re = /<(span|code)[^>]*? data-s="(\d+)" data-e="(\d+)"[^>]*>([\s\S]*?)<\/\1>/g;
    for (const m of html.matchAll(re)) {
        const inner = (m[4] ?? "").replace(/<[^>]+>/g, "");
        const text = inner
            .replace(/&lt;/g, "<")
            .replace(/&gt;/g, ">")
            .replace(/&quot;/g, '"')
            .replace(/&amp;/g, "&");
        out.push({ s: Number(m[2]), e: Number(m[3]), text });
    }
    return out;
}

describe("matchRun", () => {
    it("is exact only when the skipped source is markup", () => {
        expect(matchRun("**bold**", 0, "bold")).toEqual({ from: 2, to: 6, exact: true });
        expect(matchRun("[a](xyz.png) b", 0, "b")?.exact).toBe(false);
        expect(isMarkupOnly("- [x] ")).toBe(true);
    });

    it("matches escapes and entities as inexact runs", () => {
        expect(matchRun("a \\* b", 0, "a * b")).toEqual({ from: 0, to: 6, exact: false });
        expect(matchRun("x &amp; y", 0, "x & y")).toEqual({ from: 0, to: 9, exact: false });
    });

    it("maps offsets back over CRLF", () => {
        const map = new CrMap("ab\r\ncd\r\nef");
        expect(map.toRaw(0)).toBe(0);
        expect(map.toRaw(3)).toBe(4);
        expect(map.toRaw(6)).toBe(8);
    });
});

describe("inline source map over the corpus", () => {
    for (const file of readdirSync(CORPUS).filter((f) => f.endsWith(".md"))) {
        it(`every editable run in ${file} is an exact copy of its source`, () => {
            const text = readFileSync(resolve(CORPUS, file), "utf8");
            const lines = text.split("\n");
            const defs = collectDefinitions(lines);
            let checked = 0;
            for (const section of splitSections(lines)) {
                const src = lines.slice(section.startLine, section.endLine).join("\n");
                const { html } = renderSection(md, src, defs, section.startLine === 0);
                for (const run of runs(html)) {
                    expect(src.slice(run.s, run.e)).toBe(run.text);
                    checked++;
                }
            }
            expect(checked).toBeGreaterThan(0);
        });
    }

    it("keeps offsets in raw bytes for CRLF files", () => {
        const src = "first **bold**\r\nsecond line\r\n";
        const { html } = renderSection(md, src, collectDefinitions([]));
        for (const run of runs(html)) expect(src.slice(run.s, run.e)).toBe(run.text);
    });

    it("gives every block relative line attributes", () => {
        const { html } = renderSection(md, "# H\n\npara\n\n- a\n- b\n", collectDefinitions([]));
        expect(html).toMatch(/<h1[^>]*data-rl="0" data-rle="1"/);
        expect(html).toMatch(/<p[^>]*data-rl="2" data-rle="3"/);
        expect(html).toMatch(/<ul[^>]*data-rl="4" data-rle="6"/);
    });

    it("neutralises page attributes forged in raw HTML", () => {
        const { html } = renderSection(
            md,
            'x <span data-s="0" data-e="9" contenteditable>y</span>',
            collectDefinitions([]),
        );
        expect(html).toContain("data-user-s=");
        expect(html).toContain("data-user-contenteditable");
    });

    it("drops unsafe link schemes", () => {
        const { html } = renderSection(
            md,
            "[a](javascript:alert(1)) [b](file:///etc) [c](content://x) [d][r]\n\n[r]: javascript:bad",
            collectDefinitions(["[r]: javascript:bad"]),
        );
        expect(html).not.toMatch(/href="(javascript|file|content):/);
    });
});
