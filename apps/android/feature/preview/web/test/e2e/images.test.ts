/**
 * Pictures that cannot be drawn (docs/ux-spec.md, "Pictures"), in the built
 * bundle: one status query per burst of failures, a placeholder per state
 * that says why, "Allow access" as a real button, reserved room, a retry
 * when the host can see more, light and dark, and tap-to-edit beside a
 * placeholder that never takes or loses text.
 *
 * Set `SHOT_DIR` to keep a screenshot of every state in both themes.
 */
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import type { Page } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { applyEdit } from "../../src/core/minimalEdit";
import type { PageMessage } from "../../src/protocol";
import {
    clearReceived,
    loadMarkdown,
    openPreview,
    pageErrors,
    received,
    send,
    startHarness,
    waitForMessage,
    type Harness,
} from "./harness";

let h: Harness;
beforeAll(async () => {
    h = await startHarness();
});
afterAll(async () => h.close());

type Query = Extract<PageMessage, { type: "imageQuery" }>;

const SOURCE = [
    "# Pictures",
    "",
    "Before ![Cat on a mat](img/cat.png) after the cat.",
    "",
    "![Logo](https://example.com/logo.png)",
    "",
    "![Shared](../shared/up.png)",
    "",
    "![Scan](big.png)",
    "",
    "![Notes](notes.txt)",
    "",
    "Last line.",
].join("\n");

async function queries(page: Page): Promise<Query[]> {
    return (await received(page)).filter((m): m is Query => m.type === "imageQuery");
}

/** Waits for the harness's 404s to reach the page and be asked about. */
async function firstQuery(page: Page): Promise<Query> {
    return (await waitForMessage(page, (m) => m.type === "imageQuery")) as Query;
}

async function answer(page: Page, id: number, statuses: Record<string, string>): Promise<void> {
    await send(page, { type: "imageStatus", id, statuses });
    const failures = Object.values(statuses).filter((s) => s !== "ok").length;
    await page.waitForFunction(
        (n) => document.querySelectorAll('.img-ph:not([data-status="remote"])').length >= n,
        failures,
    );
}

async function shot(page: Page, name: string): Promise<void> {
    const dir = process.env.SHOT_DIR;
    if (!dir) return;
    mkdirSync(dir, { recursive: true });
    await page.screenshot({ path: join(dir, `${name}.png`), fullPage: true });
}

describe("picture placeholders", () => {
    it("asks once for a burst of failures and never loads a remote picture", async () => {
        const page = await openPreview(h);
        const requests: string[] = [];
        page.on("request", (r) => requests.push(r.url()));
        await loadMarkdown(page, SOURCE);
        const query = await firstQuery(page);
        await page.waitForTimeout(150);
        expect(await queries(page)).toHaveLength(1);
        expect([...query.paths].sort()).toEqual(["0/big.png", "0/img/cat.png", "0/notes.txt", "1/shared/up.png"]);
        expect(requests.some((u) => u.includes("example.com"))).toBe(false);
        // The remote one is known from its address: a placeholder at once, with the URL.
        const remote = page.locator('.img-ph[data-status="remote"]');
        await expect(remote.textContent()).resolves.toContain("Online images aren't loaded");
        await expect(remote.textContent()).resolves.toContain("https://example.com/logo.png");
        expect(pageErrors(page)).toEqual([]);
    });

    it("says why for each state, with Allow access as a real button", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        const query = await firstQuery(page);
        await answer(page, query.id, {
            "0/img/cat.png": "noAccess",
            "1/shared/up.png": "notFound",
            "0/big.png": "tooLarge",
            "0/notes.txt": "notImage",
        });
        const reason = (status: string) =>
            page.locator(`.img-ph[data-status="${status}"] .img-ph-reason`).textContent();
        expect(await reason("noAccess")).toBe("Allow access to this folder to show its images");
        expect(await reason("notFound")).toBe("Not found next to this file");
        expect(await reason("tooLarge")).toBe("Too large to show here");
        expect(await reason("notImage")).toBe("Not a picture this preview can show");
        // The path as written, and the alt text.
        const notFound = page.locator('.img-ph[data-status="notFound"]');
        expect(await notFound.textContent()).toContain("../shared/up.png");
        expect(await notFound.textContent()).toContain("Shared");

        // Accessible: the picture part is role img with a label; the button is a button.
        const picture = page.getByRole("img", { name: /Picture not shown: Cat on a mat\. Allow access/ });
        expect(await picture.count()).toBe(1);
        const button = page.getByRole("button", { name: "Allow access" });
        expect(await button.count()).toBe(1);
        await clearReceived(page);
        await button.click();
        await waitForMessage(page, (m) => m.type === "allowFolder");
        // Nothing else: no viewer, no editing.
        expect((await received(page)).map((m) => m.type)).toEqual(["allowFolder"]);

        // Room is reserved: a placeholder is never a sliver the text jumps past.
        const heights = await page.$$eval(".img-ph", (els) => els.map((e) => e.getBoundingClientRect().height));
        for (const height of heights) expect(height).toBeGreaterThanOrEqual(70);
        expect(await page.locator("img[data-src]:not([hidden])").count()).toBe(0);
    });

    it("draws calmly in light and dark", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        const query = await firstQuery(page);
        await answer(page, query.id, {
            "0/img/cat.png": "noAccess",
            "1/shared/up.png": "notFound",
            "0/big.png": "tooLarge",
            "0/notes.txt": "notImage",
        });
        const colours = async () =>
            page
                .locator(".img-ph")
                .first()
                .evaluate((el) => {
                    const s = getComputedStyle(el);
                    return { bg: s.backgroundColor, fg: s.color };
                });
        const light = await colours();
        await shot(page, "pictures-light");
        await send(page, { type: "theme", theme: { name: "Clean" }, dark: true });
        await page.waitForFunction(() => document.documentElement.dataset.dark === "true");
        const dark = await colours();
        expect(dark.bg).not.toBe(light.bg);
        expect(dark.fg).not.toBe(light.fg);
        await shot(page, "pictures-dark");
    });

    it("tries again when the host can see more, and asks again for what still fails", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        const query = await firstQuery(page);
        await answer(page, query.id, Object.fromEntries(query.paths.map((p) => [p, "noAccess"])));
        await clearReceived(page);
        await send(page, { type: "imagesChanged" });
        // Only the remote placeholder stays while the others retry.
        await page.waitForFunction(() => document.querySelectorAll(".img-ph").length === 1);
        const again = await firstQuery(page);
        expect([...again.paths].sort()).toEqual([...query.paths].sort());
        expect(again.id).toBeGreaterThan(query.id);
    });

    it("ignores an answer meant for the previous document", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, SOURCE);
        const query = await firstQuery(page);
        await loadMarkdown(page, "![Cat on a mat](img/cat.png)\n", 2);
        await send(page, { type: "imageStatus", id: query.id, statuses: { "0/img/cat.png": "tooLarge" } });
        const next = await firstQuery(page);
        await page.waitForTimeout(100);
        expect(await page.locator('.img-ph[data-status="tooLarge"]').count()).toBe(0);
        await answer(page, next.id, { "0/img/cat.png": "notFound" });
        expect(await page.locator('.img-ph[data-status="notFound"]').count()).toBe(1);
    });

    it("never takes typing, and editing beside it loses nothing", async () => {
        // Narrow, so "after the cat." wraps on every machine: the caret must not depend on where.
        const page = await openPreview(h, { width: 300, height: 860 });
        await loadMarkdown(page, SOURCE);
        const query = await firstQuery(page);
        await answer(page, query.id, Object.fromEntries(query.paths.map((p) => [p, "notFound"])));
        const box = page.locator('.img-ph[data-status="notFound"]').first();
        expect(await box.getAttribute("contenteditable")).toBe("false");
        await clearReceived(page);
        // Edit the paragraph that holds the placeholder: the text after it gets a word.
        const run = page.locator("span", { hasText: "after the cat." }).first();
        const rect = await run.boundingBox();
        if (!rect) throw new Error("no run");
        await page.mouse.click(rect.x + rect.width - 2, rect.y + rect.height / 2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        // The caret goes after "cat." by DOM position, not with End: End is the end of the
        // *visual* line, and where "after the cat." wraps depends on the machine's fonts (on CI
        // it wrapped after "the", so End put the caret before "cat." and the word went there).
        await page.evaluate(() => {
            const span = [...document.querySelectorAll<HTMLElement>("span[data-s]")].find((s) =>
                (s.textContent ?? "").includes("after the cat."),
            );
            const node = span?.lastChild;
            if (!(node instanceof Text)) throw new Error("no text run");
            const range = document.createRange();
            range.setStart(node, node.data.length);
            range.collapse(true);
            const selection = getSelection();
            selection?.removeAllRanges();
            selection?.addRange(range);
        });
        await page.keyboard.type(" Yes");
        await waitForMessage(page, (m) => m.type === "sourceEdit");
        let text = SOURCE;
        for (const m of await received(page)) {
            if (m.type !== "sourceEdit") continue;
            for (const e of [...m.edits].sort((a, b) => b.from - a.from)) text = applyEdit(text, e);
        }
        expect(text).toBe(SOURCE.replace("after the cat.", "after the cat. Yes"));
        expect((await received(page)).some((m) => m.type === "editRefused")).toBe(false);
        // A placeholder appearing while the block is edited is chrome, never a refused change.
        await send(page, { type: "imagesChanged" });
        const later = await firstQuery(page);
        await answer(page, later.id, { "0/img/cat.png": "notFound" });
        await page.waitForTimeout(250);
        expect((await received(page)).some((m) => m.type === "editRefused")).toBe(false);
        expect(pageErrors(page)).toEqual([]);
    });

    it("shows the next document's own picture for the same relative path, not the last one's", async () => {
        const page = await openPreview(h);
        // Each document's folder has its own img/a.png: 10 px wide beside A, 20 px beside B.
        let width = 10;
        await page.route("**/doc/**", async (route) => {
            const body = await page.evaluate((w) => {
                const c = document.createElement("canvas");
                c.width = w;
                c.height = 4;
                return c.toDataURL("image/png").split(",")[1] ?? "";
            }, width);
            await route.fulfill({ contentType: "image/png", body: Buffer.from(body, "base64") });
        });
        const drawnWidth = async (): Promise<number> => {
            await page.waitForFunction(() => {
                const img = document.querySelector<HTMLImageElement>("img[data-src]");
                return img !== null && img.complete && img.naturalWidth > 0;
            });
            return page.evaluate(() => document.querySelector<HTMLImageElement>("img[data-src]")?.naturalWidth ?? 0);
        };
        await loadMarkdown(page, "![A](img/a.png)\n\nIn A.");
        expect(await drawnWidth()).toBe(10);
        width = 20;
        await loadMarkdown(page, "![B](img/a.png)\n\nIn B.", 2);
        expect(await drawnWidth()).toBe(20);
    });
});
