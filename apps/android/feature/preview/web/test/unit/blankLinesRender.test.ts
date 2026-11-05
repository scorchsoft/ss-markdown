/**
 * Preview draws the blank lines a file has between blocks, beyond the usual gap
 * (Andrew, 2026-11-04: twenty empty lines in Code showed as one gap in Preview).
 */
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { splitSections } from "../../src/core/sections";
import { createMarkdown, renderSection } from "../../src/render/markdown";

const md = createMarkdown();

/** The relative lines of the drawn blank lines in [src], rendered as one section. */
function drawn(src: string): number[] {
    const html = renderSection(md, src, collectDefinitions(src.split("\n"))).html;
    return [...html.matchAll(/class="blank-line" data-rl="(\d+)"/g)].map((m) => Number(m[1]));
}

describe("drawn blank lines", () => {
    it("draws each blank line after the first between two blocks", () => {
        expect(drawn("a\n\nb")).toEqual([]);
        expect(drawn("a\n\n\nb")).toEqual([2]);
        expect(drawn("a\n\n\n\n\nb")).toEqual([2, 3, 4]);
    });

    it("measures from a block's last line of text, not where markdown-it ends it", () => {
        // A list's map runs on over the blank lines after it.
        expect(drawn("- a\n- b\n\n\n\npara")).toEqual([3, 4]);
        expect(drawn("> q\n\n\n\nb")).toEqual([2, 3]);
        expect(drawn("| a |\n|---|\n| 1 |\n\n\n\nz")).toEqual([4, 5]);
        expect(drawn("```\nx\n```\n\n\n# h")).toEqual([4]);
        expect(drawn("# h\n\n\n---\n\n\ntext")).toEqual([2, 5]);
    });

    it("leaves blank lines that are content alone", () => {
        // Inside a fence, an unclosed fence, and a list item: those lines render as what they are.
        expect(drawn("```\na\n\n\n\nb\n```")).toEqual([]);
        expect(drawn("```\na\n\n\n\n")).toEqual([]);
        expect(drawn("- a\n\n\n- b")).toEqual([]);
    });

    it("draws blank lines at the document's start and end, and none in a blank file", () => {
        expect(drawn("\n\na")).toEqual([0, 1]);
        expect(drawn("a\n")).toEqual([]);
        expect(drawn("a\n\n\n")).toEqual([2, 3]);
        expect(drawn("")).toEqual([]);
        expect(drawn("\n\n\n")).toEqual([]);
    });

    it("draws the same lines whether the document is rendered whole or by section", () => {
        const doc = "# Title\n\nIntro.\n\n\n\n\n## Chapters\n\n- a\n- b\n\n\n\nEnd.\n";
        const lines = doc.split("\n");
        const whole = drawn(doc);
        const bySection = splitSections(lines).flatMap((s) =>
            drawn(lines.slice(s.startLine, s.endLine).join("\n")).map((l) => l + s.startLine),
        );
        expect(bySection).toEqual(whole);
        expect(whole).toEqual([4, 5, 6, 12, 13]);
    });

    it("hides drawn lines from screen readers", () => {
        expect(renderSection(md, "a\n\n\nb", collectDefinitions([])).html).toContain('aria-hidden="true"');
    });
});
