/**
 * Everything the worker renders passes through DOMPurify before it touches the
 * DOM. With the strict CSP in the shell this is the second of two locks: even a
 * script that slipped past one would be stopped by the other
 * (docs/edge-cases.md, "Malicious Markdown").
 */
import DOMPurify from "dompurify";

const FORBID_TAGS = [
    "style",
    "form",
    "iframe",
    "frame",
    "frameset",
    "object",
    "embed",
    "link",
    "meta",
    "base",
    "script",
    "template",
];

const SAFE_HREF = /^(?:https?:|mailto:|tel:|#|\/doc\/|[^:]*$)/i;
const SAFE_SRC = /^(?:\/doc\/|data:image\/(?:gif|png|jpeg|webp);|blob:|[^:]*$)/i;

let hooked = false;

function installHooks(): void {
    if (hooked) return;
    hooked = true;
    DOMPurify.addHook("afterSanitizeAttributes", (node) => {
        if (!(node instanceof Element)) return;
        const href = node.getAttribute("href");
        if (href !== null && !SAFE_HREF.test(href.trim())) node.removeAttribute("href");
        const src = node.getAttribute("src");
        if (src !== null && !SAFE_SRC.test(src.trim())) node.removeAttribute("src");
        node.removeAttribute("target");
        if (node.tagName === "INPUT" && node.getAttribute("type") !== "checkbox") node.remove();
    });
}

/** Sanitises a rendered section into a fragment ready to append. */
export function sanitizeSection(html: string): DocumentFragment {
    installHooks();
    return DOMPurify.sanitize(html, {
        RETURN_DOM_FRAGMENT: true,
        ADD_ATTR: ["contenteditable"],
        FORBID_TAGS,
        FORBID_ATTR: ["srcset", "formaction", "action", "xlink:href"],
        ALLOW_DATA_ATTR: true,
    });
}

/** Sanitises a Mermaid SVG: SVG profile, styles kept (they are scoped to the diagram id). */
export function sanitizeSvg(svg: string): string {
    installHooks();
    return DOMPurify.sanitize(svg, {
        USE_PROFILES: { svg: true, svgFilters: true },
        ADD_TAGS: ["style"],
        FORBID_TAGS: ["foreignObject", "script"],
    });
}
