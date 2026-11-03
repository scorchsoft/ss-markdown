import { describe, expect, it } from "vitest";
import { readingFraction } from "../../src/core/readingFraction";

describe("readingFraction", () => {
    it("is 0 at the top and exactly 1 at the very bottom", () => {
        expect(readingFraction(0, 5000, 800)).toBe(0);
        expect(readingFraction(4200, 5000, 800)).toBe(1);
        // A fractional pixel short of the end, as browsers stop, is still the end.
        expect(readingFraction(4199.4, 5000, 800)).toBe(1);
    });

    it("is the scroll position over the scrollable range, clamped and monotonic", () => {
        expect(readingFraction(2100, 5000, 800)).toBeCloseTo(0.5, 6);
        expect(readingFraction(-30, 5000, 800)).toBe(0);
        expect(readingFraction(9000, 5000, 800)).toBe(1);
        let last = -1;
        for (let y = 0; y <= 4200; y += 7) {
            const f = readingFraction(y, 5000, 800);
            expect(f).toBeGreaterThanOrEqual(last);
            last = f;
        }
    });

    it("is 0 for a page that fits", () => {
        expect(readingFraction(0, 600, 800)).toBe(0);
        expect(readingFraction(0, 800.5, 800)).toBe(0);
    });
});
