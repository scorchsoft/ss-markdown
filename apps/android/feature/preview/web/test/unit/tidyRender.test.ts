/**
 * Tidy must never change how a document renders (core/markdown's
 * `tidy/MarkdownTidy`). `test/vectors/tidy-cases.txt` holds what Kotlin's
 * Tidy makes of every hand-written case and a few hundred generated
 * documents; this test renders each input and output with the Preview's
 * own markdown-it set-up and requires the same page.
 *
 * Allowed differences, and only these: the blank-line spacers Preview draws
 * for extra blank lines (collapsing them is the point of Tidy), the source
 * line and offset attributes (lines move), and whitespace that HTML does not
 * draw (between tags, runs of spaces in text, the line break before a code
 * block's end). Code block contents are compared exactly apart from that
 * one last line break.
 *
 * Kotlin writes the file (`TidyVectorsTest`, `-Pssmarkdown.updateVectors=true`).
 * `TIDY_VECTORS=<path>` points this test at another file of the same format.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { createMarkdown, renderSection } from "../../src/render/markdown";

interface TidyVector {
    name: string;
    input: string;
    output: string;
}

const FILE = process.env.TIDY_VECTORS ?? resolve(__dirname, "../vectors/tidy-cases.txt");

/** `=== name`, `--- input`, `--- output`; every content line ends in `¶`. */
export function parseVectors(text: string): TidyVector[] {
    const out: TidyVector[] = [];
    let current: { name: string; input: string[]; output: string[] } | null = null;
    let section: "input" | "output" | null = null;
    const flush = (): void => {
        if (current)
            out.push({ name: current.name, input: current.input.join("\n"), output: current.output.join("\n") });
    };
    for (const line of text.replace(/\n$/, "").split("\n")) {
        const head = line.endsWith("¶") ? null : /^=== (\S+)$/.exec(line);
        if (head) {
            flush();
            current = { name: head[1] ?? "", input: [], output: [] };
            section = null;
        } else if (line === "--- input" || line === "--- output") {
            section = line === "--- input" ? "input" : "output";
        } else if (current && section) {
            if (!line.endsWith("¶")) throw new Error(`${current.name}: line without ¶: ${line}`);
            current[section].push(line.slice(0, -1));
        }
    }
    flush();
    return out;
}

const md = createMarkdown();

function render(src: string): string {
    return renderSection(md, src, collectDefinitions(src.split("\n"))).html;
}

/** The page as drawn: spacers, source positions and undrawn whitespace removed. */
export function drawn(html: string): string {
    const pieces = html
        .replace(/<div class="blank-line"[^>]*><\/div>\n?/g, "")
        .replace(/ data-(?:rl|rle|s|e|edit-rl)="[^"]*"/g, "")
        .replace(/\n((?:<\/span>)*)<\/code>/g, "$1</code>")
        .split(/(<pre class="[^"]*"[\s\S]*?<\/pre>)/);
    return pieces
        .map((piece, i) => (i % 2 === 1 ? piece : piece.replace(/>\s+</g, "><").replace(/\s+/g, " ")))
        .join("")
        .trim();
}

const vectors = parseVectors(readFileSync(FILE, "utf8"));

describe("Tidy renders the same (shared with Kotlin)", () => {
    it("has the hand cases and the generated ones", () => {
        expect(vectors.length).toBeGreaterThanOrEqual(400);
        const names = vectors.map((v) => v.name);
        for (const n of ["hard-break-kept", "list-1984", "table-then-heading", "unclosed-fence", "generated-0"]) {
            expect(names).toContain(n);
        }
    });

    it("changes something in most hand cases", () => {
        const changed = vectors.filter((v) => !v.name.startsWith("generated-") && v.input !== v.output);
        expect(changed.length).toBeGreaterThan(30);
    });

    for (const v of vectors) {
        it(v.name, () => {
            expect(drawn(render(v.output))).toBe(drawn(render(v.input)));
        });
    }
});

describe("drawn", () => {
    it("drops spacers and positions, keeps code exact", () => {
        const spacer = '<div class="blank-line" data-rl="3" data-rle="4" aria-hidden="true"></div>\n';
        expect(drawn(`<p data-rl="1">a</p>\n${spacer}<p>b  c</p>`)).toBe("<p>a</p><p>b c</p>");
        expect(drawn('<pre class="code-block"><code>a  b\n\n</code></pre>')).toBe(
            '<pre class="code-block"><code>a  b\n</code></pre>',
        );
    });
});
