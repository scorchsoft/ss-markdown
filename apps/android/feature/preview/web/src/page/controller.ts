/**
 * Owns the document copy, its sections and their views, and talks to the
 * render worker. Edits come in as line ranges; only sections whose text
 * changed are sent to the worker, and only sections near the viewport are
 * sent first.
 */
import { collectDefinitions, definitionSignature, type DocDefinitions } from "../core/definitions";
import { contentHash } from "../core/hash";
import { resplitRange, splitSections, type Resplit, type Section } from "../core/sections";
import { EditSync } from "../core/editSync";
import { SourceDoc, type LineChange, type SourceEdit } from "../core/sourceDoc";
import type { HeadingInfo, RenderRequest, RenderResponse } from "../protocol";
import { SectionView, type SectionHost } from "./sectionView";
import { Virtualizer } from "./virtualizer";

/** Sections sent per background render request. */
const BATCH = 40;
/** Above this many views a splice is rebuilt with `concat` rather than spread into arguments. */
const SPLICE_SPREAD_LIMIT = 16_384;

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
    /** One view per section, in document order: the section list itself. */
    views: SectionView[] = [];
    /** The section being edited in place; its DOM is never replaced under the caret. */
    pinned: SectionView | null = null;
    readonly virtualizer: Virtualizer;
    private defs: DocDefinitions = collectDefinitions([]);
    private defsKey = "";
    /** Whole-document definition scans so far: the PERF-H statistic (one per change that can alter them). */
    definitionScans = 0;
    private generation = 0;
    private readonly requested = new Set<string>();
    /** Views by key, so a worker reply finds its sections without walking all of them. */
    private readonly byKey = new Map<string, Set<SectionView>>();
    private readonly host: SectionHost = {
        lineStart: (line) => this.doc.lineStart(line),
        mounted: new Set<SectionView>(),
    };
    /** Background rendering resumes its walk of [views] here; past the end when there is nothing to do. */
    private backgroundFrom = 0;
    /** Views a change in this batch left needing a render: sent at once when few, else nearest first. */
    private readonly dirty = new Set<SectionView>();
    private backgroundTimer: ReturnType<typeof setTimeout> | null = null;
    /** Promises waiting for sections to be rendered, each with the condition it waits for. */
    private readonly waiters: { readonly done: () => boolean; readonly resolve: () => void }[] = [];

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
        for (const old of this.views) this.drop(old);
        this.views = [];
        // A wait on the old sections would never end (they are never rendered now) and would keep them
        // alive: end it, and the print or copy behind it finds its request abandoned.
        for (const waiter of this.waiters.splice(0)) waiter.resolve();
        this.dirty.clear();
        this.refreshDefinitions(true);
        this.splice({ first: 0, end: 0, fresh: splitSections(this.doc.lines), delta: 0 });
        this.afterEdit(0);
    }

    /** Applies Kotlin's line changes, in order (the revision is already counted by [sync]). */
    applyHostLines(changes: readonly LineChange[]): void {
        let first = this.views.length;
        for (const change of changes) first = Math.min(first, this.applyLineChange(change));
        this.afterEdit(first);
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
        let first = this.views.length;
        for (const edit of ordered) {
            const fromLine = this.doc.lineAt(edit.from);
            const toLine = this.doc.lineAt(edit.to) + 1;
            const before = this.doc.lines.slice(fromLine, toLine).join("\n");
            const change = this.doc.applyEdit(edit);
            const newTo = change.fromLine + change.lines.length;
            first = Math.min(first, this.splice(resplitRange(this.views, this.doc.lines, fromLine, toLine, newTo)));
            if (definitionSignature(before) !== definitionSignature(change.lines.join("\n")))
                first = Math.min(first, this.refreshDefinitions(false));
        }
        this.afterEdit(first);
    }

    viewAtLine(line: number): SectionView | null {
        return this.views[this.indexAtLine(line)] ?? null;
    }

    /** Index of the section holding [line], by binary search. */
    private indexAtLine(line: number): number {
        let lo = 0;
        let hi = this.views.length - 1;
        while (lo < hi) {
            const mid = (lo + hi + 1) >> 1;
            if ((this.views[mid]?.startLine ?? 0) <= line) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** The section holding source [offset]. */
    viewAtOffset(offset: number): SectionView | null {
        return this.viewAtLine(this.doc.lineAt(offset));
    }

    /** The document outline with absolute lines, in order. */
    outline(): (HeadingInfo & { line: number })[] {
        return this.views.flatMap((v) => v.headings.map((h) => ({ ...h, line: v.startLine + h.relLine })));
    }

    /** Resolves when every section has HTML for its current key (used before printing). */
    renderAll(): Promise<void> {
        const missing = this.views.filter((v) => v.needsRender);
        this.request(missing);
        this.backgroundFrom = 0;
        this.scheduleBackground();
        return this.when(() => this.views.every((v) => !v.needsRender || v === this.pinned));
    }

    /**
     * The sections holding lines [fromLine, toLine), once each has HTML for
     * its current key. Only those sections are rendered: a formatted copy of
     * one section does not render the whole document.
     */
    async renderLines(fromLine: number, toLine: number): Promise<SectionView[]> {
        const views: SectionView[] = [];
        for (let i = this.indexAtLine(fromLine); i < this.views.length; i++) {
            const view = this.views[i];
            if (!view || (views.length > 0 && view.startLine >= toLine)) break;
            views.push(view);
        }
        this.request(views.filter((v) => v.needsRender));
        await this.when(() => views.every((v) => !v.needsRender || v === this.pinned));
        return views;
    }

    private when(done: () => boolean): Promise<void> {
        if (done()) return Promise.resolve();
        return new Promise((resolve) => this.waiters.push({ done, resolve }));
    }

    /** Re-renders [view] from source (after an in-place editing session ends). */
    refresh(view: SectionView): void {
        this.requested.delete(view.key);
        this.request([view]);
    }

    /** Applies one line change; returns the first view index it touched. */
    private applyLineChange(change: LineChange): number {
        const before = this.doc.lines.slice(change.fromLine, change.toLine).join("\n");
        const delta = this.doc.replaceLines(change);
        const newTo = change.toLine + delta;
        let first = this.splice(resplitRange(this.views, this.doc.lines, change.fromLine, change.toLine, newTo));
        if (definitionSignature(before) !== definitionSignature(change.lines.join("\n")))
            first = Math.min(first, this.refreshDefinitions(false));
        return first;
    }

    /**
     * Recollects document-wide definitions. When they change, every section's
     * key changes: each keeps its HTML on screen, marked stale, until the new
     * render arrives. Returns 0 then (everything needs work), else the end.
     */
    private refreshDefinitions(force: boolean): number {
        this.definitionScans++;
        this.defs = collectDefinitions(this.doc.lines);
        const key = contentHash(JSON.stringify(this.defs));
        if (key === this.defsKey && !force) return this.views.length;
        this.defsKey = key;
        this.requested.clear();
        for (const view of this.views) {
            this.setKey(view, this.keyOf(view));
            if (view.html !== null) view.stale = true;
        }
        return 0;
    }

    private keyOf(section: Section): string {
        return `${section.hash}:${this.defsKey}${section.startLine === 0 ? ":first" : ""}`;
    }

    /**
     * Replaces views `[first, end)` with views for [fresh] sections, reusing a
     * replaced view whose key matches (so a section that only moved keeps its
     * HTML), and shifts the views after them. Work is proportional to the
     * sections that changed plus one integer add per later section; nothing
     * after the edit is rebuilt, re-keyed or written to the DOM (PERF-11).
     * Returns [first].
     */
    private splice(r: Resplit): number {
        const removed = this.views.slice(r.first, r.end);
        const pool = new Map<string, SectionView[]>();
        for (const v of removed) {
            const list = pool.get(v.key) ?? [];
            list.push(v);
            pool.set(v.key, list);
        }
        const pinned = this.pinned && removed.includes(this.pinned) ? this.pinned : null;
        // A section whose text changed but still spans the same lines (Bold, a typo fix) keeps its old
        // view, its HTML on screen and stale, until the new render swaps in place. A fresh view would
        // be an empty spacer for a frame or more, and the text below jumped (Andrew, 2026-11-04: "a
        // small flash … then it's back"). Same lines only: old HTML beside a split's new half would
        // show text twice.
        const sameLines = (section: Section): SectionView | undefined =>
            removed.find(
                (v) =>
                    v.mounted &&
                    v.html !== null &&
                    v !== pinned &&
                    v.startLine === section.startLine &&
                    v.endLine === section.endLine &&
                    !claimed.has(v) &&
                    !wanted.has(v.key),
            );
        const claimed = new Set<SectionView>();
        let pinnedUsed = false;
        const sections = pinned ? this.wholeFrom(r.fresh, pinned.startLine) : r.fresh;
        // A view a section takes by its key is never taken for its lines as well.
        const wanted = new Set(sections.map((section) => this.keyOf(section)));
        const fresh = sections.map((section) => {
            const key = this.keyOf(section);
            let view = pool.get(key)?.shift();
            if (view === pinned) pinnedUsed = true;
            if (!view && pinned && !pinnedUsed && pinned.startLine === section.startLine) {
                view = pinned;
                pinnedUsed = true;
                view.hash = section.hash;
                this.setKey(view, key);
            }
            if (!view) {
                view = sameLines(section);
                if (view) {
                    view.hash = section.hash;
                    this.setKey(view, key);
                }
            }
            if (view) claimed.add(view);
            if (view) {
                view.moveTo(section.startLine, section.endLine);
            } else {
                view = new SectionView(key, section.hash, section.startLine, section.endLine, this.host);
                this.index(view);
            }
            return view;
        });
        const kept = new Set(fresh);
        for (const old of removed) if (!kept.has(old)) this.drop(old);
        for (let i = r.end; i < this.views.length; i++) this.views[i]?.shift(r.delta);
        // Back to front, so each view goes just before the one after it; views already there stay put.
        let anchor: Element | null = this.views[r.end]?.el ?? null;
        for (let i = fresh.length - 1; i >= 0; i--) {
            const view = fresh[i];
            if (!view) continue;
            const isNew = view.el.parentNode !== this.container;
            if (isNew || view.el.nextSibling !== anchor) this.container.insertBefore(view.el, anchor);
            if (isNew) {
                this.virtualizer.observe(view);
                view.unmount(this.virtualizer.spacerScale);
            }
            if (view.needsRender) this.dirty.add(view);
            anchor = view.el;
        }
        if (fresh.length < SPLICE_SPREAD_LIMIT && r.end - r.first < SPLICE_SPREAD_LIMIT) {
            this.views.splice(r.first, r.end - r.first, ...fresh);
        } else {
            this.views = this.views.slice(0, r.first).concat(fresh, this.views.slice(r.end));
        }
        return r.first;
    }

    /**
     * [fresh] with the sections from the one starting at [startLine] on made
     * one. The section being edited keeps its HTML until editing ends, so
     * splitting it (Enter in a paragraph, then typing) would show what is
     * typed twice: in the pinned HTML and in the new section's own render.
     * The larger section is still a valid one (it starts and ends at section
     * boundaries) and re-renders in place when editing ends; a later edit
     * near it re-splits it.
     */
    private wholeFrom(fresh: readonly Section[], startLine: number): readonly Section[] {
        const k = fresh.findIndex((s) => s.startLine === startLine);
        const last = fresh[fresh.length - 1];
        if (k < 0 || k === fresh.length - 1 || !last) return fresh;
        const text = this.doc.lines.slice(startLine, last.endLine).join("\n");
        return [...fresh.slice(0, k), { startLine, endLine: last.endLine, hash: contentHash(text) }];
    }

    /** After a batch of changes from view [first] on: fix mounted offsets, rescale, and render what changed. */
    private afterEdit(first: number): void {
        for (const view of this.host.mounted) {
            view.syncAttrs();
            if (view.needsRender) this.dirty.add(view);
        }
        this.virtualizer.rescale(this.views);
        const todo = [...this.dirty].filter((v) => this.byKey.get(v.key)?.has(v));
        this.dirty.clear();
        this.request(todo);
        this.backgroundFrom = Math.min(this.backgroundFrom, first);
        this.scheduleBackground();
    }

    private setKey(view: SectionView, key: string): void {
        if (view.key === key) return;
        this.unindex(view);
        view.key = key;
        // Its HTML is for the old key: shown until the new render, but nothing (a caret) may be put into it.
        if (view.html !== null) view.stale = true;
        this.index(view);
    }

    private index(view: SectionView): void {
        const set = this.byKey.get(view.key) ?? new Set<SectionView>();
        set.add(view);
        this.byKey.set(view.key, set);
    }

    private unindex(view: SectionView): void {
        const set = this.byKey.get(view.key);
        set?.delete(view);
        if (set?.size === 0) this.byKey.delete(view.key);
    }

    /** Takes a view out of the page for good. */
    private drop(view: SectionView): void {
        this.unindex(view);
        this.virtualizer.unobserve(view);
        view.dispose();
        view.el.remove();
    }

    /** Sends [views] that are not yet asked for, nearest first; the background walk picks up the rest. */
    private request(views: readonly SectionView[]): void {
        const todo = views.filter((v) => v !== this.pinned && !this.requested.has(v.key));
        if (todo.length <= BATCH) {
            this.send(todo);
            return;
        }
        const near = todo.filter((v) => this.virtualizer.isNear(v));
        this.send(near.length > 0 ? near : todo.slice(0, BATCH));
    }

    /**
     * Renders everything else in batches of [BATCH], one batch per 16 ms. The
     * walk keeps its place in [views] between ticks, so a whole pass costs
     * O(sections) in total rather than per tick (PERF-10).
     */
    private scheduleBackground(): void {
        if (this.backgroundTimer !== null || this.backgroundFrom >= this.views.length) return;
        this.backgroundTimer = setTimeout(() => {
            this.backgroundTimer = null;
            const batch: SectionView[] = [];
            let i = this.backgroundFrom;
            for (; i < this.views.length && batch.length < BATCH; i++) {
                const view = this.views[i];
                if (view && view.needsRender && view !== this.pinned && !this.requested.has(view.key)) batch.push(view);
            }
            this.backgroundFrom = i;
            this.send(batch);
            this.scheduleBackground();
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
        const touched: SectionView[] = [];
        for (const result of response.results) {
            this.requested.delete(result.key);
            for (const view of this.byKey.get(result.key) ?? []) {
                if (view === this.pinned) continue;
                view.setHtml(result.html, result.headings);
                this.virtualizer.update(view);
                touched.push(view);
            }
        }
        if (touched.length > 0) {
            this.virtualizer.rescale(this.views);
            this.hooks.onRendered(touched);
        }
        for (let i = this.waiters.length - 1; i >= 0; i--) {
            const waiter = this.waiters[i];
            if (waiter?.done()) {
                this.waiters.splice(i, 1);
                waiter.resolve();
            }
        }
    }
}
