import { afterEach, describe, expect, it, vi } from "vitest";
import { DiagramCache } from "../../src/page/diagramCache";
import { DIAGRAM_TIMEOUT_MS, checkDiagramCaps, withTimeout } from "../../src/page/mermaidRunner";

// edge-cases "Mermaid diagram that never finishes or is enormous" and
// "Hundreds of diagrams in one file".

describe("diagram timeout", () => {
    afterEach(() => vi.useRealTimers());

    it("rejects a diagram that never finishes once the timeout passes", async () => {
        vi.useFakeTimers();
        const never = new Promise<string>(() => {});
        const raced = withTimeout(never, DIAGRAM_TIMEOUT_MS);
        const outcome = raced.then(
            () => "drawn",
            (e: Error) => e.message,
        );
        await vi.advanceTimersByTimeAsync(DIAGRAM_TIMEOUT_MS + 1);
        expect(await outcome).toBe("Diagram took longer than 5 s to draw.");
    });

    it("lets a diagram that finishes in time through", async () => {
        vi.useFakeTimers();
        const raced = withTimeout(Promise.resolve("<svg/>"), DIAGRAM_TIMEOUT_MS);
        await vi.advanceTimersByTimeAsync(1);
        expect(await raced).toBe("<svg/>");
    });

    it("refuses an enormous source before Mermaid sees it", () => {
        expect(checkDiagramCaps("graph TD\n" + "A-->B\n".repeat(10_000))).toContain("too large");
        expect(checkDiagramCaps("graph TD\n  A --> B")).toBeNull();
    });
});

describe("diagram cache", () => {
    it("keeps hundreds of diagrams within its byte budget, dropping the least recently used", () => {
        const svg = "x".repeat(1_000); // 2,000 bytes as UTF-16.
        const cache = new DiagramCache(100 * 2_000);
        for (let i = 0; i < 400; i++) {
            cache.set(`d${i}`, svg);
            if (i === 350) cache.get("d300"); // Touched: survives the trimming after it.
        }
        expect(cache.size).toBeLessThanOrEqual(100 * 2_000);
        expect(cache.get("d0")).toBeUndefined();
        expect(cache.get("d399")).toBe(svg);
        expect(cache.get("d300")).toBe(svg);
    });

    it("shrinks to the lean budget at once", () => {
        const cache = new DiagramCache(1_000_000);
        for (let i = 0; i < 100; i++) cache.set(`d${i}`, "y".repeat(1_000));
        cache.setBudget(10_000);
        expect(cache.size).toBeLessThanOrEqual(10_000);
    });
});
