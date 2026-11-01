import { describe, expect, it } from "vitest";
import { cssViolation, normalizeCss } from "../../src/core/cssPolicy";
import { neutralizeUserHtml } from "../../src/core/userHtml";

describe("neutralizeUserHtml (SEC-6)", () => {
    it.each([
        ['<div data-s="0">', '<div data-user-s="0">'],
        ['<div title="x"data-s="0"data-e="20">', '<div title="x"data-user-s="0"data-user-e="20">'],
        ['<p title="x"contenteditable="true">', '<p title="x"data-user-contenteditable="true">'],
        ['<div class="a"/data-s=0>', '<div class="a"/data-user-s=0>'],
        ["<div\nDATA-LINE = 3 data-viewer=image>", "<div\ndata-user-LINE = 3 data-user-viewer=image>"],
        ["<span contenteditable>", "<span data-user-contenteditable>"],
    ])("renames reserved attributes in %s", (input, output) => {
        expect(neutralizeUserHtml(input)).toBe(output);
    });

    it("leaves values, text, comments and end tags alone", () => {
        const html = '<a title="data-s=1 contenteditable">x data-s="2"</a><!-- <b data-s=3> --></i data-s=4>';
        expect(neutralizeUserHtml(html)).toBe(html);
    });

    it("never loses characters from unterminated input", () => {
        for (const html of ["<div title=", '<div title="x', "<div data-s", "<", "a < b", "<!-- x", "<div/"])
            expect(neutralizeUserHtml(html).replace(/data-user-/g, "data-")).toBe(html);
    });
});

describe("cssViolation (SEC-5)", () => {
    it("decodes escapes and comments the way the browser does", () => {
        expect(normalizeCss("@\\69mport")).toBe("@import");
        expect(normalizeCss("\\75 rl(")).toBe("url(");
        expect(normalizeCss("@im/**/port")).toBe("@import");
    });

    it.each([
        '@\\69mport "https://evil.example/i.css";',
        'body{background-image:image-set("https://evil.example/a.png" 1x)}',
        "body{background:-webkit-image-set(url(a.png) 1x, 'https://e/x.png' 2x)}",
        ".x{background:\\75rl(https://evil.example/b.png)}",
        '.y{background:url("\\68ttps://evil.example/c.png")}',
        ".z{background:url(//evil.example/d.png)}",
        "@font-face{font-family:x;src:url(a.woff2)}",
        "</style><script>",
        "a{width:expression(alert(1))}",
    ])("refuses %s", (css) => {
        expect(cssViolation(css)).not.toBeNull();
    });

    it.each(["h1 { color: #123 }", "p{background:url(data:image/png;base64,AAAA)}", ".a::before{content:'\\2014'}"])(
        "accepts %s",
        (css) => {
            expect(cssViolation(css)).toBeNull();
        },
    );
});
