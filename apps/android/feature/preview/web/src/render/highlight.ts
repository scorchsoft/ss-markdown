/**
 * Syntax colouring for fenced code, with highlight.js's "common" language set
 * (about 35 languages) to keep the bundle small. Unknown languages render as
 * plain escaped text, never auto-detected: detection is slow and often wrong.
 */
import hljs from "highlight.js/lib/common";

export function highlightCode(code: string, lang: string, escapeHtml: (s: string) => string): string {
    const name = lang.trim().split(/\s+/)[0]?.toLowerCase() ?? "";
    if (name.length > 0 && hljs.getLanguage(name)) {
        try {
            return hljs.highlight(code, { language: name, ignoreIllegals: true }).value;
        } catch {
            // A grammar failure falls back to plain text below.
        }
    }
    return escapeHtml(code);
}
