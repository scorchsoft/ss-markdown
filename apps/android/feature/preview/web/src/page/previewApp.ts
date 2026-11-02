/**
 * The page: wires the bridge, the render controller, Mermaid, editing and
 * gestures together and answers Kotlin's messages.
 */
import { nextLoad } from "../core/imagePaths";
import { readingFraction } from "../core/readingFraction";
import type { HostMessage, PageMessage, PreviewSettings } from "../protocol";
import { Bridge } from "./bridge";
import { RenderController } from "./controller";
import { Editor } from "./editing/editor";
import { exportHtml, type FormattedCopy } from "./exportHtml";
import { exportDiagram } from "./exportImage";
import { FindMarks } from "./findMarks";
import { Gestures } from "./gestures";
import { PageImages } from "./images";
import { MermaidRunner } from "./mermaidRunner";
import { breadcrumb, fillTocs } from "./outline";
import { scrollToLine, topLine } from "./scrollSync";
import { applyTheme } from "./theme";
import { BUILT_IN_THEMES } from "./themes";

/** Reading-line resolution: a report is sent when the fraction moves by a step. */
const FRACTION_STEPS = 1000;

const DEFAULT_SETTINGS: PreviewSettings = { tapToEdit: true, pageMode: false, zoom: 1, lean: false, readOnly: false };

export class PreviewApp {
    private readonly bridge: Bridge;
    private readonly controller: RenderController;
    private readonly mermaid = new MermaidRunner();
    private readonly editor: Editor;
    private readonly find: FindMarks;
    private readonly images: PageImages;
    private settings: PreviewSettings = DEFAULT_SETTINGS;
    private anchorLine: number | null = null;
    private lastTop = -1;
    private lastFractionStep = -1;
    /**
     * Where Kotlin last asked to land, when the page could not scroll that
     * line to the top (the last screen of the document). Until the reader
     * touches the page, it reports this line as its top, so a Code → Preview
     * → Code round trip comes back to the same line instead of the earlier
     * one at the top of the last screen (rubric #3). Layout settling after
     * the jump (sections mounting, diagrams drawing) does not end the pin;
     * only the reader's own input does.
     */
    private pinned: number | null = null;
    private scrollFrame = 0;
    private renderStarted = 0;
    /** Bumped by each print preparation, `load` and `finishPrint`: a preparation answers only while it is current. */
    private printRequest = 0;

    constructor(root: HTMLElement, worker: Worker) {
        this.bridge = new Bridge((m) => this.onHostMessage(m));
        const post = (m: PageMessage): void => this.bridge.post(m);
        this.images = new PageImages(post);
        this.controller = new RenderController(root, worker, {
            onRendered: (views) => {
                this.editor.onRendered(views);
                for (const v of views) this.images.scan(v.el);
                fillTocs(document, this.controller.outline());
                this.restoreAnchor();
                this.find.onRendered();
                this.reportTop(true);
                post({
                    type: "rendered",
                    rev: this.controller.rev,
                    sections: views.length,
                    millis: Math.round(performance.now() - this.renderStarted),
                });
            },
            onMounted: (view) => {
                this.editor.onRendered([view]);
                this.images.scan(view.el);
                this.mermaid.enqueueIn(view.el);
                fillTocs(view.el, this.controller.outline());
                this.find.onRendered();
            },
        });
        this.find = new FindMarks(this.controller);
        this.editor = new Editor(this.controller, post);
        worker.onerror = (e) => post({ type: "error", message: `Render worker failed: ${e.message}` });
        new Gestures({
            controller: this.controller,
            editor: this.editor,
            mermaid: this.mermaid,
            settings: () => this.settings,
            post,
        });
        window.addEventListener("scroll", () => this.onScroll(), { passive: true });
        for (const type of ["pointerdown", "wheel", "keydown", "touchstart"]) {
            window.addEventListener(
                type,
                () => {
                    this.pinned = null;
                    this.find.onReaderInput();
                },
                { passive: true, capture: true },
            );
        }
        // Going to the background: typing still waiting for its pause goes now (DS-14).
        document.addEventListener("visibilitychange", () => {
            if (document.visibilityState === "hidden") this.editor.flushTyping();
        });
        applyTheme(
            document.documentElement,
            BUILT_IN_THEMES.clean ?? { name: "Clean" },
            matchMedia("(prefers-color-scheme: dark)").matches,
        );
    }

    private onHostMessage(m: HostMessage): void {
        switch (m.type) {
            case "load":
                // A print being prepared was for the page as it was: abandon it (it never answers).
                this.printRequest++;
                this.editor.end();
                // Relative pictures belong to the document: another one's statuses do not apply,
                // and another one's picture of the same path is not this one's.
                this.images.reset();
                nextLoad();
                this.renderStarted = performance.now();
                this.anchorLine = m.anchorLine ?? null;
                this.find.clear();
                this.controller.load(m.text ?? "", m.rev);
                break;
            case "edit":
                this.hostEdit(m);
                break;
            case "ack": {
                const problem = this.controller.sync.ack(m.rev);
                if (problem) this.resync(problem);
                break;
            }
            case "format":
                this.editor.format(m.action);
                break;
            case "select": {
                const from = this.controller.sync.fromHost(m.from, m.rev);
                const to = this.controller.sync.fromHost(m.to, m.rev);
                if (from !== null && to !== null) this.editor.select(from, to);
                break;
            }
            case "theme":
                applyTheme(document.documentElement, m.theme, m.dark, m.fonts);
                this.mermaid.setTheme(m.dark, m.theme.name);
                break;
            case "settings":
                this.applySettings(m.settings);
                break;
            case "scrollToLine":
                this.land(m.line, m.smooth ?? false);
                // Not rendered yet: land again, exactly, once its section arrives.
                if (this.controller.viewAtLine(m.line)?.html === null) this.anchorLine = m.line;
                // Split following the Code pane must not scroll a find match just revealed out of view.
                this.find.afterHostScroll();
                break;
            case "tableNav":
                this.editor.moveCell(m.direction);
                break;
            case "tableOp":
                this.editor.tableOp(m.op);
                break;
            case "endEditing":
                this.editor.end();
                (document.activeElement as HTMLElement | null)?.blur();
                break;
            case "exportImage":
                void this.exportImage(m.requestId, m.line, m.format, m.scale);
                break;
            case "preparePrint":
                void this.preparePrint(m.requestId);
                break;
            case "flush":
                this.editor.flushTyping();
                break;
            case "exportHtml":
                void this.exportHtml(m.requestId, m.fromLine, m.toLine, m.maxChars);
                break;
            case "find":
                this.findMatches(m);
                break;
            case "imageStatus":
                this.images.onStatus(m.id, m.statuses);
                break;
            case "imagesChanged":
                this.images.changed();
                break;
            case "finishPrint":
                this.printRequest++;
                this.controller.virtualizer.keepAll = false;
                document.documentElement.classList.remove("printing");
                for (const v of this.controller.views) this.controller.virtualizer.update(v);
                break;
        }
    }

    /**
     * Kotlin's own change. Typing in progress is flushed first, so it is in
     * flight and gets rebased over this change rather than lost; then the
     * change (rebased over it) is applied and the selection moved over it.
     */
    private hostEdit(m: Extract<HostMessage, { type: "edit" }>): void {
        const saved = this.editor.beforeHostEdit();
        this.renderStarted = performance.now();
        const outcome = this.controller.sync.host(m.rev, m.ops);
        switch (outcome.kind) {
            case "apply":
                this.controller.applyEdits(outcome.edits);
                this.editor.afterHostEdit(saved, outcome.edits);
                break;
            case "lines":
                this.controller.applyHostLines(m.changes);
                if (saved) this.editor.restoreCaret(saved.from);
                break;
            case "resync":
                this.resync(outcome.reason);
                break;
        }
    }

    /** Kotlin's find matches, mapped over any page edits still in flight; dropped when stale. */
    private findMatches(m: Extract<HostMessage, { type: "find" }>): void {
        if (this.controller.sync.fromHost(0, m.rev) === null) return;
        let ranges: number[] | null = null;
        if (m.ranges) {
            ranges = [];
            for (const offset of m.ranges) ranges.push(this.controller.sync.fromHost(offset, m.rev) ?? offset);
        }
        if (ranges?.length === 0) this.find.clear();
        else this.find.set(ranges, m.current, m.reveal);
    }

    /** Out of step with Kotlin: ask for its text. Every edit made here has already been sent. */
    private resync(reason: string): void {
        this.editor.end();
        this.bridge.post({ type: "resync", reason });
    }

    private applySettings(s: PreviewSettings): void {
        this.settings = s;
        const root = document.documentElement;
        root.style.setProperty("--zoom", String(Math.min(4, Math.max(0.5, s.zoom))));
        root.classList.toggle("page-mode", s.pageMode);
        root.classList.toggle("no-wrap", s.noWrap === true && !s.pageMode);
        root.classList.toggle("read-only", s.readOnly || !s.tapToEdit);
        this.mermaid.setLean(s.lean);
        if (s.readOnly || !s.tapToEdit) this.editor.end();
    }

    private restoreAnchor(): void {
        if (this.anchorLine === null) return;
        const line = this.anchorLine;
        const view = this.controller.viewAtLine(line);
        if (!view?.mounted) return;
        this.anchorLine = null;
        this.land(line);
    }

    /** Scrolls [line] to the top, pinning it as the reported top when the page cannot scroll that far. */
    private land(line: number, smooth = false): void {
        scrollToLine(this.controller, line, smooth);
        const short = !smooth && atBottom() && Math.floor(topLine(this.controller)) < line;
        this.pinned = short ? line : null;
        // Already at the bottom, the page does not move and fires no scroll event: say so anyway.
        if (short) this.reportTop(false);
    }

    /** Reports the top line and breadcrumb at most once a frame, and only on change. */
    private onScroll(): void {
        this.reportTop(false);
    }

    private reportTop(force: boolean): void {
        if (this.scrollFrame !== 0) return;
        this.scrollFrame = requestAnimationFrame(() => {
            this.scrollFrame = 0;
            // The page grew under a pin (sections mounted): land again, now that it may reach.
            if (this.pinned !== null && !atBottom()) this.land(this.pinned);
            const line = this.pinned ?? Math.floor(topLine(this.controller));
            // The reading line follows the scroll position, which moves on the last screen
            // where the top line no longer can; a step of 1/1000 is about a pixel of the line.
            const fraction = readingFraction(window.scrollY, document.documentElement.scrollHeight, window.innerHeight);
            const step = Math.round(fraction * FRACTION_STEPS);
            if (line === this.lastTop && step === this.lastFractionStep && !force) return;
            this.lastTop = line;
            this.lastFractionStep = step;
            this.bridge.post({ type: "topLine", line, crumbs: breadcrumb(this.controller.outline(), line), fraction });
        });
    }

    private async exportImage(requestId: number, line: number, format: "svg" | "png", scale: number): Promise<void> {
        try {
            scrollToLine(this.controller, line);
            const view = this.controller.viewAtLine(line);
            if (view) this.mermaid.enqueueIn(view.el, true);
            await this.mermaid.idle();
            const boxes = [...document.querySelectorAll<HTMLElement>(".ro-diagram[data-line]")];
            const box =
                boxes.find((b) => Number(b.dataset.line) === line) ?? boxes.find((b) => Number(b.dataset.line) >= line);
            if (!box) throw new Error("No diagram at that line.");
            const result = await exportDiagram(box, format, Math.min(3, Math.max(1, scale)));
            this.bridge.post({ type: "exportResult", requestId, ...result });
        } catch (e) {
            this.bridge.post({ type: "error", message: e instanceof Error ? e.message : String(e) });
        }
    }

    private async exportHtml(requestId: number, fromLine: number, toLine: number, maxChars: number): Promise<void> {
        let copy: FormattedCopy | null = null;
        try {
            copy = await exportHtml(this.controller, fromLine, toLine, maxChars, (source) =>
                this.mermaid.drawForCopy(source),
            );
        } catch (e) {
            this.bridge.post({ type: "error", message: e instanceof Error ? e.message : String(e) });
        }
        this.bridge.post({
            type: "htmlResult",
            requestId,
            html: copy?.html ?? null,
            text: copy?.text ?? null,
            truncated: copy?.truncated ?? false,
        });
    }

    /**
     * Print is the one path that renders every block. The answer names the
     * request; a `load` or `finishPrint` while it renders abandons it, so a
     * request made for one document is never answered from the next.
     */
    private async preparePrint(requestId: number): Promise<void> {
        const mine = ++this.printRequest;
        const current = (): boolean => mine === this.printRequest;
        this.editor.end();
        this.controller.virtualizer.keepAll = true;
        document.documentElement.classList.add("printing");
        await this.controller.renderAll();
        if (!current()) return;
        for (const v of this.controller.views) {
            v.mount();
            this.mermaid.enqueueIn(v.el, true);
        }
        fillTocs(document, this.controller.outline());
        await this.mermaid.idle();
        await document.fonts.ready;
        if (current()) this.bridge.post({ type: "printReady", requestId });
    }
}

/** True when the page is scrolled as far down as it goes. */
function atBottom(): boolean {
    return window.scrollY >= document.documentElement.scrollHeight - window.innerHeight - 1;
}
