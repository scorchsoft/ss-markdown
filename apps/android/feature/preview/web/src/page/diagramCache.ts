/**
 * Rendered diagram SVGs keyed by source + theme + width, with a byte budget.
 * The oldest entry goes first when the budget is exceeded
 * (docs/robustness.md, "Preview": "SVGs are cached by source hash").
 */
export class DiagramCache {
    private readonly entries = new Map<string, string>();
    private bytes = 0;

    constructor(private budget: number) {}

    get(key: string): string | undefined {
        const svg = this.entries.get(key);
        if (svg !== undefined) {
            // Re-insert to mark as most recently used.
            this.entries.delete(key);
            this.entries.set(key, svg);
        }
        return svg;
    }

    set(key: string, svg: string): void {
        const old = this.entries.get(key);
        if (old !== undefined) {
            this.bytes -= old.length * 2;
            this.entries.delete(key);
        }
        this.entries.set(key, svg);
        this.bytes += svg.length * 2;
        this.trim();
    }

    /** Lean mode after a renderer OOM shrinks the budget. */
    setBudget(budget: number): void {
        this.budget = budget;
        this.trim();
    }

    get size(): number {
        return this.bytes;
    }

    private trim(): void {
        for (const [key, svg] of this.entries) {
            if (this.bytes <= this.budget) break;
            this.entries.delete(key);
            this.bytes -= svg.length * 2;
        }
    }
}
