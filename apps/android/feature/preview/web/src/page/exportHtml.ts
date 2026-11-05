/**
 * "Copy formatted text": some source lines as HTML that pastes well into
 * mail and document editors (copy/formatted.ts), with a plain-text twin
 * (copy/plainText.ts). Only the sections holding those lines are rendered,
 * from their HTML rather than the live DOM, so nothing on screen changes,
 * and a long document is rendered a slice at a time and stops when the
 * clipboard's budget is spent.
 */
import { BLANK_LINE } from "../render/blankLinesPlugin";
import type { RenderController } from "./controller";
import { Formatter, STYLES } from "./copy/formatted";
import { embedAll } from "./copy/media";
import { plainText } from "./copy/plainText";
import type { SectionView } from "./sectionView";
import { sanitizeForCopy } from "./sanitize";

/** Lines rendered per slice of a long document. */
const SLICE_LINES = 2_000;
/** The most one picture or diagram may take of the clipboard, in characters of its data URL. */
export const MAX_IMAGE_CHARS = 200_000;
/** The most all pictures together may take. */
export const MAX_IMAGES_CHARS = 300_000;
/** How long pictures and diagrams may take before the rest stand in as alt text. */
const MEDIA_MILLIS = 8_000;
/** Said at the foot of a copy that had to stop early. */
export const SHORTENED_NOTE = "[Shortened to fit the clipboard. Copy a section, or share the file, for the rest.]";

/** A formatted copy: [html] and its plain-text twin [text], together within the budget asked for. */
export interface FormattedCopy {
    readonly html: string;
    readonly text: string;
    readonly truncated: boolean;
}

/** Characters a node adds to the HTML (close enough to budget with). */
function htmlChars(node: Node): number {
    return node.nodeType === 1 ? (node as Element).outerHTML.length : (node.textContent ?? "").length * 1.1;
}

/**
 * The formatted copy of source lines [fromLine, toLine), at most [maxChars]
 * of HTML and plain text together, or null when there is nothing there.
 * [drawDiagram] draws a Mermaid source as SVG (null: it cannot be drawn).
 */
export async function exportHtml(
    controller: RenderController,
    fromLine: number,
    toLine: number,
    maxChars: number,
    drawDiagram: (source: string) => Promise<string | null>,
): Promise<FormattedCopy | null> {
    const formatter = new Formatter(document);
    // The words come first; pictures get what is left.
    const textBudget = maxChars - SHORTENED_NOTE.length * 3;
    let used = 0;
    let truncated = false;
    const added: Node[][] = [];
    const seen = new Set<SectionView>();
    outer: for (let from = fromLine; from < toLine; from += SLICE_LINES) {
        const views = await controller.renderLines(from, Math.min(toLine, from + SLICE_LINES));
        for (const view of views) {
            if (seen.has(view) || view.html === null) continue;
            seen.add(view);
            for (const block of blocksIn(view, fromLine, toLine)) {
                const nodes = formatter.add(block);
                const cost = nodes.reduce((n, node) => n + htmlChars(node) + (node.textContent ?? "").length, 0);
                if (used + cost > textBudget) {
                    formatter.remove(nodes);
                    truncated = true;
                    break outer;
                }
                used += cost;
                added.push(nodes);
            }
        }
    }
    formatter.finish();
    if (formatter.root.childNodes.length === 0) return null;
    const size = (): number => formatter.root.innerHTML.length + plainText(formatter.root.childNodes).length;
    // The running count is close, not exact (escaping, line breaks): the last blocks go until it fits.
    if (truncated || size() > maxChars) {
        const note = document.createElement("p");
        note.setAttribute("style", `${STYLES.p};font-style:italic`);
        note.textContent = SHORTENED_NOTE;
        formatter.root.appendChild(note);
        truncated = true;
        while (size() > maxChars && added.length > 0) formatter.remove(added.pop() ?? []);
    }
    const left = maxChars - size();
    await embedAll(
        formatter.media,
        { perItem: MAX_IMAGE_CHARS, total: Math.max(0, Math.min(MAX_IMAGES_CHARS, left)) },
        Date.now() + MEDIA_MILLIS,
        drawDiagram,
    );
    return { html: formatter.root.innerHTML, text: plainText(formatter.root.childNodes), truncated };
}

/** The rendered blocks of [view] that hold any of lines [fromLine, toLine). */
function blocksIn(view: SectionView, fromLine: number, toLine: number): Element[] {
    if (view.html === null) return [];
    return [...sanitizeForCopy(view.html).children].filter((child) => {
        // A drawn blank line is Preview's own spacing, not content to copy.
        if (child.classList.contains(BLANK_LINE)) return false;
        const data = (child as HTMLElement).dataset;
        const rl = Number(data.rl);
        const line = Number.isNaN(rl) ? view.startLine : view.startLine + rl;
        const end = view.startLine + Number(data.rle ?? rl + 1);
        return line < toLine && end > fromLine;
    });
}
