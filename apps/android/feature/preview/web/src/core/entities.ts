/**
 * Character references in Markdown text: numeric ones, and the named ones
 * clipboard text uses. An unknown name stays as written. The same table as
 * Kotlin's `HtmlEntities` (`core/markdown/…/paste/HtmlEntities.kt`).
 */
const NAMED: Readonly<Record<string, string>> = {
    amp: "&",
    lt: "<",
    gt: ">",
    quot: '"',
    apos: "'",
    nbsp: "\u00a0",
    copy: "©",
    reg: "®",
    trade: "™",
    hellip: "…",
    mdash: "—",
    ndash: "–",
    lsquo: "‘",
    rsquo: "’",
    ldquo: "“",
    rdquo: "”",
    bull: "•",
    middot: "·",
    times: "×",
    divide: "÷",
    deg: "°",
    euro: "€",
    pound: "£",
    cent: "¢",
    yen: "¥",
    sect: "§",
    para: "¶",
    laquo: "«",
    raquo: "»",
    shy: "\u00ad",
    zwj: "\u200d",
    zwnj: "\u200c",
    ensp: "\u2002",
    emsp: "\u2003",
    thinsp: "\u2009",
    larr: "←",
    rarr: "→",
    uarr: "↑",
    darr: "↓",
    plusmn: "±",
    frac12: "½",
    frac14: "¼",
    frac34: "¾",
    iexcl: "¡",
    iquest: "¿",
    check: "✓",
};

const REFERENCE = /&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});/g;
const MAX_CODE_POINT = 0x10ffff;

function codePoint(value: number): string {
    const bad = value === 0 || value > MAX_CODE_POINT || (value >= 0xd800 && value <= 0xdfff);
    return String.fromCodePoint(bad ? 0xfffd : value);
}

/** [text] with its character references decoded. */
export function decodeEntities(text: string): string {
    if (!text.includes("&")) return text;
    return text.replace(REFERENCE, (whole, body: string) => {
        if (/^#[xX]/.test(body)) return codePoint(Number.parseInt(body.slice(2), 16));
        if (body.startsWith("#")) return codePoint(Number.parseInt(body.slice(1), 10));
        return Object.prototype.hasOwnProperty.call(NAMED, body) ? (NAMED[body] as string) : whole;
    });
}
