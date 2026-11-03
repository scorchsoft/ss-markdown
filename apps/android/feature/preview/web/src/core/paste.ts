/**
 * What a paste in Preview writes into the source (docs/ux-spec.md, "Copy
 * and paste"). A normal paste is Markdown kept literally (EB-CLIP-06): text
 * copied from a `.md` file, Preview's own copy and HTML converted to
 * Markdown all go in as written, so they render formatted; only what would
 * break the block it lands in is changed (a table cell's `|` and line
 * breaks, a list item's or a quote's later lines). "Paste as plain text"
 * inserts words: syntax in them is escaped, so they show exactly as pasted.
 */
import { escapeLineStart } from "./minimalEdit";
import { newlineInsert, type BlockKind } from "./newline";
import { hasMarkdown } from "./plainWords";
import type { SourceEdit } from "./sourceDoc";

export interface PasteTarget {
    readonly kind: BlockKind;
    readonly inTable: boolean;
    /** The source line the paste starts on. */
    readonly line: string;
    /** Only block markup (`- `, `> `, `## `…) is before the paste on its line. */
    readonly atBlockStart: boolean;
    /** Nothing follows the paste on its line. */
    readonly atLineEnd: boolean;
}

/** Clipboard text as the source holds it: LF line breaks, no NUL, no leading BOM. */
export function cleanClip(text: string): string {
    return text
        .replace(/\r\n?/g, "\n")
        .replace(/\0/g, "")
        .replace(/^\uFEFF/, "");
}

/**
 * Markdown syntax that could act, one character each: `\` before
 * punctuation; backticks and brackets; `*`, `~`, `^` unless spaced on both
 * sides; `_` unless inside a word; `<` before a tag or address; `==`; `$`
 * before maths; `&` of a character reference; `:` of an emoji shortcode.
 * A line's ends count as neighbours that could pair.
 */
const SYNTAX =
    /\\(?=[!-/:-@[-`{-~]|$)|[`[\]]|(?<!\s)[*~^]|[*~^](?!\s)|(?<![\p{L}\p{N}])_|_(?![\p{L}\p{N}])|<(?=[a-z/!?]|$)|(?<==|^)=|=(?==|$)|\$(?![\s\d])|&(?=#?\w+;)|:(?=[a-z_+-][\w+-]*:)/giu;

/**
 * Escapes the syntax in one pasted line, and only where it could act:
 * `snake_case`, `a * b`, `x < y`, `$5` and `10:30` stay as they are, since
 * every escape makes the text around it read-only in Preview
 * (`core/sourceMap.ts`, boundary rule).
 */
export function escapePasted(line: string, atLineStart: boolean): string {
    const out = line.replace(SYNTAX, "\\$&");
    return atLineStart ? escapeLineStart(out) : out;
}

/** True when only block markup precedes column [col] of [line]. */
export function atBlockStart(line: string, col: number): boolean {
    return /^\s*(?:>\s?)*\s*(?:(?:[-+*]|\d{1,9}[.)])\s+(?:\[[ xX]\]\s+)?|#{1,6}\s+)?$/.test(line.slice(0, col));
}

const ITEM = /^\s*(?:[-+*]|\d{1,9}[.)])\s+/;

/**
 * Words for "Paste as plain text" as source for [target]
 * (docs/editing-behaviours.md, EB-CLIP-14): verbatim in code; one line in a
 * table cell, its lines joined by `<br>` and `|` escaped; elsewhere each
 * line is escaped, so it shows exactly as pasted, and the lines are joined
 * by what Enter inserts there: paragraphs, quoted paragraphs in a quote,
 * continuation lines in a list item (decision D10).
 */
export function pasteWords(text: string, target: PasteTarget): string {
    const clean = cleanClip(text);
    if (target.kind === "code") return clean;
    const lines = clean.split("\n").filter((l) => l.trim());
    if (target.inTable) {
        return lines.map((l) => escapePasted(l.trim(), false).replace(/\|/g, "\\|")).join("<br>");
    }
    return lines
        .map((raw, i) => {
            const start = i > 0 || target.atBlockStart;
            return escapePasted(start ? raw.trim() : raw.trimEnd(), start);
        })
        .join(joinFor(target));
}

/**
 * A normal paste of [text] as source for [target] (EB-CLIP-06 to -13): the
 * text is Markdown and goes in as written. Code gets it verbatim; a table
 * cell gets one line (`<br>` between lines, `|` escaped so the table never
 * breaks); in a list item a Markdown list becomes items of this list and
 * other lines join the item as continuation lines (decision D10). Text with
 * no Markdown in it is prose: its lines become what Enter makes here.
 * [markdown] text with block syntax or several lines gets blocks of its
 * own, its lines quoted in a quote.
 */
export function pasteText(text: string, target: PasteTarget, markdown = hasMarkdown(text)): string {
    const clean = cleanClip(text);
    if (target.kind === "code") return clean;
    const lines = clean.split("\n").filter((l) => l.trim());
    if (target.inTable) return lines.map((l) => l.trim().replace(/(?<!\\)\|/g, "\\|")).join("<br>");
    if (target.kind === "listItem" && isList(lines)) return listItems(lines, target);
    if (target.kind === "listItem" || !markdown) {
        return lines.map((raw, i) => (i > 0 || target.atBlockStart ? raw.trim() : raw.trimEnd())).join(joinFor(target));
    }
    return ownBlocks(clean.replace(/^\n+|\n+$/g, ""), target);
}

/** What joins pasted lines in [target]: Enter's new paragraph, or an item's continuation line (D10). */
function joinFor(target: PasteTarget): string {
    return target.kind === "listItem" ? "\n" + under(target.line) : newlineInsert(target.line);
}

const QUOTE_PREFIX = /^((?: {0,3}>[ \t]?)+)/;

/**
 * Markdown with block syntax or several lines in blocks of its own, so a
 * copied list pastes as a list after a paragraph; inside a quote every
 * line keeps the quote's `>`. Inline Markdown on one line goes in as is.
 */
function ownBlocks(md: string, target: PasteTarget): string {
    if (!/\n|^\s*([-+*>]|#{1,6}|\d{1,9}[.)])(\s|$)/.test(md)) return md;
    const quote = QUOTE_PREFIX.exec(target.line)?.[1];
    if (!quote) return (target.atBlockStart ? "" : "\n\n") + md + (target.atLineEnd ? "" : "\n\n");
    const bare = quote.trimEnd();
    const body = md
        .split("\n")
        .map((l, i) => (i === 0 && target.atBlockStart ? l : l.trim() ? quote + l : bare))
        .join("\n");
    return (target.atBlockStart ? "" : `\n${bare}\n`) + body + (target.atLineEnd ? "" : `\n${bare}\n${quote}`);
}

const ITEM_PREFIX = /^(\s*(?:>\s?)*)(\s*)([-+*]|(\d{1,9})([.)]))(\s+)(\[[ xX]\]\s+)?/;

/** The start of the item after the one on [line]: same marker (numbers count up), a task unchecked. */
function nextItem(line: string): string {
    const m = ITEM_PREFIX.exec(line);
    if (!m) return "\n";
    const [, quote = "", indent = "", bullet = "", num, delim = "", space = " ", task] = m;
    return `\n${quote}${indent}${num === undefined ? bullet : Number(num) + 1 + delim}${space}${task ? "[ ] " : ""}`;
}

/** What lines inside the item on [line] start with: its quote, then spaces to its text. */
function under(line: string): string {
    const m = ITEM_PREFIX.exec(line);
    return m ? (m[1] ?? "") + " ".repeat(m[0].length - (m[1] ?? "").length - (m[7] ?? "").length) : "";
}

/** True when [lines] are a Markdown list: an item first, then items or indented lines. */
function isList(lines: readonly string[]): boolean {
    return ITEM.test(lines[0] ?? "") && lines.every((l) => ITEM.test(l) || /^\s/.test(l));
}

/**
 * A Markdown list pasted into an item: each of its items becomes an item of
 * this list (its marker, numbers counting on), the item split at the caret;
 * the items' text is Markdown and goes in as written (EB-CLIP-07).
 */
function listItems(lines: readonly string[], target: PasteTarget): string {
    let out = "";
    let line = target.line;
    const next = (): string => {
        const sep = nextItem(line);
        line = sep.slice(sep.lastIndexOf("\n") + 1);
        return sep;
    };
    lines.forEach((l, i) => {
        const item = ITEM.exec(l);
        if (!item) {
            out += "\n" + under(line) + l.trim();
            return;
        }
        let sep = i === 0 && target.atBlockStart ? "" : next();
        const rest = l.slice(item[0].length);
        if (/^\[[ xX]\]\s/.test(rest)) sep = sep.replace(/\[ \] $/, "");
        out += sep + rest;
        line += rest;
    });
    return target.atLineEnd ? out : out + next();
}

/** Markdown copied from Preview, for [target]: [pasteText] with the text taken as Markdown. */
export function pasteMarkdown(markdown: string, target: PasteTarget): string {
    return pasteText(markdown, target, true);
}

/**
 * `[label](url)` when [clip] is one bare web address and [label] is text,
 * not itself an address (EB-LINK-05, -07); null otherwise. Brackets in the
 * label are escaped.
 */
export function linkFor(label: string, clip: string): string | null {
    const url = clip.trim();
    if (!/^https?:\/\/\S+$/i.test(url) || /[\n`]|^\s*(https?:\/\/|www\.)/i.test(label)) return null;
    // Unbalanced parentheses would end the address early: `<>` holds it whole.
    const dest = url.split("(").length === url.split(")").length ? url : `<${url.replace(/[<>]/g, encodeURI)}>`;
    return `[${label.replace(/(^|[^\\])(?=[[\]])/g, "$1\\")}](${dest})`;
}

const ORDERED = /^(\s*(?:>\s?)*\s*)(\d{1,9})([.)])\s/;

/**
 * Renumbers the ordered items after line [after] that continue the list of
 * the item on line [item], once [added] items went in after it: the edits,
 * in source offsets. A list numbered `1.` throughout is left alone.
 */
export function renumberAfter(
    lines: readonly string[],
    lineStart: (line: number) => number,
    item: number,
    after: number,
    added: number,
): SourceEdit[] {
    const head = ORDERED.exec(lines[item] ?? "");
    const edits: SourceEdit[] = [];
    if (!head || added === 0) return edits;
    const [, prefix = "", num = ""] = head;
    let next = Number(num) + added;
    for (let i = after + 1; i < lines.length; i++) {
        const line = lines[i] ?? "";
        const m = ORDERED.exec(line);
        if (m?.[1] === prefix && m[3] === head[3]) {
            const n = m[2] ?? "";
            if (n === num && next === Number(num) + added) break;
            const at = lineStart(i) + prefix.length;
            const want = String(++next);
            if (n !== want) edits.push({ from: at, to: at + n.length, insert: want });
        } else if (!line.trim() || !line.startsWith(prefix + " ")) {
            break;
        }
    }
    return edits;
}
