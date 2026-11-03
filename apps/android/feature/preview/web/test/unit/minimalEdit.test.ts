import { describe, expect, it } from "vitest";
import { applyEdit, diffText, escapeTyped, runEdit } from "../../src/core/minimalEdit";
import { Random } from "./random";

describe("diffText", () => {
    it("returns only the changed middle", () => {
        expect(diffText("the cat sat", "the bat sat")).toEqual({ start: 4, oldEnd: 5, insert: "b" });
        expect(diffText("same", "same")).toBeNull();
    });

    it("never splits a surrogate pair", () => {
        const d = diffText("a😀b", "a😃b");
        expect(d?.insert).toBe("😃");
    });
});

describe("runEdit", () => {
    it("does not write back the no-break space Chrome shows beside typed text", () => {
        // edge-cases "Typing at the start of a task item's text wrote a no-break space": the run held " cd".
        const nbsp = String.fromCharCode(0xa0);
        const ctx = { inTable: false, atLineStart: false };
        expect(runEdit({ from: 14, to: 17, text: " cd" }, `${nbsp}Zcd`, ctx)).toEqual({
            from: 15,
            to: 15,
            insert: "Z",
        });
        // A run that already held one keeps what the browser gives back.
        expect(runEdit({ from: 0, to: 2, text: `a${nbsp}` }, `a${nbsp}b`, ctx)).toEqual({
            from: 2,
            to: 2,
            insert: "b",
        });
    });

    it("fixing a typo changes only those characters", () => {
        const source = "Some **bold tetx** here.";
        const from = source.indexOf("bold");
        const run = { from, to: from + 9, text: "bold tetx" };
        const edit = runEdit(run, "bold text", { inTable: false, atLineStart: false });
        expect(edit).toEqual({ from: from + 7, to: from + 9, insert: "xt" });
        expect(applyEdit(source, edit ?? { from: 0, to: 0, insert: "" })).toBe("Some **bold text** here.");
    });

    it("escapes typed Markdown syntax so it stays text", () => {
        expect(escapeTyped("a*b_c", { inTable: false, atLineStart: false })).toBe("a\\*b\\_c");
        expect(escapeTyped("a|b", { inTable: true, atLineStart: false })).toBe("a\\|b");
        expect(escapeTyped("# no", { inTable: false, atLineStart: true })).toBe("\\# no");
    });

    it("property: editing a run changes only bytes inside that run", () => {
        const rnd = new Random(99);
        const alphabet = "abc def*_é😀\n";
        const word = (n: number): string => Array.from({ length: n }, () => rnd.pick([...alphabet])).join("");
        for (let i = 0; i < 2000; i++) {
            const before = word(rnd.int(20));
            const text = word(rnd.int(12) + 1).replace(/\n/g, " ");
            const after = word(rnd.int(20));
            const source = `${before}${text}${after}`;
            const run = { from: before.length, to: before.length + text.length, text };
            let next = text;
            const at = rnd.int(text.length + 1);
            next = next.slice(0, at) + word(rnd.int(3)).replace(/\n/g, " ") + next.slice(at + rnd.int(3));
            const edit = runEdit(run, next, { inTable: false, atLineStart: false });
            if (!edit) {
                expect(next).toBe(text);
                continue;
            }
            expect(edit.from).toBeGreaterThanOrEqual(run.from);
            expect(edit.to).toBeLessThanOrEqual(run.to);
            const result = applyEdit(source, edit);
            expect(result.startsWith(before)).toBe(true);
            expect(result.endsWith(after)).toBe(true);
        }
    });
});
