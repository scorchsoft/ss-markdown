import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { newlineInsert } from "../../src/core/newline";
import { SourceDoc } from "../../src/core/sourceDoc";

describe("collectDefinitions", () => {
    it("collects references, footnotes in first-reference order and abbreviations", () => {
        const d = collectDefinitions([
            "See [x][r] and [^b] then [^a].",
            "```",
            "[fake]: https://no",
            "```",
            '[r]: https://example.com "T"',
            "[^a]: A",
            "[^b]: B",
            "*[HTML]: Hyper Text",
        ]);
        expect(d.references).toEqual([{ label: "r", href: "https://example.com", title: "T" }]);
        expect(d.footnoteNumbers).toEqual({ b: 1, a: 2 });
        expect(d.footnoteLabels).toEqual(["a", "b"]);
        expect(d.abbreviations).toEqual({ HTML: "Hyper Text" });
    });
});

describe("newlineInsert", () => {
    it("continues lists in the document's own style", () => {
        expect(newlineInsert("- item", "listItem")).toBe("\n- ");
        expect(newlineInsert("  + item", "listItem")).toBe("\n  + ");
        expect(newlineInsert("9) nine", "listItem")).toBe("\n10) ");
        expect(newlineInsert("- [x] done", "listItem")).toBe("\n- [ ] ");
        expect(newlineInsert("> quote", "paragraph")).toBe("\n>\n> ");
        expect(newlineInsert("para", "paragraph")).toBe("\n\n");
        expect(newlineInsert("x", "code")).toBe("\n");
    });
});

describe("SourceDoc", () => {
    it("replaces line ranges and splices characters", () => {
        const doc = new SourceDoc("a\nb\nc");
        doc.replaceLines({ fromLine: 1, toLine: 2, lines: ["B", "B2"] });
        expect(doc.text).toBe("a\nB\nB2\nc");
        doc.replaceLines({ fromLine: 1, toLine: 3, lines: [] });
        expect(doc.text).toBe("a\nc");
        doc.replaceLines({ fromLine: 1, toLine: 1, lines: [""] });
        expect(doc.text).toBe("a\n\nc");
        const change = doc.applyEdit({ from: 0, to: 1, insert: "x\ny" });
        expect(change).toEqual({ fromLine: 0, toLine: 1, lines: ["x", "y"] });
        expect(doc.text).toBe("x\ny\n\nc");
        expect(doc.lineAt(doc.lineStart(3))).toBe(3);
    });
});
