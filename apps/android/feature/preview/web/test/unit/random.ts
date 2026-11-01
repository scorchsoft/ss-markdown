/** A small seeded PRNG so property tests are reproducible without a dependency. */
export class Random {
    private state: number;

    constructor(seed: number) {
        this.state = seed >>> 0 || 1;
    }

    next(): number {
        // xorshift32
        let x = this.state;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        this.state = x >>> 0;
        return this.state / 0x100000000;
    }

    int(max: number): number {
        return Math.floor(this.next() * max);
    }

    pick<T>(items: readonly T[]): T {
        const item = items[this.int(items.length)];
        if (item === undefined) throw new Error("empty");
        return item;
    }
}

const LINES = [
    "",
    "",
    "# Heading",
    "Plain paragraph text.",
    "- item",
    "  continued",
    "1. one",
    "```",
    "~~~",
    "$$",
    "<div>",
    "</div>",
    "<!-- note",
    "-->",
    "> quote",
    "| a | b |",
    "| --- | --- |",
    "    indented",
    "---",
    "[ref]: https://example.com",
    "Text with **bold** and `code`.",
];

/** A random document built from lines that exercise every sectioning rule. */
export function randomDoc(rnd: Random, lines: number): string[] {
    return Array.from({ length: lines }, () => rnd.pick(LINES));
}
