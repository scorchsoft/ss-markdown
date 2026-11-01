/**
 * The four built-in themes (docs/ux-spec.md, "Themes"), in the same JSON shape
 * a custom theme uses, so built-in and custom themes take one code path.
 */
import type { ThemeSpec } from "../protocol";

const SANS = "Inter, Roboto, 'Segoe UI', system-ui, sans-serif";
const SERIF = "Lora, 'Noto Serif', Georgia, serif";
const MONO = "'JetBrains Mono', 'Roboto Mono', ui-monospace, monospace";

export const BUILT_IN_THEMES: Readonly<Record<string, ThemeSpec>> = {
    clean: {
        name: "Clean",
        fonts: { body: SANS, heading: SANS, code: MONO },
        size: { base: 15, headingScale: 1.25, lineHeight: 1.6 },
        light: {
            bg: "#FFFFFF",
            text: "#1F2328",
            heading: "#1F2328",
            link: "#0B57D0",
            codeBg: "#F3F4F6",
            muted: "#59636E",
            border: "#D1D9E0",
        },
        dark: {
            bg: "#121316",
            text: "#E6E7EA",
            heading: "#F2F3F5",
            link: "#8AB4F8",
            codeBg: "#1E2024",
            muted: "#9AA0A6",
            border: "#33363C",
        },
    },
    reader: {
        name: "Reader",
        fonts: { body: SERIF, heading: SERIF, code: MONO },
        size: { base: 17, headingScale: 1.22, lineHeight: 1.7 },
        light: {
            bg: "#FBF7EF",
            text: "#2B2620",
            heading: "#5A3A22",
            link: "#8A3A1E",
            codeBg: "#F1EADC",
            muted: "#6B6156",
            border: "#E2D8C6",
        },
        dark: {
            bg: "#1E1B17",
            text: "#E9E1D3",
            heading: "#F0C9A8",
            link: "#F2B49E",
            codeBg: "#2A2620",
            muted: "#A89E90",
            border: "#3A342C",
        },
    },
    compact: {
        name: "Compact",
        fonts: { body: SANS, heading: SANS, code: MONO },
        size: { base: 13.5, headingScale: 1.15, lineHeight: 1.4 },
        light: {
            bg: "#FFFFFF",
            text: "#202124",
            heading: "#202124",
            link: "#1A56C2",
            codeBg: "#F1F3F4",
            muted: "#5F6368",
            border: "#DADCE0",
        },
        dark: {
            bg: "#151618",
            text: "#E3E3E3",
            heading: "#F1F1F1",
            link: "#8AB4F8",
            codeBg: "#202124",
            muted: "#9AA0A6",
            border: "#3C4043",
        },
    },
    contrast: {
        name: "Contrast",
        fonts: { body: SANS, heading: SANS, code: MONO },
        size: { base: 16, headingScale: 1.25, lineHeight: 1.65 },
        light: {
            bg: "#FFFFFF",
            text: "#000000",
            heading: "#000000",
            link: "#0000CC",
            codeBg: "#F0F0F0",
            muted: "#262626",
            border: "#000000",
        },
        dark: {
            bg: "#000000",
            text: "#FFFFFF",
            heading: "#FFFFFF",
            link: "#99CCFF",
            codeBg: "#1A1A1A",
            muted: "#E0E0E0",
            border: "#FFFFFF",
        },
    },
};
