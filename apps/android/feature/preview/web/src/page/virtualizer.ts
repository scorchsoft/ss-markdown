/**
 * Keeps only sections within about three screens of the viewport in the DOM.
 *
 * Mounted sections also use `content-visibility: auto` (in CSS), so the
 * browser skips their layout until needed. Unmounted ones are spacers. Above
 * about 7 million pixels the spacers are scaled down, as CodeMirror's height
 * scaler does, because browser layout units overflow on huge pages.
 */
import type { SectionView } from "./sectionView";

export const MAX_PAGE_PX = 7_000_000;

export class Virtualizer {
    private readonly observer: IntersectionObserver;
    private readonly visible = new Set<SectionView>();
    private readonly byEl = new WeakMap<Element, SectionView>();
    private scale = 1;
    private all = false;

    constructor(private readonly onMounted: (view: SectionView) => void) {
        this.observer = new IntersectionObserver((entries) => this.onIntersect(entries), {
            rootMargin: "300% 0px 300% 0px",
        });
    }

    /** While printing, every section stays mounted. */
    set keepAll(value: boolean) {
        this.all = value;
    }

    get keepAll(): boolean {
        return this.all;
    }

    observe(view: SectionView): void {
        this.byEl.set(view.el, view);
        this.observer.observe(view.el);
    }

    unobserve(view: SectionView): void {
        this.observer.unobserve(view.el);
        this.visible.delete(view);
    }

    isNear(view: SectionView): boolean {
        return this.all || this.visible.has(view);
    }

    /** Recomputes the spacer scale so the page never exceeds [MAX_PAGE_PX]. */
    rescale(views: readonly SectionView[]): void {
        let mounted = 0;
        let spacers = 0;
        for (const v of views) {
            if (v.mounted) mounted += v.height;
            else spacers += v.height;
        }
        const room = Math.max(MAX_PAGE_PX - mounted, MAX_PAGE_PX / 10);
        this.scale = spacers > room ? room / spacers : 1;
        for (const v of views) if (!v.mounted) v.el.style.height = `${Math.max(1, v.height * this.scale)}px`;
    }

    get spacerScale(): number {
        return this.scale;
    }

    /** Mounts or unmounts [view] according to its distance from the viewport. */
    update(view: SectionView): void {
        if (this.isNear(view)) {
            if (view.mount()) this.onMounted(view);
        } else if (view.mounted && !view.el.contains(document.activeElement)) {
            view.unmount(this.scale);
        }
    }

    private onIntersect(entries: IntersectionObserverEntry[]): void {
        for (const entry of entries) {
            const view = this.byEl.get(entry.target);
            if (!view) continue;
            if (entry.isIntersecting) this.visible.add(view);
            else this.visible.delete(view);
            this.update(view);
        }
    }
}
