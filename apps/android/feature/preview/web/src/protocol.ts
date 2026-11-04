/**
 * The typed messages between Kotlin and the page, sent as JSON strings over a
 * WebMessagePort. Kotlin mirrors these in `bridge/BridgeMessages.kt`; bump
 * [PROTOCOL_VERSION] for any change that is not purely additive.
 *
 * No message ever carries the whole document after the first `load`: edits
 * travel as changed line ranges (docs/robustness.md, "Preview").
 *
 * Revisions: Kotlin numbers every change. Each revision reaches the page as
 * exactly one `edit` (Kotlin's change) or `ack` (the page's oldest edit in
 * flight, applied). Page edits carry `baseRev`, the last revision the page had
 * seen; edits made concurrently on both sides are rebased, never rejected
 * (core/editSync.ts and Kotlin's `PreviewEditSync`).
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
    /** "Tap to edit in Preview"; off makes Preview read-only (links still ask first, in Kotlin's chip). */
    readonly tapToEdit: boolean;
    /** A4 page mode: the print stylesheet at true scale. */
    readonly pageMode: boolean;
    /** Per-file preview text zoom, 1.0 = theme base size. */
    readonly zoom: number;
    /** Lean mode after a renderer OOM: no diagram rendering until tapped, smaller caches. */
    readonly lean: boolean;
    readonly readOnly: boolean;
    /**
     * Display → Preview wrapping "No wrap": as in Code, each source line of
     * text, code and table rows keeps its natural width, and the page pans
     * sideways. Fluid (the default) wraps code too, so nothing does.
     */
    readonly noWrap?: boolean;
}

/**
 * The fonts a theme uses, resolved by the app: `@font-face` rules for the
 * files on this phone (core/theme `ThemeCss.fontFaces`, served from the
 * page's own origin) and the complete stacks to use, generic family last.
 */
export interface ThemeFonts {
    readonly faces: string;
    readonly body?: string;
    readonly heading?: string;
    readonly code?: string;
}

/** Kotlin -> page. */
export type HostMessage =
    /**
     * Opens a document. A large text comes as [chunks] raw string frames right
     * after this message instead of in [text], so neither side has to build
     * or parse one JSON string the size of the document (PERF-12).
     */
    | {
          readonly type: "load";
          readonly rev: number;
          readonly text?: string;
          readonly chunks?: number;
          readonly anchorLine?: number;
          /**
           * The host's binding of this document. The bridge stamps it on every
           * message that carries offsets, so an edit typed just before a
           * document switch is never applied to the next document (DS-13).
           */
          readonly epoch?: number;
      }
    | {
          readonly type: "edit";
          readonly rev: number;
          readonly changes: readonly LineChange[];
          /** The same change as character edits against revision `rev - 1`, for rebasing. */
          readonly ops?: readonly SourceEdit[];
      }
    | { readonly type: "ack"; readonly rev: number }
    /** A strip key: the page answers with a `formatRequest` for its current selection. */
    | { readonly type: "format"; readonly action: FormatAction }
    /** Puts the selection at source offsets, in the text at revision [rev]. */
    | { readonly type: "select"; readonly rev: number; readonly from: number; readonly to: number }
    | { readonly type: "theme"; readonly theme: ThemeSpec; readonly dark: boolean; readonly fonts?: ThemeFonts }
    | { readonly type: "settings"; readonly settings: PreviewSettings }
    | { readonly type: "scrollToLine"; readonly line: number; readonly smooth?: boolean }
    /** The fast-scroll thumb: scroll to [fraction] (0 to 1) of the scrollable range; at most one a frame. */
    | { readonly type: "scrollToFraction"; readonly fraction: number }
    | { readonly type: "tableNav"; readonly direction: "prev" | "next" }
    | { readonly type: "tableOp"; readonly op: TableOp }
    | { readonly type: "endEditing" }
    /**
     * "Paste as plain text" from the selection toolbar or Ctrl+Shift+V
     * (Kotlin reads the clipboard): [text] and [html] are the clip's two
     * flavours; only their words go in, at the selection being edited.
     */
    | { readonly type: "pastePlain"; readonly text: string; readonly html: string }
    | {
          readonly type: "exportImage";
          readonly requestId: number;
          readonly line: number;
          readonly format: "svg" | "png";
          readonly scale: number;
      }
    /**
     * Render and mount every block, then answer `printReady` with the same id.
     * A `load` or `finishPrint` first abandons it: no answer comes.
     */
    | { readonly type: "preparePrint"; readonly requestId: number }
    | { readonly type: "finishPrint" }
    /** Write back typing still waiting for its pause, now: the app is pausing or switching document (DS-14). */
    | { readonly type: "flush" }
    /**
     * "Copy formatted text": source lines [fromLine, toLine) as HTML with
     * inline light-theme styles only, plus its plain-text twin, together at
     * most [maxChars] characters (the clipboard crosses Binder). Answered by
     * `htmlResult` with the same [requestId].
     */
    | {
          readonly type: "exportHtml";
          readonly requestId: number;
          readonly fromLine: number;
          readonly toLine: number;
          readonly maxChars: number;
      }
    /**
     * The selection toolbar's "Copy Markdown" / "Copy formatted text": the
     * current selection's source and its formatted copy, together at most
     * [maxChars]. Answered by `selectionCopy` with the same [requestId].
     */
    | { readonly type: "copySelection"; readonly requestId: number; readonly maxChars: number }
    /**
     * Find & replace: the matches, as source offsets in the text at revision
     * [rev], flat `[start0, end0, start1, end1, …]`; absent keeps the ones
     * the page holds, empty clears them. [current] is an index or -1; with
     * [reveal] the page scrolls it into view. A stale [rev] is dropped:
     * Kotlin searches again after every change and sends fresh matches.
     */
    | {
          readonly type: "find";
          readonly rev: number;
          readonly ranges?: readonly number[];
          readonly current: number;
          readonly reveal: boolean;
      }
    /**
     * The answer to `imageQuery` [id]: why each picture beside the document
     * can or cannot be shown (`ok`, `notFound`, `noAccess`, `notImage`,
     * `tooLarge`), by the key the page asked with.
     */
    | { readonly type: "imageStatus"; readonly id: number; readonly statuses: Readonly<Record<string, string>> }
    /** The host can now see more (a folder was granted or changed): pictures that failed try again. */
    | { readonly type: "imagesChanged" };

/**
 * Formatting actions Kotlin applies with core:markdown's EditCommand, in the
 * document's own style. `heading0` makes a paragraph; `undo` and `redo` are
 * passed to the host's history.
 */
export type FormatAction =
    | "bold"
    | "italic"
    | "strike"
    | "code"
    | "link"
    | "bullet"
    | "numbered"
    | "task"
    | "quote"
    | "codeBlock"
    /** The strip's Table key: Kotlin inserts a table at this selection, as in Code. */
    | "table"
    | "heading0"
    | "heading1"
    | "heading2"
    | "heading3"
    | "heading4"
    | "heading5"
    | "heading6"
    | "enter"
    | "backspace"
    | "indent"
    | "outdent"
    | "type}"
    | "type]"
    | "type)"
    | "blockParagraph"
    | "blockHeading1"
    | "blockHeading2"
    | "blockHeading3"
    | "blockBullet"
    | "blockNumbered"
    | "blockTask"
    | "blockQuote"
    | "blockCode"
    /** The Insert image key: Kotlin opens the picker and inserts at this selection. */
    | "image"
    | "undo"
    | "redo";

export type TableOp =
    | { readonly kind: "addRow" }
    | { readonly kind: "addRowAbove" }
    | { readonly kind: "addColumn" }
    | { readonly kind: "addColumnLeft" }
    | { readonly kind: "align"; readonly align: Align }
    | { readonly kind: "deleteRow" }
    | { readonly kind: "deleteColumn" }
    | { readonly kind: "deleteTable" }
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
    | {
          readonly type: "topLine";
          readonly line: number;
          readonly crumbs: readonly Crumb[];
          /** Scroll position over the scrollable range, 0 to 1, for the reading line. */
          readonly fraction?: number;
          /** How much of the page the viewport shows, 0 to 1: the fast-scroll thumb shows only on long pages. */
          readonly visible?: number;
      }
    | {
          readonly type: "sourceEdit";
          readonly baseRev: number;
          readonly edits: readonly SourceEdit[];
          readonly epoch?: number;
      }
    | {
          readonly type: "selection";
          readonly from: number;
          readonly to: number;
          readonly marks: readonly string[];
          readonly inTable: boolean;
          /** The revision the offsets were taken at; sent only with no edits in flight. */
          readonly baseRev: number;
          readonly epoch?: number;
      }
    | {
          readonly type: "formatRequest";
          readonly action: FormatAction;
          readonly from: number;
          readonly to: number;
          /** Like an edit's: Kotlin maps the selection over its changes the page has not seen. */
          readonly baseRev: number;
          readonly inTable: boolean;
          readonly epoch?: number;
      }
    /** The page fell out of step with Kotlin; Kotlin answers with a `load` of its text. */
    | { readonly type: "resync"; readonly reason: string; readonly epoch?: number }
    /**
     * A change the source map cannot write back (typing into an emoji, say) was
     * undone on screen. Kotlin tells the user to use Code for that spot.
     */
    | { readonly type: "editRefused"; readonly line: number }
    | { readonly type: "editingChanged"; readonly editing: boolean }
    | {
          readonly type: "taskToggle";
          readonly baseRev: number;
          readonly offset: number;
          readonly checked: boolean;
          readonly epoch?: number;
      }
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
    | { readonly type: "printReady"; readonly requestId: number }
    /**
     * The answer to `copySelection`: the selection's Markdown, null with
     * nothing selected; [html] and its plain-text twin [text], null when
     * the formatted copy would not fit.
     */
    | {
          readonly type: "selectionCopy";
          readonly requestId: number;
          readonly markdown: string | null;
          readonly html: string | null;
          readonly text: string | null;
      }
    /**
     * The answer to `exportHtml`: [html] and its plain-text twin [text], both
     * null when the lines could not be rendered; [truncated] when the copy
     * stopped early to fit [maxChars].
     */
    | {
          readonly type: "htmlResult";
          readonly requestId: number;
          readonly html: string | null;
          readonly text: string | null;
          readonly truncated: boolean;
      }
    /**
     * Pictures beside the document that failed to load in one burst, by key
     * (`1/shared/a.png`: climbs, then the path; core/imagePaths.ts). Kotlin
     * answers with one `imageStatus` carrying the same [id].
     */
    | { readonly type: "imageQuery"; readonly id: number; readonly paths: readonly string[] }
    /** The placeholder's "Allow access": Kotlin asks for the document's folder. */
    | { readonly type: "allowFolder" }
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
