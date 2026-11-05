/**
 * An unclosed `$$` maths block inside a list item swallowed the line that ended the item, and so
 * the text after the list vanished from Preview (found by Tidy's render fuzzing, 2026-11-04). It
 * now ends with its container, like a fenced code block does; at the top level it still runs to
 * the end, as an unclosed fence does.
 */
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { createMarkdown, renderSection } from "../../src/render/markdown";

const md = createMarkdown();
const render = (src: string): string => renderSection(md, src, collectDefinitions(src.split("\n"))).html;

describe("maths blocks in containers", () => {
    it("an unclosed $$ in a list item ends with the item, and the text after the list shows", () => {
        const html = render("- $$\n  x^2\n\nAfter the list.");
        expect(html).toContain("math-block");
        expect(html).toMatch(/<\/ul>\s*<p[^>]*><span[^>]*>After the list\.<\/span><\/p>/);
    });

    it("a nested item's unclosed $$ leaves the next paragraph and later blocks", () => {
        const html = render("- item\n\n  $$\n  x\n\nOutside.\n\n# Heading");
        expect(html).toContain("Outside.");
        expect(html).toContain("Heading</span></h1>");
    });

    it("an unclosed $$ in a quote ends with the quote", () => {
        const html = render("> $$\n> x^2\n\nAfter the quote.");
        expect(html).toMatch(/<\/blockquote>\s*<p[^>]*><span[^>]*>After the quote\.<\/span><\/p>/);
    });

    it("a closed $$ in a list item is unchanged", () => {
        const html = render("- $$\n  x^2\n  $$\n\nAfter.");
        expect(html.match(/math-block/g)?.length).toBe(1);
        expect(html).toContain("After.");
    });

    it("at the top level an unclosed $$ still takes the rest, as an unclosed fence does", () => {
        expect(render("$$\nx\n\nAfter.")).not.toMatch(/<p[^>]*>/);
    });
});
