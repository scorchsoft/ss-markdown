/**
 * Turns a change in rendered text into the smallest possible source edit.
 *
 * The inline source map guarantees that an editable text run's rendered text is
 * byte-for-byte equal to a slice of the source. A change inside that run can
 * therefore be written back as a splice of exactly the characters that changed:
 * fixing a typo never rewrites bytes the user did not touch (docs/ux-spec.md,
 * "Editing in Preview").
 */
import type { SourceEdit } from "./sourceDoc";

/** A rendered text run and the source slice it came from. */
export interface MappedRun {
    /** Start offset in the source (inclusive). */
    readonly from: number;
    /** End offset in the source (exclusive). */
    readonly to: number;
    /** The run's text when it was rendered; equal to source.slice(from, to). */
    readonly text: string;
}

/** The changed middle of two strings, after trimming the common prefix and suffix. */
export interface TextDiff {
    readonly start: number;
    readonly oldEnd: number;
    readonly insert: string;
}

/** Common prefix/suffix diff that never splits a surrogate pair. */
export function diffText(oldText: string, newText: string): TextDiff | null {
    if (oldText === newText) return null;
    let start = 0;
    const max = Math.min(oldText.length, newText.length);
    while (start < max && oldText.charCodeAt(start) === newText.charCodeAt(start)) start++;
    if (start > 0 && isHighSurrogate(oldText.charCodeAt(start - 1))) start--;
    let oldEnd = oldText.length;
    let newEnd = newText.length;
    while (oldEnd > start && newEnd > start && oldText.charCodeAt(oldEnd - 1) === newText.charCodeAt(newEnd - 1)) {
        oldEnd--;
        newEnd--;
    }
    if (oldEnd < oldText.length && isLowSurrogate(oldText.charCodeAt(oldEnd))) {
        oldEnd++;
        newEnd++;
    }
    return { start, oldEnd, insert: newText.slice(start, newEnd) };
}

export interface EscapeContext {
    /** Inside a table cell, where a bare pipe would end the cell. */
    readonly inTable: boolean;
    /** The insert lands at the start of a line, where `#`, `>` and list markers are syntax. */
    readonly atLineStart: boolean;
}

const INLINE_SYNTAX = /[\\`*_[\]<]/g;

/**
 * Escapes Markdown syntax in text typed into Preview, so it stays text.
 * Typing `*` in Preview means a star, not the start of emphasis.
 */
export function escapeTyped(text: string, ctx: EscapeContext): string {
    let out = text.replace(INLINE_SYNTAX, (c) => `\\${c}`);
    if (ctx.inTable) out = out.replace(/\|/g, "\\|");
    if (ctx.atLineStart) out = out.replace(/^([#>+-]|\d+[.)])/, "\\$1");
    return out;
}

/** A no-break space, as Chrome shows a space at a text run's edge. */
const NBSP = "\u00a0";

/**
 * The source edit for a run whose rendered text became [newText], or null if
 * nothing changed. Only the changed characters of the run are replaced.
 */
export function runEdit(run: MappedRun, newText: string, ctx: EscapeContext): SourceEdit | null {
    // Chrome shows a space at a run's edge as a no-break space while text is typed beside it; that is
    // display, not a change. Only a run that already held one keeps what the browser gives back.
    const typed = run.text.includes(NBSP) ? newText : newText.split(NBSP).join(" ");
    const diff = diffText(run.text, typed);
    if (!diff) return null;
    const atLineStart = ctx.atLineStart && diff.start === 0;
    return {
        from: run.from + diff.start,
        to: run.from + diff.oldEnd,
        insert: escapeTyped(diff.insert, { inTable: ctx.inTable, atLineStart }),
    };
}

/** Applies [edit] to [source]; the reference implementation the tests check against. */
export function applyEdit(source: string, edit: SourceEdit): string {
    return source.slice(0, edit.from) + edit.insert + source.slice(edit.to);
}

function isHighSurrogate(code: number): boolean {
    return code >= 0xd800 && code <= 0xdbff;
}

function isLowSurrogate(code: number): boolean {
    return code >= 0xdc00 && code <= 0xdfff;
}
