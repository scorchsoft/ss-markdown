/**
 * The document outline, assembled from every section's headings: the
 * breadcrumb ("where am I?") and any `[[toc]]` placeholders are built from it.
 */
import type { Crumb, HeadingInfo } from "../protocol";

export type OutlineEntry = HeadingInfo & { readonly line: number };

/** The chain of headings that contain [line]: H1 > H2 > H3… */
export function breadcrumb(outline: readonly OutlineEntry[], line: number): Crumb[] {
    const stack: Crumb[] = [];
    for (const h of outline) {
        if (h.line > line) break;
        while (stack.length > 0 && (stack[stack.length - 1]?.level ?? 0) >= h.level) stack.pop();
        stack.push({ level: h.level, text: h.text, line: h.line });
    }
    return stack;
}

/** Fills every `nav[data-toc]` in [root] with a nested list of links. */
export function fillTocs(root: ParentNode, outline: readonly OutlineEntry[]): void {
    const navs = root.querySelectorAll<HTMLElement>("nav[data-toc]");
    if (navs.length === 0) return;
    for (const nav of navs) {
        const list = document.createElement("ul");
        for (const h of outline) {
            const item = document.createElement("li");
            item.style.setProperty("--toc-depth", String(h.level - 1));
            const link = document.createElement("a");
            link.href = `#${h.id}`;
            link.textContent = h.text;
            link.dataset.tocLine = String(h.line);
            item.appendChild(link);
            list.appendChild(item);
        }
        nav.replaceChildren(list);
    }
}
