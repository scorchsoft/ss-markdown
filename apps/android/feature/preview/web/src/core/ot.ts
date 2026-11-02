/**
 * Operational transform for plain-text splices, so an edit typed in Preview and
 * an edit made in Code at the same moment both survive (docs/robustness.md,
 * "Preview": never lose work).
 *
 * An edit set is a list of non-overlapping [SourceEdit]s, sorted by offset, all
 * relative to the same text: the shape every message on the bridge uses. For
 * the transform it becomes a sequence of components (retain n, insert s,
 * delete n) with an implicit retain to the end of the text, as in ot.js
 * (MIT, https://github.com/Operational-Transformation/ot.js). That form has a
 * proven convergence property (TP1), which the unit tests check by brute force:
 *
 *     apply(apply(s, host), page') == apply(apply(s, page), host')
 *
 * Inserts are never lost: when both sides insert at the same offset, the
 * host's text goes first. Kotlin's `bridge/TextTransform.kt` is the same
 * algorithm with the same tie rule; the two must change together.
 */
import type { SourceEdit } from "./sourceDoc";

type Component = { readonly r: number } | { readonly i: string } | { readonly d: number };

class Builder {
    readonly out: Component[] = [];

    retain(n: number): void {
        if (n <= 0) return;
        const last = this.out[this.out.length - 1];
        if (last && "r" in last) this.out[this.out.length - 1] = { r: last.r + n };
        else this.out.push({ r: n });
    }

    insert(s: string): void {
        if (s.length === 0) return;
        const last = this.out[this.out.length - 1];
        // Inserts go before deletes at the same point, so equal edits have one form.
        if (last && "d" in last) {
            const prev = this.out[this.out.length - 2];
            if (prev && "i" in prev) this.out[this.out.length - 2] = { i: prev.i + s };
            else this.out.splice(this.out.length - 1, 0, { i: s });
        } else if (last && "i" in last) {
            this.out[this.out.length - 1] = { i: last.i + s };
        } else {
            this.out.push({ i: s });
        }
    }

    delete(n: number): void {
        if (n <= 0) return;
        const last = this.out[this.out.length - 1];
        if (last && "d" in last) this.out[this.out.length - 1] = { d: last.d + n };
        else this.out.push({ d: n });
    }
}

/** Throws unless [edits] are sorted, non-overlapping and well formed. */
export function checkEdits(edits: readonly SourceEdit[], length = Infinity): void {
    let at = 0;
    for (const e of edits) {
        if (!(Number.isInteger(e.from) && Number.isInteger(e.to)) || e.from < at || e.to < e.from || e.to > length) {
            throw new RangeError(`edit ${JSON.stringify(e)} is out of order or out of range`);
        }
        at = e.to;
    }
}

function toComponents(edits: readonly SourceEdit[]): Component[] {
    checkEdits(edits);
    const b = new Builder();
    let at = 0;
    for (const e of edits) {
        b.retain(e.from - at);
        b.insert(e.insert);
        b.delete(e.to - e.from);
        at = e.to;
    }
    return b.out;
}

function toEdits(components: readonly Component[]): SourceEdit[] {
    const out: SourceEdit[] = [];
    let at = 0;
    let open: { from: number; to: number; insert: string } | null = null;
    for (const c of components) {
        if ("r" in c) {
            if (open) out.push(open);
            open = null;
            at += c.r;
        } else if ("i" in c) {
            open ??= { from: at, to: at, insert: "" };
            open.insert += c.i;
        } else {
            open ??= { from: at, to: at, insert: "" };
            open.to += c.d;
            at += c.d;
        }
    }
    if (open) out.push(open);
    return out;
}

/** Reads components one piece at a time, splitting retains and deletes as needed. */
class Cursor {
    private index = 0;
    private used = 0;

    constructor(private readonly components: readonly Component[]) {}

    /** The current component with what is left of it; undefined is the implicit retain to the end. */
    peek(): Component | undefined {
        const c = this.components[this.index];
        if (!c || this.used === 0) return c;
        if ("r" in c) return { r: c.r - this.used };
        if ("d" in c) return { d: c.d - this.used };
        return c;
    }

    take(n: number): void {
        const c = this.components[this.index];
        if (!c) return;
        const size = "r" in c ? c.r : "d" in c ? c.d : 1;
        if ("i" in c || this.used + n >= size) {
            this.index++;
            this.used = 0;
        } else {
            this.used += n;
        }
    }

    get done(): boolean {
        return this.index >= this.components.length;
    }
}

function sizeOf(c: Component | undefined): number {
    if (!c) return Infinity;
    return "r" in c ? c.r : "d" in c ? c.d : c.i.length;
}

/**
 * Transforms two edit sets made against the same text. Returns `host'`, to
 * apply after [page], and `page'`, to apply after [host]; both orders give the
 * same text. The host's inserts win ties, on both sides of the bridge.
 */
export function transform(
    host: readonly SourceEdit[],
    page: readonly SourceEdit[],
): { host: SourceEdit[]; page: SourceEdit[] } {
    const a = new Cursor(toComponents(host));
    const b = new Cursor(toComponents(page));
    const hostOut = new Builder();
    const pageOut = new Builder();
    while (!a.done || !b.done) {
        const ca = a.peek();
        const cb = b.peek();
        if (ca && "i" in ca) {
            hostOut.insert(ca.i);
            pageOut.retain(ca.i.length);
            a.take(1);
            continue;
        }
        if (cb && "i" in cb) {
            hostOut.retain(cb.i.length);
            pageOut.insert(cb.i);
            b.take(1);
            continue;
        }
        const n = Math.min(sizeOf(ca), sizeOf(cb));
        const aDel = ca !== undefined && "d" in ca;
        const bDel = cb !== undefined && "d" in cb;
        if (!aDel && !bDel) {
            hostOut.retain(n);
            pageOut.retain(n);
        } else if (aDel && !bDel) {
            hostOut.delete(n);
        } else if (!aDel && bDel) {
            pageOut.delete(n);
        }
        // Both deleting the same text: it is gone once, and neither side says so again.
        a.take(n);
        b.take(n);
    }
    return { host: toEdits(hostOut.out), page: toEdits(pageOut.out) };
}

/**
 * Where [offset] lands after [edits]. A point inside deleted text moves to
 * where the deletion was; at an insert, [after] chooses the side.
 */
export function mapOffset(offset: number, edits: readonly SourceEdit[], after = true): number {
    let delta = 0;
    for (const e of edits) {
        if (offset < e.from || (offset === e.from && e.from === e.to && !after)) break;
        if (offset < e.to) return e.from + delta + (after ? e.insert.length : 0);
        delta += e.insert.length - (e.to - e.from);
    }
    return offset + delta;
}

/** Applies an edit set to [text]; the reference the tests check against. */
export function applyEdits(text: string, edits: readonly SourceEdit[]): string {
    let out = text;
    for (const e of [...edits].sort((x, y) => y.from - x.from)) out = out.slice(0, e.from) + e.insert + out.slice(e.to);
    return out;
}
