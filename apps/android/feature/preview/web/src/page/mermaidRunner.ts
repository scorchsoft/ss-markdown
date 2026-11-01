/**
 * Renders Mermaid diagrams on the main thread (Mermaid needs the DOM), one per
 * idle slice, only for mounted sections. Each diagram has caps on size and
 * edges and a timeout; a failure shows an inline error with the source and
 * never blanks the page (docs/robustness.md, "Preview").
 */
import type MermaidDefault from "mermaid";
import { contentHash } from "../core/hash";
import { DiagramCache } from "./diagramCache";
import { sanitizeSvg } from "./sanitize";

export const MAX_DIAGRAM_CHARS = 50_000;
export const MAX_DIAGRAM_LINES = 2_000;
export const MAX_DIAGRAM_EDGES = 500;
export const DIAGRAM_TIMEOUT_MS = 5_000;
const CACHE_BYTES = 8 * 1024 * 1024;
const LEAN_CACHE_BYTES = 1024 * 1024;

type MermaidApi = typeof MermaidDefault;

let mermaidPromise: Promise<MermaidApi> | null = null;
let idCounter = 0;

function loadMermaid(): Promise<MermaidApi> {
    mermaidPromise ??= import("mermaid").then((m) => m.default);
    return mermaidPromise;
}

/** Why a diagram was refused before rendering, or null if it is within the caps. */
export function checkDiagramCaps(source: string): string | null {
    if (source.length > MAX_DIAGRAM_CHARS)
        return `Diagram is too large to draw (${source.length} characters; the limit is ${MAX_DIAGRAM_CHARS}).`;
    const lines = source.split("\n").length;
    if (lines > MAX_DIAGRAM_LINES)
        return `Diagram is too large to draw (${lines} lines; the limit is ${MAX_DIAGRAM_LINES}).`;
    const edges = (source.match(/--|==|-\.|\.-|->|~~~/g) ?? []).length;
    if (edges > MAX_DIAGRAM_EDGES)
        return `Diagram has too many connections to draw (${edges}; the limit is ${MAX_DIAGRAM_EDGES}).`;
    return null;
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
    return new Promise<T>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`Diagram took longer than ${ms / 1000} s to draw.`)), ms);
        promise.then(
            (v) => {
                clearTimeout(timer);
                resolve(v);
            },
            (e: unknown) => {
                clearTimeout(timer);
                reject(e instanceof Error ? e : new Error(String(e)));
            },
        );
    });
}

export class MermaidRunner {
    private readonly queue: HTMLElement[] = [];
    private running = false;
    private dark = false;
    private lean = false;
    private themeKey = "light";
    private readonly cache = new DiagramCache(CACHE_BYTES);
    private readonly waiters: (() => void)[] = [];

    setTheme(dark: boolean, themeName: string): void {
        this.dark = dark;
        this.themeKey = `${themeName}:${dark ? "dark" : "light"}`;
    }

    setLean(lean: boolean): void {
        this.lean = lean;
        this.cache.setBudget(lean ? LEAN_CACHE_BYTES : CACHE_BYTES);
    }

    /** Queues every not-yet-drawn diagram in [root]. In lean mode they wait for a tap. */
    enqueueIn(root: ParentNode, force = false): void {
        for (const pre of root.querySelectorAll<HTMLElement>("pre.diagram-source:not([data-state])")) {
            const box = pre.parentElement;
            if (!box) continue;
            const cached = this.cache.get(this.cacheKey(pre, box));
            if (cached !== undefined) {
                this.place(pre, box, cached);
            } else if (this.lean && !force) {
                pre.dataset.state = "deferred";
                box.classList.add("diagram-deferred");
            } else {
                pre.dataset.state = "queued";
                this.queue.push(pre);
            }
        }
        this.pump();
    }

    /** Resolves when the queue is empty (used before printing and exporting). */
    idle(): Promise<void> {
        if (!this.running && this.queue.length === 0) return Promise.resolve();
        return new Promise((resolve) => this.waiters.push(resolve));
    }

    private cacheKey(pre: HTMLElement, box: HTMLElement): string {
        const width = Math.round(box.clientWidth / 50) * 50;
        return contentHash(`${pre.textContent ?? ""}\u0000${this.themeKey}\u0000${width}`);
    }

    private pump(): void {
        if (this.running) return;
        const next = this.queue.shift();
        if (!next) {
            for (const resolve of this.waiters.splice(0)) resolve();
            return;
        }
        this.running = true;
        const schedule = window.requestIdleCallback ?? ((cb: () => void) => setTimeout(cb, 1));
        schedule(() => {
            void this.draw(next).finally(() => {
                this.running = false;
                this.pump();
            });
        });
    }

    private async draw(pre: HTMLElement): Promise<void> {
        const box = pre.parentElement;
        if (!box || !pre.isConnected) return;
        const source = pre.textContent ?? "";
        const refused = checkDiagramCaps(source);
        if (refused) {
            this.fail(pre, box, refused);
            return;
        }
        try {
            const mermaid = await loadMermaid();
            mermaid.initialize({
                startOnLoad: false,
                securityLevel: "strict",
                theme: this.dark ? "dark" : "default",
                maxTextSize: MAX_DIAGRAM_CHARS,
                maxEdges: MAX_DIAGRAM_EDGES,
                htmlLabels: false,
                flowchart: { htmlLabels: false },
                fontFamily: "var(--font-body)",
            });
            const id = `ssmd-diagram-${++idCounter}`;
            const { svg } = await withTimeout(mermaid.render(id, source), DIAGRAM_TIMEOUT_MS);
            const clean = sanitizeSvg(svg);
            this.cache.set(this.cacheKey(pre, box), clean);
            if (pre.isConnected) this.place(pre, box, clean);
        } catch (e) {
            document.getElementById(`dssmd-diagram-${idCounter}`)?.remove();
            this.fail(pre, box, e instanceof Error ? e.message : String(e));
        }
    }

    private place(pre: HTMLElement, box: HTMLElement, svg: string): void {
        pre.dataset.state = "drawn";
        box.classList.remove("diagram-deferred", "diagram-failed");
        box.querySelector(".diagram-svg")?.remove();
        const holder = document.createElement("div");
        holder.className = "diagram-svg";
        holder.innerHTML = svg;
        box.insertBefore(holder, pre);
    }

    private fail(pre: HTMLElement, box: HTMLElement, message: string): void {
        pre.dataset.state = "failed";
        box.classList.add("diagram-failed");
        box.querySelector(".diagram-error")?.remove();
        const error = document.createElement("p");
        error.className = "diagram-error";
        error.setAttribute("role", "alert");
        error.textContent = `Couldn't draw this diagram: ${message.split("\n")[0] ?? ""}`;
        box.insertBefore(error, pre);
    }
}
