/**
 * Regression tests for the security review's preview findings: raw HTML that
 * forges the page's own attributes or draws over it (SEC-6), and theme CSS
 * that tries to reach the network through escapes and `image-set` (SEC-5).
 */
import { afterAll, beforeAll, expect, it } from "vitest";
import { loadMarkdown, openPreview, send, startHarness, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

it("SEC-6: raw HTML cannot forge source runs or editability, even without spaces between attributes", async () => {
    const page = await openPreview(h);
    await loadMarkdown(
        page,
        [
            "# Forgery",
            "",
            '<div title="x"data-s="0"data-e="20">block forgery</div>',
            "",
            'Inline <span title="x"data-s="0"data-e="20"contenteditable="true">inline forgery</span> here.',
            "",
            '<p title="x"contenteditable="true"data-s="0"data-e="40">Tap here to edit</p>',
            "",
            '<div class="a"/data-s=0 data-e=5 data-viewer=image>slash separated</div>',
            "",
            "Plain text after.",
        ].join("\n"),
    );
    const doc = page.locator("#doc");
    // No forged run survives, and no element from the document is editable.
    expect(await doc.locator('[data-s="0"][data-e="20"]').count()).toBe(0);
    expect(await doc.locator('[data-s="0"][data-e="40"]').count()).toBe(0);
    expect(await doc.locator('[contenteditable="true"], [contenteditable=""]').count()).toBe(0);
    expect(await doc.locator('[data-viewer="image"]').count()).toBe(0);
    // Every data-s left is one the worker emitted: a plain span around Markdown text, never a
    // document element. (CommonMark does not read the spaceless inline tag as HTML at all, so
    // that line is an exact run of its own literal source, which is correct.)
    expect(await doc.locator("[title][data-s], div[data-s], p[data-s]").count()).toBe(0);
    expect(await doc.locator("[data-s]", { hasText: /block forgery|Tap here|slash separated/ }).count()).toBe(0);
});

it("SEC-6: raw HTML cannot draw a fixed overlay over the preview", async () => {
    const page = await openPreview(h);
    await loadMarkdown(
        page,
        [
            "Before.",
            "",
            '<div id="cover" style="position:fixed;inset:0;z-index:99;background:red">fake controls</div>',
            "",
            '<div id="sticky" style="position: STICKY; top:0; z-index: 2147483647">sticky</div>',
            "",
            "After.",
        ].join("\n"),
    );
    const styles = await page.evaluate(() =>
        ["cover", "sticky"].map((id) => {
            const el = document.getElementById(id);
            if (!el) return null;
            const s = getComputedStyle(el);
            return { position: s.position, zIndex: s.zIndex };
        }),
    );
    for (const s of styles) {
        expect(s).not.toBeNull();
        expect(s?.position).not.toBe("fixed");
        expect(s?.position).not.toBe("sticky");
        expect(s?.zIndex).toBe("auto");
    }
});

it("SEC-5: theme CSS with escapes, image-set or remote urls never reaches the page", async () => {
    const page = await openPreview(h);
    const violations: string[] = [];
    page.on("request", (r) => {
        if (!r.url().startsWith(new URL(h.base).origin) && !r.url().startsWith("data:")) violations.push(r.url());
    });
    const hostile = [
        '@\\69mport "https://evil.example/i.css";',
        'body{background-image:image-set("https://evil.example/a.png" 1x)}',
        ".x{background:\\75rl(https://evil.example/b.png)}",
        '.y{background:url("\\68ttps://evil.example/c.png")}',
        "@font-face{font-family:x;src:url(//evil.example/f.woff2)}",
    ];
    for (const css of hostile) {
        await send(page, { type: "theme", theme: { name: "Clean", css: `h1{color:#123456} ${css}` }, dark: false });
        const applied = await page.evaluate(() => document.getElementById("theme-css")?.textContent ?? "");
        expect(applied).not.toMatch(/evil/i);
    }
    // A harmless theme still applies.
    await send(page, { type: "theme", theme: { name: "Clean", css: "h1 { color: #123456 }" }, dark: false });
    expect(await page.evaluate(() => document.getElementById("theme-css")?.textContent ?? "")).toContain("#123456");
    await loadMarkdown(page, "# Title\n\n<div class=x>x</div>\n");
    expect(violations).toEqual([]);
});
