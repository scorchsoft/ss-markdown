/**
 * Table operations in Preview: write-back through the table model so a table
 * keeps its style, plus the dark toolbar shown above the table while a cell is
 * being edited (docs/ux-spec.md, "Tables").
 */
import type { SourceDoc, SourceEdit } from "../../core/sourceDoc";
import {
    addColumn,
    addRow,
    cellRange,
    deleteColumn,
    deleteRow,
    diffLines,
    moveColumn,
    moveRow,
    parseTable,
    serializeTable,
    setAlign,
    setCell,
    widthsOf,
    type LineReplacement,
    type Table,
} from "../../core/table";
import type { TableOp } from "../../protocol";

/** Where a cell sits: the table's source lines and the cell's row and column. */
export interface CellAddress {
    readonly startLine: number;
    readonly endLine: number;
    readonly row: number;
    readonly col: number;
}

export function cellAddress(cell: HTMLElement): CellAddress | null {
    const table = cell.closest<HTMLElement>("table[data-line]");
    const tr = cell.closest("tr");
    if (!table || !tr) return null;
    const inHead = tr.parentElement?.tagName === "THEAD";
    const bodyRows = [...table.querySelectorAll("tbody > tr")];
    const row = inHead ? 0 : bodyRows.indexOf(tr) + 1;
    return {
        startLine: Number(table.dataset.line),
        endLine: Number(table.dataset.lineEnd),
        row,
        col: [...tr.children].indexOf(cell),
    };
}

/** Source line of table row [row] (row 0 is the header; the delimiter is skipped). */
export function rowLine(addr: CellAddress, row: number): number {
    return addr.startLine + (row === 0 ? 0 : row + 1);
}

function tableLines(doc: SourceDoc, addr: CellAddress): string[] {
    return doc.lines.slice(addr.startLine, addr.endLine);
}

/** Offset just past the last character of [line], before its terminator. */
function lineEnd(doc: SourceDoc, line: number): number {
    return doc.lineStart(line) + (doc.lines[line]?.length ?? 0);
}

/**
 * Converts whole-line replacements inside the table into character edits.
 *
 * Lines are separated by terminators, so a change that adds or removes whole
 * lines must carry one terminator with it. Removing lines takes the
 * terminator after them (or, for the document's last line, the one before),
 * so a deleted row leaves no blank line behind: a blank line inside a table
 * ends it, and every row below would turn into a paragraph of pipes.
 * Inserting after the document's last line puts the terminator in front.
 */
export function lineEdits(doc: SourceDoc, firstLine: number, replacements: readonly LineReplacement[]): SourceEdit[] {
    const last = doc.lineCount - 1;
    return replacements.map((r) => {
        const fromLine = firstLine + r.fromLine;
        const toLine = firstLine + r.toLine;
        if (r.lines.length === 0 && toLine > fromLine) {
            if (toLine <= last) return { from: doc.lineStart(fromLine), to: doc.lineStart(toLine), insert: "" };
            if (fromLine > 0) return { from: lineEnd(doc, fromLine - 1), to: lineEnd(doc, last), insert: "" };
            return { from: 0, to: lineEnd(doc, last), insert: "" };
        }
        if (toLine === fromLine) {
            if (fromLine <= last) {
                const at = doc.lineStart(fromLine);
                return { from: at, to: at, insert: r.lines.map((l) => `${l}\n`).join("") };
            }
            const at = lineEnd(doc, last);
            return { from: at, to: at, insert: r.lines.map((l) => `\n${l}`).join("") };
        }
        return { from: doc.lineStart(fromLine), to: lineEnd(doc, toLine - 1), insert: r.lines.join("\n") };
    });
}

function toSourceEdits(doc: SourceDoc, addr: CellAddress, before: string[], after: string[]): SourceEdit[] {
    return lineEdits(doc, addr.startLine, diffLines(before, after));
}

/** Whether the table at [addr] is padded, judged before anyone types into it. */
export function isPadded(doc: SourceDoc, addr: CellAddress): boolean {
    return parseTable(tableLines(doc, addr))?.style === "padded";
}

/**
 * Edits that re-pad a table that was padded when editing began. Typing writes
 * minimal edits; the padding is restored once, when the caret leaves the cell.
 */
export function repadEdits(doc: SourceDoc, addr: CellAddress): SourceEdit[] {
    const before = tableLines(doc, addr);
    const table = parseTable(before, "padded");
    if (!table) return [];
    for (const row of table.rows) row.line = null;
    table.delimiterLine = null;
    return toSourceEdits(doc, addr, before, serializeTable(table));
}

/** The cell the caret should land in after [op]. */
function targetAfter(op: TableOp, addr: CellAddress, table: Table): { row: number; col: number } {
    switch (op.kind) {
        case "addRow":
            return { row: addr.row + 1, col: addr.col };
        case "addRowAbove":
            return { row: Math.max(1, addr.row), col: addr.col };
        case "addColumn":
            return { row: addr.row, col: addr.col + 1 };
        case "deleteRow":
            return { row: Math.min(addr.row, table.rows.length - 1), col: addr.col };
        case "deleteColumn":
            return { row: addr.row, col: Math.min(addr.col, table.aligns.length - 1) };
        case "moveRow": {
            // A refused move (the header, or the last row down) leaves the caret where it was.
            const to = addr.row + op.by;
            return { row: addr.row >= 1 && to >= 1 && to < table.rows.length ? to : addr.row, col: addr.col };
        }
        case "moveColumn": {
            const to = addr.col + op.by;
            return { row: addr.row, col: to >= 0 && to < table.aligns.length ? to : addr.col };
        }
        default:
            return { row: addr.row, col: addr.col };
    }
}

export interface TableEditResult {
    readonly edits: SourceEdit[];
    /** Absolute source offset for the caret once the edits are applied. */
    readonly caretAfter: (doc: SourceDoc) => number;
}

/** Applies [op] to the table holding [addr], in the table's own style. */
export function tableOpEdits(doc: SourceDoc, addr: CellAddress, op: TableOp): TableEditResult | null {
    const before = tableLines(doc, addr);
    const table = parseTable(before);
    if (!table) return null;
    if (op.kind === "deleteTable") {
        // Only the table's own lines go; the blank lines around it stay, so
        // the blocks before and after keep their separation.
        const at = addr.startLine;
        return {
            edits: lineEdits(doc, at, [{ fromLine: 0, toLine: before.length, lines: [] }]),
            caretAfter: (next) => next.lineStart(Math.min(at, next.lineCount - 1)),
        };
    }
    const widths = widthsOf(table);
    switch (op.kind) {
        case "addRow":
            addRow(table, addr.row);
            break;
        case "addRowAbove":
            // Nothing goes above the header: from the header this adds the first body row.
            addRow(table, Math.max(0, addr.row - 1));
            break;
        case "addColumn":
            addColumn(table, addr.col);
            break;
        case "addColumnLeft":
            addColumn(table, addr.col - 1);
            break;
        case "align":
            setAlign(table, addr.col, op.align);
            break;
        case "deleteRow":
            deleteRow(table, addr.row);
            break;
        case "deleteColumn":
            deleteColumn(table, addr.col);
            break;
        case "moveRow":
            moveRow(table, addr.row, op.by);
            break;
        case "moveColumn":
            moveColumn(table, addr.col, op.by);
            break;
    }
    const after = serializeTable(table, widths);
    const target = targetAfter(op, addr, table);
    return {
        edits: toSourceEdits(doc, addr, before, after),
        caretAfter: (next) => cellOffset(next, addr, target.row, target.col),
    };
}

/**
 * The table cell that holds absolute [offset], found from the source: the
 * row from the line, the column from the pipes before the offset.
 */
export function cellAtOffset(section: HTMLElement, doc: SourceDoc, offset: number): HTMLElement | null {
    const line = doc.lineAt(offset);
    for (const table of section.querySelectorAll<HTMLElement>("table[data-line]")) {
        const start = Number(table.dataset.line);
        if (line < start || line >= Number(table.dataset.lineEnd) || line === start + 1) continue;
        const text = doc.lines[line] ?? "";
        const before = text.slice(0, offset - doc.lineStart(line));
        const pipes = (before.match(/(?<!\\)\|/g) ?? []).length;
        const col = text.trimStart().startsWith("|") ? pipes - 1 : pipes;
        const rows = table.querySelectorAll("tr");
        const row = rows[line === start ? 0 : line - start - 1];
        const cell = row?.children[Math.max(0, col)];
        return cell instanceof HTMLElement ? cell : null;
    }
    return null;
}

/** Absolute offset of the end of cell ([row], [col])'s content. */
export function cellOffset(doc: SourceDoc, addr: CellAddress, row: number, col: number): number {
    const line = rowLine(addr, row);
    const range = cellRange(doc.lines[line] ?? "", Math.max(0, col));
    return doc.lineStart(line) + (range?.to ?? 0);
}

/** Sets cell text through the model (used by tests and the cell write-back path). */
export function setCellEdits(doc: SourceDoc, addr: CellAddress, text: string): SourceEdit[] {
    const before = tableLines(doc, addr);
    const table = parseTable(before);
    if (!table) return [];
    const widths = widthsOf(table);
    setCell(table, addr.row, addr.col, text);
    return toSourceEdits(doc, addr, before, serializeTable(table, widths));
}
