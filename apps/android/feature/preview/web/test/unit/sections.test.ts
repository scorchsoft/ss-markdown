import { describe, expect, it } from "vitest";
import { resplit, splitSections } from "../../src/core/sections";
import { Random, randomDoc } from "./random";

const starts = (text: string): number[] => splitSections(text.split("\n")).map((s) => s.startLine);

describe("splitSections", () => {
    it("splits at top-level blank lines", () => {
        expect(starts("# A\n\npara\n\npara two")).toEqual([0, 2, 4]);
    });

    it("keeps fenced code with blank lines together", () => {
        expect(starts("```\ncode\n\nmore\n```\n\nafter")).toEqual([0, 6]);
        expect(starts("~~~~\n```\n\n~~~\n\n~~~~\n\nafter")).toEqual([0, 7]);
    });

    it("keeps maths blocks, front matter and HTML blocks together", () => {
        expect(starts("$$\na\n\nb\n$$\n\nx")).toEqual([0, 6]);
        expect(starts("---\ntitle: x\n\nmore: y\n---\n\nbody")).toEqual([0, 6]);
        expect(starts("<details>\n<summary>S</summary>\n\nHidden\n\n</details>\n\nafter")).toEqual([0, 7]);
        expect(starts("<!-- a\n\nb -->\n\nafter")).toEqual([0, 4]);
    });

    it("keeps loose lists and indented continuations together", () => {
        expect(starts("- a\n\n- b\n\n  more b\n\npara")).toEqual([0, 6]);
        expect(starts("[^1]: note\n\n    continued\n\npara")).toEqual([0, 4]);
    });

    it("covers every line exactly once", () => {
        const lines = randomDoc(new Random(7), 300);
        const sections = splitSections(lines);
        expect(sections[0]?.startLine).toBe(0);
        expect(sections[sections.length - 1]?.endLine).toBe(lines.length);
        for (let i = 1; i < sections.length; i++) expect(sections[i]?.startLine).toBe(sections[i - 1]?.endLine);
    });
});

describe("resplit", () => {
    it("always equals a full split (property, 500 random edits)", () => {
        const rnd = new Random(42);
        let lines = randomDoc(rnd, 120);
        let sections = splitSections(lines);
        for (let n = 0; n < 500; n++) {
            const from = rnd.int(lines.length + 1);
            const to = Math.min(lines.length, from + rnd.int(4));
            const inserted = randomDoc(rnd, rnd.int(4));
            const next = [...lines];
            next.splice(from, to - from, ...inserted);
            if (next.length === 0) next.push("");
            sections = resplit(sections, next, from, to, from + inserted.length);
            lines = next;
            expect(sections).toEqual(splitSections(lines));
        }
    });
});
