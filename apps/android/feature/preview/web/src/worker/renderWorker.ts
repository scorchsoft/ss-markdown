/**
 * The render worker: markdown-it, highlight.js and KaTeX, off the main thread.
 *
 * It holds no document state. Each request carries the section texts that
 * changed and the document-wide definitions; each response carries HTML that
 * the page still sanitises before it touches the DOM.
 */
import { createMarkdown, renderSection } from "../render/markdown";
import type { RenderRequest, RenderResponse } from "../protocol";

const md = createMarkdown();
const scope = self as unknown as DedicatedWorkerGlobalScope;

scope.onmessage = (event: MessageEvent<RenderRequest>) => {
    const request = event.data;
    if (request.type !== "render") return;
    const results = request.jobs.map((job) => {
        try {
            return { key: job.key, ...renderSection(md, job.text, request.defs, job.first) };
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e);
            return {
                key: job.key,
                html: `<div class="render-error">Could not render this section: ${escapeText(message)}</div>`,
                headings: [],
            };
        }
    });
    const response: RenderResponse = { type: "rendered", generation: request.generation, results };
    scope.postMessage(response);
};

function escapeText(s: string): string {
    return s.replace(/[&<>]/g, (c) => (c === "&" ? "&amp;" : c === "<" ? "&lt;" : "&gt;"));
}
