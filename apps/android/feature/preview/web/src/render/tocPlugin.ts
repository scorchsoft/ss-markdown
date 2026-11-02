/**
 * `[[toc]]` on a line of its own becomes a placeholder that the page fills
 * from the document-wide outline. A section render cannot see the other
 * sections' headings, so the table of contents is built where they all meet.
 */
import type { MarkdownIt } from "./mdTypes";
import type { StateBlock } from "./mdTypes";

function tocBlock(state: StateBlock, startLine: number, _endLine: number, silent: boolean): boolean {
    const pos = (state.bMarks[startLine] ?? 0) + (state.tShift[startLine] ?? 0);
    const line = state.src.slice(pos, state.eMarks[startLine] ?? 0).trim();
    if (!/^\[\[toc\]\]$/i.test(line)) return false;
    if (silent) return true;
    const token = state.push("toc", "nav", 0);
    token.block = true;
    token.map = [startLine, startLine + 1];
    state.line = startLine + 1;
    return true;
}

export function tocPlugin(md: MarkdownIt): void {
    md.block.ruler.before("paragraph", "toc", tocBlock);
}
