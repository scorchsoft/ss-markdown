/**
 * Why a picture is not drawn, and the words the page's placeholder uses for
 * it (docs/ux-spec.md, "Pictures"). Pure, so every state's wording is tested
 * without a browser.
 *
 * The host answers `ok`, `notFound`, `noAccess`, `notImage` or `tooLarge` for
 * a picture beside the document; the page itself knows `remote` and
 * `unsupported` from the address alone.
 */
export type ImageStatus = "ok" | "notFound" | "noAccess" | "notImage" | "tooLarge" | "remote" | "unsupported";

/** The statuses the host may answer with; anything else from the wire is ignored. */
export const HOST_STATUSES: ReadonlySet<string> = new Set(["ok", "notFound", "noAccess", "notImage", "tooLarge"]);

export interface ImageNotice {
    /** The one line that says why. */
    readonly reason: string;
    /** The button's label, for a state the user can fix from here. */
    readonly action?: string;
}

/** What the placeholder says for [status]. */
export function noticeFor(status: Exclude<ImageStatus, "ok">): ImageNotice {
    switch (status) {
        case "notFound":
            return { reason: "Not found next to this file" };
        case "noAccess":
            return { reason: "Allow access to this folder to show its images", action: "Allow access" };
        case "notImage":
            return { reason: "Not a picture this preview can show" };
        case "tooLarge":
            return { reason: "Too large to show here" };
        case "remote":
            return { reason: "Online images aren't loaded" };
        case "unsupported":
            return { reason: "Only pictures next to this file can be shown" };
    }
}

/** What TalkBack reads for the placeholder: that a picture is missing, which one, and why. */
export function spokenNotice(status: Exclude<ImageStatus, "ok">, alt: string, path: string): string {
    const what = alt.trim() === "" ? "Picture not shown" : `Picture not shown: ${alt.trim()}`;
    return `${what}. ${noticeFor(status).reason}. ${path}`;
}
