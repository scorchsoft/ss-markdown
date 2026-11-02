import { describe, expect, it } from "vitest";
import { collectDefinitions, definitionSignature } from "../../src/core/definitions";
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
