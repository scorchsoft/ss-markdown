import { describe, expect, it } from "vitest";
import { COMFORT_LINES, editingPad, revealDelta, shrinkDelta, trimmedPad } from "../../src/core/caretReveal";

const line = (top: number, height = 24): { top: number; bottom: number } => ({ top, bottom: top + height });

describe("shrinkDelta: the keyboard takes room from the bottom", () => {
    it("a caret the keyboard would cover ends about two lines above the strip", () => {
        const caret = line(800);
        const delta = shrinkDelta(caret, 860, 464);
        expect(caret.bottom - delta).toBe(464 - COMFORT_LINES * 24);
    });

    it("a caret near the top does not move anything", () => {
        expect(shrinkDelta(line(120), 860, 464)).toBe(0);
        // Just clear of the comfort zone still does not move.
        expect(shrinkDelta(line(464 - 3 * 24), 860, 464)).toBe(0);
    });

    it("follows the keyboard frame by frame, never leaving the caret under it", () => {
        let top = 800;
        for (let h = 860; h > 464; h -= 33) {
            const next = Math.max(464, h - 33);
            top -= shrinkDelta(line(top), h, next);
            expect(top + 24).toBeLessThanOrEqual(next);
        }
    });

    it("the page growing (the keyboard closing) never scrolls", () => {
        expect(shrinkDelta(line(400), 464, 860)).toBe(0);
        expect(shrinkDelta(line(400), 464, 464)).toBe(0);
    });

    it("a caret the reader had scrolled away from stays where it is", () => {
        expect(shrinkDelta(line(900), 860, 464)).toBe(0);
        expect(shrinkDelta(line(-100), 860, 464)).toBe(0);
    });

    it("never scrolls the caret's own line off the top of a very short page", () => {
        const caret = line(50, 40);
        const delta = shrinkDelta(caret, 200, 60);
        expect(caret.top - delta).toBeGreaterThanOrEqual(0);
    });
});

describe("revealDelta: typing and a caret put back", () => {
    it("leaves a visible caret alone", () => {
        expect(revealDelta(line(300), 464, 0)).toBe(0);
        expect(revealDelta(line(440), 464, 0)).toBe(0);
    });

    it("brings a caret below the bottom up, two lines clear", () => {
        expect(revealDelta(line(600), 464, 0)).toBe(624 - (464 - 48));
    });

    it("brings a caret above the top down, a line below the top", () => {
        const delta = revealDelta(line(-300), 464, 0);
        expect(-300 - delta).toBe(24);
    });
});

describe("the room below the document", () => {
    it("is two lines plus the keyboard's height while editing", () => {
        expect(editingPad(24, 860, 464)).toBe(48 + 396);
        expect(editingPad(24, 860, 860)).toBe(48);
        expect(editingPad(0, 860, 860)).toBe(COMFORT_LINES);
    });

    it("shrinks afterwards only by what is below the viewport", () => {
        expect(trimmedPad(400, 0)).toBe(400);
        expect(trimmedPad(400, 150)).toBe(250);
        expect(trimmedPad(400, 1000)).toBe(0);
        expect(trimmedPad(400, -20)).toBe(400);
    });
});
