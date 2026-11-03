/**
 * One block being edited in place. Typing inside a text run is left to the
 * browser and written back after a 150 ms pause as minimal source edits;
 * anything that crosses a run edge, adds a line or carries formatting is
 * intercepted and turned into a source edit directly, then the section is
 * re-rendered with the caret restored by source offset.
 */
import { runEdit } from "../../core/minimalEdit";
import { mapOffset } from "../../core/ot";
import {
    isEmptyQuoteLine,
    isIndentOnly,
    isListLine,
    itemTextStart,
    newlineInsert,
    type BlockKind,
} from "../../core/newline";
import type { FormatAction } from "../../protocol";
import { escapeTyped } from "../../core/minimalEdit";
import type { SourceDoc, SourceEdit } from "../../core/sourceDoc";
import { atBlockStart, linkFor, pasteMarkdown, pastePlain, renumberAfter } from "../../core/paste";
import { OWN_HTML } from "./clip";
import type { SectionView } from "../sectionView";
import { pointToSource, runText, sectionOffset, shiftRuns, snapshotRuns, type RunRecord } from "./runs";
import { StrayGuard } from "./strayGuard";

export const FLUSH_DELAY_MS = 150;

/** What the session needs from the editor that owns it. */
export interface SessionHost {
    readonly doc: SourceDoc;
    lineTextAt(offset: number): string;
    /** The source offset where the line holding [offset] starts. */
    lineStartAt(offset: number): number;
    isLineStart(offset: number): boolean;
    /** D4: the edit that leaves the code block at [offset], or null. */
    codeExitAt(offset: number): { from: number; to: number; insert: string; caret: number } | null;
    commit(edits: readonly SourceEdit[]): void;
    commitAndRerender(edits: readonly SourceEdit[], caret: number): void;
    /** A browser input type that formats (formatBold…) or steps history. */
    requestFormat(inputType: string): void;
    /** Asks Kotlin to apply [action] to the selection: Code's own Enter, Backspace, Indent… */
    format(action: FormatAction): void;
    nextCell(): void;
    /** A change could not be written back: undo it on screen and say so. */
    refuse(): void;
}

export class EditSession {
    private runs: Map<HTMLElement, RunRecord>;
    private timer: ReturnType<typeof setTimeout> | null = null;
    private readonly guard: StrayGuard;
    private readonly onInput = (): void => this.schedule();
    private readonly onBeforeInput = (e: InputEvent): void => this.beforeInput(e);
    private readonly onKeyDown = (e: KeyboardEvent): void => this.keyDown(e);
    readonly startKey: string;

    constructor(
        readonly block: HTMLElement,
        readonly view: SectionView,
        private readonly host: SessionHost,
    ) {
        this.runs = snapshotRuns(block);
        this.startKey = view.key;
        this.guard = new StrayGuard(block);
    }

    /** True while typed text has not been written back yet. */
    get dirty(): boolean {
        return this.timer !== null || this.guard.dirty;
    }

    get inTable(): boolean {
        return this.block.matches("td, th");
    }

    start(): void {
        this.block.contentEditable = "true";
        this.block.classList.add("editing");
        this.block.addEventListener("input", this.onInput);
        this.block.addEventListener("beforeinput", this.onBeforeInput);
        this.block.addEventListener("keydown", this.onKeyDown);
        this.block.focus({ preventScroll: true });
        this.guard.start();
    }

    stop(): void {
        this.flush();
        this.guard.stop();
        this.block.removeEventListener("input", this.onInput);
        this.block.removeEventListener("beforeinput", this.onBeforeInput);
        this.block.removeEventListener("keydown", this.onKeyDown);
        this.block.removeAttribute("contenteditable");
        this.block.classList.remove("editing");
    }

    /**
     * Writes back every run whose text changed since the last flush, and
     * anything typed next to a run (see StrayGuard). A change that cannot be
     * written back is refused: the block is re-rendered from source.
     */
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
        const stray = this.guard.take();
        if (stray.refused || stray.inserts.length > 0) {
            this.settleStray(
                edits.map((e) => e.edit),
                stray.inserts,
                stray.refused,
            );
            return;
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

    /**
     * Commits run edits plus mapped stray inserts, then re-renders, since the
     * DOM no longer matches the runs. Refuses if anything was unmappable or
     * two edits would claim one offset.
     */
    private settleStray(
        runEdits: readonly SourceEdit[],
        inserts: readonly { at: number; text: string }[],
        refused: boolean,
    ): void {
        // These run edits are written here, and the block is re-rendered next: the flush that ending
        // the session runs must not find them again and write them twice.
        this.runs = new Map();
        const all: SourceEdit[] = [...runEdits];
        for (const ins of inserts) {
            const clash = all.some((e) => e.from <= ins.at && ins.at <= e.to);
            if (clash) refused = true;
            else
                all.push({
                    from: ins.at,
                    to: ins.at,
                    insert: escapeTyped(ins.text, {
                        inTable: this.inTable,
                        atLineStart: this.host.isLineStart(ins.at),
                    }),
                });
        }
        if (refused) {
            // Legitimate run edits still count; only the unmappable change is undone.
            if (runEdits.length > 0) this.host.commit(runEdits);
            this.host.refuse();
            return;
        }
        const sorted = [...all].sort((a, b) => a.from - b.from);
        const lastInsert = inserts[inserts.length - 1];
        const caret = lastInsert ? mapOffset(lastInsert.at, sorted) : 0;
        this.host.commitAndRerender(sorted, caret);
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
            this.host.requestFormat(type);
            return;
        }
        if (type === "insertParagraph" || type === "insertLineBreak") {
            e.preventDefault();
            if (this.inTable) this.host.nextCell();
            else this.enter(type === "insertLineBreak");
            return;
        }
        if (type === "deleteContentBackward" && this.atItemTextStart()) {
            e.preventDefault();
            this.host.format("backspace");
            return;
        }
        if (type === "insertText" && this.closerToDedent(e.data)) {
            e.preventDefault();
            this.host.format(`type${e.data}` as FormatAction);
            return;
        }
        if (type === "insertFromPaste" || type === "insertFromDrop") {
            e.preventDefault();
            this.paste(e.dataTransfer);
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
        if (from === null || to === null) {
            // Nowhere in the source to put it: say so rather than drop it quietly.
            this.host.refuse();
            return;
        }
        const edit = { from: Math.min(from, to), to: Math.max(from, to), insert };
        if (edit.from === edit.to && insert.length === 0) return;
        this.host.commitAndRerender([edit], edit.from + insert.length);
    }

    /**
     * Enter. Typing waiting for its pause is written back first: until it is,
     * the caret's offset is ahead of the source, and the rules read the wrong
     * line (a soft keyboard's composing word made Enter at the end of a list
     * item read the line below: edge-cases "Enter at the end of a list item").
     * A list item, an empty quote line and code take Code's own Enter from
     * Kotlin; a paragraph, heading or quote line gets a new paragraph here.
     */
    /** Enter, for keystrokes the keyboard keeper held while the block re-rendered. */
    pressEnter(): void {
        if (this.inTable) this.host.nextCell();
        else this.enter(false);
    }

    private enter(hard: boolean): void {
        this.flush();
        const r = this.selectionRange();
        if (!r) {
            this.host.refuse();
            return;
        }
        const line = this.host.lineTextAt(r.from);
        const kind = this.blockKind();
        if (kind === "code") {
            const exit = r.from === r.to ? this.host.codeExitAt(r.from) : null;
            if (exit) this.host.commitAndRerender([exit], exit.caret);
            else this.host.format("enter");
        } else if (!hard && (kind === "listItem" || isListLine(line) || isEmptyQuoteLine(line))) {
            this.host.format("enter");
        } else {
            this.structural(newlineInsert(line, hard), null);
        }
    }

    /** True with the caret right at the start of a list item's text, where Backspace takes the marker off. */
    private atItemTextStart(): boolean {
        if (this.blockKind() === "code") return false;
        this.flush();
        const r = this.selectionRange();
        if (!r || r.from !== r.to) return false;
        const start = itemTextStart(this.host.lineTextAt(r.from));
        return start >= 0 && r.from - this.host.lineStartAt(r.from) === start;
    }

    /** A `}`, `]` or `)` typed with only indentation before it on a code line: Kotlin takes a unit off. */
    private closerToDedent(data: string | null): boolean {
        if (data === null || !"}])".includes(data) || data.length !== 1 || this.blockKind() !== "code") return false;
        this.flush();
        const r = this.selectionRange();
        if (!r || r.from !== r.to) return false;
        return isIndentOnly(this.host.lineTextAt(r.from).slice(0, r.from - this.host.lineStartAt(r.from)));
    }

    /**
     * Tab and Shift+Tab: Indent and Outdent in a list or code; Shift+Tab
     * outdents anywhere. Tab in a paragraph does nothing (D3), and never
     * moves focus out of the text, which would take the keyboard away.
     */
    private keyDown(e: KeyboardEvent): void {
        if (e.key !== "Tab" || e.ctrlKey || e.altKey || e.metaKey || this.inTable) return;
        e.preventDefault();
        if (!e.shiftKey && this.blockKind() === "paragraph") return;
        this.host.format(e.shiftKey ? "outdent" : "indent");
    }

    private blockKind(): BlockKind {
        if (this.block.matches("code, pre, pre *")) return "code";
        if (this.block.closest("li")) return "listItem";
        return "paragraph";
    }

    /** Cut: the selection's source goes, as one edit (the clipboard was filled by `clip.ts`). */
    cut(): void {
        this.structural("", null);
    }

    /**
     * A paste or a drop (docs/ux-spec.md, "Paste"). Every paste in Preview
     * enters here, plain text or HTML, from the menu, the keyboard or
     * Chromium's own "Paste as plain text". Plain text is escaped and its
     * lines become what Enter makes in this block; Preview's own copy goes
     * in as Markdown; a bare web address over selected text links it.
     */
    private paste(data: DataTransfer | null): void {
        const html = data?.getData("text/html") ?? "";
        const plain = data?.getData("text/plain") || htmlText(html);
        this.flush();
        const sel = window.getSelection();
        const ends = [sel?.anchorNode, sel?.focusNode].map((n) => (n instanceof Element ? n : n?.parentElement));
        const r = ends.some((n) => n?.closest("[contenteditable=false]")) ? null : this.selectionRange();
        if (!r) {
            // Nowhere in the source to put it, or inside text that cannot be edited: say so, never put it elsewhere.
            this.host.refuse();
            return;
        }
        const doc = this.host.doc;
        const line = doc.lineAt(r.from);
        const lineStart = doc.lineStart(line);
        const lastLine = doc.lineAt(r.to);
        const span = doc.lines.slice(line, lastLine + 1).join("\n");
        const kind = this.blockKind();
        const target = {
            kind,
            inTable: this.inTable,
            line: doc.lines[line] ?? "",
            atBlockStart: atBlockStart(span, r.from - lineStart),
            atLineEnd: r.to - doc.lineStart(lastLine) === (doc.lines[lastLine] ?? "").length,
        };
        const own = html.includes(OWN_HTML);
        let insert = own ? pasteMarkdown(plain, target) : pastePlain(plain, target);
        const inCode = ends.some((n) => n?.closest("a, code"));
        const link =
            kind !== "code" && !inCode ? linkFor(span.slice(r.from - lineStart, r.to - lineStart), plain) : null;
        if (r.from === r.to && insert.length === 0) return;
        const edits: SourceEdit[] = [{ from: r.from, to: r.to, insert }];
        if (link && r.to > r.from) {
            // Two steps, so the first Undo leaves the plain paste (decision D9, EB-UNDO-07).
            this.host.commit(edits);
            edits[0] = { from: r.from, to: r.from + insert.length, insert: link };
            insert = link;
        } else if (kind === "listItem") {
            const added = insert.match(/\n\s*(?:>\s?)*\s*\d{1,9}[.)]\s/g)?.length ?? 0;
            edits.push(...renumberAfter(doc.lines, (l) => doc.lineStart(l), line, lastLine, added));
        }
        this.host.commitAndRerender(edits, r.from + insert.length);
    }
}

/** The words of an HTML-only clip, one line per block; parsed inert, so nothing runs or loads. */
function htmlText(html: string): string {
    if (!html) return "";
    const body = new DOMParser().parseFromString(html, "text/html").body;
    body.querySelectorAll("script, style, template").forEach((e) => e.remove());
    body.querySelectorAll("br, p, div, li, tr, h1, h2, h3, h4, h5, h6, pre, blockquote").forEach((e) => e.after("\n"));
    return body.textContent ?? "";
}
