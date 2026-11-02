/**
 * Applies a theme as CSS custom properties on the root element.
 *
 * Both variants are published (`--l-*` and `--d-*`) and the active one is
 * chosen by `data-dark`, so the print stylesheet can always use the light
 * colours: dark themes print light (docs/ux-spec.md, "PDF").
 */
import type { ThemeColours, ThemeFonts, ThemeSpec } from "../protocol";
import { cssViolation, normalizeCss } from "../core/cssPolicy";
import { BUILT_IN_THEMES } from "./themes";

const COLOUR_KEYS: readonly (keyof ThemeColours)[] = ["bg", "text", "heading", "link", "codeBg", "muted", "border"];
const SAFE_COLOUR = /^(#[0-9a-f]{3,8}|rgba?\([\d\s.,%]+\)|[a-z]+)$/i;
const SAFE_FONT = /^[\w\s,'"-]+$/;

function cssName(key: string): string {
    return key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`);
}

/** Merges a custom theme over its base, so a partial JSON still yields every variable. */
export function resolveTheme(spec: ThemeSpec): ThemeSpec {
    const base = BUILT_IN_THEMES[(spec.base ?? spec.name).toLowerCase()] ?? BUILT_IN_THEMES.clean;
    return {
        name: spec.name,
        fonts: { ...base?.fonts, ...spec.fonts },
        size: { ...base?.size, ...spec.size },
        light: { ...(base?.light as ThemeColours), ...spec.light },
        dark: { ...(base?.dark as ThemeColours), ...spec.dark },
        css: spec.css ?? "",
    };
}

/**
 * Theme CSS may restyle the page but never load anything. CSS that breaks
 * the policy is dropped whole rather than patched: a half-applied theme is
 * confusing, and the CSP would block its loads anyway (core/cssPolicy.ts).
 */
export function sanitizeThemeCss(css: string): string {
    return cssViolation(css) === null ? css : "";
}

/**
 * The app's `@font-face` rules, accepted only if they are nothing else and
 * every file comes from the page's own [origin] (bundled assets or the font
 * store the app serves under `/fonts/`). The CSP's `font-src 'self'` would
 * block anything else anyway; refusing here keeps a bad rule from half-applying.
 */
export function sanitizeFontFaces(css: string, origin: string): string {
    const rules = css.match(/@font-face\s*\{[^{}]*\}/g) ?? [];
    if (rules.join("").replace(/\s+/g, "") !== css.replace(/\s+/g, "")) return "";
    const ok = rules.every((rule) => {
        const normal = normalizeCss(rule);
        if (/<\/|@import|image-set|expression\s*\(|javascript:/.test(normal)) return false;
        const urls = [...normal.matchAll(/url\s*\(\s*(["']?)([^"')]*)\1\s*\)/g)].map((m) => m[2] ?? "");
        return urls.length > 0 && urls.every((u) => u.startsWith(`${origin.toLowerCase()}/`));
    });
    return ok ? rules.join("\n") : "";
}

function styleElement(id: string): HTMLElement {
    let el = document.getElementById(id);
    if (!el) {
        el = document.createElement("style");
        el.id = id;
        document.head.appendChild(el);
    }
    return el;
}

export function applyTheme(root: HTMLElement, spec: ThemeSpec, dark: boolean, fonts?: ThemeFonts): void {
    const theme = resolveTheme(spec);
    const style = root.style;
    for (const [variant, colours] of [
        ["l", theme.light],
        ["d", theme.dark],
    ] as const) {
        for (const key of COLOUR_KEYS) {
            const value = colours?.[key];
            if (value && SAFE_COLOUR.test(value)) style.setProperty(`--${variant}-${cssName(key)}`, value);
        }
    }
    // The app's resolved stacks win over the bare family names in the theme JSON.
    const stacks: Record<string, string | undefined> = { ...theme.fonts };
    for (const key of ["body", "heading", "code"] as const) if (fonts?.[key]) stacks[key] = fonts[key];
    for (const [key, value] of Object.entries(stacks)) {
        if (value && SAFE_FONT.test(value)) style.setProperty(`--font-${key}`, value);
    }
    styleElement("font-faces").textContent = sanitizeFontFaces(fonts?.faces ?? "", location.origin);
    const size = theme.size ?? {};
    if (size.base) style.setProperty("--base-size", `${size.base}px`);
    if (size.headingScale) style.setProperty("--heading-scale", String(size.headingScale));
    if (size.lineHeight) style.setProperty("--line-height", String(size.lineHeight));
    root.dataset.theme = (spec.base ?? spec.name).toLowerCase();
    root.dataset.dark = dark ? "true" : "false";
    root.classList.add("themed");
    styleElement("theme-css").textContent = sanitizeThemeCss(theme.css ?? "");
}
