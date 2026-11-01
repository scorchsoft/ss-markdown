/**
 * The page's half of the edit protocol: a Jupiter-style client (Nichols et al.,
 * "High-latency, low-bandwidth windowing in the Jupiter collaboration system",
 * 1995) for exactly two parties, the page and Kotlin.
 *
 * - Kotlin owns the document and numbers every change with a revision. Every
 *   revision reaches the page as exactly one message: an `edit` (Kotlin's own
 *   change) or an `ack` (one of ours, applied). So each must be `seen + 1`.
 * - A Preview edit is applied here at once and sent at once with
 *   `baseRev = seen`; it stays *outstanding* until its ack arrives.
 * - A Kotlin edit that arrives while ours are outstanding was made without
 *   them. It is transformed over them (and they over it) so both survive;
 *   Kotlin does the mirror image (`PreviewEditSync.kt`).
 *
 * Nothing here touches the DOM, so the unit tests drive it directly.
 */
import { checkEdits, mapOffset, transform } from "./ot";
import type { SourceEdit } from "./sourceDoc";

export type HostEditOutcome =
    /** Apply these edits (rebased over our outstanding ones) to the page's text. */
    | { readonly kind: "apply"; readonly edits: readonly SourceEdit[] }
    /** Nothing outstanding and no character edits: apply the line changes as sent. */
    | { readonly kind: "lines" }
    /** Out of step: ask Kotlin for the whole text again. Our edits already reached it. */
    | { readonly kind: "resync"; readonly reason: string };

export class EditSync {
    /** The last revision Kotlin told us about, through a load, an edit or an ack. */
    private seenRev = 0;
    /** Our edits Kotlin has not acknowledged yet, oldest first, in the page's frame. */
    private outstanding: SourceEdit[][] = [];

    get rev(): number {
        return this.seenRev;
    }

    /** True when the page holds edits Kotlin has not confirmed. */
    get pending(): boolean {
        return this.outstanding.length > 0;
    }

    load(rev: number): void {
        this.seenRev = rev;
        this.outstanding = [];
    }

    /** Records edits made in Preview and returns the base revision to send them with. */
    local(edits: readonly SourceEdit[]): number {
        if (edits.length > 0) this.outstanding.push([...edits]);
        return this.seenRev;
    }

    /** Kotlin applied our oldest outstanding edit as revision [rev]. */
    ack(rev: number): string | null {
        if (rev !== this.seenRev + 1 || this.outstanding.length === 0) {
            return `ack ${rev} after ${this.seenRev} with ${this.outstanding.length} outstanding`;
        }
        this.seenRev = rev;
        this.outstanding.shift();
        return null;
    }

    /**
     * Kotlin's own change, revision [rev], as character edits against the text
     * before it. [ops] is absent only from a host that sends line changes
     * alone; those can be applied as-is while nothing is outstanding.
     */
    host(rev: number, ops: readonly SourceEdit[] | undefined): HostEditOutcome {
        if (rev !== this.seenRev + 1) return { kind: "resync", reason: `edit ${rev} after ${this.seenRev}` };
        if (ops === undefined) {
            if (this.outstanding.length > 0) return { kind: "resync", reason: "line edit with edits in flight" };
            this.seenRev = rev;
            return { kind: "lines" };
        }
        try {
            checkEdits(ops);
        } catch (e) {
            return { kind: "resync", reason: e instanceof Error ? e.message : String(e) };
        }
        let incoming: SourceEdit[] = [...ops];
        this.outstanding = this.outstanding.map((mine) => {
            const t = transform(incoming, mine);
            incoming = t.host;
            return t.page;
        });
        this.seenRev = rev;
        return { kind: "apply", edits: incoming };
    }

    /** Maps an offset Kotlin sent at revision [rev] into the page's frame, or null if it is stale. */
    fromHost(offset: number, rev: number): number | null {
        if (rev !== this.seenRev) return null;
        return this.outstanding.reduce((at, edits) => mapOffset(at, edits), offset);
    }
}
