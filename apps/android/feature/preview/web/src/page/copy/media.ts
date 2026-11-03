/**
 * Embeds a formatted copy's pictures and diagrams as `data:` images, within
 * a character budget, because the whole clipboard crosses Android's Binder
 * (about 1 MB; robustness.md §5). Nothing is fetched from the web: only
 * pictures Preview already loads from the document's folder (`/doc/`) or
 * from inline data. Everything is drawn on a white background, so a
 * transparent picture or diagram stays readable in a dark-mode mail app.
 */
import type { Media } from "./formatted";

/** Widest a pasted picture is shown, in CSS pixels; wider ones are scaled down to fit an email. */
export const DISPLAY_WIDTH = 640;
/** Widest a picture is stored, in pixels: twice the display width, for sharp screens. */
const STORE_WIDTH = 1280;

/** One embedded picture as data: [url], shown [width] CSS pixels wide. */
export interface Embedded {
    readonly url: string;
    readonly width: number;
}

/** Draws the image at [src] (already loaded or loadable by the page) at most [maxChars] as a data URL. */
export async function embedImage(src: string, maxChars: number): Promise<Embedded | null> {
    const img = await load(src);
    if (!img || img.naturalWidth === 0 || img.naturalHeight === 0) return null;
    return encode(img, img.naturalWidth, img.naturalHeight, maxChars, /\.png(?:[?#]|$)|^data:image\/png/i.test(src), 1);
}

/** Rasterises a Mermaid SVG (sized by its viewBox) as a PNG of at most [maxChars]. */
export async function embedSvg(svg: string, maxChars: number): Promise<Embedded | null> {
    const size = svgSize(svg);
    if (!size) return null;
    const url = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" }));
    try {
        const img = await load(url);
        if (!img) return null;
        return encode(img, size.width, size.height, maxChars, true, 2);
    } finally {
        URL.revokeObjectURL(url);
    }
}

/** The width and height an SVG draws at, from its viewBox (Mermaid sizes with 100% and a max-width). */
export function svgSize(svg: string): { width: number; height: number } | null {
    const box = /viewBox\s*=\s*"([^"]+)"/i
        .exec(svg)?.[1]
        ?.trim()
        .split(/[\s,]+/)
        .map(Number);
    if (box?.length !== 4) return null;
    const [, , width = 0, height = 0] = box;
    return width > 0 && height > 0 && Number.isFinite(width + height) ? { width, height } : null;
}

async function load(src: string): Promise<HTMLImageElement | null> {
    const img = new Image();
    img.src = src;
    try {
        await img.decode();
        return img;
    } catch {
        return null;
    }
}

/**
 * Tries the sharpest encoding first and steps down until one fits: PNG
 * (for diagrams, screenshots and anything already PNG), then JPEG at falling
 * sizes and qualities. A picture is never enlarged; an SVG is drawn at up
 * to [maxScale]× for sharp screens. Null when even the smallest is over [maxChars].
 */
function encode(
    img: HTMLImageElement,
    width: number,
    height: number,
    maxChars: number,
    pngFirst: boolean,
    maxScale: number,
): Embedded | null {
    const display = Math.min(DISPLAY_WIDTH, Math.round(width));
    const attempts: { w: number; type: string; quality?: number }[] = [
        ...(pngFirst ? [{ w: STORE_WIDTH, type: "image/png" }] : []),
        { w: STORE_WIDTH, type: "image/jpeg", quality: 0.85 },
        { w: DISPLAY_WIDTH, type: "image/jpeg", quality: 0.8 },
        { w: DISPLAY_WIDTH / 2, type: "image/jpeg", quality: 0.7 },
    ];
    for (const attempt of attempts) {
        const target = Math.max(1, Math.round(Math.min(attempt.w, width * maxScale)));
        const h = Math.max(1, Math.round((height * target) / width));
        const url = draw(img, target, h, attempt.type, attempt.quality);
        if (url !== null && url.length <= maxChars) return { url, width: display };
    }
    return null;
}

function draw(img: HTMLImageElement, width: number, height: number, type: string, quality?: number): string | null {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(img, 0, 0, width, height);
    try {
        return canvas.toDataURL(type, quality);
    } catch {
        return null;
    }
}

/** Fills [media] in document order until [budget] characters or [deadline] (ms since epoch) run out. */
export async function embedAll(
    media: readonly Media[],
    budget: { perItem: number; total: number },
    deadline: number,
    drawDiagram: (source: string) => Promise<string | null>,
): Promise<number> {
    let left = budget.total;
    for (const m of media) {
        let embedded: Embedded | null = null;
        const cap = Math.min(budget.perItem, left);
        if (cap > 0 && Date.now() < deadline) {
            if (m.kind === "image") {
                embedded = await embedImage(m.source, cap);
            } else {
                const svg = await drawDiagram(m.source);
                embedded = svg === null ? null : await embedSvg(svg, cap);
            }
        }
        if (embedded) {
            m.el.setAttribute("src", embedded.url);
            m.el.setAttribute("width", String(embedded.width));
            left -= embedded.url.length;
        } else {
            // Too big, too late, unreadable or not drawable: the alt text in brackets stands in.
            m.el.replaceWith(m.el.ownerDocument.createTextNode(`[${m.el.getAttribute("alt") ?? "Image"}]`));
        }
    }
    return budget.total - left;
}
