/**
 * The typed messages between Kotlin and the page, sent as JSON strings over a
 * WebMessagePort. Kotlin mirrors these in `bridge/BridgeMessages.kt`; bump
 * [PROTOCOL_VERSION] for any change that is not purely additive.
 *
 * No message ever carries the whole document after the first `load`: edits
 * travel as changed line ranges (docs/robustness.md, "Preview").
 */
import type { LineChange, SourceEdit } from "./core/sourceDoc";
import type { Align } from "./core/table";

export const PROTOCOL_VERSION = 1;

/** A theme as described in docs/ux-spec.md, "Themes". */
export interface ThemeColours {
    readonly bg: string;
    readonly text: string;
    readonly heading: string;
    readonly link: string;
    readonly codeBg: string;
    readonly muted?: string;
    readonly border?: string;
}

export interface ThemeSpec {
    readonly name: string;
    readonly base?: string;
    readonly fonts?: { readonly body?: string; readonly heading?: string; readonly code?: string };
    readonly size?: { readonly base?: number; readonly headingScale?: number; readonly lineHeight?: number };
    readonly light?: ThemeColours;
    readonly dark?: ThemeColours;
    readonly css?: string;
}

export interface PreviewSettings {
    /** "Tap to edit in Preview"; off makes Preview read-only and links open on tap. */
    readonly tapToEdit: boolean;
    /** A4 page mode: the print stylesheet at true scale. */
    readonly pageMode: boolean;
    /** Per-file preview text zoom, 1.0 = theme base size. */
    readonly zoom: number;
    /** Lean mode after a renderer OOM: no diagram rendering until tapped, smaller caches. */
    readonly lean: boolean;
    readonly readOnly: boolean;
}

/** Kotlin -> page. */
export type HostMessage =
    | { readonly type: "load"; readonly rev: number; readonly text: string; readonly anchorLine?: number }
    | { readonly type: "edit"; readonly rev: number; readonly changes: readonly LineChange[] }
    | { readonly type: "ack"; readonly rev: number }
    | { readonly type: "theme"; readonly theme: ThemeSpec; readonly dark: boolean }
    | { readonly type: "settings"; readonly settings: PreviewSettings }
    | { readonly type: "scrollToLine"; readonly line: number; readonly smooth?: boolean }
    | { readonly type: "tableNav"; readonly direction: "prev" | "next" }
    | { readonly type: "tableOp"; readonly op: TableOp }
    | { readonly type: "endEditing" }
    | {
          readonly type: "exportImage";
          readonly requestId: number;
          readonly line: number;
          readonly format: "svg" | "png";
          readonly scale: number;
      }
    | { readonly type: "preparePrint" }
    | { readonly type: "finishPrint" };

export type TableOp =
    | { readonly kind: "addRow" }
    | { readonly kind: "addColumn" }
    | { readonly kind: "align"; readonly align: Align }
    | { readonly kind: "deleteRow" }
    | { readonly kind: "deleteColumn" }
    | { readonly kind: "moveRow"; readonly by: -1 | 1 }
    | { readonly kind: "moveColumn"; readonly by: -1 | 1 };

export interface HeadingInfo {
    readonly level: number;
    readonly text: string;
    /** Line relative to the section; absolute in the outline the page emits. */
    readonly relLine: number;
    readonly id: string;
}

export interface Crumb {
    readonly level: number;
    readonly text: string;
    readonly line: number;
}

/** Page -> Kotlin. */
export type PageMessage =
    | { readonly type: "ready"; readonly version: number }
    | { readonly type: "rendered"; readonly rev: number; readonly sections: number; readonly millis: number }
    | { readonly type: "topLine"; readonly line: number; readonly crumbs: readonly Crumb[] }
    | { readonly type: "sourceEdit"; readonly baseRev: number; readonly edits: readonly SourceEdit[] }
    | {
          readonly type: "selection";
          readonly from: number;
          readonly to: number;
          readonly marks: readonly string[];
          readonly inTable: boolean;
      }
    | { readonly type: "formatRequest"; readonly action: string; readonly from: number; readonly to: number }
    | { readonly type: "editingChanged"; readonly editing: boolean }
    | { readonly type: "taskToggle"; readonly baseRev: number; readonly offset: number; readonly checked: boolean }
    | { readonly type: "linkTapped"; readonly href: string; readonly line: number }
    | { readonly type: "headingLongPress"; readonly line: number; readonly level: number; readonly text: string }
    | { readonly type: "editInCode"; readonly line: number }
    | {
          readonly type: "openViewer";
          readonly kind: "diagram" | "image" | "math";
          readonly index: number;
          readonly line: number;
      }
    | {
          readonly type: "mediaLongPress";
          readonly kind: "diagram" | "image" | "math";
          readonly index: number;
          readonly line: number;
      }
    | { readonly type: "tableMenu"; readonly line: number; readonly row: number; readonly column: number }
    | { readonly type: "exportResult"; readonly requestId: number; readonly mime: string; readonly data: string }
    | { readonly type: "printReady" }
    | { readonly type: "error"; readonly message: string };

/** Document-wide definitions every section render needs (see core/definitions.ts). */
export interface RenderDefinitions {
    readonly references: readonly { readonly label: string; readonly href: string; readonly title: string }[];
    readonly footnoteNumbers: Readonly<Record<string, number>>;
    readonly footnoteLabels: readonly string[];
    readonly abbreviations: Readonly<Record<string, string>>;
}

/** Page main thread -> render worker. */
export interface RenderRequest {
    readonly type: "render";
    readonly generation: number;
    readonly defs: RenderDefinitions;
    /** [first]: the section starts the document, the only place front matter may appear. */
    readonly jobs: readonly { readonly key: string; readonly text: string; readonly first: boolean }[];
}

/** Render worker -> page main thread. */
export interface RenderResponse {
    readonly type: "rendered";
    readonly generation: number;
    readonly results: readonly {
        readonly key: string;
        readonly html: string;
        readonly headings: readonly HeadingInfo[];
    }[];
}
