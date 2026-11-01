/**
 * Locates rendered text runs in their source, for the inline source map.
 *
 * markdown-it tells us which lines a block came from, not where each piece of
 * inline text sits. We walk the inline tokens in order with a cursor and find
 * each text run in the source after it. A run is **exact** when its rendered
 * text is byte-for-byte the source slice; only exact runs are editable, so an
 * edit can be written back as a splice of the characters that changed.
 *
 * Safety rule: the source skipped between two runs may contain only Markdown
 * markup (emphasis markers, brackets, list markers, whitespace…). If letters
 * were skipped, the match may have landed in the wrong place (inside a URL,
 * say), and the run is marked inexact rather than risk editing the wrong bytes.
 */
export interface RunMatch {
    readonly from: number;
    readonly to: number;
    readonly exact: boolean;
}

const MARKUP_ONLY = /^[\s*_~=^[\]()!<>`\\|#+\-.:)\d$]*$/;
const TASK_BOX = /\[[ xX]\]/g;

/** True if [skipped] holds nothing but Markdown syntax. */
export function isMarkupOnly(skipped: string): boolean {
    return MARKUP_ONLY.test(skipped.replace(TASK_BOX, ""));
}

/**
 * Finds [content] in [source] at or after [cursor], searching at most [limit]
 * characters ahead. Returns null when it cannot be found at all.
 */
export function matchRun(source: string, cursor: number, content: string, limit = source.length): RunMatch | null {
    if (content.length === 0) return { from: cursor, to: cursor, exact: false };
    const at = source.indexOf(content, cursor);
    if (at >= 0 && at <= limit) {
        return { from: at, to: at + content.length, exact: isMarkupOnly(source.slice(cursor, at)) };
    }
    for (let p = cursor; p <= Math.min(limit, source.length - 1); p++) {
        const end = matchEscapedAt(source, p, content);
        if (end >= 0) return { from: p, to: end, exact: false };
    }
    return null;
}

const ENTITIES: Readonly<Record<string, string>> = {
    amp: "&",
    lt: "<",
    gt: ">",
    quot: '"',
    apos: "'",
    nbsp: " ",
    copy: "©",
};

/** Matches [content] at [p] allowing backslash escapes and character references; returns the end or -1. */
function matchEscapedAt(source: string, p: number, content: string): number {
    let i = 0;
    let j = p;
    while (i < content.length) {
        const want = content[i];
        const entity = source[j] === "&" ? matchEntity(source, j, content, i) : null;
        if (entity) {
            i = entity.i;
            j = entity.j;
        } else if (source[j] === want) {
            i++;
            j++;
        } else if (source[j] === "\\" && source[j + 1] === want) {
            i++;
            j += 2;
        } else {
            return -1;
        }
    }
    return j;
}

/** A character reference at source[j] whose decoded text continues content at i. */
function matchEntity(source: string, j: number, content: string, i: number): { i: number; j: number } | null {
    const semi = source.indexOf(";", j);
    if (semi < 0 || semi - j > 32) return null;
    const decoded = decodeEntity(source.slice(j + 1, semi));
    if (decoded === null || !content.startsWith(decoded, i)) return null;
    return { i: i + decoded.length, j: semi + 1 };
}

function decodeEntity(name: string): string | null {
    if (name.startsWith("#x") || name.startsWith("#X")) return safeFromCodePoint(parseInt(name.slice(2), 16));
    if (name.startsWith("#")) return safeFromCodePoint(parseInt(name.slice(1), 10));
    return ENTITIES[name] ?? null;
}

function safeFromCodePoint(cp: number): string | null {
    return Number.isFinite(cp) && cp > 0 && cp <= 0x10ffff ? String.fromCodePoint(cp) : null;
}

/**
 * markdown-it normalises CRLF to LF, which shifts offsets on files with
 * Windows line endings. This maps an offset in the normalised text back to the
 * raw text, so the source map always points at the bytes Kotlin holds.
 */
export class CrMap {
    private readonly crs: number[] = [];

    constructor(raw: string) {
        let removed = 0;
        for (let i = 0; i < raw.length; i++) {
            if (raw.charCodeAt(i) === 13 && raw.charCodeAt(i + 1) === 10) {
                this.crs.push(i - removed);
                removed++;
            }
        }
    }

    /** The raw offset of normalised offset [n]. */
    toRaw(n: number): number {
        let lo = 0;
        let hi = this.crs.length;
        while (lo < hi) {
            const mid = (lo + hi) >> 1;
            if ((this.crs[mid] ?? 0) < n) lo = mid + 1;
            else hi = mid;
        }
        return n + lo;
    }
}
