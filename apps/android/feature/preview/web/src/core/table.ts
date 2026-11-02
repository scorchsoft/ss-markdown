/**
 * A GFM pipe table as rows of raw cell text, with operations that write the
 * table back in the style it was written in.
 *
 * A compact table (`|a|b|`) stays compact, a spaced one (`| a | b |`) stays
 * spaced, and a padded table stays padded, re-padded only where a column's
 * width changed. Rows the operation did not touch keep their original bytes
 * (docs/ux-spec.md, "Tables").
 */
import { displayWidth } from "./displayWidth";

export type Align = "none" | "left" | "center" | "right";
export type TableStyle = "compact" | "spaced" | "padded";

export interface TableRow {
    /** Raw cell text, trimmed, escapes kept (a `\|` stays `\|`). */
    readonly cells: string[];
    /** The original line, or null once the row must be re-serialised. */
    line: string | null;
}

export interface Table {
    readonly style: TableStyle;
    readonly leadingPipe: boolean;
    readonly trailingPipe: boolean;
    readonly indent: string;
    /** Row 0 is the header; the delimiter row is held as [aligns]. */
    readonly rows: TableRow[];
    readonly aligns: Align[];
    delimiterLine: string | null;
}

/** Splits a row on pipes that are not escaped and not inside a code span. */
export function splitRow(line: string): { cells: string[]; leading: boolean; trailing: boolean } {
    const body = line.trim();
    const cells: string[] = [];
    let current = "";
    let tick = 0;
    for (let i = 0; i < body.length; i++) {
        const c = body[i] ?? "";
        if (c === "\\" && i + 1 < body.length) {
            current += c + (body[i + 1] ?? "");
            i++;
        } else if (c === "`") {
            let run = 1;
            while (body[i + run] === "`") run++;
            tick = tick === 0 ? run : tick === run ? 0 : tick;
            current += "`".repeat(run);
            i += run - 1;
        } else if (c === "|" && tick === 0) {
            cells.push(current);
            current = "";
        } else {
            current += c;
        }
    }
    cells.push(current);
    const leading = body.startsWith("|");
    const trailing = body.length > 1 && body.endsWith("|") && !body.endsWith("\\|");
    if (leading) cells.shift();
    if (trailing) cells.pop();
    return { cells: cells.map((c) => c.trim()), leading, trailing };
}

function parseAlign(cell: string): Align {
    const left = cell.startsWith(":");
    const right = cell.endsWith(":");
    if (left && right) return "center";
    if (left) return "left";
    if (right) return "right";
    return "none";
}

/** Parses [lines] (header, delimiter, body rows) or returns null if they are not a table. */
export function parseTable(lines: readonly string[], style?: TableStyle): Table | null {
    const [header, delimiter] = lines;
    if (header === undefined || delimiter === undefined) return null;
    const delim = splitRow(delimiter);
    if (!delim.cells.every((c) => /^:?-+:?$/.test(c))) return null;
    const head = splitRow(header);
    const rows: TableRow[] = [{ cells: head.cells, line: header }];
    for (const line of lines.slice(2)) rows.push({ cells: splitRow(line).cells, line });
    return {
        style: style ?? detectStyle(lines),
        leadingPipe: head.leading,
        trailingPipe: head.trailing,
        indent: /^\s*/.exec(header)?.[0] ?? "",
        rows,
        aligns: delim.cells.map(parseAlign),
        delimiterLine: delimiter,
    };
}

/** Padded when every line has the same display width; compact when no cell has spaces around it. */
export function detectStyle(lines: readonly string[]): TableStyle {
    const widths = new Set(lines.map((l) => displayWidth(l.trimEnd())));
    if (lines.length > 2 && widths.size === 1) {
        const head = lines[0] ?? "";
        if (/\|\s{2,}|\s{2,}\|/.test(head) || /\| [^|]+ \|/.test(head)) return "padded";
    }
    return /(^|\|) | (\||$)/.test((lines[0] ?? "").trim()) ? "spaced" : "compact";
}

function columnWidths(t: Table): number[] {
    const n = t.aligns.length;
    const widths: number[] = [];
    for (let c = 0; c < n; c++) {
        let w = 3;
        for (const row of t.rows) w = Math.max(w, displayWidth(row.cells[c] ?? ""));
        widths.push(w);
    }
    return widths;
}

function joinCells(t: Table, cells: string[]): string {
    const gap = t.style === "compact" ? "" : " ";
    const inner = cells.map((c) => `${gap}${c}${gap}`).join("|");
    return `${t.indent}${t.leadingPipe ? "|" : ""}${inner}${t.trailingPipe ? "|" : ""}`;
}

function serializeRow(t: Table, row: TableRow, widths: number[]): string {
    const cells = t.aligns.map((_, c) => {
        const text = row.cells[c] ?? "";
        return t.style === "padded" ? text + " ".repeat((widths[c] ?? 0) - displayWidth(text)) : text;
    });
    return joinCells(t, cells);
}

function serializeDelimiter(t: Table, widths: number[]): string {
    const cells = t.aligns.map((a, c) => {
        const dashes = t.style === "padded" ? (widths[c] ?? 3) : 3;
        const inner = "-".repeat(Math.max(1, dashes - (a === "left" || a === "right" ? 1 : a === "center" ? 2 : 0)));
        return (a === "left" || a === "center" ? ":" : "") + inner + (a === "right" || a === "center" ? ":" : "");
    });
    return joinCells(t, cells);
}

/** The table as lines, keeping every untouched row's original bytes. */
export function serializeTable(t: Table, previousWidths?: number[]): string[] {
    const widths = columnWidths(t);
    const widthChanged = (c: number): boolean => previousWidths?.[c] !== widths[c];
    const repad = t.style === "padded" && widths.some((_, c) => widthChanged(c));
    const out = t.rows.map((row) => (row.line !== null && !repad ? row.line : serializeRow(t, row, widths)));
    const delim = t.delimiterLine !== null && !repad ? t.delimiterLine : serializeDelimiter(t, widths);
    out.splice(1, 0, delim);
    return out;
}

/** A snapshot of column widths, taken before an operation, so serialisation can tell what grew. */
export function widthsOf(t: Table): number[] {
    return columnWidths(t);
}

export function setCell(t: Table, row: number, col: number, text: string): void {
    const target = t.rows[row];
    if (!target) return;
    while (target.cells.length < t.aligns.length) target.cells.push("");
    target.cells[col] = text.replace(/(?<!\\)\|/g, "\\|").trim();
    target.line = null;
}

export function addRow(t: Table, after: number): void {
    t.rows.splice(after + 1, 0, { cells: t.aligns.map(() => ""), line: null });
}

export function addColumn(t: Table, after: number): void {
    t.aligns.splice(after + 1, 0, "none");
    for (const row of t.rows) {
        row.cells.splice(after + 1, 0, "");
        row.line = null;
    }
    t.delimiterLine = null;
}

export function setAlign(t: Table, col: number, align: Align): void {
    if (col < 0 || col >= t.aligns.length) return;
    t.aligns[col] = align;
    t.delimiterLine = null;
}

export function deleteRow(t: Table, row: number): void {
    if (row > 0 && row < t.rows.length) t.rows.splice(row, 1);
}

export function deleteColumn(t: Table, col: number): void {
    if (t.aligns.length <= 1 || col < 0 || col >= t.aligns.length) return;
    t.aligns.splice(col, 1);
    for (const row of t.rows) {
        row.cells.splice(col, 1);
        row.line = null;
    }
    t.delimiterLine = null;
}

/** Moves a body row up (-1) or down (+1); the header row stays put. */
export function moveRow(t: Table, row: number, by: -1 | 1): void {
    const to = row + by;
    if (row < 1 || to < 1 || to >= t.rows.length) return;
    const [moved] = t.rows.splice(row, 1);
    if (moved) t.rows.splice(to, 0, moved);
}

export function moveColumn(t: Table, col: number, by: -1 | 1): void {
    const to = col + by;
    if (col < 0 || to < 0 || to >= t.aligns.length) return;
    const swap = <T>(arr: T[]): void => {
        const a = arr[col];
        const b = arr[to];
        if (a === undefined || b === undefined) return;
        arr[col] = b;
        arr[to] = a;
    };
    swap(t.aligns);
    for (const row of t.rows) {
        swap(row.cells);
        row.line = null;
    }
    t.delimiterLine = null;
}

/** One replacement of whole lines, relative to the table's first line. */
export interface LineReplacement {
    readonly fromLine: number;
    readonly toLine: number;
    readonly lines: readonly string[];
}

/**
 * The fewest line replacements that turn [before] into [after]: the common
 * prefix and suffix are dropped, and an equal-length middle is compared line by
 * line so only the lines that differ are replaced.
 */
export function diffLines(before: readonly string[], after: readonly string[]): LineReplacement[] {
    let start = 0;
    while (start < before.length && start < after.length && before[start] === after[start]) start++;
    let endB = before.length;
    let endA = after.length;
    while (endB > start && endA > start && before[endB - 1] === after[endA - 1]) {
        endB--;
        endA--;
    }
    if (endB - start !== endA - start) {
        return [{ fromLine: start, toLine: endB, lines: after.slice(start, endA) }];
    }
    const out: LineReplacement[] = [];
    for (let i = start; i < endB; i++) {
        if (before[i] !== after[i]) out.push({ fromLine: i, toLine: i + 1, lines: [after[i] ?? ""] });
    }
    return out;
}

/**
 * The source range of cell [col]'s content within a table [line]: trimmed
 * content, or for an empty cell the spot where typing should insert.
 */
export function cellRange(line: string, col: number): { from: number; to: number } | null {
    const bounds: number[] = [];
    let tick = 0;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (c === "\\") i++;
        else if (c === "`") tick = tick === 0 ? 1 : 0;
        else if (c === "|" && tick === 0) bounds.push(i);
    }
    const trimmedStart = line.length - line.trimStart().length;
    const leading = line.trimStart().startsWith("|");
    const starts = leading ? bounds : [trimmedStart - 1, ...bounds];
    const start = starts[col];
    if (start === undefined) return null;
    const end = starts[col + 1] ?? line.trimEnd().length;
    const raw = line.slice(start + 1, end);
    const lead = raw.length - raw.trimStart().length;
    const content = raw.trim();
    if (content.length === 0) {
        const at = start + 1 + Math.min(1, raw.length);
        return { from: at, to: at };
    }
    return { from: start + 1 + lead, to: start + 1 + lead + content.length };
}
