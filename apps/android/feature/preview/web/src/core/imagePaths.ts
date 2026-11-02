/**
 * Where a Markdown picture's address points, decided once in the render worker
 * and again on the page, from the address as written (`data-src`).
 *
 * Pictures beside the document are served by the host's `/doc/` path handler.
 * Their URL carries the number of folders the path climbs as its first segment
 * (`../shared/a.png` is `/doc/1/shared/a.png`), because a URL parser removes
 * `..` and `%2e%2e` itself: `/doc/../a.png` would leave `/doc/` altogether.
 * The host decides how far a climb may go (never out of the granted folder,
 * SEC-9); the page only spells it.
 */

/** The prefix of every picture the host serves from beside the document. */
export const DOC_PREFIX = "/doc/";

export type ImageTarget =
    /** A path beside the document, served at [url]. */
    | { readonly kind: "doc"; readonly url: string }
    /** `http:`, `https:` or `//host`: the app is offline, so it is never fetched. */
    | { readonly kind: "remote" }
    /** `data:`, `blob:` or `#`: drawn as is (the sanitiser keeps only safe ones). */
    | { readonly kind: "inline"; readonly url: string }
    /** Any other scheme (`file:`, `content:`) or an absolute path: nothing the page may load. */
    | { readonly kind: "unsupported" };

const REMOTE = /^(?:https?:)?\/\//i;
const INLINE = /^(?:data:|blob:|#)/i;
const SCHEME = /^[a-z][a-z0-9+.-]*:/i;
const DOT = /^(?:\.|%2e)$/i;
const DOT_DOT = /^(?:\.|%2e){2}$/i;

/** What [src], as written in the Markdown, points at. */
export function imageTarget(src: string): ImageTarget {
    const s = src.trim();
    if (REMOTE.test(s)) return { kind: "remote" };
    if (INLINE.test(s)) return { kind: "inline", url: s };
    if (SCHEME.test(s) || s.startsWith("/") || s.startsWith("\\")) return { kind: "unsupported" };
    const url = docUrl(s);
    return url ? { kind: "doc", url } : { kind: "unsupported" };
}

/**
 * `a/./b/../c.png` as `/doc/0/a/c.png`, `../../x.png` as `/doc/2/x.png`: the
 * path with `.` and inner `..` folded away and the climbs counted in front.
 * The query and fragment are dropped. Null when nothing names a file.
 */
export function docUrl(src: string): string | null {
    const path = src.replace(/[?#][\s\S]*$/, "");
    const parts: string[] = [];
    let up = 0;
    for (const part of path.split("/")) {
        if (part === "" || DOT.test(part)) continue;
        if (DOT_DOT.test(part)) {
            if (parts.length > 0) parts.pop();
            else up++;
            continue;
        }
        parts.push(part);
    }
    return parts.length > 0 ? `${DOC_PREFIX}${up}/${parts.join("/")}` : null;
}

/**
 * The key the page and the host name a picture by: its URL's path after
 * `/doc/` (`1/shared/a.png`), without the query a retry adds. Null for any
 * other URL.
 */
export function docKey(url: string): string | null {
    let path = url;
    const origin = /^[a-z][a-z0-9+.-]*:\/\/[^/]*/i.exec(path);
    if (origin) path = path.slice(origin[0].length);
    path = path.replace(/[?#][\s\S]*$/, "");
    return path.startsWith(DOC_PREFIX) && path.length > DOC_PREFIX.length ? path.slice(DOC_PREFIX.length) : null;
}
