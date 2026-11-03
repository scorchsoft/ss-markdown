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
