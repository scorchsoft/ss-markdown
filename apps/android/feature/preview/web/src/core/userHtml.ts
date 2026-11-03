/**
 * Raw HTML in a document must not be able to forge the page's own attributes:
 * a hand-written `data-s` would point tap-to-edit at the wrong bytes, and a
 * `contenteditable` would let typing land where it can never be written back.
 *
 * The attributes are renamed out of the way (`data-user-…`) by walking each
 * start tag the way the HTML tokenizer does (WHATWG "before attribute name"
 * and its neighbours). A regex over the string is not enough: HTML needs no
 * space after a quoted value (`title="x"data-s="0"`), and `/` separates
 * attributes too (`<div/data-s=0>`), which is how the security review got
 * past the old version (SEC-6). DOMPurify's hooks in `page/sanitize.ts` are
 * the second lock.
 *
 * Pure string work: it runs in the render worker, which has no DOM parser.
 */

/** Attributes the page itself relies on, which document HTML may never set. */
const RESERVED =
    /^(?:data-(?:s|e|rl|rle|line|line-end|offset|edit-rl|mermaid-key|viewer|toc|toc-line|state|src)|contenteditable)$/i;

const WHITESPACE = /[\t\n\f\r ]/;
const TAG_START = /[A-Za-z]/;

/** Renames reserved attributes in every start tag of [html]; everything else is copied unchanged. */
export function neutralizeUserHtml(html: string): string {
    let out = "";
    let i = 0;
    while (i < html.length) {
        const lt = html.indexOf("<", i);
        if (lt < 0) {
            out += html.slice(i);
            break;
        }
        out += html.slice(i, lt);
        const next = html[lt + 1] ?? "";
        if (html.startsWith("<!--", lt)) {
            i = copyThrough(html, lt, "-->");
        } else if (next === "!" || next === "?" || next === "/") {
            // Bogus comments, doctypes and end tags: a browser keeps no attributes from them.
            i = copyThrough(html, lt, ">");
        } else if (TAG_START.test(next)) {
            const tag = startTag(html, lt);
            out += tag.text;
            i = tag.end;
            continue;
        } else {
            out += "<";
            i = lt + 1;
            continue;
        }
        out += html.slice(lt, i);
    }
    return out;
}

/** The index just past the first [terminator] after [from], or the end of [html]. */
function copyThrough(html: string, from: number, terminator: string): number {
    const at = html.indexOf(terminator, from + 2);
    return at < 0 ? html.length : at + terminator.length;
}

/** One start tag from [lt], with reserved attribute names renamed. */
function startTag(html: string, lt: number): { text: string; end: number } {
    let i = lt + 1;
    while (i < html.length && !WHITESPACE.test(html[i] ?? "") && html[i] !== "/" && html[i] !== ">") i++;
    let text = html.slice(lt, i);
    for (;;) {
        // Before attribute name: whitespace and stray slashes separate attributes.
        const gap = i;
        while (i < html.length && (WHITESPACE.test(html[i] ?? "") || html[i] === "/")) i++;
        text += html.slice(gap, i);
        if (i >= html.length) return { text, end: i };
        if (html[i] === ">") return { text: text + ">", end: i + 1 };
        // Attribute name: a leading "=" belongs to the name; it ends at whitespace, "/", ">" or "=".
        const nameStart = i;
        i++;
        while (i < html.length && !/[\t\n\f\r />=]/.test(html[i] ?? "")) i++;
        const name = html.slice(nameStart, i);
        text += RESERVED.test(name) ? `data-user-${name.replace(/^data-/i, "")}` : name;
        // After the name: optional whitespace, then "=" and a value, or another attribute.
        const afterName = i;
        while (i < html.length && WHITESPACE.test(html[i] ?? "")) i++;
        if (html[i] !== "=") {
            i = afterName;
            continue;
        }
        i++;
        while (i < html.length && WHITESPACE.test(html[i] ?? "")) i++;
        const quote = html[i];
        if (quote === '"' || quote === "'") {
            const close = html.indexOf(quote, i + 1);
            i = close < 0 ? html.length : close + 1;
        } else {
            while (i < html.length && !WHITESPACE.test(html[i] ?? "") && html[i] !== ">") i++;
        }
        text += html.slice(afterName, i);
    }
}
