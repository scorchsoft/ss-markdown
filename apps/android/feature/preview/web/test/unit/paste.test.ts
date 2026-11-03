import { describe, expect, it } from "vitest";
import {
    atBlockStart,
    cleanClip,
    escapePasted,
    linkFor,
    pasteMarkdown,
    pastePlain,
    type PasteTarget,
} from "../../src/core/paste";

const para: PasteTarget = {
    kind: "paragraph",
    inTable: false,
    line: "Some text",
    atBlockStart: false,
    atLineEnd: true,
};

describe("escapePasted", () => {
    it.each([
        ["snake_case_name", "snake_case_name"],
        ["_lead", "\\_lead"],
        ["a * b", "a * b"],
        ["*bold*", "\\*bold\\*"],
        ["a `tick` b", "a \\`tick\\` b"],
        ["[x](y)", "\\[x\\](y)"],
        ["x < y and <b>", "x < y and \\<b>"],
        ["~~gone~~ and ~ alone", "\\~\\~gone\\~\\~ and ~ alone"],
        ["H^2^O", "H\\^2\\^O"],
        ["a==b== x=1", "a\\=\\=b\\=\\= x=1"],
        ["$5 and $x$", "$5 and \\$x\\$"],
        ["&amp; & co", "\\&amp; & co"],
        [":smile: at 10:30:00", "\\:smile: at 10:30:00"],
        ["C:\\Users\\me and \\*", "C:\\Users\\me and \\\\\\*"],
        ["trailing \\", "trailing \\\\"],
    ])("escapes only what could act in %j", (line, escaped) => {
        expect(escapePasted(line, false)).toBe(escaped);
    });

    it.each([
        ["# not a heading", "\\# not a heading"],
        ["#hashtag", "#hashtag"],
        ["- not a list", "\\- not a list"],
        ["-5 degrees", "-5 degrees"],
        ["+ plus", "\\+ plus"],
        ["1. one", "1\\. one"],
        ["2024) was", "2024\\) was"],
        ["1.5 litres", "1.5 litres"],
        ["> quote", "\\> quote"],
        ["---", "\\---"],
        ["- - -", "\\- - -"],
    ])("escapes block syntax at a line start in %j", (line, escaped) => {
        expect(escapePasted(line, true)).toBe(escaped);
        expect(escapePasted(line, false)).toBe(line);
    });
});

describe("pastePlain", () => {
    it("cleans line endings, NUL and a leading BOM", () => {
        expect(cleanClip("\uFEFFa\r\nb\rc\0d")).toBe("a\nb\ncd");
    });

    it("makes paragraphs of lines in a paragraph, escaping each new line's start", () => {
        expect(pastePlain("one *a*\r\n\r\n- two\n# three\n", para)).toBe("one \\*a\\*\n\n\\- two\n\n\\# three");
    });

    it("escapes the first line's block syntax only where it starts the block", () => {
        expect(pastePlain("# x", para)).toBe("# x");
        expect(pastePlain("# x", { ...para, line: "- ", atBlockStart: true })).toBe("\\# x");
    });

    const list = { kind: "listItem", inTable: false, atBlockStart: false, atLineEnd: true } as const;

    it("EB-CLIP-08: lines join a list item as continuation lines, escaped, indented to its text (D10)", () => {
        expect(pastePlain("a\nb\n# c", { ...list, line: "- one" })).toBe("a\n  b\n  \\# c");
        expect(pastePlain("a\nb", { ...list, line: "10) ten" })).toBe("a\n    b");
        expect(pastePlain("a\nb", { ...list, line: "> - [x] q" })).toBe("a\n>   b");
    });

    it("EB-CLIP-07: a Markdown list becomes items of this list, split at the caret (D10)", () => {
        expect(pastePlain("- a\n- *b*", { ...list, line: "- one" })).toBe("\n- a\n- *b*");
        expect(pastePlain("* a\n  more\n* b", { ...list, line: "2) two" })).toBe("\n3) a\n   more\n4) b");
        expect(pastePlain("1. a\n2. b", { ...list, line: "- [ ] t", atLineEnd: false })).toBe(
            "\n- [ ] a\n- [ ] b\n- [ ] ",
        );
        expect(pastePlain("- [x] a", { ...list, line: "- [ ] t" })).toBe("\n- [x] a");
        expect(pastePlain("- a\n- b", { ...list, line: "- ", atBlockStart: true })).toBe("a\n- b");
    });

    it("keeps quoted lines quoted", () => {
        expect(pastePlain("a\nb", { ...para, line: "> quoted" })).toBe("a\n>\n> b");
    });

    it("puts code in verbatim", () => {
        expect(pastePlain("  *x*\r\n\n# y", { ...para, kind: "code" })).toBe("  *x*\n\n# y");
    });

    it("EB-TABLE-07: keeps a table cell on one line, lines joined by <br>, pipes escaped", () => {
        expect(pastePlain("a | b\nc *d*", { ...para, inTable: true })).toBe("a \\| b<br>c \\*d\\*");
    });
});

describe("pasteMarkdown", () => {
    it("keeps inline Markdown as written", () => {
        expect(pasteMarkdown("some **bold** [l](u)", para)).toBe("some **bold** [l](u)");
        expect(pasteMarkdown("**bold** first", para)).toBe("**bold** first");
    });

    it("gives blocks their own place", () => {
        expect(pasteMarkdown("- a\n- b\n", para)).toBe("\n\n- a\n- b");
        expect(pasteMarkdown("- a\n- b", { ...para, atLineEnd: false })).toBe("\n\n- a\n- b\n\n");
        expect(pasteMarkdown("## h", { ...para, atBlockStart: true })).toBe("## h");
        expect(pasteMarkdown("- **a**\n- b", { ...para, kind: "listItem", line: "1. x" })).toBe("\n2. **a**\n3. b");
    });

    it("keeps a cell on one line", () => {
        expect(pasteMarkdown("a | b\n**c**", { ...para, inTable: true })).toBe("a \\| b<br>**c**");
        expect(pasteMarkdown("a \\| b", { ...para, inTable: true })).toBe("a \\| b");
    });
});

describe("linkFor", () => {
    it("links a label to one bare web address", () => {
        expect(linkFor("the docs", " https://e.com/a_(b)\n")).toBe("[the docs](https://e.com/a_(b))");
        expect(linkFor("[x]", "http://e.com")).toBe("[\\[x\\]](http://e.com)");
        expect(linkFor("x", "https://e.com/a)b")).toBe("[x](<https://e.com/a)b>)");
    });

    it("refuses anything else", () => {
        for (const clip of ["see https://e.com", "mailto:a@b.c", "https://a.b\nhttps://c.d", "ftp://x"]) {
            expect(linkFor("x", clip)).toBeNull();
        }
        expect(linkFor("a\nb", "https://e.com")).toBeNull();
        expect(linkFor("`c`", "https://e.com")).toBeNull();
        // EB-LINK-07: never a link of an address to an address.
        expect(linkFor("https://old.com", "https://e.com")).toBeNull();
        expect(linkFor("www.old.com", "https://e.com")).toBeNull();
    });
});

describe("atBlockStart", () => {
    it("is true after block markup only", () => {
        expect(atBlockStart("- [ ] task", 6)).toBe(true);
        expect(atBlockStart("> 1. x", 5)).toBe(true);
        expect(atBlockStart("## Head", 3)).toBe(true);
        expect(atBlockStart("text", 0)).toBe(true);
        expect(atBlockStart("- text", 3)).toBe(false);
    });
});
