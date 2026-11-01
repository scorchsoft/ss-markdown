/**
 * Tap-to-edit in Preview: starts and ends editing sessions, commits their
 * edits to the document and to Kotlin, and puts the caret back by source
 * offset after a section re-renders.
 */
import type { SourceEdit } from "../../core/sourceDoc";
import type { PageMessage, TableOp } from "../../protocol";
import type { RenderController } from "../controller";
import type { SectionView } from "../sectionView";
import { EditSession, type SessionHost } from "./editSession";
import { emptyRun, placeCaret, placeholderRun, sectionOf, setCaretInRun } from "./runs";
import {
    cellAddress,
    cellAtOffset,
    cellOffset,
    isPadded,
    repadEdits,
    tableOpEdits,
    type CellAddress,
} from "./tableEditor";
import { TableToolbar } from "./tableToolbar";

/** Blocks that take a caret. Anything inside a read-only block never does. */
export const EDITABLE_BLOCK = "p, h1, h2, h3, h4, h5, h6, li, td, th, dt, dd, pre.code-block > code[data-s]";

export class Editor implements SessionHost {
    private session: EditSession | null = null;
    private pendingCaret: number | null = null;
    private readonly toolbar: TableToolbar;
    private selectionTimer: ReturnType<typeof setTimeout> | null = null;
    /** Whether the table being edited was padded when its first cell was tapped. */
    private paddedTable = false;

    constructor(
        private readonly controller: RenderController,
        private readonly post: (message: PageMessage) => void,
    ) {
        this.toolbar = new TableToolbar({
            op: (op) => this.tableOp(op),
            more: () => this.tableMenu(),
        });
        document.addEventListener("selectionchange", () => this.onSelectionChange());
    }

    get editing(): boolean {
        return this.session !== null;
    }

    /** The block a tap on [target] would edit, or null if it is not editable. */
    editableBlock(target: Element): HTMLElement | null {
        if (target.closest(".ro-block, .ro-run, [contenteditable=false]")) return null;
        const block = target.closest<HTMLElement>(EDITABLE_BLOCK);
        if (!block || !sectionOf(block)) return null;
        const hasRun = block.matches("[data-s]") || block.querySelector("[data-s]") !== null;
        return hasRun || block.matches("td, th") ? block : null;
    }

    /** Starts editing [block] with the caret at the tap point. */
    begin(block: HTMLElement, x: number, y: number): void {
        if (this.session?.block === block) return;
        this.end();
        const view = this.viewOf(block);
        if (!view) return;
        this.startSession(block, view);
        const caret = document.caretRangeFromPoint?.(x, y);
        if (caret && block.contains(caret.startContainer)) {
            window.getSelection()?.collapse(caret.startContainer, caret.startOffset);
        }
        this.ensureCellRun(block);
        this.updateToolbar();
        this.post({ type: "editingChanged", editing: true });
    }

    /** Ends the session: flushes, re-pads a padded table, and re-renders if the text changed. */
    end(): void {
        const session = this.session;
        if (!session) return;
        this.session = null;
        session.stop();
        const addr = session.inTable ? cellAddress(session.block) : null;
        this.controller.pinned = null;
        this.toolbar.hide();
        if (addr && this.paddedTable) {
            const edits = repadEdits(this.controller.doc, addr);
            if (edits.length > 0) this.commit(edits);
        }
        if (session.view.key !== session.startKey || addr) this.controller.refresh(session.view);
        this.post({ type: "editingChanged", editing: false });
    }

    /** Called when sections get new HTML: restores a pending caret. */
    onRendered(views: readonly SectionView[]): void {
        const offset = this.pendingCaret;
        if (offset === null) return;
        const view = views.find((v) => v.startOffset <= offset && offset <= this.endOffset(v));
        if (!view || !view.mounted) return;
        this.pendingCaret = null;
        let run = placeCaret(view.el, offset);
        if (!run) {
            const cell = cellAtOffset(view.el, this.controller.doc, offset);
            run = cell
                ? cell.appendChild(emptyRun(view.el, offset))
                : placeholderRun(view.el, offset, this.controller.doc.lineAt(offset));
            setCaretInRun(run, 0);
        }
        const block = this.editableBlock(run) ?? run.closest<HTMLElement>(".placeholder");
        if (!block) return;
        const sel = window.getSelection();
        const anchor = sel && sel.rangeCount > 0 ? sel.getRangeAt(0) : null;
        this.startSession(block, view);
        if (anchor) {
            sel?.removeAllRanges();
            sel?.addRange(anchor);
        }
        this.updateToolbar();
    }

    private startSession(block: HTMLElement, view: SectionView): void {
        this.controller.pinned = view;
        this.session = new EditSession(block, view, this);
        this.session.start();
        const addr = this.session.inTable ? cellAddress(block) : null;
        this.paddedTable = addr ? isPadded(this.controller.doc, addr) : false;
    }

    /** Kotlin's Prev / Next cell keys. "Next" on the last cell adds a row. */
    moveCell(direction: "prev" | "next"): void {
        const session = this.session;
        if (!session?.inTable) return;
        const addr = cellAddress(session.block);
        if (!addr) return;
        const cols = session.block.parentElement?.children.length ?? 1;
        const rows = Number(session.block.closest("table")?.querySelectorAll("tr").length ?? 1);
        let row = addr.row;
        let col = addr.col + (direction === "next" ? 1 : -1);
        if (col >= cols) {
            col = 0;
            row++;
        } else if (col < 0) {
            col = cols - 1;
            row = Math.max(0, row - 1);
        }
        if (row >= rows) {
            this.tableOp({ kind: "addRow" }, { ...addr, col: 0 });
            return;
        }
        this.end();
        this.pendingCaret = cellOffset(this.controller.doc, addr, row, col);
        this.flushPendingCaret();
    }

    nextCell(): void {
        this.moveCell("next");
    }

    tableOp(op: TableOp, at?: CellAddress): void {
        const block = this.session?.block;
        const addr = at ?? (block ? cellAddress(block) : null);
        if (!addr) return;
        this.session?.flush();
        const result = tableOpEdits(this.controller.doc, addr, op);
        if (!result) return;
        this.end();
        this.commit(result.edits);
        this.pendingCaret = result.caretAfter(this.controller.doc);
        this.flushPendingCaret();
    }

    // SessionHost

    lineTextAt(offset: number): string {
        const doc = this.controller.doc;
        return doc.lines[doc.lineAt(offset)] ?? "";
    }

    isLineStart(offset: number): boolean {
        const doc = this.controller.doc;
        return doc.lineStart(doc.lineAt(offset)) === offset;
    }

    commit(edits: readonly SourceEdit[]): void {
        if (edits.length === 0) return;
        const baseRev = this.controller.rev;
        const sorted = [...edits].sort((a, b) => a.from - b.from);
        this.controller.applyLocalEdits(sorted);
        this.post({ type: "sourceEdit", baseRev, edits: sorted });
    }

    commitAndRerender(edits: readonly SourceEdit[], caret: number): void {
        this.end();
        this.commit(edits);
        this.pendingCaret = caret;
        this.flushPendingCaret();
    }

    requestFormat(action: string, from: number, to: number): void {
        this.session?.flush();
        this.post({ type: "formatRequest", action, from, to });
    }

    /** Before a Kotlin edit lands: flush typing and remember where the caret was. */
    beforeHostEdit(): number | null {
        if (!this.session) return null;
        const caret = this.session.selectionRange()?.from ?? null;
        this.end();
        return caret;
    }

    restoreCaret(offset: number): void {
        this.pendingCaret = offset;
        this.flushPendingCaret();
    }

    private flushPendingCaret(): void {
        const offset = this.pendingCaret;
        if (offset === null) return;
        const view = this.controller.views.find((v) => v.startOffset <= offset && offset <= this.endOffset(v));
        if (view?.mounted && view.html !== null && !view.el.querySelector(".editing")) {
            // The section may already be current (e.g. a caret move between cells).
            this.onRendered([view]);
        }
    }

    private endOffset(view: SectionView): number {
        const doc = this.controller.doc;
        const last = Math.max(view.startLine, view.endLine - 1);
        return doc.lineStart(last) + (doc.lines[last]?.length ?? 0);
    }

    private viewOf(block: HTMLElement): SectionView | null {
        const section = sectionOf(block);
        return this.controller.views.find((v) => v.el === section) ?? null;
    }

    /** An empty table cell has no run; give it one so typing has offsets. */
    private ensureCellRun(block: HTMLElement): void {
        if (!block.matches("td, th") || block.querySelector("[data-s]")) return;
        const addr = cellAddress(block);
        const section = sectionOf(block);
        if (!addr || !section) return;
        const view = this.session?.view;
        if (!view) return;
        const run = emptyRun(section, cellOffset(this.controller.doc, addr, addr.row, addr.col));
        block.appendChild(run);
        this.session?.stop();
        this.startSession(block, view);
        setCaretInRun(run, 0);
    }

    private updateToolbar(): void {
        const table = this.session?.inTable ? this.session.block.closest<HTMLElement>("table") : null;
        if (table) this.toolbar.showFor(table);
        else this.toolbar.hide();
    }

    private tableMenu(): void {
        const block = this.session?.block;
        const addr = block ? cellAddress(block) : null;
        if (addr) this.post({ type: "tableMenu", line: addr.startLine, row: addr.row, column: addr.col });
    }

    private onSelectionChange(): void {
        if (!this.session) return;
        if (this.selectionTimer !== null) clearTimeout(this.selectionTimer);
        this.selectionTimer = setTimeout(() => this.reportSelection(), 50);
    }

    private reportSelection(): void {
        const session = this.session;
        const range = session?.selectionRange();
        if (!session || !range) return;
        const marks = new Set<string>();
        const sel = window.getSelection();
        let node: Node | null = sel?.anchorNode ?? null;
        while (node && node !== session.block.parentElement) {
            if (node instanceof Element) {
                const mark = MARKS[node.tagName];
                if (mark) marks.add(mark);
            }
            node = node.parentNode;
        }
        this.post({ type: "selection", from: range.from, to: range.to, marks: [...marks], inTable: session.inTable });
    }
}

const MARKS: Readonly<Record<string, string>> = {
    STRONG: "bold",
    EM: "italic",
    CODE: "code",
    S: "strike",
    DEL: "strike",
    A: "link",
    MARK: "highlight",
    H1: "h1",
    H2: "h2",
    H3: "h3",
    BLOCKQUOTE: "quote",
    LI: "list",
};
