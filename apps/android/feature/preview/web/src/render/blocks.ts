/**
 * Renderer rules for blocks that need more than markdown-it's defaults:
 * the source-mapped text runs, fenced code, diagrams, maths and every
 * construct that Preview shows but does not let you edit.
 */
import type { Env, MarkdownIt, Token } from "./mdTypes";
import { contentHash } from "../core/hash";
import type { CrMap } from "../core/sourceMap";
import { highlightCode } from "./highlight";
import { renderTex } from "./mathPlugin";
import type { RunMeta } from "./sourceMapPlugin";

/** Per-render state the rules read through markdown-it's env. */
export interface RenderEnv extends Env {
    crMap: CrMap;
    footnoteNumbers: Record<string, number>;
    sectionSource: string;
}

type Rule = NonNullable<MarkdownIt["renderer"]["rules"][string]>;

function envOf(env: Env | undefined): RenderEnv {
    return env as RenderEnv;
}

function lineAttrs(token: Token): string {
    const rl = token.attrGet("data-rl");
    const rle = token.attrGet("data-rle");
    if (rl === null) {
        return token.map ? ` data-rl="${token.map[0]}" data-rle="${token.map[1]}"` : "";
    }
    return ` data-rl="${rl}" data-rle="${rle ?? rl}"`;
}

/** The chip on a non-editable block that switches to Code at its line. */
function editInCode(token: Token): string {
    const line = token.map?.[0] ?? 0;
    return `<button type="button" class="edit-in-code" contenteditable="false" data-edit-rl="${line}">Edit in Code</button>`;
}

function readOnlyBlock(token: Token, kind: string, inner: string): string {
    return `<div class="ro-block ro-${kind}" contenteditable="false"${lineAttrs(token)}>${inner}${editInCode(token)}</div>\n`;
}

function runAttrs(token: Token, env: RenderEnv): string | null {
    const run = (token.meta as RunMeta | null)?.run;
    if (!run?.exact) return null;
    return ` data-s="${env.crMap.toRaw(run.from)}" data-e="${env.crMap.toRaw(run.to)}"`;
}

const text: Rule = (tokens, idx, _o, env) => {
    const token = tokens[idx];
    if (!token) return "";
    const escaped = escape(token.content);
    const attrs = runAttrs(token, envOf(env));
    return attrs ? `<span${attrs}>${escaped}</span>` : `<span class="ro-run" contenteditable="false">${escaped}</span>`;
};

const codeInline: Rule = (tokens, idx, _o, env) => {
    const token = tokens[idx];
    if (!token) return "";
    const attrs = runAttrs(token, envOf(env)) ?? ' contenteditable="false"';
    return `<code${attrs}>${escape(token.content)}</code>`;
};

/** A fence's content is an exact source slice when the fence is not indented. */
function fenceRun(token: Token, env: RenderEnv): string {
    const map = token.map;
    const src = env.sectionSource;
    if (!map) return ' contenteditable="false"';
    const lines = src.split("\n");
    let start = 0;
    for (let i = 0; i <= map[0]; i++) start += (lines[i]?.length ?? 0) + 1;
    if (src.slice(start, start + token.content.length) !== token.content) return ' contenteditable="false"';
    return ` data-s="${env.crMap.toRaw(start)}" data-e="${env.crMap.toRaw(start + token.content.length)}"`;
}

const fence: Rule = (tokens, idx, _o, env) => {
    const token = tokens[idx];
    if (!token) return "";
    const lang = token.info.trim().split(/\s+/)[0]?.toLowerCase() ?? "";
    if (lang === "mermaid") {
        const key = contentHash(token.content);
        const inner = `<pre class="diagram-source" data-mermaid-key="${key}">${escape(token.content)}</pre>`;
        return readOnlyBlock(token, "diagram", `<div class="diagram" data-viewer="diagram">${inner}</div>`);
    }
    if (lang === "math" || lang === "katex" || lang === "latex") {
        return readOnlyBlock(
            token,
            "math",
            `<div class="math-block" data-viewer="math">${renderTex(token.content, true)}</div>`,
        );
    }
    const body = highlightCode(token.content, lang, escape);
    const langClass = lang ? ` language-${escape(lang)}` : "";
    return `<pre class="code-block"${lineAttrs(token)}><code class="hljs${langClass}"${fenceRun(token, envOf(env))}>${body}</code></pre>\n`;
};

const codeBlock: Rule = (tokens, idx) => {
    const token = tokens[idx];
    if (!token) return "";
    return `<pre class="code-block"${lineAttrs(token)}><code contenteditable="false">${escape(token.content)}</code></pre>\n`;
};

/**
 * Raw HTML in a document must not be able to forge the page's own attributes:
 * a hand-written `data-s` would point tap-to-edit at the wrong bytes. They are
 * renamed out of the way; DOMPurify still sanitises everything else.
 */
export function neutralizeUserHtml(html: string): string {
    return html
        .replace(
            /(\s)data-(s|e|rl|rle|line|line-end|offset|edit-rl|mermaid-key|viewer|toc|toc-line|state)(?=[\s=>/])/gi,
            "$1data-user-$2",
        )
        .replace(/(\s)contenteditable(?=[\s=>/])/gi, "$1data-user-contenteditable");
}

const VOID_TAGS = /^(?:area|base|br|col|embed|hr|img|input|link|meta|source|track|wbr)$/i;

/** True when every tag opened in [html] is closed in it, so it can be wrapped safely. */
export function isBalancedHtml(html: string): boolean {
    let depth = 0;
    for (const m of html.matchAll(/<(\/?)([a-z][a-z0-9-]*)[^>]*?(\/?)>/gi)) {
        if (VOID_TAGS.test(m[2] ?? "") || m[3] === "/") continue;
        depth += m[1] === "/" ? -1 : 1;
        if (depth < 0) return false;
    }
    return depth === 0;
}

/**
 * A self-contained HTML block is wrapped as a read-only block with its Edit in
 * Code chip. An open tag that wraps Markdown (`<details>` … `</details>`) is
 * emitted as-is: wrapping it would close the tag early and change the page.
 */
const htmlBlock: Rule = (tokens, idx) => {
    const token = tokens[idx];
    if (!token) return "";
    const html = neutralizeUserHtml(token.content);
    return isBalancedHtml(html) ? readOnlyBlock(token, "html", `<div class="raw-html">${html}</div>`) : html;
};

/**
 * An inline HTML element is raw HTML, which Preview never edits: its opening
 * tag is marked non-editable so no caret can land inside it. (Its text is
 * already an inexact run; this also covers elements with no text at all.)
 */
export function lockInlineHtml(tag: string): string {
    const html = neutralizeUserHtml(tag);
    return /^<[a-z][a-z0-9-]*/i.test(html) ? html.replace(/^<[a-z][a-z0-9-]*/i, '$& contenteditable="false"') : html;
}

const htmlInline: Rule = (tokens, idx) => lockInlineHtml(tokens[idx]?.content ?? "");

/** An emoji shortcode renders one picture character, not its source: read-only. */
const emoji: Rule = (tokens, idx) =>
    `<span class="emoji" contenteditable="false">${escape(tokens[idx]?.content ?? "")}</span>`;

const frontMatter: Rule = (tokens, idx) => {
    const token = tokens[idx];
    if (!token) return "";
    const meta = typeof token.meta === "string" ? token.meta : "";
    return readOnlyBlock(token, "front-matter", `<pre class="front-matter">${escape(meta)}</pre>`);
};

const mathInline: Rule = (tokens, idx) =>
    `<span class="math-inline" contenteditable="false" data-viewer="math">${renderTex(tokens[idx]?.content ?? "", false)}</span>`;

const mathBlock: Rule = (tokens, idx) => {
    const token = tokens[idx];
    return token
        ? readOnlyBlock(
              token,
              "math",
              `<div class="math-block" data-viewer="math">${renderTex(token.content, true)}</div>`,
          )
        : "";
};

const image: Rule = (tokens, idx, options, env, self) => {
    const token = tokens[idx];
    if (!token) return "";
    const alt = self.renderInlineAsText(token.children ?? [], options, env);
    const src = String(token.attrGet("src") ?? "");
    const title = token.attrGet("title");
    const titleAttr = title ? ` title="${escape(String(title))}"` : "";
    return `<img src="${escape(resolveImage(src))}" alt="${escape(alt)}"${titleAttr} loading="lazy" decoding="async" data-viewer="image" contenteditable="false">`;
};

/**
 * Relative image paths resolve against the document's folder through the
 * host's `/doc/` path handler; the page itself lives under `/assets/preview/`.
 */
export function resolveImage(src: string): string {
    if (/^(?:[a-z][a-z0-9+.-]*:|\/\/|#)/i.test(src)) return src;
    return `/doc/${src.replace(/^\.?\//, "")}`;
}

const alertOpen: Rule = (tokens, idx) => {
    const token = tokens[idx];
    const meta = (token?.meta ?? {}) as { title?: string; type?: string; icon?: string };
    const type = escape(meta.type ?? "note");
    const attrs = token ? lineAttrs(token) : "";
    return `<div class="markdown-alert markdown-alert-${type}"${attrs}><p class="markdown-alert-title" contenteditable="false">${meta.icon ?? ""}${escape(meta.title ?? "")}</p>`;
};

const footnoteRef: Rule = (tokens, idx, _o, env) => {
    const meta = (tokens[idx]?.meta ?? {}) as { id?: number; label?: string };
    const label = meta.label ?? "";
    const n = envOf(env).footnoteNumbers[label] ?? (meta.id ?? 0) + 1;
    const id = escape(label || String(n));
    return `<sup class="footnote-ref" contenteditable="false"><a href="#fn-${id}" id="fnref-${id}">${n}</a></sup>`;
};

const footnoteDefOpen: Rule = (tokens, idx, _o, env) => {
    const token = tokens[idx];
    const label = ((token?.meta ?? {}) as { label?: string }).label ?? "";
    const n = envOf(env).footnoteNumbers[label] ?? "?";
    const attrs = token ? lineAttrs(token) : "";
    const chip = token ? editInCode(token) : "";
    return `<div class="ro-block footnote-def" id="fn-${escape(label)}" contenteditable="false"${attrs}>${chip}<span class="footnote-label">${n}.</span>`;
};

const footnoteDefClose: Rule = () => "</div>\n";

/** The `[[toc]]` placeholder: the page fills it from the document outline. */
const toc: Rule = (tokens, idx) => {
    const token = tokens[idx];
    return `<nav class="toc" data-toc contenteditable="false"${token ? lineAttrs(token) : ""}></nav>\n`;
};

export function escape(s: string): string {
    return s.replace(/[&<>"]/g, (c) => (c === "&" ? "&amp;" : c === "<" ? "&lt;" : c === ">" ? "&gt;" : "&quot;"));
}

export function installBlockRules(md: MarkdownIt): void {
    Object.assign(md.renderer.rules, {
        text,
        code_inline: codeInline,
        fence,
        code_block: codeBlock,
        html_block: htmlBlock,
        html_inline: htmlInline,
        emoji,
        front_matter: frontMatter,
        math_inline: mathInline,
        math_block: mathBlock,
        image,
        alert_open: alertOpen,
        footnote_ref: footnoteRef,
        footnote_reference_open: footnoteDefOpen,
        footnote_reference_close: footnoteDefClose,
        toc,
    });
}
