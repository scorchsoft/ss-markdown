/**
 * "Copy formatted text": Preview's rendering rebuilt as HTML that pastes
 * well into Gmail, Google Docs, Word and Outlook.
 *
 * Those editors keep inline `style` attributes and drop the rest (`<style>`,
 * classes, the page's theme variables), so nothing here relies on them: the
 * output is built fresh, element by element, from an allow-list, and every
 * look it has is an inline style in fixed light colours, whatever theme the
 * app shows (dark text pasted into a dark editor would be unreadable).
 * Anything not on the list keeps its text and loses its tag.
 *
 * Pictures and diagrams are left as placeholders ([Media]) for the caller to
 * fill in, because filling them needs the page (canvas, Mermaid) and a size
 * budget; the plain-text twin is the same either way.
 */
import { mathToText } from "./mathText";

/** The light palette every pasted style uses. */
export const COLOURS = {
    ink: "#1f2328",
    muted: "#57606a",
    border: "#d0d7de",
    codeBg: "#f6f8fa",
    inlineCodeBg: "#eff1f3",
    link: "#0969da",
    mark: "#fff3a3",
} as const;

const MONO = "Consolas, Menlo, 'Courier New', monospace";
const MATHS = "'Cambria Math', 'STIX Two Math', 'Times New Roman', serif";

/** Heading sizes: big enough to read as headings, small enough for an email. Body text inherits. */
const HEADING_PX: Readonly<Record<string, number>> = { h1: 20, h2: 18, h3: 16, h4: 15, h5: 14, h6: 13 };

export const STYLES = {
    p: "margin:0 0 12px 0",
    heading: (tag: string) => `margin:16px 0 8px 0;font-size:${HEADING_PX[tag] ?? 14}px;font-weight:bold`,
    list: "margin:0 0 12px 0;padding-left:24px",
    li: "margin:2px 0",
    task: "margin:2px 0;list-style-type:none",
    blockquote: `margin:0 0 12px 0;padding:0 0 0 12px;border-left:3px solid ${COLOURS.border};color:${COLOURS.muted}`,
    pre:
        `margin:0 0 12px 0;padding:10px 12px;background-color:${COLOURS.codeBg};color:${COLOURS.ink};` +
        `border-radius:6px;font-family:${MONO};font-size:13px;line-height:1.45;` +
        "white-space:pre-wrap;word-wrap:break-word;overflow-wrap:anywhere",
    code:
        `font-family:${MONO};font-size:90%;background-color:${COLOURS.inlineCodeBg};color:${COLOURS.ink};` +
        "padding:1px 4px;border-radius:4px",
    link: `color:${COLOURS.link};text-decoration:underline`,
    table: "border-collapse:collapse;margin:0 0 12px 0",
    th:
        `border:1px solid ${COLOURS.border};padding:6px 10px;background-color:${COLOURS.codeBg};` +
        `color:${COLOURS.ink};font-weight:bold`,
    td: `border:1px solid ${COLOURS.border};padding:6px 10px`,
    hr: `border:none;border-top:1px solid ${COLOURS.border};margin:16px 0`,
    mark: `background-color:${COLOURS.mark};color:${COLOURS.ink}`,
    mathInline: `font-family:${MATHS}`,
    mathBlock: `margin:0 0 12px 0;text-align:center;font-family:${MATHS}`,
    dt: "margin:8px 0 2px 0;font-weight:bold",
    dd: "margin-left:24px;margin-bottom:8px",
    footnote: "margin:0 0 4px 0;font-size:13px",
    picture: "max-width:100%;height:auto",
} as const;

/** GitHub's alert colours, by type. */
const ALERT_COLOURS: Readonly<Record<string, string>> = {
    note: "#0969da",
    tip: "#1a7f37",
    important: "#8250df",
    warning: "#9a6700",
    caution: "#cf222e",
};

/** A picture or diagram waiting to be embedded: [el] is its `<img>` in the output, with alt text and no src yet. */
export interface Media {
    readonly kind: "image" | "diagram";
    readonly el: HTMLImageElement;
    /** The image's URL as Preview loads it, or the diagram's Mermaid source. */
    readonly source: string;
}

/** Page controls and things that are not the document's content. */
const SKIP =
    "button, .edit-in-code, .table-toolbar, .diagram-show-source, .diagram-error, .ro-front-matter, " +
    "nav.toc, script, style, template, annotation, annotation-xml, .footnote-label, svg";

/** Tags kept as themselves (with no attributes). */
const SIMPLE: Readonly<Record<string, string>> = {
    strong: "strong",
    b: "strong",
    em: "em",
    i: "em",
    cite: "em",
    s: "s",
    del: "s",
    strike: "s",
    u: "u",
    ins: "u",
    sub: "sub",
    sup: "sup",
    thead: "thead",
    tbody: "tbody",
    tfoot: "tfoot",
    tr: "tr",
};

/** Output elements the plain-text twin lays out as blocks. */
export const BLOCK_TAGS = new Set([
    "p",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "pre",
    "blockquote",
    "ul",
    "ol",
    "li",
    "table",
    "hr",
    "div",
]);

/** The friendly name of a Mermaid diagram, from its first keyword. */
export function diagramName(source: string): string {
    const keyword =
        source
            .split("\n")
            .map((l) => l.trim())
            .find((l) => l !== "" && !l.startsWith("%%") && l !== "---")
            ?.split(/[\s:;{]/)[0]
            ?.toLowerCase() ?? "";
    const names: Record<string, string> = {
        graph: "flowchart",
        flowchart: "flowchart",
        sequencediagram: "sequence diagram",
        classdiagram: "class diagram",
        statediagram: "state diagram",
        "statediagram-v2": "state diagram",
        erdiagram: "entity relationship diagram",
        gantt: "Gantt chart",
        pie: "pie chart",
        journey: "user journey",
        gitgraph: "git graph",
        timeline: "timeline",
        quadrantchart: "quadrant chart",
        xychart: "chart",
        "xychart-beta": "chart",
        sankey: "Sankey diagram",
        "sankey-beta": "Sankey diagram",
        requirementdiagram: "requirement diagram",
        c4context: "C4 diagram",
    };
    return names[keyword] ?? "diagram";
}

/** The alt text of a diagram: its `accTitle` when it has one, else what kind of diagram it is. */
export function diagramAlt(source: string): string {
    const title = /^\s*accTitle\s*:\s*(.+)$/m.exec(source)?.[1]?.trim();
    return title ? `Diagram: ${title}` : `Diagram: ${diagramName(source)}`;
}

/**
 * Builds the formatted copy one rendered block at a time. [add] converts a
 * block and says how many characters it added; [remove] takes the last one
 * back (when it would not fit); [finish] appends the footnotes.
 */
export class Formatter {
    /** The output; its children are the converted blocks. */
    readonly root: HTMLElement;
    readonly media: Media[] = [];
    private readonly footnotes: { readonly label: string; readonly nodes: Node[] }[] = [];

    constructor(private readonly doc: Document) {
        this.root = doc.createElement("div");
    }

    /** Converts one rendered block and appends it; returns the nodes added. */
    add(block: Element): Node[] {
        const nodes = this.convert(block);
        for (const n of nodes) this.root.appendChild(n);
        return nodes;
    }

    /** Takes back nodes [add] returned (and any media inside them). */
    remove(nodes: readonly Node[]): void {
        for (const n of nodes) n.parentNode?.removeChild(n);
        for (let i = this.media.length - 1; i >= 0; i--) {
            const m = this.media[i];
            if (m && !this.root.contains(m.el)) this.media.splice(i, 1);
        }
    }

    /** Appends the footnotes collected so far, under a rule, as "¹ text" lines. */
    finish(): void {
        if (this.footnotes.length === 0) return;
        this.root.appendChild(this.el("hr", STYLES.hr));
        for (const note of this.footnotes) {
            const p = this.el("p", STYLES.footnote);
            const sup = this.doc.createElement("sup");
            sup.textContent = note.label;
            p.append(sup, " ", ...note.nodes);
            this.root.appendChild(p);
        }
        this.footnotes.length = 0;
    }

    private el(tag: string, style?: string): HTMLElement {
        const e = this.doc.createElement(tag);
        if (style) e.setAttribute("style", style);
        return e;
    }

    private text(s: string): Text {
        return this.doc.createTextNode(s);
    }

    private children(el: Element): Node[] {
        return [...el.childNodes].flatMap((c) => this.convert(c));
    }

    private wrap(tag: string, style: string | undefined, el: Element): HTMLElement {
        const out = this.el(tag, style);
        out.append(...this.children(el));
        return out;
    }

    // One branch per construct the renderer emits; splitting it would only scatter the list.
    private convert(node: Node): Node[] {
        if (node.nodeType === 3) return [this.text(node.textContent ?? "")];
        if (node.nodeType !== 1) return [];
        const el = node as Element;
        const tag = el.localName.toLowerCase();
        if (el.matches(SKIP)) return [];
        if (el.matches(".ro-diagram, .diagram")) return this.diagram(el);
        if (el.matches(".footnote-def")) return this.footnote(el);
        if (el.matches(".math-block")) return this.maths(el, true);
        if (el.matches(".math-inline, .katex")) return this.maths(el, false);
        if (el.matches(".math-error")) return [this.text(el.textContent ?? "")];
        if (el.matches(".markdown-alert")) return [this.alert(el)];
        if (el.matches(".markdown-alert-title")) return [];
        if (el.matches("sup.footnote-ref")) return [this.supText(el.textContent ?? "")];
        if (el.matches("input")) return this.checkbox(el);
        const simple = SIMPLE[tag];
        if (simple) return [this.wrap(simple, undefined, el)];
        if (tag in HEADING_PX) return [this.wrap(tag, STYLES.heading(tag), el)];
        switch (tag) {
            case "p":
                return [this.wrap("p", STYLES.p, el)];
            case "ul":
            case "ol":
                return [this.list(el, tag)];
            case "li":
                return [this.wrap("li", el.matches(".task-list-item") ? STYLES.task : STYLES.li, el)];
            case "blockquote":
                return [this.wrap("blockquote", STYLES.blockquote, el)];
            case "pre":
                return [this.pre(el)];
            case "code":
            case "kbd":
            case "samp":
                return [this.inlineCode(el)];
            case "a":
                return this.link(el);
            case "img":
                return [this.image(el)];
            case "table":
                return [this.wrap("table", STYLES.table, el)];
            case "th":
            case "td":
                return [this.cell(el, tag)];
            case "caption":
            case "summary":
            case "dt":
                return [this.wrap("p", tag === "caption" ? STYLES.p : STYLES.dt, el)];
            case "dd":
                return [this.wrap("div", STYLES.dd, el)];
            case "hr":
                return [this.el("hr", STYLES.hr)];
            case "br":
                return [this.el("br")];
            case "mark":
                return [this.wrap("span", STYLES.mark, el)];
            default:
                // div, span, details, section, abbr, font…: the text stays, the tag goes.
                return this.children(el);
        }
    }

    private list(el: Element, tag: string): HTMLElement {
        // A list inside a list item sits tight under it.
        const nested = el.parentElement?.localName.toLowerCase() === "li";
        const out = this.wrap(tag, nested ? "margin:0;padding-left:24px" : STYLES.list, el);
        const start = el.getAttribute("start");
        if (tag === "ol" && start !== null && /^\d+$/.test(start) && start !== "1") out.setAttribute("start", start);
        return out;
    }

    /** A task checkbox pastes as nothing anywhere, so it becomes ☐ or ☑. */
    private checkbox(el: Element): Node[] {
        if (el.getAttribute("type") !== "checkbox") return [];
        return [this.text(el.hasAttribute("checked") ? "☑" : "☐")];
    }

    private pre(el: Element): HTMLElement {
        const out = this.el("pre", STYLES.pre);
        out.textContent = (el.textContent ?? "").replace(/\n$/, "");
        return out;
    }

    private inlineCode(el: Element): HTMLElement {
        const out = this.el("code", STYLES.code);
        out.textContent = el.textContent ?? "";
        return out;
    }

    /** Web, mail and phone links stay links; links inside the document or to its files cannot work elsewhere. */
    private link(el: Element): Node[] {
        const href = (el.getAttribute("href") ?? "").trim();
        if (!/^(?:https?:|mailto:|tel:)/i.test(href)) return this.children(el);
        const out = this.el("a");
        out.setAttribute("href", href);
        out.setAttribute("style", STYLES.link);
        out.append(...this.children(el));
        return [out];
    }

    /**
     * A web picture is never fetched (the app is offline): it pastes as a link
     * to itself. A picture next to the file, or inline data, waits for the
     * caller to embed it.
     */
    private image(el: Element): Node {
        const src = (el.getAttribute("data-remote-src") ?? el.getAttribute("src") ?? "").trim();
        const alt = (el.getAttribute("alt") ?? "").trim();
        if (/^(?:https?:)?\/\//i.test(src)) {
            const a = this.el("a");
            a.setAttribute("href", src.startsWith("//") ? `https:${src}` : src);
            a.setAttribute("style", STYLES.link);
            a.textContent = `[${alt || "Image"}]`;
            return a;
        }
        if (src === "") return this.text(`[${alt || "Image"}]`);
        const img = this.el("img", STYLES.picture) as HTMLImageElement;
        img.setAttribute("alt", alt || "Image");
        this.media.push({ kind: "image", el: img, source: src });
        return img;
    }

    private cell(el: Element, tag: string): HTMLElement {
        const align = /text-align\s*:\s*(left|right|center)/i.exec(el.getAttribute("style") ?? "")?.[1];
        const base = tag === "th" ? STYLES.th : STYLES.td;
        const side = align?.toLowerCase() ?? (tag === "th" ? "left" : undefined);
        const out = this.wrap(tag, side ? `${base};text-align:${side}` : base, el);
        for (const span of ["colspan", "rowspan"]) {
            const v = el.getAttribute(span);
            if (v !== null && /^\d+$/.test(v)) out.setAttribute(span, v);
        }
        return out;
    }

    private diagram(el: Element): Node[] {
        const source = el.querySelector("pre.diagram-source")?.textContent ?? "";
        if (source.trim() === "") return [];
        const p = this.el("p", STYLES.p);
        const img = this.el("img", STYLES.picture) as HTMLImageElement;
        img.setAttribute("alt", diagramAlt(source));
        this.media.push({ kind: "diagram", el: img, source });
        p.appendChild(img);
        return [p];
    }

    /** Footnotes gather at the end, as they would on paper. */
    private footnote(el: Element): Node[] {
        const label = (el.querySelector(".footnote-label")?.textContent ?? "").replace(/\.$/, "").trim();
        const nodes = this.children(el).flatMap((n) =>
            // A footnote's own paragraphs run on in one line at the foot.
            n.nodeType === 1 && (n as Element).localName === "p" ? [...n.childNodes] : [n],
        );
        const last = nodes[nodes.length - 1];
        if (last?.nodeType === 3) last.textContent = (last.textContent ?? "").trimEnd();
        this.footnotes.push({ label, nodes });
        return [];
    }

    /** Maths as readable text (see mathText.ts); the TeX itself when KaTeX could not lay it out. */
    private maths(el: Element, block: boolean): Node[] {
        const math = el.querySelector("math");
        const error = el.querySelector(".math-error");
        const text = math ? mathToText(math) : (error?.textContent ?? el.textContent ?? "").trim();
        if (text === "") return [];
        const out = this.el(block ? "p" : "span", block ? STYLES.mathBlock : STYLES.mathInline);
        out.textContent = text;
        return [out];
    }

    private alert(el: Element): HTMLElement {
        const type = [...el.classList].find((c) => c.startsWith("markdown-alert-"))?.slice(15) ?? "note";
        const colour = ALERT_COLOURS[type] ?? COLOURS.border;
        const out = this.el("blockquote", `${STYLES.blockquote};border-left-color:${colour}`);
        const title = (el.querySelector(".markdown-alert-title")?.textContent ?? "").trim();
        if (title !== "") {
            const p = this.el("p", `margin:0 0 4px 0;font-weight:bold;color:${colour}`);
            p.textContent = title;
            out.appendChild(p);
        }
        out.append(...this.children(el));
        return out;
    }

    private supText(text: string): HTMLElement {
        const sup = this.doc.createElement("sup");
        sup.textContent = text.trim();
        return sup;
    }
}
