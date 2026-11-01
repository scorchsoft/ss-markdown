/**
 * The page's copy of the Markdown source, kept as an array of lines.
 *
 * Kotlin sends changed line ranges, never the whole document after the first
 * load, so the page needs a structure where replacing lines is cheap and where
 * line -> offset lookups (for the inline source map) are fast.
 */
export interface LineChange {
    /** First line replaced (0-based). */
    readonly fromLine: number;
    /** Line after the last replaced one (exclusive). Equal to fromLine for a pure insert. */
    readonly toLine: number;
    /** The replacement lines, without terminators. Empty removes the range. */
    readonly lines: readonly string[];
}

/** A splice of plain characters, in UTF-16 offsets of the whole source. */
export interface SourceEdit {
    readonly from: number;
    readonly to: number;
    readonly insert: string;
}

export class SourceDoc {
    private linesArr: string[];
    private starts: number[] | null = null;

    constructor(text: string) {
        this.linesArr = splitLines(text);
    }

    get lines(): readonly string[] {
        return this.linesArr;
    }

    get lineCount(): number {
        return this.linesArr.length;
    }

    get text(): string {
        return this.linesArr.join("\n");
    }

    /** Replaces whole lines and returns the line delta (new count minus old). */
    replaceLines(change: LineChange): number {
        const from = clamp(change.fromLine, 0, this.linesArr.length);
        const to = clamp(change.toLine, from, this.linesArr.length);
        const inserted = change.lines;
        this.linesArr.splice(from, to - from, ...inserted);
        if (this.linesArr.length === 0) this.linesArr.push("");
        this.starts = null;
        return inserted.length - (to - from);
    }

    /** Applies a character splice and reports which lines it touched, for re-sectioning. */
    applyEdit(edit: SourceEdit): LineChange {
        const fromLine = this.lineAt(edit.from);
        const toLine = this.lineAt(edit.to) + 1;
        const blockStart = this.lineStart(fromLine);
        const original = this.linesArr.slice(fromLine, toLine).join("\n");
        const text = original.slice(0, edit.from - blockStart) + edit.insert + original.slice(edit.to - blockStart);
        const change = { fromLine, toLine, lines: splitLines(text) };
        this.replaceLines(change);
        return change;
    }

    /** UTF-16 offset of the first character of [line]. */
    lineStart(line: number): number {
        const starts = this.lineStarts();
        return starts[clamp(line, 0, starts.length - 1)] ?? 0;
    }

    /** The line containing [offset], by binary search over line starts. */
    lineAt(offset: number): number {
        const starts = this.lineStarts();
        let lo = 0;
        let hi = starts.length - 1;
        while (lo < hi) {
            const mid = (lo + hi + 1) >> 1;
            if ((starts[mid] ?? 0) <= offset) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private lineStarts(): number[] {
        if (this.starts) return this.starts;
        const starts = new Array<number>(this.linesArr.length);
        let at = 0;
        for (let i = 0; i < this.linesArr.length; i++) {
            starts[i] = at;
            at += (this.linesArr[i] ?? "").length + 1;
        }
        this.starts = starts;
        return starts;
    }
}

/** Splits on LF; a CR before the LF stays on the line so offsets match the bytes Kotlin holds. */
export function splitLines(text: string): string[] {
    return text.split("\n");
}

function clamp(value: number, min: number, max: number): number {
    return Math.min(Math.max(value, min), max);
}
