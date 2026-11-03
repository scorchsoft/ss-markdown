# Theme CSS contract

`ThemeCss` (this module) turns a `Theme` into one stylesheet. The preview
bundle (`feature/preview`) styles Markdown **only** through the custom
properties below, so switching theme, appearance or font is replacing a
single `<style id="ssm-theme">` element. Never hard-code a colour, font or
size in the preview's own CSS; add a variable here first.

Change this file and `ThemeCss.kt` together. `ThemeCssTest` fails if the
generator emits a variable this file does not document.

## What the stylesheet contains, in order

1. A comment naming the theme and variant.
2. `@font-face` rules for the theme's body, heading and code families
   that are on this phone (bundled, downloaded or imported), resolved by
   `feature/fonts`. A family that is not available has no rule; its
   stack falls through to the generic family until the download lands.
3. One `:root { … }` block: `color-scheme` plus the variables below.
4. The theme's **Advanced CSS**, verbatim and last, so it can override
   anything. It has been checked by `CssPolicy` after decoding CSS
   escapes and comments (no `</`, no `@import` or `@font-face`, no
   `image-set()` or `src()`, no `url()` but `data:` and relative paths,
   no script); the preview's CSP is still the real wall.

## Variables

All are set on `:root`. Colours are `#RRGGBB`; there is no alpha.

### Colour (from the active variant's palette)

| Variable | JSON key | Use |
|---|---|---|
| `--ssm-bg` | `bg` | Page background. |
| `--ssm-text` | `text` | Body text, list markers, table text. |
| `--ssm-heading` | `heading` | h1–h6. |
| `--ssm-link` | `link` | Links (the preview underlines them, so colour is never the only cue). |
| `--ssm-code` | `code` | Inline code and fenced-block text outside highlight.js tokens. |
| `--ssm-code-bg` | `codeBg` | Inline code and fenced-block background. |
| `--ssm-muted` | `muted` | Blockquote text, captions, footnotes, table header rules. |
| `--ssm-border` | `border` | `hr`, table borders, the blockquote bar. Decorative: not contrast-checked. |
| `--ssm-code-comment` | `codeComment` | Highlighted code: comments (`.hljs-comment`, `.hljs-quote`). |
| `--ssm-code-keyword` | `codeKeyword` | Keywords, built-in types and literals (`.hljs-keyword`, `-type`, `-literal`, `-selector-tag`). |
| `--ssm-code-string` | `codeString` | Strings, regular expressions, diff additions. |
| `--ssm-code-number` | `codeNumber` | Numbers, symbols, list bullets. |
| `--ssm-code-title` | `codeTitle` | Function and class names, section headers. |
| `--ssm-code-attr` | `codeAttr` | Attributes, keys, properties, sigil variables. |
| `--ssm-code-built-in` | `codeBuiltIn` | Built-in functions, markup tags and their names. |
| `--ssm-code-meta` | `codeMeta` | Annotations, decorators, preprocessor lines. |
| `--ssm-code-deletion` | `codeDeletion` | Diff deletions. |

The nine `code*` colours are optional in JSON (a custom theme without
them inherits its base's) and each is contrast-checked against `codeBg`.
Their defaults are exactly Preview's highlight.js palette in
`feature/preview/web/src/styles/code.css` (a GitHub-like set, and an AAA
set for Contrast); `BuiltInThemesTest` reads that file and fails if the
two drift. The Code view draws the same colours (`feature/editor`
`EditorTheme.withSyntax`). Preview's stylesheet does not read these
variables yet, so a custom theme's code colours reach the Code view
only; the variables are emitted so it can.

`color-scheme` is `light` or `dark`, so scrollbars and form controls match.
Print always uses the **light** variant (ux-spec, PDF).

### Type

| Variable | Example (Clean) | Use |
|---|---|---|
| `--ssm-font-body` | `"Inter", sans-serif` | `body`. A complete stack: use it as-is. |
| `--ssm-font-heading` | `"Inter", sans-serif` | h1–h6. |
| `--ssm-font-code` | `"JetBrains Mono", monospace` | `code`, `pre`, `kbd`. |
| `--ssm-size-base` | `15px` | Body size **at 100% zoom** (ux-spec "Text size: one model"). The preview multiplies it by its zoom: `font-size: calc(var(--ssm-size-base) * var(--ssm-zoom, 1))`. `--ssm-zoom` belongs to the preview, not the theme. |
| `--ssm-line-height` | `1.6` | Unitless body line height. |
| `--ssm-heading-scale` | `1.25` | The modular scale; the h-sizes below are derived from it. |
| `--ssm-h1`, `--ssm-h2`, `--ssm-h3` | `1.9531em`, `1.5625em`, `1.25em` | scale³, scale², scale, in `em` of the body size. |
| `--ssm-h4` | `1em` | Body size, bold. |
| `--ssm-h5`, `--ssm-h6` | `0.875em`, `0.85em` | Fixed, below body size. |
| `--ssm-code-size` | `0.875em` | Code relative to surrounding text. |

Print typography is fixed in points (ux-spec, PDF) and ignores
`--ssm-size-base` and the h-sizes; it still uses the colour and font
variables.

## Font faces

```css
@font-face { font-family: "Inter"; src: url("…/inter.ttf") format("truetype");
             font-style: normal; font-weight: 100 900; font-display: swap; }
```

Variable fonts declare a weight range; static faces a single weight.
`font-display: swap` means text is never invisible while a face loads.
Strings are CSS-escaped, so a family name or URL can never end the rule.
