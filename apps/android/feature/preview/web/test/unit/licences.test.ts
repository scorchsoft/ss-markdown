/**
 * Every library whose code is in the built bundle is named in LICENSES.txt
 * (CC-1). esbuild's metafile only sees node_modules inputs, so code a
 * package ships pre-bundled (Mermaid's parser carries Chevrotain and
 * Langium) has to be listed by hand in scripts/build.mjs; this catches a
 * bump that brings in something new.
 */
import { readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { expect, it } from "vitest";

const OUT = resolve(__dirname, "../../../src/main/assets/preview");

/** A string only that library's code contains, and the name LICENSES.txt must list. */
const MARKERS: readonly [RegExp, string][] = [
    [/chevrotain/i, "chevrotain"],
    [/regexp-to-ast/, "@chevrotain/regexp-to-ast"],
    [/langium/i, "langium"],
    [/vscode-languageserver/, "vscode-languageserver-protocol"],
    [/vscode-jsonrpc/, "vscode-jsonrpc"],
    [/markdown-it/, "markdown-it"],
    [/DOMPurify/, "dompurify"],
    [/katex/i, "katex"],
];

it("names every library whose code is in the bundle", () => {
    const licences = readFileSync(join(OUT, "LICENSES.txt"), "utf8");
    const js = [join(OUT, "js"), join(OUT, "js/chunks")]
        .flatMap((dir) => readdirSync(dir).map((f) => join(dir, f)))
        .filter((f) => f.endsWith(".js"))
        .map((f) => readFileSync(f, "utf8"))
        .join("\n");
    for (const [marker, name] of MARKERS) {
        if (marker.test(js)) expect(licences, name).toMatch(new RegExp(`^${name.replace(/[/.]/g, "\\$&")} \\d`, "m"));
    }
});
