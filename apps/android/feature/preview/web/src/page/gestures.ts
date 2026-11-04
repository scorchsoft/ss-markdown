/**
 * Routes taps and long-presses in Preview by the precedence in
 * docs/ux-spec.md, "Gestures in Preview" (top wins):
 *
 * 1. A tap within 500 ms of a scroll or fling only stops the scroll.
 * 2. Diagram, image or maths -> the viewer.
 * 3. Link -> Kotlin shows the link chip (Open · Copy link · Edit link), even when tap-to-edit is off.
 * 4. Task checkbox -> toggle (one character changes).
 * 5. Text -> caret and keyboard, when tap-to-edit is on.
 * 6. Long-press a heading -> the section menu. 7. Other long-presses stay native.
 *
 * There are no double-tap gestures: listening for one would delay every tap.
 */
import type { PageMessage, PreviewSettings } from "../protocol";
import type { RenderController } from "./controller";
import type { Editor } from "./editing/editor";
import { ALLOW_BUTTON, PLACEHOLDER } from "./images";
import { toggleDiagramSource, type MermaidRunner } from "./mermaidRunner";
import { scrollToLine } from "./scrollSync";

export const FLING_GUARD_MS = 500;
/**
 * Half the task checkbox's hit area: 48 dp in all, though the box is drawn at
 * about 16 px (ux-spec, review 3: "Task checkboxes in Preview get a 48 dp hit
 * area"). The page's viewport is device-width, so a CSS pixel is a dp.
 */
export const TASK_HIT_RADIUS = 24;
const TASK_LINE = /^(\s*(?:>\s?)*\s*(?:[-+*]|\d{1,9}[.)])\s+)\[([ xX])\]/;

type ViewerKind = "diagram" | "image" | "math";

export interface GestureDeps {
    readonly controller: RenderController;
    readonly editor: Editor;
    readonly mermaid: MermaidRunner;
    readonly settings: () => PreviewSettings;
    readonly post: (message: PageMessage) => void;
    readonly now?: () => number;
}

function lineOf(el: Element): number {
    const holder = el.closest<HTMLElement>("[data-line]");
    return Number(holder?.dataset.line ?? 0);
}

function viewerIndex(el: HTMLElement, kind: ViewerKind): number {
    const all = [...document.querySelectorAll<HTMLElement>(`[data-viewer="${kind}"]`)];
    return Math.max(0, all.indexOf(el));
}

/**
 * The task checkbox a tap at ([x], [y]) means: the box itself, or the one on
 * the same list item whose centre is within [TASK_HIT_RADIUS] (UX-12).
 */
export function taskCheckboxAt(target: Element, x: number, y: number): HTMLInputElement | null {
    if (target instanceof HTMLInputElement && target.classList.contains("task-list-item-checkbox")) return target;
    const item = target.closest(".task-list-item");
    const box = item?.querySelector<HTMLInputElement>("input.task-list-item-checkbox");
    if (!box || box.closest(".task-list-item") !== item) return null;
    const r = box.getBoundingClientRect();
    const near =
        Math.abs(x - (r.left + r.width / 2)) <= TASK_HIT_RADIUS &&
        Math.abs(y - (r.top + r.height / 2)) <= TASK_HIT_RADIUS;
    return near ? box : null;
}

export class Gestures {
    private lastScrollAt = -Infinity;
    private readonly now: () => number;

    constructor(private readonly deps: GestureDeps) {
        this.now = deps.now ?? (() => performance.now());
        window.addEventListener("scroll", () => (this.lastScrollAt = this.now()), { passive: true });
        document.addEventListener("click", (e) => this.onClick(e), true);
        document.addEventListener("contextmenu", (e) => this.onLongPress(e), true);
    }

    private onClick(e: MouseEvent): void {
        const target = e.target instanceof Element ? e.target : null;
        if (!target || target.closest(".table-toolbar")) return;
        // A picture's placeholder is page chrome: its button works even in the block being
        // edited, and a tap on the rest of it neither opens the viewer nor starts editing.
        if (target.closest(`.${PLACEHOLDER}`)) {
            if (target.closest(`.${ALLOW_BUTTON}`)) this.deps.post({ type: "allowFolder" });
            e.preventDefault();
            e.stopPropagation();
            return;
        }
        const editingBlock = target.closest(".editing");
        if (editingBlock) return; // moving the caret inside the block being edited
        if (this.now() - this.lastScrollAt < FLING_GUARD_MS) {
            e.preventDefault();
            e.stopPropagation();
            return;
        }
        if (this.route(target, e)) {
            e.preventDefault();
            e.stopPropagation();
        }
    }

    /** Returns true when the tap was consumed. */
    private route(target: Element, e: MouseEvent): boolean {
        const { editor, post } = this.deps;
        const chip = target.closest<HTMLElement>(".edit-in-code");
        if (chip) {
            const section = chip.closest<HTMLElement>("section.sec");
            post({ type: "editInCode", line: Number(section?.dataset.line ?? 0) + Number(chip.dataset.editRl ?? 0) });
            return true;
        }
        const showSource = target.closest<HTMLElement>(".diagram-show-source");
        if (showSource) {
            toggleDiagramSource(showSource);
            return true;
        }
        const deferred = target.closest<HTMLElement>(".diagram-deferred");
        if (deferred) {
            deferred.querySelector("pre.diagram-source")?.removeAttribute("data-state");
            this.deps.mermaid.enqueueIn(deferred, true);
            return true;
        }
        const viewer = target.closest<HTMLElement>("[data-viewer]");
        if (viewer) {
            const kind = viewer.dataset.viewer as ViewerKind;
            post({ type: "openViewer", kind, index: viewerIndex(viewer, kind), line: lineOf(viewer) });
            return true;
        }
        const link = target.closest<HTMLAnchorElement>("a[href]");
        if (link) return this.onLink(link);
        const task = taskCheckboxAt(target, e.clientX, e.clientY);
        if (task) {
            this.toggleTask(task);
            return true;
        }
        const settings = this.deps.settings();
        if (!settings.tapToEdit || settings.readOnly) {
            editor.end();
            return false;
        }
        // On a drawn blank line: typing on that line (render/blankLinesPlugin.ts).
        if (editor.beginOnBlankLine(target)) return false;
        // On or beside a rule (and not on a block's own text): before or after it (EB-BREAK-08).
        if (!editor.editableBlock(target) && editor.beginAtRule(e.clientX, e.clientY)) return false;
        const block = editor.blockAt(target, e.clientX, e.clientY);
        if (block) {
            editor.begin(block, e.clientX, e.clientY);
            return false;
        }
        // Below everything shown, and anywhere in an empty file: editing starts at the end.
        if (editor.beginBelowEnd(e.clientX, e.clientY)) return false;
        editor.end();
        return false;
    }

    private onLink(link: HTMLAnchorElement): boolean {
        const href = link.getAttribute("href") ?? "";
        if (href.startsWith("#")) {
            const toc = link.dataset.tocLine;
            if (toc !== undefined) scrollToLine(this.deps.controller, Number(toc));
            else document.getElementById(decodeURIComponent(href.slice(1)))?.scrollIntoView();
            return true;
        }
        this.deps.post({ type: "linkTapped", href, line: lineOf(link) });
        return true;
    }

    private toggleTask(box: HTMLInputElement): void {
        const { controller, post } = this.deps;
        const line = lineOf(box);
        const text = controller.doc.lines[line] ?? "";
        const m = TASK_LINE.exec(text);
        // A file that refuses edits keeps its boxes as they are.
        if (!m?.[1] || this.deps.settings().readOnly) {
            box.checked = !box.checked;
            return;
        }
        const offset = controller.doc.lineStart(line) + m[1].length + 1;
        const checked = m[2] === " ";
        // Applied locally too, as a one-character edit in flight like any other, so the
        // page and Kotlin stay in step even if Kotlin changes the text meanwhile.
        const baseRev = controller.applyLocalEdits([{ from: offset, to: offset + 1, insert: checked ? "x" : " " }]);
        post({ type: "taskToggle", baseRev, offset, checked });
    }

    private onLongPress(e: MouseEvent): void {
        const target = e.target instanceof Element ? e.target : null;
        if (!target) return;
        // A heading's long-press selects its text like any other (Andrew, 2026-11-04: "there might be many
        // reasons that I select text"): the selection's own menu copies it as Markdown or formatted, and
        // the section menu stays one long-press away in the Navigator. `headingLongPress` is no longer sent.
        const viewer = target.closest<HTMLElement>("[data-viewer]");
        if (viewer) {
            e.preventDefault();
            const kind = viewer.dataset.viewer as ViewerKind;
            this.deps.post({ type: "mediaLongPress", kind, index: viewerIndex(viewer, kind), line: lineOf(viewer) });
        }
    }
}
