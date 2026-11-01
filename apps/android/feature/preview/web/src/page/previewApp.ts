/**
 * The page: wires the bridge, the render controller, Mermaid, editing and
 * gestures together and answers Kotlin's messages.
 */
import type { HostMessage, PageMessage, PreviewSettings } from "../protocol";
import { Bridge } from "./bridge";
import { RenderController } from "./controller";
import { Editor } from "./editing/editor";
import { exportHtml } from "./exportHtml";
import { exportDiagram } from "./exportImage";
import { Gestures } from "./gestures";
import { MermaidRunner } from "./mermaidRunner";
import { breadcrumb, fillTocs } from "./outline";
import { scrollToLine, topLine } from "./scrollSync";
import { applyTheme } from "./theme";
import { BUILT_IN_THEMES } from "./themes";

const DEFAULT_SETTINGS: PreviewSettings = { tapToEdit: true, pageMode: false, zoom: 1, lean: false, readOnly: false };

export class PreviewApp {
    private readonly bridge: Bridge;
    private readonly controller: RenderController;
    private readonly mermaid = new MermaidRunner();
    private readonly editor: Editor;
    private settings: PreviewSettings = DEFAULT_SETTINGS;
    private anchorLine: number | null = null;
    private lastTop = -1;
    private scrollFrame = 0;
    private renderStarted = 0;

    constructor(root: HTMLElement, worker: Worker) {
        this.bridge = new Bridge((m) => this.onHostMessage(m));
        const post = (m: PageMessage): void => this.bridge.post(m);
        this.controller = new RenderController(root, worker, {
            onRendered: (views) => {
                this.editor.onRendered(views);
                fillTocs(document, this.controller.outline());
                this.restoreAnchor();
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
                this.mermaid.enqueueIn(view.el);
                fillTocs(view.el, this.controller.outline());
            },
        });
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
                this.editor.end();
                this.renderStarted = performance.now();
                this.anchorLine = m.anchorLine ?? null;
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
                scrollToLine(this.controller, m.line, m.smooth ?? false);
                // Not rendered yet: land again, exactly, once its section arrives.
                if (this.controller.viewAtLine(m.line)?.html === null) this.anchorLine = m.line;
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
                void this.preparePrint();
                break;
            case "flush":
                this.editor.flushTyping();
                break;
            case "exportHtml":
                void this.exportHtml(m.requestId, m.fromLine, m.toLine);
                break;
            case "finishPrint":
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
        scrollToLine(this.controller, line);
    }

    /** Reports the top line and breadcrumb at most once a frame, and only on change. */
    private onScroll(): void {
        this.reportTop(false);
    }

    private reportTop(force: boolean): void {
        if (this.scrollFrame !== 0) return;
        this.scrollFrame = requestAnimationFrame(() => {
            this.scrollFrame = 0;
            const line = Math.floor(topLine(this.controller));
            if (line === this.lastTop && !force) return;
            this.lastTop = line;
            this.bridge.post({ type: "topLine", line, crumbs: breadcrumb(this.controller.outline(), line) });
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

    private async exportHtml(requestId: number, fromLine: number, toLine: number): Promise<void> {
        let html: string | null = null;
        try {
            html = await exportHtml(this.controller, fromLine, toLine);
        } catch (e) {
            this.bridge.post({ type: "error", message: e instanceof Error ? e.message : String(e) });
        }
        this.bridge.post({ type: "htmlResult", requestId, html });
    }

    /** Print is the one path that renders every block; Kotlin shows progress meanwhile. */
    private async preparePrint(): Promise<void> {
        this.editor.end();
        this.controller.virtualizer.keepAll = true;
        document.documentElement.classList.add("printing");
        await this.controller.renderAll();
        for (const v of this.controller.views) {
            v.mount();
            this.mermaid.enqueueIn(v.el, true);
        }
        fillTocs(document, this.controller.outline());
        await this.mermaid.idle();
        await document.fonts.ready;
        this.bridge.post({ type: "printReady" });
    }
}
