/**
 * Annotates tokens with where they came from in the section's source.
 *
 * - Every block token with a `map` gets `data-rl` / `data-rle`: its first and
 *   end line relative to the section. The page adds the section's start line
 *   to produce the absolute `data-line` / `data-line-end` used by scroll sync.
 * - Every inline text run gets `meta.run`: its source offsets and whether it
 *   is an exact copy of the source (see core/sourceMap.ts). Renderers turn
 *   exact runs into `<span data-s data-e>` that tap-to-edit can write back to.
 */
import type { MarkdownIt } from "./mdTypes";
import type { StateCore } from "./mdTypes";
import type { Token } from "./mdTypes";
import { matchRun, type RunMatch } from "../core/sourceMap";

export interface RunMeta {
    run?: RunMatch;
}

/** Offsets of each line start in [src], for map -> offset conversion. */
function lineStarts(src: string): number[] {
    const starts = [0];
    for (let i = 0; i < src.length; i++) if (src.charCodeAt(i) === 10) starts.push(i + 1);
    return starts;
}

/** Index just after the `)` that closes the `(` at [open], honouring nesting. */
function closeParen(src: string, open: number): number {
    let depth = 0;
    for (let i = open; i < src.length; i++) {
        const c = src[i];
        if (c === "\\") i++;
        else if (c === "(") depth++;
        else if (c === ")" && --depth === 0) return i + 1;
        else if (c === "\n" && src[i + 1] === "\n") break;
    }
    return open + 1;
}

/** Moves past a link's tail: `](url "title")`, `][ref]`, `]` or an autolink's `>`. */
function skipLinkTail(src: string, cursor: number, markup: string): number {
    if (markup === "linkify") return cursor;
    if (markup === "autolink") return src[cursor] === ">" ? cursor + 1 : cursor;
    const close = src.indexOf("]", cursor);
    if (close < 0) return cursor;
    if (src[close + 1] === "(") return closeParen(src, close + 1);
    if (src[close + 1] === "[") return src.indexOf("]", close + 2) + 1 || close + 1;
    return close + 1;
}

/** Walks one inline token's children, assigning runs and advancing the cursor. */
function mapInline(src: string, inline: Token, start: number, limit: number): number {
    let cursor = start;
    for (const child of inline.children ?? []) {
        switch (child.type) {
            case "text":
            case "code_inline": {
                const m = matchRun(src, cursor, child.content, limit);
                if (m) {
                    (child.meta as RunMeta | null) = { ...(child.meta ?? {}), run: m };
                    cursor = m.to;
                }
                break;
            }
            case "link_close":
                cursor = skipLinkTail(src, cursor, child.markup);
                break;
            case "image": {
                const bang = src.indexOf("![", cursor);
                if (bang >= 0) cursor = skipLinkTail(src, bang + 2, "");
                break;
            }
            case "footnote_ref": {
                const open = src.indexOf("[^", cursor);
                if (open >= 0) cursor = src.indexOf("]", open) + 1 || cursor;
                break;
            }
            case "emoji": {
                const at = src.indexOf(`:${child.markup}:`, cursor);
                if (at >= 0) cursor = at + child.markup.length + 2;
                break;
            }
            case "html_inline":
            case "math_inline": {
                const needle = child.type === "math_inline" ? `$${child.content}$` : child.content;
                const at = src.indexOf(needle, cursor);
                if (at >= 0) cursor = at + needle.length;
                break;
            }
            default:
                break;
        }
    }
    return cursor;
}

function annotate(state: StateCore): void {
    const src = state.src;
    const starts = lineStarts(src);
    let cursor = 0;
    let blockEnd = src.length;
    for (const token of state.tokens) {
        if (token.map && token.block) {
            cursor = Math.max(cursor, starts[token.map[0]] ?? src.length);
            blockEnd = starts[token.map[1]] ?? src.length;
            if (token.nesting >= 0 || token.type.endsWith("_open")) {
                token.attrSet("data-rl", String(token.map[0]));
                token.attrSet("data-rle", String(token.map[1]));
            }
        }
        if (token.type === "inline") cursor = mapInline(src, token, cursor, blockEnd);
    }
}

export function sourceMapPlugin(md: MarkdownIt): void {
    md.core.ruler.push("source_map", annotate);
}
