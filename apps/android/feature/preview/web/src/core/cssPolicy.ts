/**
 * What a theme's Advanced CSS may contain, judged on the CSS as the browser
 * reads it. Twin of Kotlin's `core/theme` `CssPolicy`; change both together.
 *
 * The page's Content-Security-Policy is the real wall: it allows no network
 * at all, so even CSS that passed this check could load nothing. This check
 * exists so a theme that *tries* is refused whole rather than half-applied,
 * and it has to read CSS the way the browser does: escapes (`@\69mport`,
 * `\75rl(`), comments and quoted escapes inside `url("…")` all decode before
 * matching, and `image-set("…")` loads without any `url(` (SEC-5).
 */

/** Longest Advanced CSS accepted, in characters; the same cap as Kotlin's. */
export const MAX_THEME_CSS = 20_000;

/** CSS with comments removed and every escape decoded, lower-cased: the text the browser's tokenizer sees. */
export function normalizeCss(css: string): string {
    return css
        .replace(/\/\*[\s\S]*?(?:\*\/|$)/g, "")
        .replace(/\\(?:([0-9a-fA-F]{1,6})[\t\n\f\r ]?|\r\n|[\n\f\r]|([\s\S]))/g, (_m, hex?: string, char?: string) => {
            if (hex !== undefined) {
                const code = parseInt(hex, 16);
                return code === 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)
                    ? "�"
                    : String.fromCodePoint(code);
            }
            return char ?? "";
        })
        .toLowerCase();
}

const FORBIDDEN: readonly RegExp[] = [
    /<\//, // could close the <style> element
    /@import/,
    /@font-face/, // fonts come from the app's own @font-face rules
    /image-set\s*\(/, // loads like url() with a bare string
    /(?:^|[^a-z-])src\s*\(/,
    /expression\s*\(/,
    /javascript:/,
    /-moz-binding|behavior\s*:/,
];

/** True when [target] (a url() argument, decoded) stays inside the page: `data:` or a relative path. */
function isLocal(target: string): boolean {
    const t = target
        .trim()
        .replace(/^["']|["']$/g, "")
        .trim();
    if (t.startsWith("data:")) return true;
    return !/^[a-z][a-z0-9+.-]*:/.test(t) && !t.startsWith("//") && !t.startsWith("\\");
}

/** Null when [css] is acceptable, or the first rule it breaks, for messages and tests. */
export function cssViolation(css: string): string | null {
    if (css.length > MAX_THEME_CSS) return "too long";
    const normal = normalizeCss(css);
    const rule = FORBIDDEN.find((r) => r.test(normal));
    if (rule) return rule.source;
    for (const m of normal.matchAll(/url\s*\(([^)]*)\)?/g)) {
        if (!isLocal(m[1] ?? "")) return "remote url()";
    }
    return null;
}
