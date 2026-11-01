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

/**
 * Above this many lines a splice is rebuilt with `concat`: spreading a huge
 * array into `splice` passes every line as an argument and overflows the
 * stack (about 200k lines in V8; PERF-11, the "paste of 50 MB" case).
 */
const SPREAD_LIMIT = 16_384;

export class SourceDoc {
    private linesArr: string[];
    /**
     * Line starts as a Fenwick tree over `length + 1` of each line, so a
     * keystroke updates one entry in O(log n) instead of invalidating an
     * array the size of the document (PERF-11). Null until first needed
     * after an edit that changed the line count.
     */
    private index: Float64Array | null = null;

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
        if (inserted.length === to - from) {
            // Same line count, the typing case: patch the index in place.
            for (let i = 0; i < inserted.length; i++) {
                const line = inserted[i] ?? "";
                const delta = line.length - (this.linesArr[from + i] ?? "").length;
                this.linesArr[from + i] = line;
                if (delta !== 0 && this.index) addAt(this.index, from + i, delta);
            }
            return 0;
        }
        if (inserted.length < SPREAD_LIMIT && to - from < SPREAD_LIMIT) {
            this.linesArr.splice(from, to - from, ...inserted);
        } else {
            this.linesArr = this.linesArr.slice(0, from).concat(inserted, this.linesArr.slice(to));
        }
        if (this.linesArr.length === 0) this.linesArr.push("");
        this.index = null;
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

    /** UTF-16 offset of the first character of [line], in O(log n). */
    lineStart(line: number): number {
        const tree = this.tree();
        let i = clamp(line, 0, this.linesArr.length - 1);
        let sum = 0;
        while (i > 0) {
            sum += tree[i] ?? 0;
            i -= i & -i;
        }
        return sum;
    }

    /** The line containing [offset] (the last line past the end), by descending the Fenwick tree. */
    lineAt(offset: number): number {
        const tree = this.tree();
        const n = this.linesArr.length;
        let pos = 0;
        let rest = offset;
        for (let step = highestBit(n); step > 0; step >>= 1) {
            const next = pos + step;
            if (next <= n && (tree[next] ?? 0) <= rest) {
                pos = next;
                rest -= tree[next] ?? 0;
            }
        }
        return Math.min(pos, n - 1);
    }

    private tree(): Float64Array {
        if (this.index) return this.index;
        const n = this.linesArr.length;
        const tree = new Float64Array(n + 1);
        for (let i = 1; i <= n; i++) {
            tree[i] = (tree[i] ?? 0) + (this.linesArr[i - 1] ?? "").length + 1;
            const parent = i + (i & -i);
            if (parent <= n) tree[parent] = (tree[parent] ?? 0) + (tree[i] ?? 0);
        }
        this.index = tree;
        return tree;
    }
}

/** Adds [delta] to the length of [line] in a Fenwick [tree]. */
function addAt(tree: Float64Array, line: number, delta: number): void {
    for (let i = line + 1; i < tree.length; i += i & -i) tree[i] = (tree[i] ?? 0) + delta;
}

function highestBit(n: number): number {
    let bit = 1;
    while (bit * 2 <= n) bit *= 2;
    return bit;
}

/** Splits on LF; a CR before the LF stays on the line so offsets match the bytes Kotlin holds. */
export function splitLines(text: string): string[] {
    return text.split("\n");
}

function clamp(value: number, min: number, max: number): number {
    return Math.min(Math.max(value, min), max);
}
