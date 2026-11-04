/**
 * The bridge messages added after the first release of the protocol: chunked
 * loads (PERF-12), theme fonts and the No wrap setting (formatted copy: copyFormatted.test.ts).
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { PageMessage } from "../../src/protocol";
import { clearReceived, loadMarkdown, openPreview, send, startHarness, waitForMessage, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

describe("bridge", () => {
    it("loads a document sent as a header and raw text frames (PERF-12)", async () => {
        const page = await openPreview(h);
        const text = "# Chunked\n\nfirst paragraph\n\n" + "x".repeat(10) + '\n\n"quotes" and \\ backslashes';
        const parts = [text.slice(0, 7), text.slice(7, 30), text.slice(30)];
        await page.evaluate(
            ({ parts }) => {
                const w = window as unknown as { __port: MessagePort };
                w.__port.postMessage(JSON.stringify({ type: "load", rev: 1, chunks: parts.length }));
                for (const part of parts) w.__port.postMessage(part);
            },
            { parts },
        );
        await waitForMessage(page, (m) => m.type === "rendered");
        await page.waitForFunction(() => document.querySelector("h1")?.textContent === "Chunked");
        expect(await page.locator("p").last().textContent()).toBe('"quotes" and \\ backslashes');
    });

    it("applies the app's font faces and stacks, refusing faces from elsewhere", async () => {
        const page = await openPreview(h);
        const origin = new URL(h.base).origin;
        const good = `@font-face { font-family: "Inter"; src: url("${origin}/assets/fonts/bundled/Inter.ttf") format("truetype"); font-style: normal; font-weight: 100 900; font-display: swap; }`;
        await send(page, {
            type: "theme",
            theme: { name: "Clean" },
            dark: false,
            fonts: { faces: good, body: '"Inter", sans-serif', code: '"JetBrains Mono", monospace' },
        });
        expect(await page.evaluate(() => document.getElementById("font-faces")?.textContent)).toContain("Inter.ttf");
        expect(
            await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue("--font-body")),
        ).toContain('"Inter", sans-serif');
        for (const faces of [
            good.replace(origin, "https://evil.example"),
            `${good} body { display: none }`,
            good.replace(`url("${origin}`, `url("\\68ttps://evil.example`),
        ]) {
            await send(page, { type: "theme", theme: { name: "Clean" }, dark: false, fonts: { faces } });
            expect(await page.evaluate(() => document.getElementById("font-faces")?.textContent)).toBe("");
        }
    });

    it("wraps code in Fluid and keeps lines whole in No wrap", async () => {
        const page = await openPreview(h);
        const long = "const x = " + "abcdefghij ".repeat(40) + ";";
        await loadMarkdown(
            page,
            "```js\n" + long + "\n```\n\n| a | b |\n|---|---|\n| " + "word ".repeat(30) + "| c |\n",
        );
        const width = (): Promise<{ code: number; box: number; screen: number }> =>
            page.evaluate(() => {
                const pre = document.querySelector("pre.code-block") as HTMLElement;
                return { code: pre.scrollWidth, box: pre.clientWidth, screen: innerWidth };
            });
        const fluid = await width();
        expect(fluid.code).toBeLessThanOrEqual(fluid.box);
        const settings = { tapToEdit: true, pageMode: false, zoom: 1, lean: false, readOnly: false };
        await send(page, { type: "settings", settings: { ...settings, noWrap: true } });
        const noWrap = await width();
        // The line stays whole: wider than the screen, the page pans sideways (noWrap.test.ts).
        expect(noWrap.code).toBeGreaterThan(noWrap.screen);
        expect(await page.evaluate(() => getComputedStyle(document.querySelector("td") as Element).whiteSpace)).toBe(
            "nowrap",
        );
    });

    it("stamps edits with the load's epoch and flushes waiting typing on demand (DS-13, DS-14)", async () => {
        const page = await openPreview(h);
        await clearReceived(page);
        await send(page, { type: "load", rev: 1, text: "Some words here.", epoch: 7 });
        await waitForMessage(page, (m) => m.type === "rendered");
        const run = page.locator("span", { hasText: "Some words here." });
        const box = await run.boundingBox();
        if (!box) throw new Error("no run");
        await page.mouse.click(box.x + box.width - 2, box.y + box.height / 2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await clearReceived(page);
        // Typing waits 150 ms before it is written back; a flush sends it at once.
        await page.keyboard.type("X");
        await send(page, { type: "flush" });
        const edit = (await waitForMessage(page, (m) => m.type === "sourceEdit", 100)) as Extract<
            PageMessage,
            { type: "sourceEdit" }
        >;
        expect(edit.epoch).toBe(7);
        expect(edit.edits.map((e) => e.insert).join("")).toBe("X");
    });

    it("flushes waiting typing when the page is hidden (DS-14)", async () => {
        const page = await openPreview(h);
        await loadMarkdown(page, "Hidden typing.");
        const box = await page.locator("span", { hasText: "Hidden typing." }).boundingBox();
        if (!box) throw new Error("no run");
        await page.mouse.click(box.x + box.width - 2, box.y + box.height / 2);
        await waitForMessage(page, (m) => m.type === "editingChanged");
        await clearReceived(page);
        await page.keyboard.type("Y");
        await page.evaluate(() => {
            Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
            document.dispatchEvent(new Event("visibilitychange"));
        });
        await waitForMessage(page, (m) => m.type === "sourceEdit", 100);
    });
});
