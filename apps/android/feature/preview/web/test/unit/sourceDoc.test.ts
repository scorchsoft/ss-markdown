import { describe, expect, it } from "vitest";
import { SourceDoc } from "../../src/core/sourceDoc";
import { Random } from "./random";

/** Line starts the slow, obvious way. */
function starts(lines: readonly string[]): number[] {
    const out: number[] = [];
    let at = 0;
    for (const line of lines) {
        out.push(at);
        at += line.length + 1;
    }
    return out;
}

describe("SourceDoc", () => {
    it("takes a paste of 250k lines without overflowing the stack (PERF-11)", () => {
        const doc = new SourceDoc("a\nb");
        const lines = Array.from({ length: 250_000 }, (_, i) => `line ${i}`);
        expect(() => doc.replaceLines({ fromLine: 1, toLine: 1, lines })).not.toThrow();
        expect(doc.lineCount).toBe(250_002);
        expect(doc.lines[250_001]).toBe("b");
        expect(doc.lineAt(doc.lineStart(250_001))).toBe(250_001);
    });

    it("answers lineStart and lineAt like a full scan after any edit (property)", () => {
        const rnd = new Random(11);
        const doc = new SourceDoc("one\ntwo\n\nthree");
        for (let n = 0; n < 400; n++) {
            const count = doc.lineCount;
            const from = rnd.int(count);
            const sameCount = rnd.int(2) === 0;
            const to = sameCount ? Math.min(count, from + 1 + rnd.int(3)) : Math.min(count, from + rnd.int(3));
            const size = sameCount ? to - from : rnd.int(4);
            const lines = Array.from({ length: size }, () => "x".repeat(rnd.int(6)));
            doc.replaceLines({ fromLine: from, toLine: to, lines });
            const expected = starts(doc.lines);
            for (let i = 0; i < doc.lineCount; i++) {
                expect(doc.lineStart(i)).toBe(expected[i]);
                expect(doc.lineAt(expected[i] ?? 0)).toBe(i);
                expect(doc.lineAt((expected[i] ?? 0) + (doc.lines[i]?.length ?? 0))).toBe(i);
            }
            expect(doc.lineAt(doc.text.length + 5)).toBe(doc.lineCount - 1);
        }
    });
});
