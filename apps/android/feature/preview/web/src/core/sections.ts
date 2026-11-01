/**
 * Splits a document into top-level sections at blank lines that sit outside
 * fences, maths blocks, front matter, lists and open HTML blocks.
 *
 * markdown-it is not incremental, so the preview parses one section at a time
 * and re-parses only the sections whose text changed (docs/robustness.md,
 * "Preview (WebView): virtualised too"). A split must never change how the
 * document renders, so every rule below errs towards keeping lines together.
 */
import { contentHash } from "./hash";

export interface Section {
    /** First line (0-based, inclusive). */
    readonly startLine: number;
    /** Line after the last one (exclusive). Trailing blank lines belong to the section. */
    readonly endLine: number;
    /** Key for caching the render: a hash of the section text. */
    readonly hash: string;
}

const FENCE = /^ {0,3}(`{3,}|~{3,})/;
const MATH_FENCE = /^ {0,3}\$\$/;
const LIST_MARKER = /^ {0,3}(?:[-+*]|\d{1,9}[.)])(?:[ \t]|$)/;
const HTML_RAW_OPEN = /^ {0,3}<(script|pre|style|textarea)(?=[\s>]|$)/i;
const HTML_COMMENT_OPEN = /^ {0,3}<!--/;
const HTML_BLOCK_TAG =
    /^ {0,3}<(\/?)(div|details|section|article|aside|table|figure|blockquote|nav|header|footer|main|ul|ol|dl|form|fieldset)(?=[\s>/]|$)/i;

/** Scanner state at a line boundary. Sections always start in the clean state. */
interface ScanState {
    fence: string | null;
    math: boolean;
    frontMatter: boolean;
    rawHtmlEnd: RegExp | null;
    htmlDepth: number;
    inList: boolean;
}

function cleanState(): ScanState {
    return { fence: null, math: false, frontMatter: false, rawHtmlEnd: null, htmlDepth: 0, inList: false };
}

/** True while the scanner is inside a construct that a blank line does not end. */
function isOpen(s: ScanState): boolean {
    return s.fence !== null || s.math || s.frontMatter || s.rawHtmlEnd !== null || s.htmlDepth > 0;
}

/** Advances [s] past one line. */
function step(s: ScanState, line: string, index: number): void {
    if (s.frontMatter) {
        if (index > 0 && /^(?:---|\.\.\.)\s*$/.test(line)) s.frontMatter = false;
        return;
    }
    if (s.fence !== null) {
        const close = new RegExp(`^ {0,3}${s.fence[0] === "`" ? "`" : "~"}{${s.fence.length},}\\s*$`);
        if (close.test(line)) s.fence = null;
        return;
    }
    if (s.math) {
        if (/\$\$\s*$/.test(line)) s.math = false;
        return;
    }
    if (s.rawHtmlEnd !== null) {
        if (s.rawHtmlEnd.test(line)) s.rawHtmlEnd = null;
        return;
    }
    if (index === 0 && /^---\s*$/.test(line)) {
        s.frontMatter = true;
        return;
    }
    const fence = FENCE.exec(line);
    if (fence?.[1]) {
        s.fence = fence[1];
        return;
    }
    if (MATH_FENCE.test(line)) {
        const t = line.trim();
        // "$$ x $$" on one line is complete; a bare "$$" opens a block.
        s.math = !(t.length > 4 && t.endsWith("$$"));
        return;
    }
    stepHtml(s, line);
    if (line.trim().length > 0 && !/^[ \t]/.test(line)) s.inList = LIST_MARKER.test(line);
}

function stepHtml(s: ScanState, line: string): void {
    const raw = HTML_RAW_OPEN.exec(line);
    if (raw?.[1]) {
        const end = new RegExp(`</${raw[1]}>`, "i");
        if (!end.test(line.slice(raw.index + raw[0].length))) s.rawHtmlEnd = end;
        return;
    }
    if (HTML_COMMENT_OPEN.test(line)) {
        if (!/-->/.test(line.slice(line.indexOf("<!--") + 4))) s.rawHtmlEnd = /-->/;
        return;
    }
    const tag = HTML_BLOCK_TAG.exec(line);
    if (tag) {
        const closes = new RegExp(`</${tag[2]}\\s*>`, "i").test(line);
        if (tag[1] === "/") s.htmlDepth = Math.max(0, s.htmlDepth - 1);
        else if (!closes) s.htmlDepth++;
    }
}

/** Whether a blank line at [blank] separates two sections, judged by the next non-blank line. */
function isBoundary(lines: readonly string[], blank: number, s: ScanState): boolean {
    if (isOpen(s)) return false;
    let next = blank + 1;
    while (next < lines.length && (lines[next] ?? "").trim().length === 0) next++;
    if (next >= lines.length) return false;
    const line = lines[next] ?? "";
    if (/^[ \t]/.test(line)) return false; // indented continuation: list item, footnote, code
    if (s.inList && LIST_MARKER.test(line)) return false; // loose list
    return true;
}

function makeSection(lines: readonly string[], start: number, end: number): Section {
    return { startLine: start, endLine: end, hash: contentHash(lines.slice(start, end).join("\n")) };
}

/**
 * Scans from [start] (a section start, so the scanner state is clean) and
 * returns sections until [stopWhen] says a boundary is good enough to stop at.
 */
function scan(lines: readonly string[], start: number, stopWhen: (boundary: number) => boolean): Section[] {
    const out: Section[] = [];
    const s = cleanState();
    let sectionStart = start;
    for (let i = start; i < lines.length; i++) {
        const line = lines[i] ?? "";
        if (line.trim().length === 0 && isBoundary(lines, i, s)) {
            let end = i + 1;
            while (end < lines.length && (lines[end] ?? "").trim().length === 0) end++;
            out.push(makeSection(lines, sectionStart, end));
            sectionStart = end;
            Object.assign(s, cleanState());
            i = end - 1;
            if (stopWhen(end)) return out;
            continue;
        }
        step(s, line, i);
    }
    if (sectionStart < lines.length || out.length === 0) out.push(makeSection(lines, sectionStart, lines.length));
    return out;
}

/** Splits the whole document. */
export function splitSections(lines: readonly string[]): Section[] {
    return scan(lines, 0, () => false);
}

/**
 * How a re-split changes the section list: sections `[first, end)` of the old
 * list are replaced by [fresh], and every old section from [end] on moves by
 * [delta] lines (same text, same hash).
 */
export interface Resplit {
    readonly first: number;
    readonly end: number;
    readonly fresh: readonly Section[];
    readonly delta: number;
}

/**
 * Re-splits after lines [fromLine, oldToLine) were replaced by lines
 * [fromLine, newToLine). Scanning restarts at the section that held the edit
 * and stops at the first boundary past the edit that matches an old boundary
 * (shifted by the line delta), so a keystroke costs one or two sections, not
 * the whole document. Old boundaries are found by binary search, so the cost
 * does not grow with the number of sections after the edit (PERF-11).
 * Applying the result ([applyResplit]) always equals a full [splitSections].
 */
export function resplitRange(
    old: readonly Section[],
    lines: readonly string[],
    fromLine: number,
    oldToLine: number,
    newToLine: number,
): Resplit {
    const delta = newToLine - oldToLine;
    // The section holding the edit, then one back: the edit may have joined it to the previous one.
    let first = Math.max(0, upperBound(old, 0, fromLine) - 1);
    if (first > 0) first--;
    const startLine = old[first]?.startLine ?? 0;
    // Old sections that start at or after the edited range may be resumed, shifted by delta.
    const resumable = lowerBound(old, first + 1, oldToLine);
    let end = old.length;
    const fresh = scan(lines, startLine, (boundary) => {
        if (boundary <= newToLine) return false;
        const idx = lowerBound(old, resumable, boundary - delta);
        if (old[idx]?.startLine !== boundary - delta) return false;
        end = idx;
        return true;
    });
    return { first, end, fresh, delta };
}

/** The new section list a [Resplit] describes. */
export function applyResplit(old: readonly Section[], r: Resplit): Section[] {
    const tail = old.slice(r.end).map((sec) => ({
        startLine: sec.startLine + r.delta,
        endLine: sec.endLine + r.delta,
        hash: sec.hash,
    }));
    return [...old.slice(0, r.first), ...r.fresh, ...tail];
}

/** [applyResplit] of [resplitRange]: the whole new list, for callers that keep plain sections. */
export function resplit(
    old: readonly Section[],
    lines: readonly string[],
    fromLine: number,
    oldToLine: number,
    newToLine: number,
): Section[] {
    return applyResplit(old, resplitRange(old, lines, fromLine, oldToLine, newToLine));
}

/** The first index at or after [from] whose section starts at or after [line]. */
function lowerBound(sections: readonly Section[], from: number, line: number): number {
    let lo = from;
    let hi = sections.length;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if ((sections[mid]?.startLine ?? 0) < line) lo = mid + 1;
        else hi = mid;
    }
    return lo;
}

/** The first index at or after [from] whose section starts after [line]. */
function upperBound(sections: readonly Section[], from: number, line: number): number {
    let lo = from;
    let hi = sections.length;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if ((sections[mid]?.startLine ?? 0) <= line) lo = mid + 1;
        else hi = mid;
    }
    return lo;
}
