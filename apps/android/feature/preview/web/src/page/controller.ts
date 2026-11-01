/**
 * Owns the document copy, its sections and their views, and talks to the
 * render worker. Edits come in as line ranges; only sections whose text
 * changed are sent to the worker, and only sections near the viewport are
 * sent first.
 */
import { collectDefinitions, type DocDefinitions } from "../core/definitions";
import { contentHash } from "../core/hash";
import { resplit, splitSections, type Section } from "../core/sections";
import { EditSync } from "../core/editSync";
import { SourceDoc, type LineChange, type SourceEdit } from "../core/sourceDoc";
import type { HeadingInfo, RenderRequest, RenderResponse } from "../protocol";
import { SectionView } from "./sectionView";
import { Virtualizer } from "./virtualizer";

/** Sections sent per background render request. */
const BATCH = 40;
const DEFINITION_HINT = /^\s{0,3}\*?\[|\[\^/m;

export interface ControllerHooks {
    /** A batch of sections has new HTML (some may be mounted). */
    onRendered(views: readonly SectionView[]): void;
    /** A section just entered the DOM. */
    onMounted(view: SectionView): void;
}

export class RenderController {
    doc = new SourceDoc("");
    /** Revisions and edits in flight between the page and Kotlin. */
    readonly sync = new EditSync();
    views: SectionView[] = [];
    /** The section being edited in place; its DOM is never replaced under the caret. */
    pinned: SectionView | null = null;
    readonly virtualizer: Virtualizer;
    private sections: Section[] = [];
    private defs: DocDefinitions = collectDefinitions([]);
    private defsKey = "";
    private generation = 0;
    private readonly requested = new Set<string>();
    private backgroundTimer: ReturnType<typeof setTimeout> | null = null;
    private readonly waiters: (() => void)[] = [];

    constructor(
        private readonly container: HTMLElement,
        private readonly worker: Worker,
        private readonly hooks: ControllerHooks,
    ) {
        this.virtualizer = new Virtualizer((view) => hooks.onMounted(view));
        worker.onmessage = (e: MessageEvent<RenderResponse>) => this.onRendered(e.data);
    }

    /** The last revision Kotlin told us about. */
    get rev(): number {
        return this.sync.rev;
    }

    load(text: string, rev: number): void {
        this.doc = new SourceDoc(text);
        this.sync.load(rev);
        this.refreshDefinitions(true);
        this.sections = splitSections(this.doc.lines);
        this.reconcile(true);
    }

    /** Applies Kotlin's line changes, in order (the revision is already counted by [sync]). */
    applyHostLines(changes: readonly LineChange[]): void {
        for (const change of changes) this.applyLineChange(change);
        this.reconcile(false);
    }

    /**
     * Applies edits made in Preview, records them as in flight and returns the
     * revision they are based on, to send with them.
     */
    applyLocalEdits(edits: readonly SourceEdit[]): number {
        const baseRev = this.sync.local(edits);
        this.applyEdits(edits);
        return baseRev;
    }

    /** Applies non-overlapping character edits, all relative to the current text. */
    applyEdits(edits: readonly SourceEdit[]): void {
        const ordered = [...edits].sort((a, b) => b.from - a.from);
        for (const edit of ordered) {
            const fromLine = this.doc.lineAt(edit.from);
            const toLine = this.doc.lineAt(edit.to) + 1;
            const before = this.doc.lines.slice(fromLine, toLine).join("\n");
            const change = this.doc.applyEdit(edit);
            const newTo = change.fromLine + change.lines.length;
            this.sections = resplit(this.sections, this.doc.lines, fromLine, toLine, newTo);
            if (DEFINITION_HINT.test(before) || DEFINITION_HINT.test(change.lines.join("\n")))
                this.refreshDefinitions(false);
        }
        this.reconcile(false);
    }

    viewAtLine(line: number): SectionView | null {
        let lo = 0;
        let hi = this.views.length - 1;
        while (lo < hi) {
            const mid = (lo + hi + 1) >> 1;
            if ((this.views[mid]?.startLine ?? 0) <= line) lo = mid;
            else hi = mid - 1;
        }
        return this.views[lo] ?? null;
    }

    /** The document outline with absolute lines, in order. */
    outline(): (HeadingInfo & { line: number })[] {
        return this.views.flatMap((v) => v.headings.map((h) => ({ ...h, line: v.startLine + h.relLine })));
    }

    /** Resolves when every section has HTML (used before printing). */
    renderAll(): Promise<void> {
        const missing = this.views.filter((v) => v.html === null);
        if (missing.length === 0) return Promise.resolve();
        this.request(missing);
        return new Promise((resolve) => this.waiters.push(resolve));
    }

    /** Re-renders [view] from source (after an in-place editing session ends). */
    refresh(view: SectionView): void {
        this.requested.delete(view.key);
        this.request([view]);
    }

    private applyLineChange(change: LineChange): void {
        const before = this.doc.lines.slice(change.fromLine, change.toLine).join("\n");
        const delta = this.doc.replaceLines(change);
        const newTo = change.toLine + delta;
        this.sections = resplit(this.sections, this.doc.lines, change.fromLine, change.toLine, newTo);
        if (DEFINITION_HINT.test(before) || DEFINITION_HINT.test(change.lines.join("\n")))
            this.refreshDefinitions(false);
    }

    private refreshDefinitions(force: boolean): void {
        this.defs = collectDefinitions(this.doc.lines);
        const key = contentHash(JSON.stringify(this.defs));
        if (key !== this.defsKey || force) {
            this.defsKey = key;
            this.requested.clear();
        }
    }

    private keyOf(section: Section): string {
        return `${section.hash}:${this.defsKey}${section.startLine === 0 ? ":first" : ""}`;
    }

    /** Matches sections to existing views by key, creating and removing as needed. */
    private reconcile(reset: boolean): void {
        const pool = new Map<string, SectionView[]>();
        if (!reset) {
            for (const v of this.views) {
                const list = pool.get(v.key) ?? [];
                list.push(v);
                pool.set(v.key, list);
            }
        }
        const next: SectionView[] = [];
        for (const section of this.sections) {
            const key = this.keyOf(section);
            const offset = this.doc.lineStart(section.startLine);
            let view = pool.get(key)?.shift();
            if (!view && this.pinned && this.pinned.startLine === section.startLine && !next.includes(this.pinned)) {
                view = this.pinned;
                view.key = key;
            }
            if (view) view.moveTo(section.startLine, section.endLine, offset);
            else view = new SectionView(key, section.startLine, section.endLine, offset);
            next.push(view);
        }
        const kept = new Set(next);
        for (const old of reset ? this.views : this.views.filter((v) => !kept.has(v))) {
            this.virtualizer.unobserve(old);
            old.el.remove();
        }
        let cursor: ChildNode | null = this.container.firstChild;
        for (const view of next) {
            if (view.el !== cursor) {
                this.container.insertBefore(view.el, cursor);
                this.virtualizer.observe(view);
                if (view.html === null) view.unmount(this.virtualizer.spacerScale);
            } else {
                cursor = cursor.nextSibling;
            }
        }
        this.views = next;
        this.virtualizer.rescale(next);
        this.request(next.filter((v) => v.html === null));
    }

    /** Sends near-viewport sections now and the rest in background batches. */
    private request(views: readonly SectionView[]): void {
        const todo = views.filter((v) => v !== this.pinned && !this.requested.has(v.key));
        const near = todo.filter((v) => this.virtualizer.isNear(v) || todo.length <= BATCH);
        const far = todo.filter((v) => !near.includes(v));
        if (near.length === 0 && far.length > 0) near.push(...far.splice(0, BATCH));
        this.send(near);
        if (far.length > 0) this.scheduleBackground(far);
    }

    private scheduleBackground(views: SectionView[]): void {
        if (this.backgroundTimer !== null) clearTimeout(this.backgroundTimer);
        this.backgroundTimer = setTimeout(() => {
            this.backgroundTimer = null;
            const live = views.filter((v) => this.views.includes(v) && !this.requested.has(v.key));
            this.send(live.slice(0, BATCH));
            if (live.length > BATCH) this.scheduleBackground(live.slice(BATCH));
        }, 16);
    }

    private send(views: readonly SectionView[]): void {
        if (views.length === 0) return;
        const jobs = views.map((v) => {
            this.requested.add(v.key);
            return {
                key: v.key,
                text: this.doc.lines.slice(v.startLine, v.endLine).join("\n"),
                first: v.startLine === 0,
            };
        });
        const request: RenderRequest = { type: "render", generation: ++this.generation, defs: this.defs, jobs };
        this.worker.postMessage(request);
    }

    private onRendered(response: RenderResponse): void {
        const byKey = new Map(response.results.map((r) => [r.key, r]));
        for (const key of byKey.keys()) this.requested.delete(key);
        const touched: SectionView[] = [];
        for (const view of this.views) {
            const result = byKey.get(view.key);
            if (!result || view === this.pinned) continue;
            view.setHtml(result.html, result.headings);
            this.virtualizer.update(view);
            touched.push(view);
        }
        if (touched.length > 0) {
            this.virtualizer.rescale(this.views);
            this.hooks.onRendered(touched);
        }
        if (this.waiters.length > 0 && this.views.every((v) => v.html !== null)) {
            for (const resolve of this.waiters.splice(0)) resolve();
        }
    }
}
