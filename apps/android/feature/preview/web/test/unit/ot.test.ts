import { describe, expect, it } from "vitest";
import { applyEdits, mapOffset, transform } from "../../src/core/ot";
import type { SourceEdit } from "../../src/core/sourceDoc";
import { Random } from "./random";

/** A random, valid edit set against a text of [length]. */
function randomEdits(rnd: Random, length: number, alphabet: string): SourceEdit[] {
    const out: SourceEdit[] = [];
    let at = 0;
    const count = rnd.int(4);
    for (let k = 0; k < count && at <= length; k++) {
        const from = at + rnd.int(Math.max(1, length - at + 1));
        if (from > length) break;
        const to = Math.min(length, from + (rnd.int(3) === 0 ? 0 : rnd.int(5)));
        const insert = Array.from({ length: rnd.int(4) }, () => rnd.pick([...alphabet])).join("");
        if (from === to && insert === "") continue;
        out.push({ from, to, insert });
        at = to + 1;
    }
    return out;
}

describe("transform", () => {
    it("puts the host's insert first when both insert at one offset", () => {
        const t = transform([{ from: 1, to: 1, insert: "H" }], [{ from: 1, to: 1, insert: "P" }]);
        expect(applyEdits(applyEdits("ab", [{ from: 1, to: 1, insert: "H" }]), t.page)).toBe("aHPb");
        expect(applyEdits(applyEdits("ab", [{ from: 1, to: 1, insert: "P" }]), t.host)).toBe("aHPb");
    });

    it("keeps a page insert that lands inside text the host deleted", () => {
        const host = [{ from: 2, to: 10, insert: "" }];
        const page = [{ from: 5, to: 5, insert: "typed" }];
        const t = transform(host, page);
        expect(applyEdits(applyEdits("0123456789AB", host), t.page)).toBe("01typedAB");
    });

    it("property: both orders converge and no inserted character is ever lost", () => {
        const rnd = new Random(2024);
        for (let i = 0; i < 20_000; i++) {
            const base = Array.from({ length: rnd.int(16) }, () => rnd.pick([..."abcdef\n"])).join("");
            const host = randomEdits(rnd, base.length, "XYZ");
            const page = randomEdits(rnd, base.length, "123");
            const t = transform(host, page);
            const viaHost = applyEdits(applyEdits(base, host), t.page);
            const viaPage = applyEdits(applyEdits(base, page), t.host);
            expect(viaHost, JSON.stringify({ base, host, page })).toBe(viaPage);
            const typed = page.map((e) => e.insert).join("") + host.map((e) => e.insert).join("");
            for (const c of "XYZ123") {
                const want = typed.split(c).length - 1;
                expect(viaHost.split(c).length - 1, JSON.stringify({ base, host, page })).toBe(want);
            }
        }
    });
});

describe("mapOffset", () => {
    it("moves a caret over edits before it and onto the end of an edit it was inside", () => {
        const edits = [
            { from: 0, to: 0, insert: "ab" },
            { from: 4, to: 8, insert: "x" },
        ];
        expect(mapOffset(2, edits)).toBe(4);
        expect(mapOffset(6, edits)).toBe(7);
        expect(mapOffset(9, edits)).toBe(8);
        expect(mapOffset(0, edits, false)).toBe(0);
    });
});
