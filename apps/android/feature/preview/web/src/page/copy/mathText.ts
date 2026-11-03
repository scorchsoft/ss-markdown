/**
 * Maths for the clipboard, as readable text: "∫₀¹ x² dx = 1/3" rather than
 * KaTeX's nested spans, which paste as soup, or MathML, which Gmail and
 * Google Docs drop. KaTeX has already parsed the TeX into MathML (every
 * `\alpha` is an `α` there), so this only lays that tree out in a line.
 */

const SUPERSCRIPT: Readonly<Record<string, string>> = {
    "0": "⁰",
    "1": "¹",
    "2": "²",
    "3": "³",
    "4": "⁴",
    "5": "⁵",
    "6": "⁶",
    "7": "⁷",
    "8": "⁸",
    "9": "⁹",
    "+": "⁺",
    "-": "⁻",
    "−": "⁻",
    "=": "⁼",
    "(": "⁽",
    ")": "⁾",
    a: "ᵃ",
    b: "ᵇ",
    c: "ᶜ",
    d: "ᵈ",
    e: "ᵉ",
    f: "ᶠ",
    g: "ᵍ",
    h: "ʰ",
    i: "ⁱ",
    j: "ʲ",
    k: "ᵏ",
    l: "ˡ",
    m: "ᵐ",
    n: "ⁿ",
    o: "ᵒ",
    p: "ᵖ",
    r: "ʳ",
    s: "ˢ",
    t: "ᵗ",
    u: "ᵘ",
    v: "ᵛ",
    w: "ʷ",
    x: "ˣ",
    y: "ʸ",
    z: "ᶻ",
    "′": "′",
};

const SUBSCRIPT: Readonly<Record<string, string>> = {
    "0": "₀",
    "1": "₁",
    "2": "₂",
    "3": "₃",
    "4": "₄",
    "5": "₅",
    "6": "₆",
    "7": "₇",
    "8": "₈",
    "9": "₉",
    "+": "₊",
    "-": "₋",
    "−": "₋",
    "=": "₌",
    "(": "₍",
    ")": "₎",
    a: "ₐ",
    e: "ₑ",
    h: "ₕ",
    i: "ᵢ",
    j: "ⱼ",
    k: "ₖ",
    l: "ₗ",
    m: "ₘ",
    n: "ₙ",
    o: "ₒ",
    p: "ₚ",
    r: "ᵣ",
    s: "ₛ",
    t: "ₜ",
    u: "ᵤ",
    v: "ᵥ",
    x: "ₓ",
};

/** Accents KaTeX writes as `<mover accent="true">`: the combining mark that puts them on one letter. */
const COMBINING: Readonly<Record<string, string>> = {
    "^": "̂",
    ˆ: "̂",
    "~": "̃",
    "˜": "̃",
    "¯": "̄",
    ˉ: "̄",
    "‾": "̅",
    "˙": "̇",
    "¨": "̈",
    "→": "⃗",
    "⃗": "⃗",
};

/** Operators that read better with a space each side. */
const SPACED = new Set([..."=+−-×÷·±∓<>≤≥≠≈≡∼≃≅∝→←↔⇒⇐⇔↦∈∉∋⊂⊃⊆⊇∪∩∧∨∖"]);

/** [s] in raised characters when every one has a raised form, else `^s` (or `^(s)`). */
export function superscript(s: string): string {
    return script(s, SUPERSCRIPT, "^");
}

/** [s] in lowered characters when every one has a lowered form, else `_s` (or `_(s)`). */
export function subscript(s: string): string {
    return script(s, SUBSCRIPT, "_");
}

function script(s: string, table: Readonly<Record<string, string>>, mark: string): string {
    const t = s.replace(/\s+/g, "");
    if (t === "") return "";
    const chars = [...t];
    if (chars.every((c) => table[c] !== undefined)) return chars.map((c) => table[c]).join("");
    return chars.length === 1 ? `${mark}${t}` : `${mark}(${t})`;
}

/** Wraps [s] in brackets when it is more than one term, so `a+b` over `2` reads `(a + b)/2`. */
function group(s: string): string {
    const t = s.trim();
    if ([...t].length <= 1 || /^[\p{L}\p{N}.]+$/u.test(t) || /^\(.*\)$/.test(t)) return t;
    return `(${t})`;
}

/** The readable text of a KaTeX `<math>` element (its TeX annotation left out). */
export function mathToText(math: Element): string {
    return tidy(walk(math));
}

function tidy(s: string): string {
    return (
        s
            .replace(/\s+/g, " ")
            .replace(/\s+([,;)\]}])/g, "$1")
            .replace(/([([{])\s+/g, "$1")
            .trim()
            // A minus with nothing before it, or after another operator, is a sign: "−1", not "− 1".
            .replace(/(^|[=+−×÷±<>≤≥,(] ?)− (?=\S)/g, "$1−")
    );
}

function kids(el: Element): Element[] {
    return [...el.children];
}

function walkAll(nodes: readonly Element[]): string {
    return nodes.map(walk).join("");
}

function walk(el: Element): string {
    const name = el.localName.toLowerCase();
    const [a, b, c] = kids(el);
    switch (name) {
        case "annotation":
        case "annotation-xml":
            return "";
        case "semantics":
            return a ? walk(a) : "";
        case "mi":
        case "mn":
        case "mtext":
        case "ms":
            return el.textContent ?? "";
        case "mo":
            return operator(el.textContent ?? "");
        case "mspace":
            return " ";
        case "msup":
            return `${a ? walk(a) : ""}${superscript(b ? tidy(walk(b)) : "")}`;
        case "msub":
            return `${a ? walk(a) : ""}${subscript(b ? tidy(walk(b)) : "")}`;
        case "msubsup":
            return `${a ? walk(a) : ""}${subscript(b ? tidy(walk(b)) : "")}${superscript(c ? tidy(walk(c)) : "")}`;
        case "munder":
            return `${a ? walk(a) : ""}${subscript(b ? tidy(walk(b)) : "")}`;
        case "mover":
            return over(el, a, b);
        case "munderover":
            return `${a ? walk(a) : ""}${subscript(b ? tidy(walk(b)) : "")}${superscript(c ? tidy(walk(c)) : "")}`;
        case "mfrac":
            return ` ${group(a ? tidy(walk(a)) : "")}/${group(b ? tidy(walk(b)) : "")} `;
        case "msqrt":
            return `√${group(tidy(walkAll(kids(el))))}`;
        case "mroot":
            return `${superscript(b ? tidy(walk(b)) : "")}√${group(a ? tidy(walk(a)) : "")}`;
        case "mtable":
            return `[${kids(el)
                .map((row) =>
                    kids(row)
                        .map((cell) => tidy(walk(cell)))
                        .join("  "),
                )
                .join("; ")}]`;
        case "mfenced": {
            const open = el.getAttribute("open") ?? "(";
            const close = el.getAttribute("close") ?? ")";
            return `${open}${kids(el)
                .map((k) => tidy(walk(k)))
                .join(", ")}${close}`;
        }
        case "mphantom":
            return "";
        default:
            return walkAll(kids(el)) || (el.children.length === 0 ? (el.textContent ?? "") : "");
    }
}

/** `\hat x` is x̂, `\vec v` is v⃗; any other thing over a base reads as a superscript. */
function over(el: Element, base: Element | undefined, top: Element | undefined): string {
    const b = base ? tidy(walk(base)) : "";
    const t = top ? (top.textContent ?? "").trim() : "";
    const mark = COMBINING[t];
    if (mark !== undefined && el.getAttribute("accent") === "true" && [...b].length === 1) return `${b}${mark}`;
    if (mark !== undefined && el.getAttribute("accent") === "true") return `${group(b)}${mark}`;
    return `${b}${superscript(top ? tidy(walk(top)) : "")}`;
}

function operator(op: string): string {
    const t = op.trim();
    if (t === ",") return ", ";
    if (t === "⁡" || t === "⁢" || t === "⁣") return ""; // function application, invisible times
    return SPACED.has(t) ? ` ${t} ` : t;
}
