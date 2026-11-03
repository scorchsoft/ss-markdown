/**
 * Markdown as the words it shows, for "Paste as plain text" (docs/ux-spec.md,
 * "Copy and paste"): no `#`, `*`, `_`, `` ` ``, `>`, brackets, list markers,
 * task boxes or fences. A code block's lines stay exactly as written; each
 * list item is a line of its own; a table is one row per line with its
 * cells separated by a TAB (no pipes, no divider row); a link keeps its
 * text and a picture its alt text; blocks are separated by one blank line.
 *
 * Kotlin's twin is `core/markdown/…/paste/MarkdownPlainText.kt` (with
 * `PlainInline.kt`); `test/vectors/paste-conversions.txt` keeps them identical.
 */
import { decodeEntities } from "./entities";

const SQUASH = /[ \t\n\r\f\v\u00a0]+/g;

/** The words [markdown] shows, without its syntax. */
export function plainWords(markdown: string): string {
    const lines = markdown.replace(/\r\n?/g, "\n").split("\n");
    return blocks(lines)
        .map((b) => b.join("\n"))
        .join("\n\n");
}

/** Text longer than this is judged by its first this-many characters (Kotlin's `PasteClip.RICH_SCAN_CHARS`). */
export const RICH_SCAN_CHARS = 100_000;

/** True when [text] holds Markdown syntax: its words differ from the text once spacing is ignored. */
export function hasMarkdown(text: string): boolean {
    const head = text.slice(0, RICH_SCAN_CHARS);
    return plainWords(head).replace(SQUASH, "") !== head.replace(SQUASH, "");
}

const FENCE = /^( {0,3})(`{3,}|~{3,})(.*)$/;
const SETEXT = /^ {0,3}(?:=+|-+)[ \t]*$/;
const HEADING = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$/;
const CLOSING_HASHES = /(?:^|[ \t]+)#+$/;
const RULE = /^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$/;
const DIVIDER = /^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;
const QUOTE = /^ {0,3}>/;
const QUOTE_MARKER = /^ {0,3}>[ \t]?/;
const ITEM = /^( {0,3})([-+*]|[0-9]{1,9}[.)])([ \t]+|$)/;
const TASK = /^\[[ xX]\][ \t]+/;
const DEFINITION = /^ {0,3}\[(?!\^)[^\]]+\]:[ \t]*\S/;
const HARD_BREAK = /(?:\\| {2,})$/;
const BR_TAG = /<br\s*\/?>/gi;
const MAX_ITEM_SPACES = 4;

function isBlank(s: string): boolean {
    return s.trim() === "";
}

function leadingSpaces(s: string): number {
    return /^ */.exec(s)?.[0].length ?? 0;
}

function blocks(lines: readonly string[]): string[][] {
    const out: string[][] = [];
    let paragraph: string[] = [];
    const flush = (): void => {
        const words = paragraph
            .map((l) => plainInline(l.replace(HARD_BREAK, "").trim()).trim())
            .filter((w) => w !== "");
        if (words.length > 0) out.push(words);
        paragraph = [];
    };
    let i = 0;
    while (i < lines.length) {
        const line = lines[i] as string;
        const next = lines[i + 1];
        if (isBlank(line)) {
            flush();
            i++;
        } else if (FENCE.test(line) && !isBadFence(line)) {
            flush();
            i = fenced(lines, i, out);
        } else if (paragraph.length > 0 && SETEXT.test(line)) {
            flush();
            i++;
        } else if (HEADING.test(line)) {
            flush();
            const text = headingText(line);
            if (text !== "") out.push([text]);
            i++;
        } else if (RULE.test(line)) {
            flush();
            i++;
        } else if (next !== undefined && isTableStart(line, next)) {
            flush();
            i = table(lines, i, out);
        } else if (QUOTE.test(line)) {
            flush();
            let end = i;
            while (end < lines.length && QUOTE.test(lines[end] as string)) end++;
            out.push(...blocks(lines.slice(i, end).map((l) => l.replace(QUOTE_MARKER, ""))));
            i = end;
        } else if (ITEM.test(line)) {
            flush();
            i = list(lines, i, out);
        } else if (paragraph.length === 0 && DEFINITION.test(line)) {
            i++;
        } else {
            paragraph.push(line);
            i++;
        }
    }
    flush();
    return out;
}

function isBadFence(line: string): boolean {
    const m = FENCE.exec(line);
    if (!m) return true;
    return (m[2] ?? "").startsWith("`") && (m[3] ?? "").includes("`");
}

function headingText(line: string): string {
    const text = HEADING.exec(line)?.[2] ?? "";
    return plainInline(text.replace(CLOSING_HASHES, "")).trim();
}

/** A fenced block from [start]: its lines verbatim (less the fence's indent); returns the line after it. */
function fenced(lines: readonly string[], start: number, out: string[][]): number {
    const m = FENCE.exec(lines[start] as string) as RegExpExecArray;
    const indent = (m[1] ?? "").length;
    const marker = m[2] ?? "```";
    const close = new RegExp(`^ {0,3}\\${marker[0]}{${marker.length},}[ \\t]*$`);
    const code: string[] = [];
    let i = start + 1;
    while (i < lines.length && !close.test(lines[i] as string)) {
        const line = lines[i] as string;
        code.push(line.slice(Math.min(leadingSpaces(line), indent)));
        i++;
    }
    if (code.some((l) => !isBlank(l))) out.push(code);
    return Math.min(i + 1, lines.length);
}

function cells(row: string): string[] {
    let text = row.trim();
    if (text.startsWith("|")) text = text.slice(1);
    if (text.endsWith("|") && !text.endsWith("\\|")) text = text.slice(0, -1);
    return text.split(/(?<!\\)\|/);
}

function isTableStart(line: string, next: string): boolean {
    return line.includes("|") && DIVIDER.test(next) && next.includes("-") && cells(line).length === cells(next).length;
}

function table(lines: readonly string[], start: number, out: string[][]): number {
    const rows = [row(lines[start] as string)];
    let i = start + 2;
    while (i < lines.length && !isBlank(lines[i] as string) && (lines[i] as string).includes("|")) {
        rows.push(row(lines[i++] as string));
    }
    out.push(rows);
    return i;
}

function row(line: string): string {
    return cells(line)
        .map((cell) => plainInline(cell.trim().replace(BR_TAG, " ")).trim())
        .join("\t");
}

/** A list from [start]: each item's words, every line of them, with no blank lines. Returns the line after it. */
function list(lines: readonly string[], start: number, out: string[][]): number {
    const items: string[][] = [];
    let contentIndent = 0;
    let i = start;
    for (; i < lines.length; i++) {
        const line = lines[i] as string;
        const indent = leadingSpaces(line);
        const m = ITEM.exec(line);
        if (isBlank(line)) {
            const next = lines.slice(i).find((l) => !isBlank(l));
            if (next === undefined) break;
            if (leadingSpaces(next) < contentIndent && !ITEM.test(next)) break;
            items[items.length - 1]?.push("");
        } else if (RULE.test(line) && indent < contentIndent) {
            break;
        } else if (m && (items.length === 0 || indent < contentIndent)) {
            const width = (m[1] ?? "").length + (m[2] ?? "").length;
            const spaces = (m[3] ?? "").length;
            const fits = spaces >= 1 && spaces <= MAX_ITEM_SPACES;
            const gap = fits ? spaces : Math.min(spaces, 1);
            contentIndent = width + (fits ? spaces : 1);
            items.push([line.slice(width + gap).replace(TASK, "")]);
        } else if (indent >= contentIndent) {
            items[items.length - 1]?.push(line.slice(contentIndent));
        } else if (!isBlank(lines[i - 1] as string) && !startsBlock(line)) {
            items[items.length - 1]?.push(line.trimStart());
        } else {
            break;
        }
    }
    const words = items.flatMap((content) =>
        blocks(content)
            .flat()
            .filter((l) => !isBlank(l)),
    );
    if (words.length > 0) out.push(words);
    return i;
}

function startsBlock(line: string): boolean {
    return HEADING.test(line) || FENCE.test(line) || QUOTE.test(line) || RULE.test(line) || ITEM.test(line);
}

// Inline -------------------------------------------------------------------------------------------------

const AUTOLINK = /^<([A-Za-z][A-Za-z0-9+.-]{1,31}:[^ \t\n<>]*)>/;
const EMAIL = /^<([^ \t\n<>@]+@[^ \t\n<>@]+)>/;
const COMMENT = /^<!--[\s\S]*?-->/;
const TAG = /^<\/?[A-Za-z][A-Za-z0-9-]*(?:[ \t\n][^<>]*)?\/?>/;
const BR = /^<[Bb][Rr][ \t\n/>]/;
const FOOTNOTE = /^\[\^[^\]\s]+\]/;
const ENTITY = /^&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});/;
const DELIMITERS = "*_~^=";

interface Token {
    readonly text: string;
    readonly delimiter: string | null;
    count: number;
    canOpen: boolean;
    canClose: boolean;
}

/**
 * One line of inline Markdown as the words it shows: escapes resolved,
 * code spans as their code, links as their text, pictures as their alt
 * text, autolinks as their address, tags and footnote references gone, and
 * emphasis delimiters (`*`, `_`, `~`, `^`, `==`) removed where they pair up
 * as CommonMark pairs them; a delimiter with no partner stays, as it shows.
 */
export function plainInline(text: string): string {
    const tokens = tokenize(text);
    pair(tokens);
    return tokens.map((t) => (t.delimiter === null ? t.text : t.delimiter.repeat(t.count))).join("");
}

function tokenize(s: string): Token[] {
    const tokens: Token[] = [];
    let literal = "";
    const flush = (): void => {
        if (literal !== "") tokens.push({ text: literal, delimiter: null, count: 0, canOpen: false, canClose: false });
        literal = "";
    };
    let i = 0;
    while (i < s.length) {
        const c = s[i] as string;
        if (c === "\\" && i + 1 < s.length && isAsciiPunctuation(s[i + 1] as string)) {
            literal += s[i + 1];
            i += 2;
        } else if (c === "`") {
            const r = codeSpan(s, i);
            literal += r.text;
            i = r.end;
        } else if (c === "!" && s.startsWith("![", i)) {
            const link = parseLink(s, i + 1);
            if (link === null) {
                literal += c;
                i++;
            } else {
                literal += plainInline(link.text);
                i = link.end;
            }
        } else if (c === "[") {
            const note = FOOTNOTE.exec(s.slice(i));
            const link = note === null ? parseLink(s, i) : null;
            if (note !== null) {
                i += note[0].length;
            } else if (link !== null) {
                literal += plainInline(link.text);
                i = link.end;
            } else {
                literal += c;
                i++;
            }
        } else if (c === "<") {
            const rest = s.slice(i);
            const m = AUTOLINK.exec(rest) ?? EMAIL.exec(rest);
            const skip = COMMENT.exec(rest) ?? TAG.exec(rest);
            if (m !== null) {
                literal += m[1];
                i += m[0].length;
            } else if (skip !== null) {
                if (BR.test(skip[0])) literal += " ";
                i += skip[0].length;
            } else {
                literal += c;
                i++;
            }
        } else if (c === "&" && ENTITY.test(s.slice(i))) {
            const m = ENTITY.exec(s.slice(i)) as RegExpExecArray;
            literal += decodeEntities(m[0]);
            i += m[0].length;
        } else if (DELIMITERS.includes(c)) {
            let end = i;
            while (end < s.length && s[end] === c) end++;
            const count = end - i;
            if (c === "=" && count < 2) {
                literal += c;
            } else {
                flush();
                tokens.push(delimiter(c, count, s[i - 1], s[end]));
            }
            i = end;
        } else {
            literal += c;
            i++;
        }
    }
    flush();
    return tokens;
}

/** A code span at [start]: its code, unchanged; or the backticks as text when it never closes. */
function codeSpan(s: string, start: number): { text: string; end: number } {
    let end = start;
    while (end < s.length && s[end] === "`") end++;
    const run = end - start;
    let search = end;
    while (search < s.length) {
        const open = s.indexOf("`", search);
        if (open < 0) break;
        let close = open;
        while (close < s.length && s[close] === "`") close++;
        if (close - open === run) {
            let code = s.slice(end, open);
            if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && !isBlank(code)) {
                code = code.slice(1, -1);
            }
            return { text: code, end: close };
        }
        search = close;
    }
    return { text: "`".repeat(run), end };
}

/** CommonMark's flanking rules for a run of [c] between [before] and [after] (undefined: the line's edge). */
function delimiter(c: string, count: number, before: string | undefined, after: string | undefined): Token {
    const spaceBefore = before === undefined || /\s/u.test(before);
    const spaceAfter = after === undefined || /\s/u.test(after);
    const punctBefore = before !== undefined && isPunctuation(before);
    const punctAfter = after !== undefined && isPunctuation(after);
    const left = !spaceAfter && (!punctAfter || spaceBefore || punctBefore);
    const right = !spaceBefore && (!punctBefore || spaceAfter || punctAfter);
    const underscore = c === "_";
    return {
        text: "",
        delimiter: c,
        count,
        canOpen: underscore ? left && (!right || punctBefore) : left,
        canClose: underscore ? right && (!left || punctAfter) : right,
    };
}

/** Pairs closers with the nearest openers of the same character; paired characters are removed. */
function pair(tokens: Token[]): void {
    tokens.forEach((closer, ci) => {
        if (closer.delimiter === null || !closer.canClose) return;
        let oi = ci - 1;
        while (closer.count > 0 && oi >= 0) {
            const opener = tokens[oi] as Token;
            const use = closer.count >= 2 && opener.count >= 2 ? 2 : 1;
            const usable = opener.delimiter === closer.delimiter && opener.canOpen && opener.count > 0;
            if (!usable || (closer.delimiter === "=" && use < 2)) {
                oi--;
                continue;
            }
            opener.count -= use;
            closer.count -= use;
            // Delimiters between the pair can no longer pair across it.
            for (let k = oi + 1; k < ci; k++) {
                const t = tokens[k] as Token;
                t.canOpen = false;
                t.canClose = false;
            }
            if (opener.count === 0) oi--;
        }
    });
}

function isAsciiPunctuation(c: string): boolean {
    return /^[!-/:-@[-`{-~]$/.test(c);
}

function isPunctuation(c: string): boolean {
    return isAsciiPunctuation(c) || /^\p{P}$/u.test(c);
}

/** `[text](address)` and `[text][ref]` from a `[`: the text and where the link ends. */
function parseLink(s: string, open: number): { text: string; end: number } | null {
    const close = closing(s, open, "[", "]");
    if (close === null) return null;
    const text = s.slice(open + 1, close);
    const after = s[close + 1];
    if (after === "(") {
        const end = closing(s, close + 1, "(", ")");
        return end === null ? null : { text, end: end + 1 };
    }
    if (after === "[") {
        const end = s.indexOf("]", close + 2);
        return end < 0 ? null : { text, end: end + 1 };
    }
    return null;
}

/** The index of the [right] that closes the [left] at [open], counting nesting and skipping escapes. */
function closing(s: string, open: number, left: string, right: string): number | null {
    let depth = 0;
    for (let i = open; i < s.length; i++) {
        const c = s[i];
        if (c === "\\") i++;
        else if (c === left) depth++;
        else if (c === right && --depth === 0) return i;
    }
    return null;
}
