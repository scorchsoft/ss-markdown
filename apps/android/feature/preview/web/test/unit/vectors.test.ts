/**
 * Golden vectors shared with Kotlin (CC-20). The two halves of the bridge and
 * of the edit protocol are written twice, once per language; these files are
 * the one copy of the truth both sides' tests read. Kotlin's
 * `SharedVectorsTest` runs the same files against `TextTransform` and
 * `BridgeCodec`.
 *
 * `ot.json` is generated from this implementation: run
 * `UPDATE_VECTORS=1 npx vitest run --project unit test/unit/vectors.test.ts`
 * after an intended change to the algorithm, and review the diff.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { applyEdits, mapOffset, transform } from "../../src/core/ot";
import type { SourceEdit } from "../../src/core/sourceDoc";
import { HOST_TYPES } from "../../src/page/bridge";
import { PROTOCOL_VERSION } from "../../src/protocol";
import { Random } from "./random";

const DIR = resolve(__dirname, "../vectors");

interface OtCase {
    base: string;
    host: SourceEdit[];
    page: SourceEdit[];
    hostPrime: SourceEdit[];
    pagePrime: SourceEdit[];
    result: string;
}

interface MapCase {
    offset: number;
    edits: SourceEdit[];
    after: boolean;
    expected: number;
}

interface OtVectors {
    transform: OtCase[];
    mapOffset: MapCase[];
}

function randomEdits(rnd: Random, length: number, alphabet: string): SourceEdit[] {
    const out: SourceEdit[] = [];
    let at = 0;
    for (let k = rnd.int(4); k > 0 && at <= length; k--) {
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

function generate(): OtVectors {
    const rnd = new Random(20261101);
    const cases: OtCase[] = [];
    const named: [string, SourceEdit[], SourceEdit[]][] = [
        ["ab", [{ from: 1, to: 1, insert: "H" }], [{ from: 1, to: 1, insert: "P" }]],
        ["0123456789AB", [{ from: 2, to: 10, insert: "" }], [{ from: 5, to: 5, insert: "typed" }]],
        ["abcdef", [{ from: 1, to: 4, insert: "" }], [{ from: 2, to: 5, insert: "Z" }]],
        ["line\nnext", [{ from: 4, to: 5, insert: "" }], [{ from: 9, to: 9, insert: "!" }]],
        ["é😀x", [{ from: 1, to: 3, insert: "" }], [{ from: 3, to: 3, insert: "y" }]],
    ];
    for (const [base, host, page] of named) cases.push(caseOf(base, host, page));
    while (cases.length < 200) {
        const base = Array.from({ length: rnd.int(16) }, () => rnd.pick([..."abcdef\n"])).join("");
        cases.push(caseOf(base, randomEdits(rnd, base.length, "XYZ"), randomEdits(rnd, base.length, "123")));
    }
    const maps: MapCase[] = [];
    const edits = [
        { from: 0, to: 0, insert: "ab" },
        { from: 4, to: 8, insert: "x" },
    ];
    for (const offset of [0, 1, 2, 4, 6, 8, 9, 20])
        for (const after of [true, false])
            maps.push({ offset, edits, after, expected: mapOffset(offset, edits, after) });
    return { transform: cases, mapOffset: maps };
}

function caseOf(base: string, host: SourceEdit[], page: SourceEdit[]): OtCase {
    const t = transform(host, page);
    return {
        base,
        host,
        page,
        hostPrime: t.host,
        pagePrime: t.page,
        result: applyEdits(applyEdits(base, host), t.page),
    };
}

function read<T>(name: string): T {
    return JSON.parse(readFileSync(resolve(DIR, name), "utf8")) as T;
}

describe("shared vectors (CC-20)", () => {
    if (process.env.UPDATE_VECTORS) writeFileSync(resolve(DIR, "ot.json"), JSON.stringify(generate(), null, 1) + "\n");

    it("transform and mapOffset give the recorded answers", () => {
        const v = read<OtVectors>("ot.json");
        expect(v.transform.length).toBeGreaterThan(100);
        for (const c of v.transform) {
            const t = transform(c.host, c.page);
            expect(t.host, JSON.stringify(c)).toEqual(c.hostPrime);
            expect(t.page, JSON.stringify(c)).toEqual(c.pagePrime);
            expect(applyEdits(applyEdits(c.base, c.page), t.host)).toBe(c.result);
        }
        for (const m of v.mapOffset) expect(mapOffset(m.offset, m.edits, m.after), JSON.stringify(m)).toBe(m.expected);
    });

    it("every host message vector is one the page accepts, at this protocol version", () => {
        const v = read<{ protocolVersion: number; host: Record<string, { type: string }>; page: unknown[] }>(
            "messages.json",
        );
        expect(v.protocolVersion).toBe(PROTOCOL_VERSION);
        for (const [name, json] of Object.entries(v.host)) expect(HOST_TYPES.has(json.type), name).toBe(true);
        expect(Object.keys(v.host).length).toBeGreaterThan(10);
        expect(v.page.length).toBeGreaterThan(10);
    });
});
