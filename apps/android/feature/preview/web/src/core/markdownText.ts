/**
 * Words from pasted HTML written as Markdown text: syntax characters are
 * escaped only where they could act, and block syntax at a line's start is
 * escaped so a line of text never turns into a heading or a list. Kotlin's
 * twin is `core/markdown/…/paste/MarkdownText.kt`; spaces are spelled out so
 * the two regexes agree character for character.
 */

/** A no-break space; HTML keeps it, Markdown output turns it into a space. */
export const NBSP = "\u00a0";

/** Whitespace the HTML parser collapses (not the no-break space). */
export const COLLAPSIBLE = /[ \t\n\r\f]+/g;

const INLINE_SYNTAX =
    /\\(?=[!-/:-@[-`{-~]|$)|[`[\]]|(?<![ \t\n\r\f\v\u00a0])[*~^]|[*~^](?![ \t\n\r\f\v\u00a0])|(?<![\p{L}\p{N}])_|_(?![\p{L}\p{N}])|<(?=[A-Za-z/!?]|$)|(?<==|^)=|=(?==|$)|\$(?![ \t\n\r\f\v\u00a00-9])|&(?=#?[A-Za-z0-9_]+;)|:(?=[A-Za-z_+-][A-Za-z0-9_+-]*:)/gu;

const BLOCK_START = /^(?:[#+-](?=[ \t]|$)|>|-(?=(?:[ \t]*-){2,}[ \t]*$))/;
const NUMBER_START = /^([0-9]{1,9})(?=[.)](?:[ \t]|$))/;

/** [text] with every character that could act as inline syntax escaped. */
export function escapeInline(text: string): string {
    return text.replace(INLINE_SYNTAX, "\\$&");
}

/** [line] with block syntax at its start escaped (`\# `, `\- `, `1\. `, `\>`). */
export function escapeBlockStart(line: string): string {
    return line.replace(BLOCK_START, "\\$&").replace(NUMBER_START, "$1\\");
}

/** The longest run of [c] in [text]. */
export function longestRun(text: string, c: string): number {
    let best = 0;
    let run = 0;
    for (const ch of text) {
        run = ch === c ? run + 1 : 0;
        if (run > best) best = run;
    }
    return best;
}
