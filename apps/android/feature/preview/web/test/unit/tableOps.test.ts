/**
 * Table operations from the strip and the table sheet, applied to a whole
 * document the way the page applies them, and checked against markdown-it
 * (the renderer) and an independent model: every operation leaves a valid
 * GFM table with the model's cells, and every line outside the table
 * byte-identical (docs/robustness.md, "A Preview edit changes only its own
 * block"; docs/edge-cases.md, "Preview table operations").
 */
import MarkdownIt from "markdown-it";
import { describe, expect, it } from "vitest";
import { applyEdit } from "../../src/core/minimalEdit";
import { SourceDoc } from "../../src/core/sourceDoc";
import { setCellEdits, tableOpEdits, type CellAddress } from "../../src/page/editing/tableEditor";
import type { TableOp } from "../../src/protocol";
import { Random } from "./random";

const md = new MarkdownIt({ html: true, linkify: true });

function apply(source: string, edits: readonly { from: number; to: number; insert: string }[]): string {
    let text = source;
    for (const e of [...edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
    return text;
}

function run(source: string, addr: CellAddress, op: TableOp): string {
    const result = tableOpEdits(new SourceDoc(source), addr, op);
    if (!result) throw new Error("not a table");
    return apply(source, result.edits);
}

interface Rendered {
    readonly map: readonly [number, number];
    readonly rows: string[][];
}

/** Every table markdown-it finds in [source], as its line range and cell texts. */
function tables(source: string): Rendered[] {
    const out: { map: [number, number]; rows: string[][] }[] = [];
    let row: string[] | null = null;
    let inCell = false;
    for (const t of md.parse(source, {})) {
        if (t.type === "table_open" && t.map) out.push({ map: [t.map[0], t.map[1]], rows: [] });
        else if (t.type === "tr_open") row = [];
        else if (t.type === "tr_close" && row) out[out.length - 1]?.rows.push(row);
        else if (t.type === "th_open" || t.type === "td_open") inCell = true;
        else if (t.type === "th_close" || t.type === "td_close") {
            if (inCell) row?.push("");
            inCell = false;
        } else if (t.type === "inline" && inCell && row) {
            row.push(t.content);
            inCell = false;
        }
    }
    return out;
}

/** Where the page would address a cell: the rendered table's lines (data-line, data-line-end). */
function addressOf(source: string, row: number, col: number): CellAddress {
    const t = tables(source)[0];
    if (!t) throw new Error("no table");
    return { startLine: t.map[0], endLine: t.map[1], row, col };
}

const doc = "Intro\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n\nAfter";

describe("table operations on a document", () => {
    it("deletes a row it just added without leaving a blank line in the table (Andrew, 0.2.0)", () => {
        const added = run(doc, addressOf(doc, 0, 0), { kind: "addRow" });
        expect(added).toBe("Intro\n\n| a | b |\n| --- | --- |\n|  |  |\n| 1 | 2 |\n| 3 | 4 |\n\nAfter");
        const deleted = run(added, addressOf(added, 1, 0), { kind: "deleteRow" });
        expect(deleted).toBe(doc);
        expect(tables(deleted)[0]?.rows).toEqual([
            ["a", "b"],
            ["1", "2"],
            ["3", "4"],
        ]);
    });

    it("deletes the last row, also at the very end of a file with no final newline", () => {
        expect(run(doc, addressOf(doc, 2, 1), { kind: "deleteRow" })).toBe(
            "Intro\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\nAfter",
        );
        const end = "|a|b|\n|-|-|\n|1|2|\n|3|4|";
        expect(run(end, addressOf(end, 2, 0), { kind: "deleteRow" })).toBe("|a|b|\n|-|-|\n|1|2|");
    });

    it("adds a row below the last row of a file with no final newline", () => {
        const end = "|a|b|\n|-|-|\n|1|2|";
        expect(run(end, addressOf(end, 1, 0), { kind: "addRow" })).toBe("|a|b|\n|-|-|\n|1|2|\n|||");
    });

    it("keeps header and delimiter when the only body row goes, and never deletes the header", () => {
        const one = "| a | b |\n| --- | --- |\n| 1 | 2 |\n";
        const left = run(one, addressOf(one, 1, 0), { kind: "deleteRow" });
        expect(left).toBe("| a | b |\n| --- | --- |\n");
        expect(tables(left)[0]?.rows).toEqual([["a", "b"]]);
        expect(run(left, addressOf(left, 0, 0), { kind: "deleteRow" })).toBe(left);
    });

    it("adds a row above, and from the header adds the first body row", () => {
        expect(run(doc, addressOf(doc, 2, 0), { kind: "addRowAbove" })).toBe(
            "Intro\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n|  |  |\n| 3 | 4 |\n\nAfter",
        );
        expect(run(doc, addressOf(doc, 0, 0), { kind: "addRowAbove" })).toBe(
            "Intro\n\n| a | b |\n| --- | --- |\n|  |  |\n| 1 | 2 |\n| 3 | 4 |\n\nAfter",
        );
    });

    it("adds a column on the left of a table without outer pipes and keeps it a table", () => {
        const bare = "a | b\n--- | ---\n1 | 2";
        const after = run(bare, addressOf(bare, 1, 0), { kind: "addColumnLeft" });
        expect(after).toBe("|  | a | b\n--- | --- | ---\n|  | 1 | 2");
        expect(tables(after)[0]?.rows).toEqual([
            ["", "a", "b"],
            ["", "1", "2"],
        ]);
    });

    it("deletes the table and only its lines", () => {
        expect(run(doc, addressOf(doc, 1, 1), { kind: "deleteTable" })).toBe("Intro\n\n\nAfter");
        const only = "|a|\n|-|\n|1|";
        expect(run(only, addressOf(only, 1, 0), { kind: "deleteTable" })).toBe("");
        const last = "Intro\n\n|a|\n|-|\n|1|";
        expect(run(last, addressOf(last, 1, 0), { kind: "deleteTable" })).toBe("Intro\n");
    });

    it("addresses columns as the renderer splits them, pipes in code spans included", () => {
        const code = "| x | y |\n| - | - |\n| `c | d` |";
        expect(tables(code)[0]?.rows[1]).toEqual(["`c", "d`"]);
        const after = run(code, addressOf(code, 1, 1), { kind: "deleteColumn" });
        expect(tables(after)[0]?.rows).toEqual([["x"], ["`c"]]);
    });

    it("keeps cells past the header's count when a row is rewritten", () => {
        const extra = "| a | b |\n| - | - |\n| 1 | 2 | kept |";
        expect(run(extra, addressOf(extra, 1, 0), { kind: "addColumn" })).toContain("| 1 |  | 2 | kept |");
    });

    it("moves a value in a short row to the column it is moved to", () => {
        const short = "| a | b |\n| - | - |\n| 1 |";
        expect(tables(run(short, addressOf(short, 1, 0), { kind: "moveColumn", by: 1 }))[0]?.rows[1]).toEqual([
            "",
            "1",
        ]);
    });
});

// ---- Property: random tables, random operation sequences, an independent model ----

type Model = { rows: string[][]; aligns: string[] };

const CELLS = ["a", "1", "Bold **b**", "x \\| y", "`c \\| d`", "日本", "👍🏽", "", ":-", "a-b", "[l](u)"];
const TYPED = ["new", "p|q", "  spaced  ", "日本語", "", "z"];

function pipeEscape(text: string): string {
    return text.replace(/(?<!\\)\|/g, "\\|").trim();
}

/** What markdown-it shows for a model cell: `\|` reads as a pipe. */
function shown(cell: string): string {
    return cell.replace(/\\\|/g, "|");
}

function generate(r: Random): { lines: string[]; model: Model } {
    const n = 1 + r.int(4);
    const bodyRows = r.int(4);
    const rows: string[][] = [];
    for (let i = 0; i <= bodyRows; i++) {
        rows.push(Array.from({ length: n }, () => (i === 0 ? r.pick(CELLS.filter((c) => c !== "")) : r.pick(CELLS))));
    }
    const aligns = Array.from({ length: n }, () => r.pick(["none", "left", "center", "right"]));
    const outer = r.pick(["both", "both", "none", "leading", "trailing"]);
    const lead = outer === "both" || outer === "leading" || n === 1;
    const trail = outer === "both" || outer === "trailing";
    const gap = r.pick(["", " "]);
    const indent = " ".repeat(r.int(4));
    const join = (cells: string[]): string => {
        const first = lead || cells[0] === "";
        return `${indent}${first ? "|" : ""}${cells.map((c) => `${gap}${c}${gap}`).join("|")}${trail ? "|" : ""}`;
    };
    const delim = aligns.map((a) => (a === "left" ? ":--" : a === "right" ? "--:" : a === "center" ? ":-:" : "---"));
    const lines = [join(rows[0] ?? []), join(delim), ...rows.slice(1).map(join)];
    return { lines, model: { rows, aligns } };
}

function modelOp(m: Model, op: TableOp, row: number, col: number): void {
    const n = m.aligns.length;
    for (const r of m.rows) while (r.length < n) r.push("");
    switch (op.kind) {
        case "addRow":
            m.rows.splice(row + 1, 0, Array<string>(n).fill(""));
            break;
        case "addRowAbove":
            m.rows.splice(Math.max(1, row), 0, Array<string>(n).fill(""));
            break;
        case "addColumn":
        case "addColumnLeft": {
            const at = op.kind === "addColumn" ? col + 1 : col;
            m.aligns.splice(at, 0, "none");
            for (const r of m.rows) r.splice(at, 0, "");
            break;
        }
        case "deleteRow":
            if (row > 0) m.rows.splice(row, 1);
            break;
        case "deleteColumn":
            if (n > 1) {
                m.aligns.splice(col, 1);
                for (const r of m.rows) r.splice(col, 1);
            }
            break;
        case "moveRow": {
            const to = row + op.by;
            if (row >= 1 && to >= 1 && to < m.rows.length) {
                const [moved] = m.rows.splice(row, 1);
                if (moved) m.rows.splice(to, 0, moved);
            }
            break;
        }
        case "moveColumn": {
            const to = col + op.by;
            if (to >= 0 && to < n) {
                for (const r of [m.aligns, ...m.rows]) {
                    const a = r[col] ?? "";
                    r[col] = r[to] ?? "";
                    r[to] = a;
                }
            }
            break;
        }
        case "align":
            m.aligns[col] = op.align;
            break;
        case "deleteTable":
            break;
    }
}

const OPS: TableOp[] = [
    { kind: "addRow" },
    { kind: "addRowAbove" },
    { kind: "addColumn" },
    { kind: "addColumnLeft" },
    { kind: "deleteRow" },
    { kind: "deleteRow" },
    { kind: "deleteColumn" },
    { kind: "moveRow", by: -1 },
    { kind: "moveRow", by: 1 },
    { kind: "moveColumn", by: -1 },
    { kind: "moveColumn", by: 1 },
    { kind: "align", align: "center" },
    { kind: "align", align: "none" },
];

function renderedAligns(source: string, start: number): string[] {
    const aligns: string[] = [];
    let seen = false;
    for (const t of md.parse(source, {})) {
        if (t.type === "table_open") seen = t.map?.[0] === start;
        if (seen && t.type === "th_open") {
            const style = String(t.attrGet("style") ?? "");
            aligns.push(/text-align:(\w+)/.exec(style)?.[1] ?? "none");
        }
        if (seen && t.type === "thead_close") break;
    }
    return aligns;
}

function check(source: string, prefix: readonly string[], suffix: readonly string[], m: Model, label: string): void {
    const lines = source.split("\n");
    expect(lines.slice(0, prefix.length), `${label}: lines above`).toEqual(prefix);
    const found = tables(source);
    expect(found.length, `${label}: one table\n${source}`).toBe(1);
    const t = found[0];
    if (!t) return;
    const height = m.rows.length + 1;
    expect(t.map, `${label}: table lines\n${source}`).toEqual([prefix.length, prefix.length + height]);
    expect(lines.slice(prefix.length + height), `${label}: lines below`).toEqual(suffix);
    const n = m.aligns.length;
    const want = m.rows.map((r) => Array.from({ length: n }, (_, c) => shown(r[c] ?? "")));
    expect(t.rows, `${label}: cells\n${source}`).toEqual(want);
    const aligns = m.aligns.map((a) => (a === "left" || a === "right" || a === "center" ? a : "none"));
    expect(renderedAligns(source, prefix.length), `${label}: alignment`).toEqual(aligns);
}

describe("table operations, property", () => {
    it("every operation sequence keeps one valid table that matches the model and leaves other lines alone", () => {
        for (let seed = 1; seed <= 400; seed++) {
            const r = new Random(seed);
            const { lines: tableLines, model } = generate(r);
            let prefix = r.pick([[], ["# Title", ""], ["Para", "", "- item", ""]]);
            const suffix = r.pick([[], [""], ["", "After"], ["", "After", ""]]);
            let source = [...prefix, ...tableLines, ...suffix].join("\n");
            check(source, prefix, suffix, model, `seed ${seed} start`);
            for (let step = 0; step < 12; step++) {
                const label = `seed ${seed} step ${step}`;
                const choice = r.int(10);
                if (choice === 0) {
                    // A Code-pane edit above the table moves it; the page re-addresses from the render.
                    prefix = prefix.length > 0 && r.int(2) === 0 ? prefix.slice(2) : ["Typed in Code", "", ...prefix];
                    source = [
                        ...prefix,
                        ...source
                            .split("\n")
                            .slice(source.split("\n").length - (model.rows.length + 1 + suffix.length)),
                    ].join("\n");
                } else {
                    const row = r.int(model.rows.length);
                    const col = r.int(model.aligns.length);
                    const addr = addressOf(source, row, col);
                    if (choice === 1) {
                        const text = r.pick(TYPED);
                        source = apply(source, setCellEdits(new SourceDoc(source), addr, text));
                        for (const rr of model.rows) while (rr.length < model.aligns.length) rr.push("");
                        const target = model.rows[row];
                        if (target) target[col] = pipeEscape(text);
                    } else {
                        const op = r.pick(OPS);
                        const result = tableOpEdits(new SourceDoc(source), addr, op);
                        if (!result) throw new Error(`${label}: not a table`);
                        source = apply(source, result.edits);
                        modelOp(model, op, row, col);
                        const caretLine = new SourceDoc(source).lineAt(result.caretAfter(new SourceDoc(source)));
                        expect(caretLine, `${label}: caret in the table`).toBeGreaterThanOrEqual(prefix.length);
                        expect(caretLine, `${label}: caret in the table`).toBeLessThan(
                            prefix.length + model.rows.length + 1,
                        );
                    }
                }
                check(source, prefix, suffix, model, label);
            }
            const gone = run(source, addressOf(source, 0, 0), { kind: "deleteTable" });
            expect(tables(gone), `seed ${seed}: deleted`).toEqual([]);
            expect(gone.split("\n").slice(0, prefix.length)).toEqual(prefix);
        }
    });
});
