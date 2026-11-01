/**
 * Document-wide definitions that a section render needs but cannot see:
 * link reference definitions, footnote numbering and abbreviations.
 *
 * markdown-it resolves `[text][ref]` through `env.references`, so the page
 * collects every definition once per change and hands the same env to each
 * section render. Footnotes are numbered in order of first reference across
 * the whole document, as a single render would number them.
 */
export interface ReferenceDef {
    readonly label: string;
    readonly href: string;
    readonly title: string;
}

export interface DocDefinitions {
    readonly references: readonly ReferenceDef[];
    /** Footnote label -> its 1-based number, by first reference. */
    readonly footnoteNumbers: Readonly<Record<string, number>>;
    /** Every footnote label that has a definition. */
    readonly footnoteLabels: readonly string[];
    /** Abbreviation -> expansion. */
    readonly abbreviations: Readonly<Record<string, string>>;
}

const FENCE = /^ {0,3}(`{3,}|~{3,})/;
const REF_DEF =
    /^ {0,3}\[((?:[^\]\\]|\\.){1,999})\]:[ \t]*<?([^\s>]+)>?(?:[ \t]+(?:"([^"]*)"|'([^']*)'|\(([^)]*)\)))?[ \t]*$/;
const FOOTNOTE_DEF = /^ {0,3}\[\^([^\]\s]+)\]:/;
const FOOTNOTE_REF = /\[\^([^\]\s]+)\](?!:)/g;
const ABBR_DEF = /^ {0,3}\*\[([^\]]+)\]:[ \t]*(.+)$/;

/** Scans every line once, skipping fenced code, where none of these apply. */
export function collectDefinitions(lines: readonly string[]): DocDefinitions {
    const references: ReferenceDef[] = [];
    const footnoteNumbers: Record<string, number> = {};
    const footnoteLabels: string[] = [];
    const abbreviations: Record<string, string> = {};
    let fence: string | null = null;
    let next = 1;
    for (const line of lines) {
        const f = FENCE.exec(line);
        if (fence !== null) {
            if (f?.[1] && f[1][0] === fence[0] && f[1].length >= fence.length && line.trim() === f[1]) fence = null;
            continue;
        }
        if (f?.[1]) {
            fence = f[1];
            continue;
        }
        const fnDef = FOOTNOTE_DEF.exec(line);
        if (fnDef?.[1]) footnoteLabels.push(fnDef[1]);
        const ref = fnDef ? null : REF_DEF.exec(line);
        if (ref?.[1] && ref[2] && !ref[1].startsWith("^")) {
            references.push({ label: ref[1], href: ref[2], title: ref[3] ?? ref[4] ?? ref[5] ?? "" });
        }
        const abbr = ABBR_DEF.exec(line);
        if (abbr?.[1] && abbr[2]) abbreviations[abbr[1]] ??= abbr[2].trim();
        const body = fnDef ? line.slice(fnDef[0].length) : line;
        for (const m of body.matchAll(FOOTNOTE_REF)) {
            const label = m[1];
            if (label && footnoteNumbers[label] === undefined) footnoteNumbers[label] = next++;
        }
    }
    return { references, footnoteNumbers, footnoteLabels, abbreviations };
}

/** A line that defines something (`[x]: url`, `[^x]: text`, `*[X]: text`) or fences code, where definitions do not count. */
const DEFINING_LINE = /^ {0,3}(?:\*?\[(?:[^\]\\]|\\.)+\]:|`{3,}|~{3,})/;

/**
 * What in [text] can change the document's definitions: its defining and
 * fence lines, and its footnote references in order (they number the
 * footnotes). Two texts with the same signature leave
 * [collectDefinitions]'s answer unchanged, so an edit whose before and
 * after agree needs no whole-document rescan. Typing in a paragraph that
 * cites `[^1]` is such an edit; adding or removing the citation is not.
 */
export function definitionSignature(text: string): string {
    if (!text.includes("[") && !text.includes("`") && !text.includes("~")) return "";
    const parts: string[] = [];
    for (const line of text.split("\n")) {
        if (DEFINING_LINE.test(line)) parts.push(line);
        for (const m of line.matchAll(FOOTNOTE_REF)) parts.push(m[0]);
    }
    return parts.join("\n");
}
