/**
 * The dark toolbar above a table while one of its cells is being edited:
 * + Row, + Column, alignment and ⋯ (the rest, in Kotlin's sheet).
 */
import type { Align } from "../../core/table";
import type { TableOp } from "../../protocol";

export interface ToolbarActions {
    op(op: TableOp): void;
    more(): void;
}

const ALIGNS: readonly { align: Align; label: string; name: string }[] = [
    { align: "left", label: "⇤", name: "Align left" },
    { align: "center", label: "↔", name: "Align centre" },
    { align: "right", label: "⇥", name: "Align right" },
];

export class TableToolbar {
    private readonly el: HTMLElement;

    constructor(actions: ToolbarActions) {
        this.el = document.createElement("div");
        this.el.className = "table-toolbar";
        this.el.setAttribute("role", "toolbar");
        this.el.setAttribute("aria-label", "Table");
        this.el.hidden = true;
        const add = (label: string, name: string, run: () => void): void => {
            const b = document.createElement("button");
            b.type = "button";
            b.textContent = label;
            b.setAttribute("aria-label", name);
            // Keep focus (and the caret) in the cell.
            b.addEventListener("pointerdown", (e) => e.preventDefault());
            b.addEventListener("click", (e) => {
                e.stopPropagation();
                run();
            });
            this.el.appendChild(b);
        };
        add("+ Row", "Add row", () => actions.op({ kind: "addRow" }));
        add("+ Column", "Add column", () => actions.op({ kind: "addColumn" }));
        for (const a of ALIGNS) add(a.label, a.name, () => actions.op({ kind: "align", align: a.align }));
        add("⋯", "More table actions", () => actions.more());
        document.body.appendChild(this.el);
    }

    showFor(table: HTMLElement): void {
        const rect = table.getBoundingClientRect();
        this.el.hidden = false;
        this.el.style.top = `${Math.max(0, rect.top + window.scrollY - this.el.offsetHeight - 4)}px`;
        this.el.style.left = `${Math.max(8, rect.left + window.scrollX)}px`;
    }

    hide(): void {
        this.el.hidden = true;
    }
}
