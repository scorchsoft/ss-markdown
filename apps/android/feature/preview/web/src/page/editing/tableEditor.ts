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

/** Converts whole-line replacements inside the table into character edits. */
function toSourceEdits(doc: SourceDoc, addr: CellAddress, before: string[], after: string[]): SourceEdit[] {
    return diffLines(before, after).map((r) => {
        const fromLine = addr.startLine + r.fromLine;
        const toLine = addr.startLine + r.toLine;
        const from = doc.lineStart(fromLine);
        if (r.toLine === r.fromLine) {
            return { from, to: from, insert: r.lines.map((l) => `${l}\n`).join("") };
        }
        const to = doc.lineStart(toLine - 1) + (doc.lines[toLine - 1]?.length ?? 0);
        return { from, to, insert: r.lines.join("\n") };
    });
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
        case "addColumn":
            return { row: addr.row, col: addr.col + 1 };
        case "deleteRow":
            return { row: Math.min(addr.row, table.rows.length - 1), col: addr.col };
        case "deleteColumn":
            return { row: addr.row, col: Math.min(addr.col, table.aligns.length - 1) };
        case "moveRow":
            return { row: addr.row + op.by, col: addr.col };
        case "moveColumn":
            return { row: addr.row, col: addr.col + op.by };
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
    const widths = widthsOf(table);
    const body = addr.row === 0 ? 0 : addr.row;
    switch (op.kind) {
        case "addRow":
            addRow(table, body);
            break;
        case "addColumn":
            addColumn(table, addr.col);
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
