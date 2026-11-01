/**
 * Display width in terminal-style columns, used to re-pad padded tables.
 *
 * East Asian wide and fullwidth characters and emoji take two columns, every
 * other grapheme one (docs/ux-spec.md, "Tables"). Counting UTF-16 units would
 * misalign every table with an accent, a CJK word or an emoji in it.
 */
const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });
const EMOJI = /\p{Extended_Pictographic}/u;

export function displayWidth(text: string): number {
    let width = 0;
    for (const { segment } of segmenter.segment(text)) {
        const cp = segment.codePointAt(0) ?? 0;
        width += isEmoji(segment, cp) || isWide(cp) ? 2 : 1;
    }
    return width;
}

/** Pictographs above U+1F000, or any with the emoji presentation selector. */
function isEmoji(segment: string, cp: number): boolean {
    return EMOJI.test(segment) && (cp >= 0x1f000 || segment.includes("\ufe0f"));
}

/** Wide/Fullwidth blocks from Unicode's EastAsianWidth.txt, coarsened to ranges. */
function isWide(cp: number): boolean {
    return (
        (cp >= 0x1100 && cp <= 0x115f) ||
        (cp >= 0x2e80 && cp <= 0x303e) ||
        (cp >= 0x3041 && cp <= 0x33ff) ||
        (cp >= 0x3400 && cp <= 0x4dbf) ||
        (cp >= 0x4e00 && cp <= 0x9fff) ||
        (cp >= 0xa000 && cp <= 0xa4cf) ||
        (cp >= 0xac00 && cp <= 0xd7a3) ||
        (cp >= 0xf900 && cp <= 0xfaff) ||
        (cp >= 0xfe30 && cp <= 0xfe4f) ||
        (cp >= 0xff00 && cp <= 0xff60) ||
        (cp >= 0xffe0 && cp <= 0xffe6) ||
        (cp >= 0x20000 && cp <= 0x3fffd)
    );
}
