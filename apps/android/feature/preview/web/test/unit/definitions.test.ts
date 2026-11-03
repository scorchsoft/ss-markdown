import { describe, expect, it } from "vitest";
import { collectDefinitions, definitionSignature } from "../../src/core/definitions";
import { codeExit, isEmptyQuoteLine, isListLine, itemTextStart, newlineInsert } from "../../src/core/newline";
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

describe("definitionSignature", () => {
    it("is unchanged by typing in a paragraph that cites a footnote (PERF-H)", () => {
        // Before: any line with "[^" forced a whole-document rescan on every keystroke.
        expect(definitionSignature("See the note[^1] here")).toBe(definitionSignature("See the notes[^1] here"));
        expect(definitionSignature("[link] text")).toBe(definitionSignature("[link] texts"));
        expect(definitionSignature("plain words")).toBe("");
    });

    it("changes whenever the definitions can", () => {
        const same = (a: string, b: string) => definitionSignature(a) === definitionSignature(b);
        expect(same("See[^1]", "See[^1] and[^2]")).toBe(false); // a new citation renumbers
        expect(same("[^1] then [^2]", "[^2] then [^1]")).toBe(false); // so does reordering
        expect(same("[r]: https://a", "[r]: https://b")).toBe(false);
        expect(same("[^a]: one", "[^a]: two")).toBe(false);
        expect(same("*[HTML]: Hyper", "*[HTML]: Hyper Text")).toBe(false);
        expect(same("text", "```")).toBe(false); // a fence hides what follows
    });

    it("never says the same when collectDefinitions would differ", () => {
        const lines = ["Intro[^a] and [x][r].", "[r]: https://e.com", "[^a]: A", "*[HTML]: H", "Body text[^b]"];
        const edits = ["", "x", "[^c]", "[q]: u", "```", "*[CSS]: C", "[^a]", "]:"];
        lines.forEach((before, line) => {
            for (const insert of edits) {
                for (let col = 0; col <= before.length; col++) {
                    const after = before.slice(0, col) + insert + before.slice(col);
                    if (definitionSignature(before) !== definitionSignature(after)) continue;
                    const edited = lines.map((l, i) => (i === line ? after : l));
                    expect(collectDefinitions(edited)).toEqual(collectDefinitions(lines));
                }
            }
        });
    });
});

describe("newlineInsert", () => {
    it("starts a new paragraph, in a quote a new paragraph of the quote", () => {
        expect(newlineInsert("> quote")).toBe("\n>\n> ");
        expect(newlineInsert("para")).toBe("\n\n");
        expect(newlineInsert("# Heading")).toBe("\n\n");
        expect(newlineInsert("para", true)).toBe("\\\n");
    });

    it("knows the lines whose Enter is Kotlin's: list items and empty quote lines", () => {
        for (const line of ["- ", "-", "* a", "2. x", "10) ", "- [ ] a", "- [x]", "  - a", "> - a", "> > 1. b"]) {
            expect(isListLine(line), line).toBe(true);
        }
        for (const line of ["", "para", "> quote", "-- ", "-x", "1234567890. ", "#1"]) {
            expect(isListLine(line), line).toBe(false);
        }
        expect(isEmptyQuoteLine("> ")).toBe(true);
        expect(isEmptyQuoteLine("> > ")).toBe(true);
        expect(isEmptyQuoteLine("> a")).toBe(false);
        expect(isEmptyQuoteLine("> - ")).toBe(false);
    });

    it("finds where an item's text starts, after any task box", () => {
        expect(itemTextStart("- a")).toBe(2);
        expect(itemTextStart("  10) a")).toBe(6);
        expect(itemTextStart("- [ ] a")).toBe(6);
        expect(itemTextStart("> 1. a")).toBe(5);
        expect(itemTextStart("para")).toBe(-1);
    });

    it("leaves a code block on a second empty last line (D4)", () => {
        const lines = ["```", "a", "", "", "```", "next"];
        const starts = [0, 4, 6, 7, 8, 12];
        const at = (i: number): number => starts[i] ?? 0;
        expect(codeExit(lines, 3, at)).toEqual({ from: 6, to: 11, insert: "```\n\n", caret: 10 });
        expect(codeExit(["```", "a", "", "```"], 2, at)).toBeNull();
        expect(codeExit(["```", "a", "b", "", "```"], 3, at)).toBeNull();
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
