/**
 * One block being edited in place. Typing inside a text run is left to the
 * browser and written back after a 150 ms pause as minimal source edits;
 * anything that crosses a run edge, adds a line or carries formatting is
 * intercepted and turned into a source edit directly, then the section is
 * re-rendered with the caret restored by source offset.
 */
import { runEdit } from "../../core/minimalEdit";
import { newlineInsert, type BlockKind } from "../../core/newline";
import { escapeTyped } from "../../core/minimalEdit";
import type { SourceEdit } from "../../core/sourceDoc";
import type { SectionView } from "../sectionView";
import { pointToSource, runText, sectionOffset, shiftRuns, snapshotRuns, type RunRecord } from "./runs";

export const FLUSH_DELAY_MS = 150;

/** What the session needs from the editor that owns it. */
export interface SessionHost {
    lineTextAt(offset: number): string;
    isLineStart(offset: number): boolean;
    commit(edits: readonly SourceEdit[]): void;
    commitAndRerender(edits: readonly SourceEdit[], caret: number): void;
    requestFormat(action: string, from: number, to: number): void;
    nextCell(): void;
}

export class EditSession {
    private runs: Map<HTMLElement, RunRecord>;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private readonly onInput = (): void => this.schedule();
    private readonly onBeforeInput = (e: InputEvent): void => this.beforeInput(e);
    readonly startKey: string;

    constructor(
        readonly block: HTMLElement,
        readonly view: SectionView,
        private readonly host: SessionHost,
    ) {
        this.runs = snapshotRuns(block);
        this.startKey = view.key;
    }

    get inTable(): boolean {
        return this.block.matches("td, th");
    }

    start(): void {
        this.block.contentEditable = "true";
        this.block.classList.add("editing");
        this.block.addEventListener("input", this.onInput);
        this.block.addEventListener("beforeinput", this.onBeforeInput);
        this.block.focus({ preventScroll: true });
    }

    stop(): void {
        this.flush();
        this.block.removeEventListener("input", this.onInput);
        this.block.removeEventListener("beforeinput", this.onBeforeInput);
        this.block.removeAttribute("contenteditable");
        this.block.classList.remove("editing");
    }

    /** Writes back every run whose text changed since the last flush. */
    flush(): void {
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = null;
        const edits: { edit: SourceEdit; el: HTMLElement }[] = [];
        for (const [el, rec] of this.runs) {
            if (!el.isConnected) continue;
            const atLineStart = this.host.isLineStart(rec.from);
            const edit = runEdit(rec, runText(el), { inTable: this.inTable, atLineStart });
            if (edit) edits.push({ edit, el });
        }
        if (edits.length === 0) return;
        this.host.commit(edits.map((e) => e.edit));
        const section = this.view.el;
        const base = sectionOffset(section);
        for (const { edit, el } of edits.sort((a, b) => b.edit.from - a.edit.from)) {
            const delta = edit.insert.length - (edit.to - edit.from);
            shiftRuns(section, edit.to - base, delta, el);
        }
        this.runs = snapshotRuns(this.block);
    }

    /** The selection as absolute source offsets, or null outside any run. */
    selectionRange(): { from: number; to: number } | null {
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return null;
        const range = sel.getRangeAt(0);
        if (!this.block.contains(range.startContainer)) return null;
        const a = pointToSource(this.block, range.startContainer, range.startOffset);
        const b = pointToSource(this.block, range.endContainer, range.endOffset);
        if (a === null || b === null) return null;
        return { from: Math.min(a, b), to: Math.max(a, b) };
    }

    private schedule(): void {
        if (this.timer !== null) clearTimeout(this.timer);
        this.timer = setTimeout(() => this.flush(), FLUSH_DELAY_MS);
    }

    private beforeInput(e: InputEvent): void {
        const type = e.inputType;
        if (type.startsWith("format") || type === "historyUndo" || type === "historyRedo") {
            e.preventDefault();
            const r = this.selectionRange();
            this.host.requestFormat(type, r?.from ?? 0, r?.to ?? 0);
            return;
        }
        if (type === "insertParagraph" || type === "insertLineBreak") {
            e.preventDefault();
            if (this.inTable) this.host.nextCell();
            else this.structural(this.newline(type === "insertLineBreak"), null);
            return;
        }
        if (type === "insertFromPaste" || type === "insertFromDrop") {
            e.preventDefault();
            const text = e.dataTransfer?.getData("text/plain") ?? "";
            this.structural(this.escapePasted(text), null);
            return;
        }
        const target = e.getTargetRanges()[0];
        if (target && !this.withinOneRun(target)) {
            e.preventDefault();
            const insert = e.data ? escapeTyped(e.data, { inTable: this.inTable, atLineStart: false }) : "";
            this.structural(insert, target);
        }
    }

    private withinOneRun(range: StaticRange): boolean {
        const runOf = (n: Node): Element | null =>
            (n instanceof Element ? n : n.parentElement)?.closest("[data-s]") ?? null;
        const a = runOf(range.startContainer);
        return a !== null && a === runOf(range.endContainer) && this.block.contains(a);
    }

    private structural(insert: string, target: StaticRange | null): void {
        this.flush();
        let from: number | null;
        let to: number | null;
        if (target) {
            from = pointToSource(this.block, target.startContainer, target.startOffset);
            to = pointToSource(this.block, target.endContainer, target.endOffset);
        } else {
            const r = this.selectionRange();
            from = r?.from ?? null;
            to = r?.to ?? null;
        }
        if (from === null || to === null) return;
        const edit = { from: Math.min(from, to), to: Math.max(from, to), insert };
        if (edit.from === edit.to && insert.length === 0) return;
        this.host.commitAndRerender([edit], edit.from + insert.length);
    }

    private newline(hard: boolean): string {
        const r = this.selectionRange();
        const line = this.host.lineTextAt(r?.from ?? 0);
        return newlineInsert(line, this.blockKind(), hard);
    }

    private blockKind(): BlockKind {
        if (this.block.matches("code, pre, pre *")) return "code";
        if (this.block.closest("li")) return "listItem";
        return "paragraph";
    }

    private escapePasted(text: string): string {
        if (this.blockKind() === "code") return text;
        return text
            .replace(/\r\n?/g, "\n")
            .split("\n")
            .map((line, i) => escapeTyped(line, { inTable: this.inTable, atLineStart: i > 0 }))
            .join(this.inTable ? " " : "\n");
    }
}
