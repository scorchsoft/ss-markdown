/**
 * The markdown-it instance and the one function the render worker calls:
 * render one section's source to HTML plus the headings it holds.
 */
import MarkdownItConstructor from "markdown-it";
import abbr from "markdown-it-abbr";
import anchorPlugin from "markdown-it-anchor";
import deflist from "markdown-it-deflist";
import { full as emoji } from "markdown-it-emoji";
import footnote from "markdown-it-footnote";
import frontMatterPlugin from "markdown-it-front-matter";
import alertsPlugin from "markdown-it-github-alerts";
import mark from "markdown-it-mark";
import sub from "markdown-it-sub";
import sup from "markdown-it-sup";
import taskLists from "markdown-it-task-lists";
import type { Token } from "./mdTypes";
import { CrMap } from "../core/sourceMap";
import type { HeadingInfo, RenderDefinitions } from "../protocol";
import { installBlockRules, type RenderEnv } from "./blocks";
import { mathPlugin } from "./mathPlugin";
import type { MarkdownIt } from "./mdTypes";
import { blankLinesPlugin } from "./blankLinesPlugin";
import { sourceMapPlugin } from "./sourceMapPlugin";
import { tocPlugin } from "./tocPlugin";

/**
 * These three ship types written against the old @types/markdown-it; markdown-it
 * 15 has its own, so they are re-typed here rather than pulling both in.
 */
type Plugin = (md: MarkdownIt, options?: unknown) => void;
const anchor = anchorPlugin as unknown as Plugin;
const frontMatter = frontMatterPlugin as unknown as Plugin;
const alerts = alertsPlugin as unknown as Plugin;

/** GitHub-style heading ids: lower case, punctuation dropped, spaces to hyphens. */
export function slugify(text: string): string {
    return text
        .trim()
        .toLowerCase()
        .replace(/[^\p{L}\p{N}\s_-]/gu, "")
        .replace(/\s/g, "-");
}

/**
 * Links may use the web and mail schemes only. `javascript:`, `file:`,
 * `content:` and `intent:` never reach the DOM; images may also be inline data.
 */
export function isSafeLink(url: string): boolean {
    const scheme = /^\s*([a-z][a-z0-9+.-]*):/i.exec(url)?.[1]?.toLowerCase();
    if (scheme === undefined) return true;
    if (scheme === "data") return /^\s*data:image\/(?:gif|png|jpeg|webp);/i.test(url);
    return scheme === "http" || scheme === "https" || scheme === "mailto" || scheme === "tel";
}

export function createMarkdown(): MarkdownIt {
    const md = new MarkdownItConstructor({ html: true, linkify: true, typographer: false, breaks: false });
    md.validateLink = isSafeLink;
    md.use(frontMatter, () => undefined)
        .use(footnote)
        .use(deflist)
        .use(sub)
        .use(sup)
        .use(mark)
        .use(abbr)
        .use(emoji)
        .use(taskLists, { enabled: true })
        .use(taskLabels)
        .use(alerts)
        .use(mathPlugin)
        .use(tocPlugin)
        .use(anchor, { slugify, tabIndex: false })
        .use(sourceMapPlugin);
    // Footnote definitions render where they are written, not gathered at the end of
    // each section: a section cannot know the document's other footnotes.
    md.core.ruler.disable("footnote_tail");
    installBlockRules(md);
    md.use(blankLinesPlugin);
    return md;
}

const CHECKBOX = '<input class="task-list-item-checkbox"';

/**
 * Gives each task checkbox an accessible name, its item's text (UX2-11), so
 * TalkBack says "Write the spec, checkbox, not checked" rather than a bare
 * "checkbox". The plugin's own `label: true` would wrap the text in a
 * `<label>`, and a tap on the text would then toggle the box instead of
 * placing the caret; `aria-label` names it without changing what a tap does.
 */
function taskLabels(md: MarkdownIt): void {
    md.core.ruler.after("github-task-lists", "task-labels", (state) => {
        for (const token of state.tokens) {
            const box = token.type === "inline" ? token.children?.[0] : undefined;
            if (box?.type !== "html_inline" || !box.content.startsWith(CHECKBOX)) continue;
            const text = (token.children ?? [])
                .slice(1)
                .filter((c) => c.type === "text" || c.type === "code_inline")
                .map((c) => c.content)
                .join("")
                .replace(/\s+/g, " ")
                .trim();
            if (text.length === 0) continue;
            box.content = `${box.content.slice(0, -1)} aria-label="${md.utils.escapeHtml(text)}">`;
        }
        return false;
    });
}

function headingsOf(tokens: Token[]): HeadingInfo[] {
    const out: HeadingInfo[] = [];
    tokens.forEach((token, i) => {
        if (token.type !== "heading_open") return;
        const inline = tokens[i + 1];
        out.push({
            level: Number(token.tag.slice(1)),
            text:
                inline?.children
                    ?.filter((c) => c.type === "text" || c.type === "code_inline")
                    .map((c) => c.content)
                    .join("") ?? "",
            relLine: token.map?.[0] ?? 0,
            id: String(token.attrGet("id") ?? ""),
        });
    });
    return out;
}

export interface SectionRender {
    readonly html: string;
    readonly headings: HeadingInfo[];
}

/** Renders one section with the document-wide definitions in its env. */
export function renderSection(md: MarkdownIt, raw: string, defs: RenderDefinitions, first = true): SectionRender {
    const source = raw.replace(/\r\n?/g, "\n");
    const references: Record<string, { href: string; title: string }> = {};
    for (const ref of defs.references) {
        const key = md.utils.normalizeReference(ref.label);
        if (key && references[key] === undefined && isSafeLink(ref.href)) {
            references[key] = { href: md.normalizeLink(ref.href), title: ref.title };
        }
    }
    const refs: Record<string, number> = {};
    for (const label of defs.footnoteLabels) refs[`:${label}`] = -1;
    const abbreviations: Record<string, string> = {};
    for (const [k, v] of Object.entries(defs.abbreviations)) abbreviations[`:${k}`] = v;
    const env: RenderEnv = {
        crMap: new CrMap(raw),
        footnoteNumbers: { ...defs.footnoteNumbers },
        references,
        footnotes: { refs },
        sectionSource: source,
    };
    // markdown-it-abbr builds an empty-alternation regex, and loops forever, if
    // handed an empty table: only pass abbreviations when there are some.
    if (Object.keys(abbreviations).length > 0) env.abbreviations = abbreviations;
    // A `---` line opening a later section is a thematic break, not front matter.
    if (first) md.block.ruler.enable("front_matter");
    else md.block.ruler.disable("front_matter");
    const tokens = md.parse(source, env);
    return { html: md.renderer.render(tokens, md.options, env), headings: headingsOf(tokens) };
}
