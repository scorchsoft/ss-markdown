import { describe, expect, it } from "vitest";
import { EditSync } from "../../src/core/editSync";
import { applyEdits, transform } from "../../src/core/ot";
import type { SourceEdit } from "../../src/core/sourceDoc";
import { Random } from "./random";

/**
 * Kotlin's half, as `PreviewEditSync.kt` implements it: a Jupiter server that
 * rebases page edits over host changes the page had not seen.
 */
class Host {
    rev = 1;
    private outgoing: { rev: number; edits: SourceEdit[] }[] = [];

    constructor(public text: string) {}

    edit(edits: SourceEdit[]): { rev: number; ops: SourceEdit[] } {
        this.text = applyEdits(this.text, edits);
        this.rev++;
        this.outgoing.push({ rev: this.rev, edits });
        return { rev: this.rev, ops: edits };
    }

    page(baseRev: number, edits: SourceEdit[]): number {
        this.outgoing = this.outgoing.filter((o) => o.rev > baseRev);
        let incoming = edits;
        this.outgoing = this.outgoing.map((o) => {
            const t = transform(o.edits, incoming);
            incoming = t.page;
            return { rev: o.rev, edits: t.host };
        });
        this.text = applyEdits(this.text, incoming);
        return ++this.rev;
    }
}

function randomEdit(rnd: Random, length: number, alphabet: string, deletes: boolean): SourceEdit[] {
    const from = rnd.int(length + 1);
    const to = deletes ? Math.min(length, from + rnd.int(3)) : from;
    const insert = Array.from({ length: 1 + rnd.int(2) }, () => rnd.pick([...alphabet])).join("");
    return [{ from, to, insert }];
}

describe("EditSync", () => {
    it("rebases a Kotlin edit over typing in flight instead of asking for a reload", () => {
        const sync = new EditSync();
        sync.load(1);
        let page = "hello world";
        page = applyEdits(page, [{ from: 5, to: 5, insert: "!" }]);
        expect(sync.local([{ from: 5, to: 5, insert: "!" }])).toBe(1);
        const outcome = sync.host(2, [{ from: 0, to: 0, insert: "> " }]);
        expect(outcome.kind).toBe("apply");
        if (outcome.kind === "apply") page = applyEdits(page, outcome.edits);
        expect(page).toBe("> hello! world");
        expect(sync.ack(3)).toBeNull();
        expect(sync.pending).toBe(false);
    });

    it("asks for a resync, never guesses, when revisions skip", () => {
        const sync = new EditSync();
        sync.load(4);
        expect(sync.host(6, []).kind).toBe("resync");
        expect(sync.ack(5)).not.toBeNull();
    });

    for (const deletes of [false, true])
        it(`property: concurrent ${deletes ? "edits converge" : "typing converges and keeps every keystroke"}`, () => {
            const rnd = new Random(deletes ? 8 : 7);
            for (let round = 0; round < 300; round++) {
                const start = "The quick brown fox\njumps over";
                const host = new Host(start);
                const sync = new EditSync();
                sync.load(1);
                let page = start;
                const toHost: { baseRev: number; edits: SourceEdit[] }[] = [];
                const toPage: ({ type: "edit"; rev: number; ops: SourceEdit[] } | { type: "ack"; rev: number })[] = [];
                const typed: string[] = [];
                for (let step = 0; step < 40; step++) {
                    const roll = rnd.int(5);
                    if (roll === 0) {
                        const edits = randomEdit(rnd, page.length, "PQR", deletes);
                        typed.push(edits[0]?.insert ?? "");
                        page = applyEdits(page, edits);
                        toHost.push({ baseRev: sync.local(edits), edits });
                    } else if (roll === 1) {
                        const edits = randomEdit(rnd, host.text.length, "XYZ", deletes);
                        typed.push(edits[0]?.insert ?? "");
                        toPage.push({ type: "edit", ...host.edit(edits) });
                    } else if (roll === 2 && toHost.length > 0) {
                        const m = toHost.shift();
                        if (m) toPage.push({ type: "ack", rev: host.page(m.baseRev, m.edits) });
                    } else if (toPage.length > 0) {
                        const m = toPage.shift();
                        if (m?.type === "ack") expect(sync.ack(m.rev)).toBeNull();
                        else if (m) {
                            const outcome = sync.host(m.rev, m.ops);
                            expect(outcome.kind).toBe("apply");
                            if (outcome.kind === "apply") page = applyEdits(page, outcome.edits);
                        }
                    }
                }
                while (toHost.length > 0 || toPage.length > 0) {
                    const up = toHost.shift();
                    if (up) toPage.push({ type: "ack", rev: host.page(up.baseRev, up.edits) });
                    const down = toPage.shift();
                    if (down?.type === "ack") expect(sync.ack(down.rev)).toBeNull();
                    else if (down) {
                        const outcome = sync.host(down.rev, down.ops);
                        if (outcome.kind === "apply") page = applyEdits(page, outcome.edits);
                    }
                }
                expect(page).toBe(host.text);
                if (deletes) continue;
                for (const c of "PQRXYZ") {
                    const want = typed.join("").split(c).length - 1;
                    expect(page.split(c).length - 1, `round ${round}, ${c}`).toBe(want);
                }
            }
        });
});
