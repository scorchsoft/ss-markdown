/**
 * The Enter and Backspace rules the page applies itself, written in the
 * document's own syntax. Everything with list, quote or code structure is
 * Kotlin's: the page asks it to run Code's own command (`enter`,
 * `backspace`, `type}`…), so both views give the same text
 * (docs/editing-behaviours.md, rule 5). What is left here is Preview's own
 * convention: Enter in a paragraph or heading starts a new paragraph, and
 * in a quote a new paragraph of the quote (D14).
 */
export type BlockKind = "paragraph" | "listItem" | "code";

/** A list item's prefix: quote, indent, marker, spaces and any task box. */
const LIST_PREFIX = /^(?: {0,3}>[ \t]?)*[ \t]*(?:[-+*]|\d{1,9}[.)])(?:[ \t]+\[[ xX]\](?=[ \t]|$))?(?:[ \t]+|$)/;
const QUOTE_PREFIX = /^((?: {0,3}>[ \t]?)+)/;
const EMPTY_QUOTE = /^(?: {0,3}>[ \t]?)+[ \t]*$/;
const FENCE = /^ {0,3}(`{3,}|~{3,})/;

/** What Enter inserts in a paragraph, heading or quote; a hard break in the file's style for Shift+Enter. */
export function newlineInsert(lineText: string, hardBreak = false): string {
    if (hardBreak) return "\\\n";
    const quote = QUOTE_PREFIX.exec(lineText)?.[1];
    if (quote) return `\n${quote.trimEnd()}\n${quote}`;
    return "\n\n";
}

/** True for a line that starts with a list marker (quoted or nested): Enter there is Kotlin's. */
export function isListLine(lineText: string): boolean {
    return LIST_PREFIX.test(lineText);
}

/** True for a quote line with nothing after its `>`: Enter there ends the quote (Kotlin). */
export function isEmptyQuoteLine(lineText: string): boolean {
    return EMPTY_QUOTE.test(lineText) && !isListLine(lineText);
}

/** Where a list item's text starts in its line, or -1 when the line is no item. */
export function itemTextStart(lineText: string): number {
    const m = LIST_PREFIX.exec(lineText);
    return m ? m[0].length : -1;
}

/** True when only spaces or tabs (at least one) come before the caret: a closer typed there dedents (Kotlin). */
export function isIndentOnly(beforeCaret: string): boolean {
    return /^[ \t]+$/.test(beforeCaret);
}

/**
 * Leaving a fenced block in Preview (docs/editing-behaviours.md D4): Enter on
 * an empty last line when the line above is empty too. Both empty lines go,
 * and the caret lands on a new line after the closing fence (a blank line
 * keeps it from joining a paragraph right below). [line] is the caret's line
 * index in [lines]; null when the rule does not apply.
 */
export function codeExit(
    lines: readonly string[],
    line: number,
    lineStart: (line: number) => number,
): { from: number; to: number; insert: string; caret: number } | null {
    const fence = lines[line + 1];
    if (line < 2 || fence === undefined || !FENCE.test(fence)) return null;
    if ((lines[line] ?? "").trim() !== "" || (lines[line - 1] ?? "").trim() !== "") return null;
    const from = lineStart(line - 1);
    const to = lineStart(line + 1) + fence.length;
    const next = lines[line + 2];
    const insert = `${fence}\n${next !== undefined && next.trim() !== "" ? "\n" : ""}`;
    return { from, to, insert, caret: from + fence.length + 1 };
}

/** An edit the page makes itself, and where the caret goes after it. */
export interface LineEdit {
    readonly from: number;
    readonly to: number;
    readonly insert: string;
    readonly caret: number;
}

const TABLE_ROW = /^ {0,3}\|/;
const THEMATIC = /^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$/;

const blank = (text: string | undefined): boolean => (text ?? "").trim() === "";

/**
 * Backspace on a blank line in Preview, the line the caret sits on after Enter (Andrew, 2026-11-04:
 * after Enter twice split a list, Backspace left a blank line and the list spaced out). One extra
 * blank line goes per press, so presses walk back up the lines Enter made. At the usual one-line gap,
 * the line joins the text block above, the caret at its end; between two items of a list the whole
 * gap goes, so the list is one tight list again. Above a table or code, which the caret cannot end
 * in, only an extra blank line goes. Null when [line] is not blank, or there is nothing to do.
 */
export function blankLineBackspace(
    lines: readonly string[],
    line: number,
    lineStart: (line: number) => number,
): LineEdit | null {
    if (!blank(lines[line]) || line === 0) return null;
    let above = line - 1;
    while (above >= 0 && blank(lines[above])) above--;
    const start = lineStart(line);
    const upOne: LineEdit = { from: start - 1, to: start, insert: "", caret: start - 1 };
    const between = line - above - 1;
    if (above < 0 || between >= 2) return upOne;
    const prev = lines[above] ?? "";
    // The last blank line under a table stays (text right under one would be another row), and under
    // code or a rule (a rule's own Backspace is EditSession's): the caret cannot end in them anyway.
    if (TABLE_ROW.test(prev) || FENCE.test(prev) || THEMATIC.test(prev)) return null;
    const end = lineStart(above) + prev.length;
    let next = line + 1;
    while (next < lines.length && blank(lines[next])) next++;
    const quote = (text: string): string => QUOTE_PREFIX.exec(text)?.[1]?.replace(/\s/g, "") ?? "";
    const nextText = lines[next];
    if (isListLine(prev) && nextText !== undefined && isListLine(nextText) && quote(prev) === quote(nextText)) {
        return { from: end, to: lineStart(next), insert: "\n", caret: end };
    }
    return { from: end, to: start + (lines[line] ?? "").length, insert: "", caret: end };
}

/**
 * Enter on a blank line in Preview: one more line, the caret on it. Markdown shows any number of
 * blank lines as one gap, so the page draws the extra ones while the line is being edited
 * ([blankLinesAbove]); what is typed is still a paragraph of its own.
 */
export function blankLineEnter(
    lines: readonly string[],
    line: number,
    lineStart: (line: number) => number,
): LineEdit | null {
    if (!blank(lines[line])) return null;
    const at = lineStart(line);
    return { from: at, to: at, insert: "\n", caret: at + 1 };
}

/** The blank lines right above [line], back to the last line with text (or the document's start). */
export function blankLinesAbove(lines: readonly string[], line: number): number {
    let n = 0;
    for (let i = line - 1; i >= 0 && blank(lines[i]); i--) n++;
    return n;
}
