import { describe, expect, it } from "vitest";
import { displayWidth } from "../../src/core/displayWidth";
import {
    addColumn,
    addRow,
    cellRange,
    diffLines,
    parseTable,
    serializeTable,
    setAlign,
    setCell,
    widthsOf,
} from "../../src/core/table";

function edit(lines: string[], op: (t: NonNullable<ReturnType<typeof parseTable>>) => void): string[] {
    const t = parseTable(lines);
    if (!t) throw new Error("not a table");
    const widths = widthsOf(t);
    op(t);
    return serializeTable(t, widths);
}

describe("display width", () => {
    it("counts wide characters and emoji as two columns", () => {
        expect(displayWidth("abc")).toBe(3);
        expect(displayWidth("日本")).toBe(4);
        expect(displayWidth("👍🏽")).toBe(2);
        expect(displayWidth("é")).toBe(1);
    });
});

describe("table write-back", () => {
    const compact = ["|a|b|", "|-|-|", "|1|2|", "|3|4|"];
    const spaced = ["| a | b |", "| --- | --- |", "| 1 | 2 |", "| 3 | 4 |"];
    const padded = ["| Name | Value |", "| ---- | ----- |", "| one  | 1     |", "| two  | 2     |"];

    it("keeps a compact table compact and touches one line", () => {
        const after = edit(compact, (t) => setCell(t, 1, 0, "x"));
        expect(after).toEqual(["|a|b|", "|-|-|", "|x|2|", "|3|4|"]);
        expect(diffLines(compact, after)).toEqual([{ fromLine: 2, toLine: 3, lines: ["|x|2|"] }]);
    });

    it("keeps a spaced table spaced", () => {
        expect(edit(spaced, (t) => setCell(t, 2, 1, "five"))[3]).toBe("| 3 | five |");
    });

    it("re-pads a padded table only when a column's width changes", () => {
        const same = edit(padded, (t) => setCell(t, 1, 0, "uno"));
        expect(diffLines(padded, same)).toEqual([{ fromLine: 2, toLine: 3, lines: ["| uno  | 1     |"] }]);
        const wider = edit(padded, (t) => setCell(t, 1, 0, "eleven"));
        expect(wider).toEqual(["| Name   | Value |", "| ------ | ----- |", "| eleven | 1     |", "| two    | 2     |"]);
    });

    it("pads by display width", () => {
        const after = edit(padded, (t) => setCell(t, 2, 0, "日本"));
        expect(after[3]).toBe("| 日本 | 2     |");
    });

    it("adds a row as one inserted line and keeps alignment markers", () => {
        const aligned = ["| a | b |", "| :-- | --: |", "| 1 | 2 |"];
        const after = edit(aligned, (t) => addRow(t, 1));
        expect(after).toEqual(["| a | b |", "| :-- | --: |", "| 1 | 2 |", "|  |  |"]);
        expect(diffLines(aligned, after)).toEqual([{ fromLine: 3, toLine: 3, lines: ["|  |  |"] }]);
    });

    it("changes only the delimiter row when aligning", () => {
        const after = edit(spaced, (t) => setAlign(t, 1, "right"));
        expect(diffLines(spaced, after)).toEqual([{ fromLine: 1, toLine: 2, lines: ["| --- | --: |"] }]);
    });

    it("adds a column everywhere and escapes pipes in cells", () => {
        const after = edit(compact, (t) => {
            addColumn(t, 0);
            setCell(t, 0, 1, "x|y");
        });
        expect(after[0]).toBe("|a|x\\|y|b|");
        expect(after[1]).toBe("|---|---|---|");
    });

    it("finds a cell's content range in a line", () => {
        expect(cellRange("| one  | 1     |", 1)).toEqual({ from: 9, to: 10 });
        expect(cellRange("|a||c|", 1)).toEqual({ from: 3, to: 3 });
        expect(cellRange("a | b", 1)).toEqual({ from: 4, to: 5 });
    });
});
