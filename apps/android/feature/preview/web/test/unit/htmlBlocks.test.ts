/**
 * Raw HTML blocks in Preview: a comment draws nothing and has no chip; other HTML
 * keeps its Edit in Code chip (Andrew, 2026-11-05: a comment's chip hung, clipped,
 * out of an empty block, and a lone <hr>'s chip sat over the next paragraph).
 */
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { createMarkdown, renderSection } from "../../src/render/markdown";

const md = createMarkdown();
const render = (src: string) => renderSection(md, src, collectDefinitions(src.split("\n"))).html;

describe("HTML blocks", () => {
    it("draws a comment as an empty block with its lines and no chip", () => {
        const html = render("para\n\n<!-- COMMENT TEST: hidden in rendered output. -->\n\n<hr>\n\nEnd");
        expect(html).toMatch(
            /<div class="ro-block ro-comment" contenteditable="false" data-rl="2" data-rle="3"><\/div>/,
        );
        expect(html).not.toContain("COMMENT TEST");
        expect(html.match(/Edit in Code/g)).toHaveLength(1); // the <hr>'s
    });

    it("treats several comments, and a multi-line one, as comments", () => {
        expect(render("<!-- a --> <!-- b -->")).toContain("ro-comment");
        expect(render("<!--\nline one\nline two\n-->")).toContain("ro-comment");
    });

    it("keeps the chip when anything but a comment is in the block", () => {
        const html = render("<!-- note --><div>shown</div>");
        expect(html).toContain("ro-html");
        expect(html).toContain("Edit in Code");
        expect(html).toContain("shown");
    });
});
