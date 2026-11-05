/**
 * Draws the blank lines a document has between blocks, beyond the usual one.
 *
 * Markdown reads any number of blank lines as a single gap, so a file with
 * twenty empty lines between two paragraphs showed them butted together,
 * while the Code view showed twenty lines (Andrew, 2026-11-04). Preview is
 * the document as written, so each blank line after the first between two
 * top-level blocks becomes an empty line of its own: a `div.blank-line`
 * carrying the line it stands for, which a tap can type on.
 *
 * - The first blank line of a gap is the gap itself (the blocks' margins).
 * - Only top-level gaps: blank lines inside a list or a quote change how it
 *   renders (a loose list), not how much space there is.
 * - A section with no blocks draws nothing: a blank file keeps its own tap
 *   rule (editor.ts beginBelowEnd).
 */
import type { MarkdownIt, StateCore, Token } from "./mdTypes";

/** The class of a drawn blank line; the page finds them by it. */
export const BLANK_LINE = "blank-line";

const TOKEN = "blank_line";

/**
 * The line after [token]'s last line with text. A list's map runs on over the blank lines after it;
 * a fence's never does, and an unclosed one's blank lines are its code.
 */
function contentEnd(token: Token, lines: readonly string[]): number {
    let end = token.map?.[1] ?? 0;
    if (token.type === "fence" || token.type === "code_block") return end;
    const start = token.map?.[0] ?? 0;
    while (end - 1 > start && (lines[end - 1] ?? "").trim() === "") end--;
    return end;
}

function spacers(state: StateCore, from: number, to: number): Token[] {
    const out: Token[] = [];
    for (let line = from; line < to; line++) {
        const token = new state.Token(TOKEN, "div", 0);
        token.block = true;
        token.map = [line, line + 1];
        out.push(token);
    }
    return out;
}

function addBlankLines(state: StateCore): void {
    const lines = state.src.split("\n");
    const tokens: Token[] = [];
    let previousEnd: number | null = null;
    let openEnd = 0;
    for (const token of state.tokens) {
        const top = token.level === 0 && token.block;
        if (top && token.map && token.nesting >= 0) {
            // Leading blank lines (the document's first section only) are all drawn: no block is above them.
            const from = previousEnd === null ? 0 : previousEnd + 1;
            tokens.push(...spacers(state, from, token.map[0]));
            openEnd = contentEnd(token, lines);
        }
        tokens.push(token);
        if (top && token.nesting <= 0)
            previousEnd = Math.max(previousEnd ?? 0, token.map ? contentEnd(token, lines) : openEnd);
    }
    if (previousEnd !== null) tokens.push(...spacers(state, previousEnd + 1, lines.length));
    state.tokens = tokens;
}

export function blankLinesPlugin(md: MarkdownIt): void {
    md.core.ruler.push("blank_lines", addBlankLines);
    md.renderer.rules[TOKEN] = (tokens, i) => {
        const line = tokens[i]?.map?.[0] ?? 0;
        return `<div class="${BLANK_LINE}" data-rl="${line}" data-rle="${line + 1}" aria-hidden="true"></div>\n`;
    };
}
