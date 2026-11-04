/**
 * `$inline$` and `$$ block $$` maths, rendered by KaTeX.
 *
 * This runs inside the render worker: KaTeX's renderToString needs no DOM.
 * `trust: false` keeps \href, \url and \htmlClass out, and maxExpand/maxSize
 * stop a hostile macro from hanging the worker or drawing a giant box
 * (docs/robustness.md, "Preview (WebView): virtualised too").
 */
import katex from "katex";
import type { MarkdownIt } from "./mdTypes";
import type { StateBlock } from "./mdTypes";
import type { StateInline } from "./mdTypes";

const KATEX_OPTIONS = {
    throwOnError: false,
    trust: false,
    strict: "ignore" as const,
    maxExpand: 1000,
    maxSize: 50,
    output: "htmlAndMathml" as const,
};

/** Renders TeX, falling back to an inline error that shows the source. */
export function renderTex(tex: string, displayMode: boolean): string {
    try {
        return katex.renderToString(tex, { ...KATEX_OPTIONS, displayMode });
    } catch (e) {
        const message = e instanceof Error ? e.message : String(e);
        return `<span class="math-error" title="${escapeAttr(message)}">${escapeAttr(tex)}</span>`;
    }
}

function escapeAttr(text: string): string {
    return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function mathInline(state: StateInline, silent: boolean): boolean {
    const src = state.src;
    const start = state.pos;
    if (src.charCodeAt(start) !== 0x24 /* $ */ || src.charCodeAt(start + 1) === 0x24) return false;
    // "$5 and $6" is money, not maths: the opener must not be followed by a space.
    const next = src.charCodeAt(start + 1);
    if (Number.isNaN(next) || next === 0x20 || next === 0x09) return false;
    let end = start + 1;
    while ((end = src.indexOf("$", end)) !== -1) {
        if (src.charCodeAt(end - 1) === 0x5c /* \ */) {
            end++;
            continue;
        }
        break;
    }
    if (end === -1 || end === start + 1) return false;
    const before = src.charCodeAt(end - 1);
    const after = src.charCodeAt(end + 1);
    if (before === 0x20 || before === 0x09 || (after >= 0x30 && after <= 0x39)) return false;
    if (!silent) {
        const token = state.push("math_inline", "math", 0);
        token.markup = "$";
        token.content = src.slice(start + 1, end);
    }
    state.pos = end + 1;
    return true;
}

function mathBlock(state: StateBlock, startLine: number, endLine: number, silent: boolean): boolean {
    let pos = (state.bMarks[startLine] ?? 0) + (state.tShift[startLine] ?? 0);
    let max = state.eMarks[startLine] ?? 0;
    if ((state.sCount[startLine] ?? 0) - state.blkIndent >= 4) return false;
    if (!state.src.startsWith("$$", pos)) return false;
    if (silent) return true;
    pos += 2;
    let firstLine = state.src.slice(pos, max);
    let lastLine = "";
    let next = startLine;
    let found = false;
    if (firstLine.trim().endsWith("$$")) {
        firstLine = firstLine.trim().slice(0, -2);
        found = true;
    }
    while (!found) {
        next++;
        if (next >= endLine) break;
        pos = (state.bMarks[next] ?? 0) + (state.tShift[next] ?? 0);
        max = state.eMarks[next] ?? 0;
        if (pos < max && (state.tShift[next] ?? 0) < state.blkIndent) break;
        const line = state.src.slice(pos, max).trim();
        if (line.endsWith("$$")) {
            lastLine = line.slice(0, -2);
            found = true;
        }
    }
    // Unclosed, it ends where its list item or quote does (a line indented less than the container's
    // content), and that line belongs to what follows: before, it was swallowed, and with it every line
    // after an unclosed `$$` in a list item ("- $$" then text below the list vanished).
    state.line = found ? next + 1 : next;
    const token = state.push("math_block", "math", 0);
    token.block = true;
    const middle = next > startLine + 1 ? state.getLines(startLine + 1, next, state.tShift[startLine] ?? 0, true) : "";
    token.content = [firstLine.trim(), middle, lastLine].filter((s) => s.trim().length > 0).join("\n");
    token.map = [startLine, state.line];
    token.markup = "$$";
    return true;
}

export function mathPlugin(md: MarkdownIt): void {
    md.inline.ruler.after("escape", "math_inline", mathInline);
    md.block.ruler.after("blockquote", "math_block", mathBlock, {
        alt: ["paragraph", "reference", "blockquote", "list"],
    });
}
