/**
 * Pictures that cannot be drawn (docs/ux-spec.md, "Pictures"): where an
 * address points, the URL the host serves it at, and what the placeholder
 * says and reads out for each state.
 */
import { JSDOM } from "jsdom";
import { describe, expect, it } from "vitest";
import { collectDefinitions } from "../../src/core/definitions";
import { docKey, docUrl, imageTarget } from "../../src/core/imagePaths";
import { noticeFor, spokenNotice, type ImageStatus } from "../../src/core/imageStatus";
import { placeholder } from "../../src/page/images";
import { createMarkdown, renderSection } from "../../src/render/markdown";

const md = createMarkdown();
const render = (src: string): string => renderSection(md, src, collectDefinitions(src.split("\n"))).html;

describe("image targets", () => {
    it("serves pictures beside the document with their climbs counted up front", () => {
        expect(docUrl("img/a.png")).toBe("/doc/0/img/a.png");
        expect(docUrl("./img/./a.png")).toBe("/doc/0/img/a.png");
        expect(docUrl("img/../b.png")).toBe("/doc/0/b.png");
        expect(docUrl("../shared/logo.svg")).toBe("/doc/1/shared/logo.svg");
        expect(docUrl("../../x.png")).toBe("/doc/2/x.png");
        // A URL parser would fold these too, and leave /doc/ altogether.
        expect(docUrl("%2e%2e/x.png")).toBe("/doc/1/x.png");
        expect(docUrl("a.png?v=2#top")).toBe("/doc/0/a.png");
        expect(docUrl("my%20pic.png")).toBe("/doc/0/my%20pic.png");
        expect(docUrl("./")).toBeNull();
        expect(docUrl("")).toBeNull();
    });

    it("never loads a remote or unsupported address", () => {
        expect(imageTarget("https://example.com/a.png").kind).toBe("remote");
        expect(imageTarget("http://example.com/a.png").kind).toBe("remote");
        expect(imageTarget("//example.com/a.png").kind).toBe("remote");
        expect(imageTarget("file:///sdcard/a.png").kind).toBe("unsupported");
        expect(imageTarget("content://x/y").kind).toBe("unsupported");
        expect(imageTarget("/etc/a.png").kind).toBe("unsupported");
        expect(imageTarget("data:image/png;base64,AAAA")).toEqual({
            kind: "inline",
            url: "data:image/png;base64,AAAA",
        });
        expect(imageTarget("img/a.png")).toEqual({ kind: "doc", url: "/doc/0/img/a.png" });
    });

    it("names a picture by its path after /doc/, without a retry's query", () => {
        expect(docKey("/doc/1/shared/a.png")).toBe("1/shared/a.png");
        expect(docKey("https://appassets.androidplatform.net/doc/0/a.png?r=3")).toBe("0/a.png");
        expect(docKey("/assets/x.png")).toBeNull();
        expect(docKey("/doc/")).toBeNull();
    });

    it("renders the address as written, and no src for a remote picture", () => {
        const local = render("![Cat](../pics/cat.png)\n");
        expect(local).toContain('src="/doc/1/pics/cat.png"');
        expect(local).toContain('data-src="../pics/cat.png"');
        expect(local).toContain('class="img-pending"');
        const remote = render("![Logo](https://example.com/logo.png)\n");
        expect(remote).not.toMatch(/\ssrc=/);
        expect(remote).toContain('data-src="https://example.com/logo.png"');
    });
});

describe("placeholder", () => {
    const doc = new JSDOM("").window.document;
    const states: Exclude<ImageStatus, "ok">[] = [
        "notFound",
        "noAccess",
        "notImage",
        "tooLarge",
        "remote",
        "unsupported",
    ];

    it("says why for every state, and offers Allow access only without folder access", () => {
        for (const status of states) {
            const box = placeholder(doc, status, "Diagram", "img/a.png");
            expect(box.getAttribute("contenteditable")).toBe("false");
            expect(box.textContent).toContain(noticeFor(status).reason);
            expect(box.textContent).toContain("Diagram");
            expect(box.textContent).toContain("img/a.png");
            expect(box.querySelectorAll("button").length).toBe(status === "noAccess" ? 1 : 0);
        }
        expect(noticeFor("notFound").reason).toBe("Not found next to this file");
        expect(noticeFor("noAccess").reason).toBe("Allow access to this folder to show its images");
        expect(noticeFor("remote").reason).toBe("Online images aren't loaded");
    });

    it("is a picture with a spoken label, beside a real button", () => {
        const box = placeholder(doc, "noAccess", "Cat", "cat.png");
        const img = box.querySelector('[role="img"]');
        expect(img?.getAttribute("aria-label")).toBe(spokenNotice("noAccess", "Cat", "cat.png"));
        expect(img?.querySelector("button")).toBeNull();
        expect(box.querySelector("button")?.getAttribute("type")).toBe("button");
        expect(box.querySelector("svg")?.getAttribute("aria-hidden")).toBe("true");
    });

    it("shows hostile alt text and paths as text, never as markup", () => {
        const box = placeholder(doc, "notFound", "<b>x</b>", '"><script>alert(1)</script>');
        expect(box.querySelector("b, script")).toBeNull();
        expect(box.textContent).toContain("<b>x</b>");
    });
});
