/**
 * Find & replace in Preview (docs/ux-spec.md, "Find & replace"; edge-cases
 * "Find next does not move"): Kotlin sends the matches as source offsets;
 * the page paints every one, marks the current one distinctly and scrolls
 * it into view, mounting its section first when it is far away.
 */
import type { Page } from "playwright";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { loadMarkdown, openPreview, pageErrors, send, startHarness, type Harness } from "./harness";

let h: Harness;
beforeAll(async () => (h = await startHarness()));
afterAll(async () => h.close());

/** 600 paragraphs; every 100th holds "needle". */
function doc(): string {
    const parts: string[] = [];
    for (let i = 0; i < 600; i++)
        parts.push(i % 100 === 50 ? `Paragraph ${i} holds the needle here.` : `Paragraph ${i}.`);
    return parts.join("\n\n");
}

function ranges(text: string, word: string): number[] {
    const out: number[] = [];
    for (let at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) out.push(at, at + word.length);
    return out;
}

interface Painted {
    readonly all: string[];
    readonly current: string[];
    /** The current match's box against the viewport, or null when it is not painted. */
    readonly box: { top: number; bottom: number } | null;
    readonly height: number;
}

async function painted(page: Page): Promise<Painted> {
    return page.evaluate(() => {
        const registry = (CSS as unknown as { highlights: Map<string, Set<Range>> }).highlights;
        const all = [...(registry.get("ssm-find") ?? [])].map((r) => r.toString());
        const currentRanges = [...(registry.get("ssm-find-current") ?? [])];
        const rect = currentRanges[0]?.getBoundingClientRect();
        return {
            all,
            current: currentRanges.map((r) => r.toString()),
            box: rect ? { top: rect.top, bottom: rect.bottom } : null,
            height: window.innerHeight,
        };
    });
}

/** Waits until the current match is painted and on screen. */
async function currentShown(page: Page): Promise<Painted> {
    await page.waitForFunction(() => {
        const registry = (CSS as unknown as { highlights: Map<string, Set<Range>> }).highlights;
        const range = [...(registry.get("ssm-find-current") ?? [])][0];
        if (!range) return false;
        const rect = range.getBoundingClientRect();
        return rect.height > 0 && rect.top >= 0 && rect.bottom <= window.innerHeight;
    });
    return painted(page);
}

describe("find in Preview", () => {
    it("marks the current match distinctly and scrolls next and previous into view, wrapping round", async () => {
        const page = await openPreview(h);
        const text = doc();
        await loadMarkdown(page, text);
        const flat = ranges(text, "needle");
        expect(flat.length).toBe(12);

        await send(page, { type: "find", rev: 1, ranges: flat, current: 0, reveal: true });
        let p = await currentShown(page);
        expect(p.current).toEqual(["needle"]);
        // The other matches on screen are painted too, but never as current.
        expect(p.all.every((t) => t === "needle")).toBe(true);
        const firstTop = await page.evaluate(() => window.scrollY);

        // Next, three times: each is far below the last, so the page must move to it.
        for (const index of [1, 2, 3]) {
            await send(page, { type: "find", rev: 1, current: index, reveal: true });
            p = await currentShown(page);
            expect(p.current).toEqual(["needle"]);
            const line = await page.evaluate(() => {
                const registry = (CSS as unknown as { highlights: Map<string, Set<Range>> }).highlights;
                const range = [...(registry.get("ssm-find-current") ?? [])][0];
                return range?.startContainer.parentElement?.closest<HTMLElement>("[data-line]")?.dataset.line;
            });
            expect(Number(line)).toBe(2 * (100 * index + 50));
        }
        expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(firstTop);

        // Previous from the first wraps to the last, at the end of the document.
        await send(page, { type: "find", rev: 1, current: 5, reveal: true });
        p = await currentShown(page);
        expect(p.current).toEqual(["needle"]);
        expect(pageErrors(page)).toEqual([]);
    });

    it("a match already on screen does not move the page", async () => {
        const page = await openPreview(h);
        const text = "# Title\n\nThe needle is near the top.\n\nMore text.";
        await loadMarkdown(page, text);
        await send(page, { type: "find", rev: 1, ranges: ranges(text, "needle"), current: 0, reveal: true });
        await currentShown(page);
        expect(await page.evaluate(() => window.scrollY)).toBe(0);
    });

    it("paints matches in a section that mounts later, and clears them all", async () => {
        const page = await openPreview(h);
        const text = doc();
        await loadMarkdown(page, text);
        await send(page, { type: "find", rev: 1, ranges: ranges(text, "needle"), current: 0, reveal: true });
        await currentShown(page);
        // Later, Kotlin scrolls to the last match's section: it mounts and is painted, not current.
        await page.waitForTimeout(700);
        await send(page, { type: "scrollToLine", line: 2 * 550 - 2 });
        await page.waitForFunction(() => {
            const registry = (CSS as unknown as { highlights: Map<string, Set<Range>> }).highlights;
            return [...(registry.get("ssm-find") ?? [])].some((r) => {
                const rect = r.getBoundingClientRect();
                return rect.height > 0 && rect.top >= 0 && rect.bottom <= window.innerHeight;
            });
        });
        await send(page, { type: "find", rev: 1, ranges: [], current: -1, reveal: false });
        const p = await painted(page);
        expect(p.all).toEqual([]);
        expect(p.current).toEqual([]);
    });

    it("a match inside inline formatting and code is painted on the rendered text", async () => {
        const page = await openPreview(h);
        const text = "Some **bold needle** and `needle()` here.\n\n```\nlet needle = 1\n```\n";
        await loadMarkdown(page, text);
        await send(page, { type: "find", rev: 1, ranges: ranges(text, "needle"), current: 2, reveal: true });
        const p = await currentShown(page);
        expect(p.all).toEqual(["needle", "needle"]);
        expect(p.current).toEqual(["needle"]);
    });

    it("in Split, a follow-up scroll right after a reveal keeps the match in view", async () => {
        const page = await openPreview(h);
        const text = doc();
        await loadMarkdown(page, text);
        await send(page, { type: "find", rev: 1, ranges: ranges(text, "needle"), current: 3, reveal: true });
        await currentShown(page);
        // The Code pane's top line, well above the match, arrives as a follow scroll.
        await send(page, { type: "scrollToLine", line: 2 * 350 - 80 });
        await currentShown(page);
    });

    it("a stale revision is dropped", async () => {
        const page = await openPreview(h);
        const text = "a needle\n";
        await loadMarkdown(page, text);
        await send(page, { type: "find", rev: 7, ranges: ranges(text, "needle"), current: 0, reveal: true });
        await page.waitForTimeout(100);
        const p = await painted(page);
        expect(p.all.length + p.current.length).toBe(0);
    });
});
