/**
 * What Enter inserts in Preview, written in the document's own syntax:
 * a list item continues the list with the same marker (numbers count up),
 * a task item starts unchecked, a quote stays quoted, code gets a plain
 * newline and anything else starts a new paragraph.
 */
export type BlockKind = "paragraph" | "listItem" | "code";

const LIST_PREFIX = /^(\s*(?:>\s?)*)(\s*)([-+*]|(\d{1,9})([.)]))(\s+)(\[[ xX]\]\s+)?/;
const QUOTE_PREFIX = /^(\s*(?:>\s?)+)/;

export function newlineInsert(lineText: string, kind: BlockKind, hardBreak = false): string {
    if (kind === "code") return "\n";
    if (hardBreak) return "\\\n";
    if (kind === "listItem") {
        const m = LIST_PREFIX.exec(lineText);
        if (m) {
            const [, quote = "", indent = "", bullet = "", num, delim = "", space = " ", task] = m;
            const marker = num !== undefined ? `${Number(num) + 1}${delim}` : bullet;
            return `\n${quote}${indent}${marker}${space}${task ? "[ ] " : ""}`;
        }
    }
    const quote = QUOTE_PREFIX.exec(lineText)?.[1];
    if (quote) return `\n${quote.trimEnd()}\n${quote}`;
    return "\n\n";
}
