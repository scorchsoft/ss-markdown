// Builds the preview bundle into ../src/main/assets/preview so Gradle never
// needs Node. Run with `npm run build`; the output is committed.
import * as esbuild from "esbuild";
import { createHash } from "node:crypto";
import {
    copyFileSync,
    existsSync,
    mkdirSync,
    readdirSync,
    readFileSync,
    rmSync,
    statSync,
    writeFileSync,
} from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Code that a package ships already bundled into its own dist, so esbuild
 * never sees it as a node_modules input and the scan below misses it (CC-1).
 * Versions are the ones in the package's source maps; the licence texts are
 * vendored in licences/ because these are the package's devDependencies and
 * are never installed. Re-check when bumping the parent package:
 * `test/unit/licences.test.ts` fails if a known library's code is in the
 * bundle and this list does not name it.
 */
const PREBUNDLED = {
    "@mermaid-js/parser": [
        ["chevrotain", "11.1.2", "Apache-2.0", "chevrotain.txt"],
        ["@chevrotain/gast", "11.1.2", "Apache-2.0", "chevrotain.txt"],
        ["@chevrotain/regexp-to-ast", "11.1.2", "Apache-2.0", "chevrotain.txt"],
        ["@chevrotain/utils", "11.1.2", "Apache-2.0", "chevrotain.txt"],
        ["chevrotain-allstar", "0.3.1", "MIT", "chevrotain-allstar.txt"],
        ["langium", "4.2.1", "MIT", "langium.txt"],
        ["vscode-jsonrpc", "8.2.0", "MIT", "vscode-languageserver.txt"],
        ["vscode-languageserver-protocol", "3.17.5", "MIT", "vscode-languageserver.txt"],
        ["vscode-languageserver-textdocument", "1.0.12", "MIT", "vscode-languageserver.txt"],
        ["vscode-languageserver-types", "3.17.5", "MIT", "vscode-languageserver.txt"],
        ["vscode-uri", "3.1.0", "MIT", "vscode-uri.txt"],
        ["path-browserify", "1.0.1", "MIT", "path-browserify.txt"],
        ["lodash-es", "4.17.23", "MIT", null],
    ],
};

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const out = resolve(root, "../src/main/assets/preview");

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

// KaTeX's stylesheet lists woff2, woff and ttf for every face. WebView always
// takes woff2, so only those are shipped; the others stay as dead URLs.
const woff2Only = {
    name: "woff2-only",
    setup(build) {
        build.onResolve({ filter: /\.(woff|ttf)$/ }, (args) => ({ path: args.path, external: true }));
    },
};

// cytoscape and its layouts serve only Mermaid's mindmap and architecture
// diagrams; see src/stubs/cytoscape.ts for why they are left out.
const stubCytoscape = {
    name: "stub-cytoscape",
    setup(build) {
        const stub = join(root, "src/stubs/cytoscape.ts");
        build.onResolve({ filter: /^(cytoscape|cytoscape-fcose|cytoscape-cose-bilkent)$/ }, () => ({ path: stub }));
    },
};

const js = await esbuild.build({
    absWorkingDir: root,
    entryPoints: { main: "src/page/main.ts", worker: "src/worker/renderWorker.ts" },
    outdir: join(out, "js"),
    bundle: true,
    splitting: true,
    format: "esm",
    target: ["chrome90"],
    minify: true,
    legalComments: "none",
    chunkNames: "chunks/[name]-[hash]",
    plugins: [stubCytoscape],
    metafile: true,
    logLevel: "warning",
});

const css = await esbuild.build({
    absWorkingDir: root,
    entryPoints: { preview: "src/styles/preview.css" },
    outdir: out,
    bundle: true,
    minify: true,
    loader: { ".woff2": "file" },
    assetNames: "fonts/[name]",
    plugins: [woff2Only],
    metafile: true,
    logLevel: "warning",
});

copyFileSync(join(root, "src/shell/index.html"), join(out, "index.html"));
writeFileSync(join(out, "LICENSES.txt"), collectLicences([js.metafile, css.metafile]));

function collectLicences(metafiles) {
    const packages = new Set();
    for (const meta of metafiles) {
        for (const input of Object.keys(meta.inputs)) {
            const m = /node_modules\/((?:@[^/]+\/)?[^/]+)/.exec(input);
            if (m) packages.add(m[1]);
        }
    }
    const printed = new Map();
    const prebundled = [...packages]
        .sort()
        .flatMap((parent) => (PREBUNDLED[parent] ?? []).map((entry) => [parent, ...entry]))
        .map(([parent, name, version, licence, file]) => {
            let text = `Licensed under ${licence}; the text is the ${name} entry above.`;
            if (file && printed.has(file)) text = `The same ${licence} text as ${printed.get(file)}, above.`;
            else if (file) {
                text = readFileSync(join(root, "licences", file), "utf8").trim();
                printed.set(file, name);
            }
            return `${name} ${version} (${licence}), pre-bundled inside ${parent}\n${"-".repeat(72)}\n${text}\n`;
        });
    const sections = [...packages].sort().map((name) => {
        const dir = join(root, "node_modules", name);
        const pkg = JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
        const file = readdirSync(dir).find((f) => /^(licen[cs]e|copying)(\.|$)/i.test(f));
        const licence = pkg.license ?? pkg.licenses?.map((l) => l.type).join(" OR ") ?? "see licence text";
        const text = file ? readFileSync(join(dir, file), "utf8").trim() : `Licensed under ${licence}.`;
        return `${name} ${pkg.version} (${licence})\n${"-".repeat(72)}\n${text}\n`;
    });
    return (
        "SS Markdown preview bundle: third-party software\n" +
        "Generated by apps/android/feature/preview/web/scripts/build.mjs. Do not edit.\n\n" +
        [...sections, ...prebundled].join("\n")
    );
}

function sizeOf(dir) {
    let total = 0;
    for (const f of readdirSync(dir)) {
        const p = join(dir, f);
        total += statSync(p).isDirectory() ? sizeOf(p) : statSync(p).size;
    }
    return total;
}

const digest = createHash("sha256");
for (const f of ["index.html", "preview.css", "js/main.js", "js/worker.js"]) {
    if (existsSync(join(out, f))) digest.update(readFileSync(join(out, f)));
}
console.log(
    `Preview bundle: ${(sizeOf(out) / 1024 / 1024).toFixed(2)} MB in ${relative(process.cwd(), out)} (${digest.digest("hex").slice(0, 12)})`,
);
