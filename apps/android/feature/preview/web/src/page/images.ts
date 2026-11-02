/**
 * Pictures that cannot be drawn get a calm placeholder in their place
 * (docs/ux-spec.md, "Pictures"): an icon, the alt text, the path as written
 * and why, with "Allow access" when the host lacks the document's folder.
 *
 * The page learns why from the host in batches: every picture beside the
 * document that fails to load in one burst is asked about in one
 * `imageQuery`, never one request per picture. Remote and unsupported
 * addresses are known from the address alone and are never loaded.
 *
 * The `<img>` stays in the DOM (hidden), so the viewer's picture indexes do
 * not move, and the placeholder is `contenteditable="false"`: no caret can
 * enter it, and tap-to-edit's stray guard ignores it.
 */
import { docKey, imageTarget } from "../core/imagePaths";
import { HOST_STATUSES, noticeFor, spokenNotice, type ImageStatus } from "../core/imageStatus";
import type { PageMessage } from "../protocol";

/** The class the worker gives a picture beside the document until it loads: it reserves room. */
export const PENDING = "img-pending";
/** The placeholder's class; page chrome, never content. */
export const PLACEHOLDER = "img-ph";
/** The placeholder's "Allow access" button. */
export const ALLOW_BUTTON = "img-ph-allow";

/** Failures within this many milliseconds go in one query. */
const BATCH_MS = 40;
/** The most paths one query names; the rest go in the next. */
export const MAX_QUERY = 200;

const SVG_NS = "http://www.w3.org/2000/svg";
/** Line icons on a 24-unit grid, like the app's strip icons. */
const ICONS: Record<"picture" | "lock" | "globe", readonly string[]> = {
    picture: ["M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M4 16l5-5 4 4 3-3 4 4"],
    lock: ["M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3"],
    globe: ["M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M3 12h18", "M12 3c3 3 3 15 0 18c-3-3-3-15 0-18z"],
};

type Failure = Exclude<ImageStatus, "ok">;

export class PageImages {
    /** What the host said about each key, for this document. */
    private readonly known = new Map<string, ImageStatus>();
    /** Keys asked about and not yet answered. */
    private readonly asked = new Set<string>();
    private readonly batch = new Set<string>();
    private timer: ReturnType<typeof setTimeout> | null = null;
    /** Answers to queries made before the last [reset] are for another document. */
    private nextQuery = 1;
    private firstCurrentQuery = 1;
    private retry = 0;

    constructor(
        private readonly post: (message: PageMessage) => void,
        private readonly doc: Document = document,
    ) {
        doc.addEventListener("error", (e) => this.onError(e.target), true);
        doc.addEventListener("load", (e) => this.onLoad(e.target), true);
    }

    /** New or re-rendered content: pictures whose fate is already known show it at once. */
    scan(root: ParentNode): void {
        for (const img of root.querySelectorAll<HTMLImageElement>("img[data-src]")) this.prepare(img);
    }

    /** Another document: nothing known about this one's pictures applies. */
    reset(): void {
        this.known.clear();
        this.asked.clear();
        this.batch.clear();
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = null;
        this.firstCurrentQuery = this.nextQuery;
    }

    /** The host's answer to query [id]. */
    onStatus(id: number, statuses: Readonly<Record<string, string>>): void {
        if (id < this.firstCurrentQuery) return;
        const answered = new Map<string, ImageStatus>();
        for (const [key, status] of Object.entries(statuses)) {
            if (!HOST_STATUSES.has(status)) continue;
            this.asked.delete(key);
            this.known.set(key, status as ImageStatus);
            answered.set(key, status as ImageStatus);
        }
        for (const img of this.docImages()) {
            const key = keyOf(img);
            const status = key === null ? undefined : answered.get(key);
            // Found and a picture, yet it failed to draw: a format this WebView cannot decode.
            if (status !== undefined && failed(img)) this.show(img, status === "ok" ? "notImage" : status);
        }
    }

    /**
     * The host can now see more (a folder was granted, the folder changed):
     * every placeholder for a picture beside the document tries again.
     */
    changed(): void {
        this.reset();
        this.retry++;
        for (const img of this.docImages()) {
            const box = placeholderOf(img);
            if (!box && !failed(img)) continue;
            box?.remove();
            img.hidden = false;
            img.classList.add(PENDING);
            const url = imageTarget(img.dataset.src ?? "");
            if (url.kind === "doc") img.src = `${url.url}?r=${this.retry}`;
        }
    }

    private prepare(img: HTMLImageElement): void {
        const target = imageTarget(img.dataset.src ?? "");
        if (target.kind === "remote" || target.kind === "unsupported") {
            this.show(img, target.kind);
            return;
        }
        if (target.kind !== "doc" || !failed(img)) return;
        const key = keyOf(img);
        const status = key === null ? undefined : this.known.get(key);
        if (status !== undefined) this.show(img, status === "ok" ? "notImage" : status);
    }

    private onError(target: EventTarget | null): void {
        if (!(target instanceof HTMLImageElement) || target.dataset.src === undefined) return;
        const key = keyOf(target);
        if (key === null) return;
        const status = this.known.get(key);
        if (status !== undefined) {
            this.show(target, status === "ok" ? "notImage" : status);
        } else if (!this.asked.has(key)) {
            this.batch.add(key);
            this.timer ??= setTimeout(() => this.flush(), BATCH_MS);
        }
    }

    private onLoad(target: EventTarget | null): void {
        if (!(target instanceof HTMLImageElement) || target.dataset.src === undefined) return;
        target.classList.remove(PENDING);
        target.hidden = false;
        placeholderOf(target)?.remove();
    }

    private flush(): void {
        this.timer = null;
        const paths = [...this.batch].slice(0, MAX_QUERY);
        for (const p of paths) {
            this.batch.delete(p);
            this.asked.add(p);
        }
        if (paths.length > 0) this.post({ type: "imageQuery", id: this.nextQuery++, paths });
        if (this.batch.size > 0) this.timer = setTimeout(() => this.flush(), 0);
    }

    private docImages(): HTMLImageElement[] {
        return [...this.doc.querySelectorAll<HTMLImageElement>("img[data-src]")].filter(
            (img) => imageTarget(img.dataset.src ?? "").kind === "doc",
        );
    }

    private show(img: HTMLImageElement, status: Failure): void {
        img.hidden = true;
        img.classList.remove(PENDING);
        const box = placeholderOf(img);
        if (box?.dataset.status === status) return;
        const fresh = placeholder(this.doc, status, img.alt, img.dataset.src ?? "");
        if (box) box.replaceWith(fresh);
        else img.after(fresh);
    }
}

/** True when [img] holds no drawn picture: it failed, or has nothing to load. */
function failed(img: HTMLImageElement): boolean {
    return img.complete && img.naturalWidth === 0;
}

function keyOf(img: HTMLImageElement): string | null {
    return docKey(img.getAttribute("src") ?? "");
}

function placeholderOf(img: HTMLImageElement): HTMLElement | null {
    const next = img.nextElementSibling;
    return next instanceof HTMLElement && next.classList.contains(PLACEHOLDER) ? next : null;
}

/**
 * The placeholder for a picture that is not drawn. The box itself is a
 * group; its picture part has role img and a spoken label, and the
 * "Allow access" button stays a real button beside it (a button inside
 * role img would be hidden from TalkBack).
 */
export function placeholder(doc: Document, status: Failure, alt: string, path: string): HTMLElement {
    const notice = noticeFor(status);
    const box = doc.createElement("span");
    box.className = PLACEHOLDER;
    box.setAttribute("contenteditable", "false");
    box.dataset.status = status;
    const body = doc.createElement("span");
    body.className = "img-ph-body";
    body.setAttribute("role", "img");
    body.setAttribute("aria-label", spokenNotice(status, alt, path));
    body.append(icon(doc, status === "noAccess" ? "lock" : status === "remote" ? "globe" : "picture"));
    const text = doc.createElement("span");
    text.className = "img-ph-text";
    text.append(line(doc, "img-ph-reason", notice.reason));
    if (alt.trim() !== "") text.append(line(doc, "img-ph-alt", alt.trim()));
    text.append(line(doc, "img-ph-path", path));
    body.append(text);
    box.append(body);
    if (notice.action) {
        const button = doc.createElement("button");
        button.type = "button";
        button.className = ALLOW_BUTTON;
        button.textContent = notice.action;
        box.append(button);
    }
    return box;
}

function line(doc: Document, className: string, value: string): HTMLElement {
    const span = doc.createElement("span");
    span.className = className;
    span.textContent = value;
    return span;
}

function icon(doc: Document, name: keyof typeof ICONS): SVGElement {
    const svg = doc.createElementNS(SVG_NS, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("class", "img-ph-icon");
    svg.setAttribute("aria-hidden", "true");
    for (const d of ICONS[name]) {
        const path = doc.createElementNS(SVG_NS, "path");
        path.setAttribute("d", d);
        svg.append(path);
    }
    return svg;
}
