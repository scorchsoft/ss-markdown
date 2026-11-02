/**
 * "Copy formatted text" on the renderer's output, without a browser (the
 * Playwright twin in e2e/bridge.test.ts embeds pictures and draws diagrams):
 * the HTML has inline styles only, in light colours, for every corpus file,
 * and the plain-text twin carries the words without Markdown symbols.
 */
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { JSDOM } from "jsdom";
import katex from "katex";
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { splitSections } from "../../src/core/sections";
import { COLOURS, Formatter } from "../../src/page/copy/formatted";
import { mathToText, subscript, superscript } from "../../src/page/copy/mathText";
import { plainText } from "../../src/page/copy/plainText";
import { createMarkdown, renderSection } from "../../src/render/markdown";

const CORPUS = resolve(__dirname, "../corpus");
const md = createMarkdown();
const host = new JSDOM("").window.document;

/** Renders [src] section by section, as the page does, into one body. */
function rendered(src: string): Element[] {
    const lines = src.split("\n");
    const defs = collectDefinitions(lines);
    const doc = host.implementation.createHTMLDocument("");
    for (const section of splitSections(lines)) {
        const raw = lines.slice(section.startLine, section.endLine).join("\n");
        doc.body.insertAdjacentHTML("beforeend", renderSection(md, raw, defs, section.startLine === 0).html);
    }
    return [...doc.body.children];
}

function format(src: string): Formatter {
    const f = new Formatter(host);
    for (const block of rendered(src)) f.add(block);
    f.finish();
    return f;
}

function html(src: string): string {
    return format(src).root.innerHTML;
}

function text(src: string): string {
    return plainText(format(src).root.childNodes);
}

const PALETTE = new Set<string>(Object.values(COLOURS).map((c) => c.toLowerCase()));
const ALERTS = new Set(["#0969da", "#1a7f37", "#8250df", "#9a6700", "#cf222e"]);

describe("formatted copy HTML", () => {
    for (const name of readdirSync(CORPUS).filter((f) => f.endsWith(".md"))) {
        it(`${name}: inline styles only, in the light palette`, () => {
            const out = format(readFileSync(resolve(CORPUS, name), "utf8")).root;
            const all = [...out.querySelectorAll("*")];
            expect(out.innerHTML).not.toMatch(/<(?:style|script|link|input|button|svg|math|nav)\b/i);
            for (const el of all) {
                for (const attr of [...el.attributes]) {
                    expect(["style", "href", "src", "alt", "width", "start", "colspan", "rowspan"]).toContain(
                        attr.name,
                    );
                }
                const style = el.getAttribute("style") ?? "";
                expect(style).not.toMatch(/var\(|rgb|hsl/i);
                for (const colour of style.match(/#[0-9a-f]{3,8}\b/gi) ?? []) {
                    expect(PALETTE.has(colour.toLowerCase()) || ALERTS.has(colour.toLowerCase())).toBe(true);
                }
            }
        });
    }

    it("styles headings, code, quotes and tables for a mail editor", () => {
        const out = html(readFileSync(resolve(CORPUS, "basics.md"), "utf8"));
        expect(out).toContain('<h1 style="margin:16px 0 8px 0;font-size:20px;font-weight:bold">Basics heading</h1>');
        expect(out).toMatch(/<pre style="[^"]*background-color:#f6f8fa;[^"]*white-space:pre-wrap[^"]*">fun main/);
        expect(out).toMatch(/<code style="font-family:Consolas[^"]*">inline code<\/code>/);
        expect(out).toMatch(/<blockquote style="[^"]*border-left:3px solid #d0d7de/);
        expect(out).toMatch(/<td style="border:1px solid #d0d7de;padding:6px 10px;text-align:right">1<\/td>/);
        expect(out).toContain('<a href="https://example.com" style="color:#0969da;text-decoration:underline">link</a>');
    });

    it("turns tasks into ☐ and ☑, drops front matter, gathers footnotes at the end", () => {
        const f = format(readFileSync(resolve(CORPUS, "extended.md"), "utf8"));
        const out = f.root.innerHTML;
        expect(out).toContain("☐ Open task");
        expect(out).toContain("☑ Done task");
        expect(out).not.toContain("tags: [test]");
        expect(out).not.toContain("Edit in Code");
        const last = f.root.lastElementChild?.textContent ?? "";
        expect(last).toBe("2 A named footnote.");
        expect(out).toContain("reference<sup>1</sup>");
    });

    it("leaves diagrams and nearby pictures for the page to embed; web pictures become links", () => {
        const f = format(
            "![Cat](pictures/cat.png)\n\n![Logo](https://example.com/logo.png)\n\n```mermaid\nflowchart LR\n  A --> B\n```\n",
        );
        expect(f.media.map((m) => [m.kind, m.source, m.el.getAttribute("alt")])).toEqual([
            ["image", "/doc/0/pictures/cat.png", "Cat"],
            ["diagram", "flowchart LR\n  A --> B\n", "Diagram: flowchart"],
        ]);
        expect(f.root.innerHTML).toContain(
            '<a href="https://example.com/logo.png" style="color:#0969da;text-decoration:underline">[Logo]</a>',
        );
    });

    it("writes maths as readable text, not KaTeX's spans", () => {
        const out = html("Inline $E = mc^2$.\n\n$$\n\\int_0^1 x^2\\,dx = \\frac{1}{3}\n$$\n");
        expect(out).toContain(">E = mc²</span>");
        expect(out).toContain(">∫₀¹x² dx = 1/3</p>");
        expect(out).not.toContain("katex");
    });

    it("matches the reviewed snapshot of a representative document", async () => {
        const src =
            "# Weekly update\n\nShipped **three** fixes and a [release](https://example.com/r).\n\n" +
            "## Done\n\n- [x] Copy as…\n- [ ] Print\n\n1. First\n2. Second\n   - nested\n\n" +
            "> Quote with `code`.\n\n| Area | Count |\n| :--- | ---: |\n| Bugs | 3 |\n\n" +
            "```js\nconst x = 1;\n```\n\nArea $\\pi r^2$ and H~2~O.[^n]\n\n[^n]: A note.\n";
        await expect(html(src)).toMatchFileSnapshot("__snapshots__/formatted-copy.html");
    });
});

describe("formatted copy plain text", () => {
    it("has headings as lines, list markers, tab-separated tables and no Markdown symbols", () => {
        const out = text(readFileSync(resolve(CORPUS, "basics.md"), "utf8"));
        expect(out.startsWith("Basics heading\n\nA paragraph with bold, italic, strike, inline code and a link")).toBe(
            true,
        );
        expect(out).toContain("link (https://example.com)");
        expect(out).toContain("• Bullet one\n• Bullet two\n  • Nested bullet\n• Bullet three");
        expect(out).toContain("1. First\n2. Second\n3. Third");
        expect(out).toContain("    Quoted text that runs over two lines.");
        expect(out).toContain("Name\tValue\none\t1\ntwo\t2");
        expect(out).toContain("[An image]");
        expect(out).toContain("Final paragraph with a hard break\nafter two spaces.");
        expect(out).not.toMatch(/^#|\*\*|^\||^```|^> /m);
    });

    it("keeps tasks, footnotes and maths readable", () => {
        const out = text(readFileSync(resolve(CORPUS, "extended.md"), "utf8"));
        expect(out).toContain("☐ Open task\n☑ Done task");
        expect(out).toContain("Here is a footnote reference¹ and another²");
        expect(out).toContain("¹ The first footnote.");
        expect(out).toContain("H₂O and E=mc² with marked text");
        expect(out).toContain("[Diagram: flowchart]");
        expect(out).not.toContain("title: Extended syntax");
    });
});

describe("maths as text", () => {
    const mathOf = (tex: string): string => {
        const doc = host.implementation.createHTMLDocument("");
        doc.body.innerHTML = katex.renderToString(tex, { output: "mathml", throwOnError: false });
        const math = doc.querySelector("math");
        if (!math) throw new Error("no MathML");
        return mathToText(math);
    };

    it.each([
        ["x^2 + y^2 = z^2", "x² + y² = z²"],
        ["\\frac{a+b}{2}", "(a + b)/2"],
        ["\\sqrt{x}", "√x"],
        ["\\alpha \\leq \\beta", "α ≤ β"],
        ["\\sum_{i=1}^{n} i", "∑ᵢ₌₁ⁿi"],
        ["x_{max}", "xₘₐₓ"],
        ["x_{q}", "x_q"],
        ["\\hat{x}", "x̂"],
        ["e^{i\\pi} = -1", "e^(iπ) = −1"],
        ["-x + 1", "−x + 1"],
    ])("%s reads as %s", (tex, expected) => {
        expect(mathOf(tex)).toBe(expected);
    });

    it("raises or lowers only what has a Unicode form", () => {
        expect(superscript("2")).toBe("²");
        expect(superscript("q")).toBe("^q");
        expect(subscript("12")).toBe("₁₂");
        expect(subscript("ab")).toBe("_(ab)");
    });
});
