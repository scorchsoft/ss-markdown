/**
 * What a clip pastes as, in Preview and in Code alike (docs/ux-spec.md,
 * "Copy and paste"; docs/editing-behaviours.md EB-CLIP-06, -13, -14). A
 * normal paste of HTML is that HTML as Markdown, unless the HTML is only a
 * plain wrapper round the clip's own plain text (a single span, or
 * paragraphs with no formatting), when the plain text is used as it is.
 * "Paste as plain text" is the words of whatever a normal paste would
 * insert. Kotlin's twin is `core/markdown/…/paste/PasteClip.kt`.
 */
import { htmlToMarkdown, parseHtml } from "./htmlToMarkdown";
import { plainWords } from "./plainWords";

export interface ClipContent {
    /** Markdown. */
    readonly text: string;
    /** True when it came from converting HTML. */
    readonly fromHtml: boolean;
}

/** The Markdown a normal paste of [plain] and/or [html] inserts. */
export function clipContent(
    plain: string | null,
    html: string | null,
    parse: (html: string) => Document = parseHtml,
): ClipContent {
    if (html !== null && html.trim() !== "") {
        const converted = htmlToMarkdown(html, parse);
        if (converted.markdown.trim() !== "" && (converted.formatted || !plain)) {
            return { text: converted.markdown, fromHtml: true };
        }
    }
    return { text: plain ?? "", fromHtml: false };
}

/** What "Paste as plain text" inserts: only the words, with no Markdown or HTML. */
export function clipWords(
    plain: string | null,
    html: string | null,
    parse: (html: string) => Document = parseHtml,
): string {
    return plainWords(clipContent(plain, html, parse).text);
}
