/**
 * The paste conversions shared with Kotlin (`core/markdown`'s
 * `PasteConversionsTest` reads the same file): clipboard HTML from Gmail,
 * Google Docs, Word and a web page to Markdown, and Markdown to the words
 * "Paste as plain text" inserts. Each case's `markdown` is what a normal
 * paste of its `html` (with its `plain` twin, if any) inserts, and `words`
 * what a plain paste inserts; both languages must give them exactly.
 *
 * After an intended change, run
 * `UPDATE_VECTORS=1 npx vitest run --project unit test/unit/pasteConversions.test.ts`,
 * review the diff, then run Kotlin's test.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { JSDOM } from "jsdom";
import { describe, expect, it } from "vitest";
import { htmlToMarkdown } from "../../src/core/htmlToMarkdown";
import { clipContent, clipWords } from "../../src/core/pasteClip";
import { hasMarkdown, plainWords } from "../../src/core/plainWords";
import { escapeBlockStart, escapeInline } from "../../src/core/markdownText";
import { decodeEntities } from "../../src/core/entities";

const FILE = resolve(__dirname, "../vectors/paste-conversions.txt");
const { DOMParser } = new JSDOM("").window;
const parse = (html: string): Document => new DOMParser().parseFromString(html, "text/html");

type Section = "html" | "plain" | "markdown" | "words";
const ORDER: readonly Section[] = ["html", "plain", "markdown", "words"];

interface Case {
    name: string;
    sections: Partial<Record<Section, string>>;
}

/** The cases of [text]: `=== name` starts one, `--- section` starts each of its parts. */
export function parseCases(text: string): Case[] {
    const cases: Case[] = [];
    let section: Section | null = null;
    let lines: string[] = [];
    const end = (): void => {
        const current = cases[cases.length - 1];
        if (current && section) current.sections[section] = lines.join("\n");
        lines = [];
    };
    for (const line of text.replace(/\n$/, "").split("\n")) {
        const caseHead = /^=== (\S+)$/.exec(line);
        const sectionHead = /^--- (html|plain|markdown|words)$/.exec(line);
        if (caseHead) {
            end();
            section = null;
            cases.push({ name: caseHead[1] as string, sections: {} });
        } else if (sectionHead) {
            end();
            section = sectionHead[1] as Section;
        } else {
            lines.push(line);
        }
    }
    end();
    return cases;
}

function write(cases: readonly Case[]): string {
    return (
        cases
            .map((c) =>
                [
                    `=== ${c.name}`,
                    ...ORDER.flatMap((s) => (c.sections[s] === undefined ? [] : [`--- ${s}`, c.sections[s]])),
                ].join("\n"),
            )
            .join("\n") + "\n"
    );
}

function expected(c: Case): Partial<Record<Section, string>> {
    const html = c.sections.html ?? null;
    const plain = c.sections.plain ?? null;
    if (html === null) return { words: plainWords(plain ?? "") };
    return { markdown: clipContent(plain, html, parse).text, words: clipWords(plain, html, parse) };
}

const cases = parseCases(readFileSync(FILE, "utf8"));

if (process.env.UPDATE_VECTORS) {
    writeFileSync(FILE, write(cases.map((c) => ({ name: c.name, sections: { ...c.sections, ...expected(c) } }))));
}

describe("paste conversions (shared with Kotlin)", () => {
    it("has the clip kinds the matrix needs", () => {
        const names = cases.map((c) => c.name);
        for (const n of ["gmail", "google-docs", "word", "nested-lists", "code-with-language", "links", "images"]) {
            expect(names).toContain(n);
        }
        for (const c of cases) expect(c.sections.words, c.name).toBeDefined();
    });

    for (const c of cases) {
        it(c.name, () => {
            const want = expected(c);
            if (want.markdown !== undefined) expect(want.markdown).toBe(c.sections.markdown);
            expect(want.words).toBe(c.sections.words);
        });
    }
});

describe("htmlToMarkdown", () => {
    const md = (html: string): string => htmlToMarkdown(html, parse).markdown;

    it("never writes an address that is not the web or mail", () => {
        for (const href of ["javascript:alert(1)", "JaVaScRiPt:x", "data:text/html,x", "vbscript:x", "file:///etc"]) {
            expect(md(`<a href="${href}">t</a>`)).toBe("t");
        }
        expect(md('<img src="javascript:x" alt="a">')).toBe("");
    });

    it("drops event handlers, scripts, styles and comments with their text", () => {
        const out = md('<p onmouseover="x()">a<script>b</script><style>c</style><!--d-->e</p>');
        expect(out).toBe("ae");
    });

    it("says whether anything was formatting", () => {
        expect(htmlToMarkdown("<p>plain</p><p>text</p>", parse).formatted).toBe(false);
        expect(htmlToMarkdown("<p>a<br>b</p>", parse).formatted).toBe(false);
        expect(htmlToMarkdown("<p><b>b</b></p>", parse).formatted).toBe(true);
    });

    it("leaves blank emphasis and empty links alone", () => {
        expect(md("<p>a<b> </b>b<a href='https://e.com'></a></p>")).toBe("a b");
    });
});

describe("plainWords", () => {
    it("tells Markdown from plain prose", () => {
        expect(hasMarkdown("# Title")).toBe(true);
        expect(hasMarkdown("some **bold**")).toBe(true);
        expect(hasMarkdown("| a | b |\n| - | - |")).toBe(true);
        expect(hasMarkdown("snake_case and a * b, $5, 10:30")).toBe(false);
        expect(hasMarkdown("Two\n\n\nparagraphs")).toBe(false);
    });

    it("keeps unclosed code and links as written", () => {
        expect(plainWords("a `b and [c](d")).toBe("a `b and [c](d");
    });
});

describe("markdownText", () => {
    it("escapes only what could act", () => {
        expect(escapeInline("snake_case a * b *x* [y] <b> $5 $x$ ==m== :smile:")).toBe(
            "snake_case a * b \\*x\\* \\[y\\] \\<b> $5 \\$x$ \\=\\=m\\=\\= \\:smile:",
        );
        expect(escapeBlockStart("# h")).toBe("\\# h");
        expect(escapeBlockStart("12) x")).toBe("12\\) x");
    });

    it("decodes character references", () => {
        expect(decodeEntities("&amp;&#x41;&#66;&#0;&nope;")).toBe("&AB�&nope;");
    });
});
