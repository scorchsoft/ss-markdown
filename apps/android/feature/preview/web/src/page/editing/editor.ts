/**
 * Tap-to-edit in Preview: starts and ends editing sessions, commits their
 * edits to the document and to Kotlin, and puts the caret back by source
 * offset after a section re-renders.
 */
import type { SourceDoc, SourceEdit } from "../../core/sourceDoc";
import { mapOffset } from "../../core/ot";
import type { FormatAction, PageMessage, TableOp } from "../../protocol";
import type { RenderController } from "../controller";
import type { SectionView } from "../sectionView";
import { escapeTyped } from "../../core/minimalEdit";
import { codeExit } from "../../core/newline";
import { EditSession, type SessionHost } from "./editSession";
import { CaretKeeper } from "./caretKeeper";
import { KeyboardKeeper } from "./keyboardKeeper";
import { emptyRun, placeCaret, placeholderRun, pointToSource, sectionOf, setCaretInRun } from "./runs";
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
import { blockNear, caretAt } from "./tapPoint";
import { copySelection } from "./clip";

/** Blocks that take a caret. Anything inside a read-only block never does. */
export const EDITABLE_BLOCK = "p, h1, h2, h3, h4, h5, h6, li, td, th, dt, dd, pre.code-block > code[data-s]";

export class Editor implements SessionHost {
    private session: EditSession | null = null;
    private pendingCaret: number | null = null;
    /** The far end of a selection to restore with [pendingCaret]; null for a caret. */
    private pendingEnd: number | null = null;
    private readonly toolbar: TableToolbar;
    private selectionTimer: ReturnType<typeof setTimeout> | null = null;
    /** Whether the table being edited was padded when its first cell was tapped. */
    private paddedTable = false;
    /** A handoff skipped the re-pad: the session that takes over still owes it. */
    private carryPadding = false;
    /** Holds the keyboard while a block is re-rendered under the caret. */
    private readonly keeper = new KeyboardKeeper();
    /** Kept typing still to write after a kept Enter has run (see [afterStart]). */
    private carried = "";
    /** A kept Enter waits for the session that starts after the text before it was written. */
    private enterNext = false;
    /** Keeps the caret above the keyboard and the strip (ux-spec "Keyboard"). */
    private readonly caretKeeper = new CaretKeeper(() => this.session !== null);
    /** What Kotlin was last told by `editingChanged`. */
    private announced = false;

    constructor(
        private readonly controller: RenderController,
        private readonly post: (message: PageMessage) => void,
    ) {
        this.toolbar = new TableToolbar({
            op: (op) => this.tableOp(op),
            more: () => this.tableMenu(),
        });
        document.addEventListener("selectionchange", () => this.onSelectionChange());
        document.addEventListener("copy", (e) => copySelection(e, this.controller.doc));
        document.addEventListener("cut", (e) => {
            const session = this.session;
            const inBlock = session?.block.contains(window.getSelection()?.anchorNode ?? null);
            if (copySelection(e, this.controller.doc) && inBlock) session?.cut();
        });
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

    /** The block a tap on [target] at ([x], [y]) edits, including one beside it (tapPoint.ts). */
    blockAt(target: Element, x: number, y: number): HTMLElement | null {
        return blockNear(target, x, y, (el) => this.editableBlock(el));
    }

    /** Starts editing [block] with the caret at the tap point. */
    begin(block: HTMLElement, x: number, y: number): void {
        if (this.session?.block === block) {
            caretAt(block, x, y); // a tap in the padding of the block being edited
            return;
        }
        this.end();
        const view = this.viewOf(block);
        if (!view) return;
        const typed = this.startSession(block, view);
        caretAt(block, x, y);
        this.ensureCellRun(block);
        this.updateToolbar();
        this.announce(true);
        this.afterStart(typed, null);
    }

    /**
     * Ends the session: flushes, re-pads a padded table, and re-renders if the
     * text changed. With [handoff] the session is only being swapped for the
     * re-rendered block (a strip command, Enter, a table move): focus waits in
     * the [KeyboardKeeper] so the keyboard stays up, and Kotlin is not told
     * editing stopped. Without it, editing really ends and the keyboard may go.
     */
    end(handoff = false, repad = true): void {
        const session = this.session;
        if (!handoff) this.carryPadding = false;
        if (!session) {
            if (!handoff) this.stopHolding();
            return;
        }
        this.session = null;
        // Focus moves to the keeper before the block stops being editable, never after.
        const held = handoff && this.keeper.hold(() => this.announce(false));
        session.stop();
        const addr = session.inTable ? cellAddress(session.block) : null;
        this.controller.pinned = null;
        this.toolbar.hide();
        if (addr && this.paddedTable && !repad) {
            this.carryPadding = true;
        } else if (addr && this.paddedTable) {
            const edits = repadEdits(this.controller.doc, addr);
            if (edits.length > 0) this.commit(edits);
        }
        if (session.view.key !== session.startKey || addr) this.controller.refresh(session.view);
        if (!held) {
            this.stopHolding();
            this.announce(false);
        }
    }

    /** Lets the keyboard go if the keeper still has it. */
    private stopHolding(): void {
        this.keeper.drop();
    }

    private announce(editing: boolean): void {
        if (this.announced === editing) return;
        this.announced = editing;
        if (editing) this.caretKeeper.begin();
        this.post({ type: "editingChanged", editing });
    }

    /** Called when sections get new HTML: restores a pending caret. */
    onRendered(views: readonly SectionView[]): void {
        const offset = this.pendingCaret;
        if (offset === null) return;
        const target = this.controller.viewAtOffset(offset);
        const view = target && views.includes(target) ? target : null;
        // A section whose HTML an edit has outdated (still on screen until its render) never takes the caret.
        if (!view || !view.mounted || view.needsRender) return;
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
        const end = this.pendingEnd;
        this.pendingEnd = null;
        if (end !== null && sel && sel.rangeCount > 0) {
            const start = sel.getRangeAt(0);
            const startNode = start.startContainer;
            const startOffset = start.startOffset;
            if (placeCaret(view.el, end)) {
                const far = sel.getRangeAt(0);
                sel.setBaseAndExtent(startNode, startOffset, far.startContainer, far.startOffset);
            }
        }
        const anchor = sel && sel.rangeCount > 0 ? sel.getRangeAt(0).cloneRange() : null;
        const typed = this.startSession(block, view);
        if (anchor) {
            sel?.removeAllRanges();
            sel?.addRange(anchor);
        }
        this.updateToolbar();
        // Only now, with the caret back where it belongs: focusing the block moved it to the block's start.
        this.afterStart(typed, offset);
        // Enter, a strip key, Undo or Kotlin's select may have put the caret under the keyboard.
        this.caretKeeper.reveal();
    }

    /**
     * Starts editing [block] and returns what was typed while the keeper held
     * the keyboard. The caller writes it with [insertKept] once the caret is
     * where it belongs: focusing the block moves the selection to its start.
     */
    private startSession(block: HTMLElement, view: SectionView): string {
        this.controller.pinned = view;
        const session = new EditSession(block, view, this);
        this.session = session;
        const typed = this.keeper.release();
        session.start();
        const addr = session.inTable ? cellAddress(block) : null;
        this.paddedTable = addr ? this.carryPadding || isPadded(this.controller.doc, addr) : false;
        this.carryPadding = false;
        this.announce(true);
        return typed;
    }

    /**
     * What the keeper held, in order, once a session has its caret: a kept
     * Enter runs as Enter (its own edit and re-render), and what was typed
     * after it waits for the session that follows (edge-cases "Typing
     * straight after Enter in a list lost letters").
     */
    private afterStart(typed: string, fallback: number | null): void {
        const all = this.carried + typed;
        this.carried = "";
        if (this.enterNext) {
            this.enterNext = false;
            this.carried = all;
            this.session?.pressEnter();
            return;
        }
        const nl = all.indexOf("\n");
        if (nl < 0) {
            this.insertKept(all, fallback);
            return;
        }
        this.carried = all.slice(nl + 1);
        if (nl === 0) {
            this.session?.pressEnter();
        } else {
            this.enterNext = true;
            this.insertKept(all.slice(0, nl), fallback);
        }
    }

    /**
     * Text typed while the keeper held the keyboard: written at the caret, or
     * at [fallback] (where the caret was being restored to) when the selection
     * is not in the block. Never dropped (edge-cases "Typing straight after
     * Enter in a list lost letters").
     */
    private insertKept(typed: string, fallback: number | null): void {
        const session = this.session;
        if (typed.length === 0 || !session) return;
        const range = session.selectionRange() ?? (fallback === null ? null : { from: fallback, to: fallback });
        const from = range?.from ?? this.firstRunOffset(session.block);
        const to = range?.to ?? from;
        const insert = escapeTyped(typed, { inTable: session.inTable, atLineStart: this.isLineStart(from) });
        this.commitAndRerender([{ from, to, insert }], from + insert.length);
    }

    /** The source offset where [block]'s first run starts: the last resort for kept typing. */
    private firstRunOffset(block: HTMLElement): number {
        const run = block.matches("[data-s]") ? block : block.querySelector<HTMLElement>("[data-s]");
        return run ? (pointToSource(block, run, 0) ?? 0) : 0;
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
        this.end(true);
        this.pendingCaret = cellOffset(this.controller.doc, addr, row, col);
        this.flushPendingCaret();
    }

    /** "Paste as plain text" from Kotlin's menu item or Ctrl+Shift+V: the words of the clip, at the selection. */
    pastePlain(text: string, html: string): void {
        this.session?.insertClip(text, html, true);
    }

    nextCell(): void {
        this.moveCell("next");
    }

    /** Writes back typing waiting for its 150 ms pause, now, and stays in editing (DS-14). */
    flushTyping(): void {
        this.session?.flush();
    }

    tableOp(op: TableOp, at?: CellAddress): void {
        const block = this.session?.block;
        const addr = at ?? (block ? cellAddress(block) : null);
        if (!addr) return;
        // Ending first writes back the typing and re-pads the table, so the op is worked out on the
        // text as it is now: worked out before, its offsets missed the re-pad and broke the table.
        this.end(true);
        const doc = this.controller.doc;
        const result = tableOpEdits(doc, addr, op);
        if (result) this.commit(result.edits);
        this.pendingCaret = result ? result.caretAfter(this.controller.doc) : cellOffset(doc, addr, addr.row, addr.col);
        this.flushPendingCaret();
    }

    // SessionHost

    get doc(): SourceDoc {
        return this.controller.doc;
    }

    lineTextAt(offset: number): string {
        const doc = this.controller.doc;
        return doc.lines[doc.lineAt(offset)] ?? "";
    }

    lineStartAt(offset: number): number {
        const doc = this.controller.doc;
        return doc.lineStart(doc.lineAt(offset));
    }

    codeExitAt(offset: number): { from: number; to: number; insert: string; caret: number } | null {
        const doc = this.controller.doc;
        return codeExit(doc.lines, doc.lineAt(offset), (line) => doc.lineStart(line));
    }

    isLineStart(offset: number): boolean {
        const doc = this.controller.doc;
        return doc.lineStart(doc.lineAt(offset)) === offset;
    }

    commit(edits: readonly SourceEdit[]): void {
        if (edits.length === 0) return;
        const sorted = [...edits].sort((a, b) => a.from - b.from);
        const baseRev = this.controller.applyLocalEdits(sorted);
        this.post({ type: "sourceEdit", baseRev, edits: sorted });
    }

    commitAndRerender(edits: readonly SourceEdit[], caret: number): void {
        // No re-pad now: it would move [edits]' offsets. The session that takes over re-pads on leaving.
        this.end(true, false);
        this.commit(edits);
        this.pendingCaret = caret;
        this.flushPendingCaret();
    }

    /** A formatting shortcut from the keyboard (`beforeinput` formatBold…). */
    requestFormat(inputType: string): void {
        const action = INPUT_FORMATS[inputType];
        if (action) this.format(action);
    }

    /**
     * A strip key or keyboard shortcut: flush typing, then ask Kotlin to apply
     * [action] to the selection's source range. Kotlin answers with an edit
     * and a `select` that puts the selection back.
     */
    format(action: FormatAction): void {
        const session = this.session;
        if (!session) return;
        session.flush();
        if (this.session !== session) return; // the flush re-rendered (a refused change)
        const range = session.selectionRange();
        if (!range) return;
        this.post({
            type: "formatRequest",
            action,
            from: range.from,
            to: range.to,
            baseRev: this.controller.rev,
            inTable: session.inTable,
        });
        // Keys that reshape the text under the caret (Enter, Backspace…): hand over at once, so what is
        // typed before Kotlin's answer is kept by the keeper and written where the caret lands.
        if (STRUCTURAL.has(action)) this.end(true);
    }

    /** Kotlin's `select`, already mapped into the page's text: restore it after the re-render. */
    select(from: number, to: number): void {
        // A session the host's edit already restored (its render can land between the edit and this
        // `select`) hands over, so the caret goes where Kotlin says and not where it was mapped to.
        if (this.session) this.end(true);
        this.pendingCaret = from;
        this.pendingEnd = to > from ? to : null;
        this.flushPendingCaret();
    }

    /** Undoes an unmappable change on screen by re-rendering from source, and tells Kotlin. */
    refuse(): void {
        const session = this.session;
        if (!session) return;
        const line = Number(session.block.closest<HTMLElement>("[data-line]")?.dataset.line ?? session.view.startLine);
        const caret = session.selectionRange()?.from ?? null;
        const view = session.view;
        this.end(true);
        this.controller.refresh(view);
        view.el.classList.add("edit-refused");
        setTimeout(() => view.el.classList.remove("edit-refused"), 600);
        this.post({ type: "editRefused", line });
        // Restored when the re-render arrives, not into the DOM being replaced.
        this.pendingCaret = caret;
        this.pendingEnd = null;
    }

    /** Before a Kotlin edit lands: flush typing and remember where the selection was. */
    beforeHostEdit(): { from: number; to: number } | null {
        if (!this.session) return null;
        this.session.flush();
        const range = this.session?.selectionRange() ?? null;
        // The edit re-renders the block under the caret: hand over, keep the keyboard (Bold, ¶). No
        // re-pad now: it would race the edit, whose insert inside a re-padded row lands at its end.
        this.end(true, false);
        return range;
    }

    /** Puts back a selection saved by [beforeHostEdit], moved over Kotlin's [edits]. */
    afterHostEdit(
        saved: { from: number; to: number } | null,
        edits: readonly { from: number; to: number; insert: string }[],
    ): void {
        if (!saved) return;
        this.select(mapOffset(saved.from, edits, false), mapOffset(saved.to, edits));
    }

    restoreCaret(offset: number): void {
        this.pendingCaret = offset;
        this.pendingEnd = null;
        this.flushPendingCaret();
    }

    private flushPendingCaret(): void {
        const offset = this.pendingCaret;
        if (offset === null) return;
        const view = this.controller.viewAtOffset(offset);
        // Not into HTML the edit has outdated: the caret waits for the section's new render.
        if (view?.mounted && !view.needsRender && !view.el.querySelector(".editing")) {
            // The section may already be current (e.g. a caret move between cells).
            this.onRendered([view]);
        }
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
        const typed = this.startSession(block, view);
        setCaretInRun(run, 0);
        this.afterStart(typed, null);
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
        if (session && (session.dirty || this.controller.sync.pending)) {
            // Offsets are only meaningful to Kotlin once typing has reached it.
            this.onSelectionChange();
            return;
        }
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
        this.post({
            type: "selection",
            from: range.from,
            to: range.to,
            marks: [...marks],
            inTable: session.inTable,
            baseRev: this.controller.rev,
        });
    }
}

/** Format actions that stand for a key press, answered by an edit and a `select`. */
const STRUCTURAL: ReadonlySet<FormatAction> = new Set(["enter", "backspace", "type}", "type]", "type)"]);

/** Browser formatting input types and the actions they mean. */
const INPUT_FORMATS: Readonly<Record<string, FormatAction>> = {
    formatBold: "bold",
    formatItalic: "italic",
    formatStrikeThrough: "strike",
    historyUndo: "undo",
    historyRedo: "redo",
};

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
