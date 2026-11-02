import { afterAll, beforeAll, expect, it } from "vitest";
import { corpus, diagramsSettled, loadMarkdown, openPreview, startHarness, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

it("neutralises the security corpus", async () => {
    const page = await openPreview(h);
    const requests: string[] = [];
    page.on("request", (r) => requests.push(r.url()));
    await loadMarkdown(page, corpus("malicious.md"));
    await diagramsSettled(page);
    await page.waitForTimeout(500);

    expect(await page.evaluate(() => (window as unknown as { __pwned?: string }).__pwned)).toBeUndefined();
    const doc = page.locator("#doc");
    expect(await doc.locator("script, iframe, object, embed, form, meta, base, link").count()).toBe(0);
    expect(await doc.locator("section style").count()).toBe(await doc.locator(".diagram-svg svg style").count());
    expect(await doc.locator("[onerror], [onload], [onclick]").count()).toBe(0);
    const hrefs = await doc.locator("a[href]").evaluateAll((as) => as.map((a) => a.getAttribute("href") ?? ""));
    for (const href of hrefs) expect(href).not.toMatch(/^\s*(javascript|data|file|content|intent|vbscript):/i);
    expect(await doc.locator('[data-s="0"][data-e="20"]').count()).toBe(0);
    expect(await doc.locator(".katex").count()).toBeGreaterThan(0);
    expect(await doc.locator("p", { hasText: "Text after everything is still here." }).count()).toBe(1);
    expect(await page.evaluate(() => getComputedStyle(document.body).display)).not.toBe("none");
    // Nothing left the device: every request stayed on the bundle's own origin.
    const origin = new URL(h.base).origin;
    for (const url of requests)
        expect(url.startsWith(origin) || url.startsWith("blob:") || url.startsWith("data:")).toBe(true);
});
