/**
 * Everything the worker renders passes through DOMPurify before it touches the
 * DOM. With the strict CSP in the shell this is the second of two locks: even a
 * script that slipped past one would be stopped by the other
 * (docs/edge-cases.md, "Malicious Markdown").
 */
import DOMPurify from "dompurify";
import { DOC_PREFIX, docSrc } from "../core/imagePaths";

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

/**
 * The only `data-*` attributes a section may carry: the ones the render
 * worker emits. Document HTML gets its reserved names renamed in the worker
 * (core/userHtml.ts); anything else, renamed or not, is dropped here, so a
 * spelling the worker missed still cannot reach the page (SEC-6).
 */
const SECTION_DATA_ATTRS = [
    "data-s",
    "data-e",
    "data-rl",
    "data-rle",
    "data-edit-rl",
    "data-mermaid-key",
    "data-viewer",
    "data-toc",
    "data-src",
];

/** Positions that let raw HTML leave its block and draw over the page or its controls (SEC-6). */
const ESCAPING_POSITION = /^(?:fixed|sticky|absolute)$/i;

let hooked = false;
/** True while [sanitizeForCopy] runs. */
let keepRemoteSrc = false;

function installHooks(): void {
    if (hooked) return;
    hooked = true;
    DOMPurify.addHook("afterSanitizeAttributes", (node) => {
        if (!(node instanceof Element)) return;
        const href = node.getAttribute("href");
        if (href !== null && !SAFE_HREF.test(href.trim())) node.removeAttribute("href");
        const src = node.getAttribute("src");
        if (src !== null && !SAFE_SRC.test(src.trim())) {
            // A copy keeps a web picture's address to paste as a link; it is never loaded.
            if (keepRemoteSrc && /^(?:https?:)?\/\//i.test(src.trim()))
                node.setAttribute("data-remote-src", src.trim());
            node.removeAttribute("src");
        } else if (src?.startsWith(DOC_PREFIX)) {
            node.setAttribute("src", docSrc(src));
        }
        node.removeAttribute("target");
        if (node.tagName === "INPUT" && node.getAttribute("type") !== "checkbox") node.remove();
        if (node instanceof HTMLElement || node instanceof SVGElement) containStyle(node);
    });
    DOMPurify.addHook("uponSanitizeAttribute", (_node, data) => {
        // The worker only ever emits contenteditable="false"; only the page's editor turns it on.
        if (data.attrName === "contenteditable" && data.attrValue.trim().toLowerCase() !== "false")
            data.keepAttr = false;
    });
}

/** Keeps an inline style inside its block: no fixed, sticky or absolute boxes and no stacking order. */
function containStyle(node: HTMLElement | SVGElement): void {
    const style = node.style;
    if (!node.hasAttribute("style")) return;
    if (ESCAPING_POSITION.test(style.position)) style.removeProperty("position");
    style.removeProperty("z-index");
    if (style.length === 0) node.removeAttribute("style");
}

/** Sanitises a rendered section into a fragment ready to append. */
export function sanitizeSection(html: string): DocumentFragment {
    installHooks();
    return DOMPurify.sanitize(html, {
        RETURN_DOM_FRAGMENT: true,
        ADD_ATTR: ["contenteditable", ...SECTION_DATA_ATTRS],
        FORBID_TAGS,
        FORBID_ATTR: ["srcset", "formaction", "action", "xlink:href"],
        ALLOW_DATA_ATTR: false,
    });
}

/**
 * [sanitizeSection] for "Copy formatted text": the same, except that a web
 * picture's address survives as `data-remote-src` (with no `src`, so it is
 * never fetched), for the copy to paste as a link.
 */
export function sanitizeForCopy(html: string): DocumentFragment {
    keepRemoteSrc = true;
    try {
        return sanitizeSection(html);
    } finally {
        keepRemoteSrc = false;
    }
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
