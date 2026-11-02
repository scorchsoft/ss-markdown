import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { createMarkdown, renderSection } from "../../src/render/markdown";

// edge-cases "Very large embedded image": an image is never decoded up
// front or on the main thread, and is drawn no wider than the column.

describe("embedded images", () => {
    it("load lazily and decode off the main thread", () => {
        const { html } = renderSection(
            createMarkdown(),
            "![A huge scan](scan-12000x9000.png)\n",
            collectDefinitions([]),
        );
        expect(html).toMatch(/<img [^>]*loading="lazy"/);
        expect(html).toMatch(/<img [^>]*decoding="async"/);
    });

    it("are scaled to the column, never wider", () => {
        const css = readFileSync(resolve(__dirname, "../../src/styles/preview.css"), "utf8");
        const rule = /(^|\n)img\s*\{([^}]*)\}/.exec(css)?.[2] ?? "";
        expect(rule).toMatch(/max-width:\s*100%/);
        expect(rule).toMatch(/height:\s*auto/);
    });
});
