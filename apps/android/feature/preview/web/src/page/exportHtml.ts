/**
 * "Copy as rich text": the page's own rendering of some source lines, as
 * clean HTML for the clipboard. Only the sections holding those lines are
 * rendered, from their HTML rather than the live DOM, so nothing on screen
 * changes and the page is never asked to render the whole document.
 */
import type { RenderController } from "./controller";
import { sanitizeSection } from "./sanitize";

/** Elements that are page controls, not content. */
const CHROME = "button, .edit-in-code, .table-toolbar, .diagram-show-source";

/** The HTML of source lines [fromLine, toLine), or null when there is none. */
export async function exportHtml(
    controller: RenderController,
    fromLine: number,
    toLine: number,
): Promise<string | null> {
    const views = await controller.renderLines(fromLine, toLine);
    const out = document.createElement("div");
    for (const view of views) {
        if (view.html === null) continue;
        for (const child of [...sanitizeSection(view.html).children]) {
            const rl = Number((child as HTMLElement).dataset.rl);
            const line = Number.isNaN(rl) ? view.startLine : view.startLine + rl;
            const end = view.startLine + Number((child as HTMLElement).dataset.rle ?? rl + 1);
            if (line < toLine && end > fromLine) out.appendChild(child);
        }
    }
    for (const el of out.querySelectorAll(CHROME)) el.remove();
    for (const el of [out, ...out.querySelectorAll("*")]) {
        for (const attr of [...el.attributes]) {
            if (attr.name === "contenteditable" || attr.name.startsWith("data-")) el.removeAttribute(attr.name);
        }
    }
    // Source-run spans carry nothing once their attributes are gone: keep just their text.
    for (const span of [...out.querySelectorAll("span")]) {
        if (span.attributes.length === 0) span.replaceWith(...span.childNodes);
    }
    out.normalize();
    return out.children.length > 0 ? out.innerHTML : null;
}
