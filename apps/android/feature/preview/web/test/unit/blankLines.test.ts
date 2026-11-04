/**
 * Enter and Backspace on a blank line in Preview (Andrew, 2026-11-04): Enter adds a line each time;
 * Backspace walks back up one extra line per press, then joins the block above, and between two
 * items of a list takes the whole gap so the list is tight again. `‸` is the caret.
 */
import { describe, expect, it } from "vitest";
import { blankLineBackspace, blankLineEnter, blankLinesAbove, type LineEdit } from "../../src/core/newline";

function run(rule: typeof blankLineBackspace, marked: string): string | null {
    const caret = marked.indexOf("‸");
    const text = marked.replace("‸", "");
    const lines = text.split("\n");
    const starts: number[] = [];
    let at = 0;
    for (const l of lines) {
        starts.push(at);
        at += l.length + 1;
    }
    const line = text.slice(0, caret).split("\n").length - 1;
    const edit: LineEdit | null = rule(lines, line, (l) => starts[l] ?? text.length);
    if (!edit) return null;
    const after = text.slice(0, edit.from) + edit.insert + text.slice(edit.to);
    return after.slice(0, edit.caret) + "‸" + after.slice(edit.caret);
}

describe("Backspace on a blank line", () => {
    for (const [name, before, after] of [
        ["rejoins a bullet list split by Enter", "- a\n- b\n\n‸\n- d", "- a\n- b‸\n- d"],
        ["rejoins a numbered list split by Enter", "1. a\n2. b\n\n‸\n\n3. d", "1. a\n2. b‸\n3. d"],
        ["rejoins a quoted list", "> - a\n\n‸\n> - b", "> - a‸\n> - b"],
        ["joins a paragraph, keeping the gap to the next block", "Para\n\n‸\n\nNext", "Para‸\n\nNext"],
        ["joins a heading", "## Head\n\n‸", "## Head‸"],
        ["joins the last item at the end of a list", "- a\n- b\n\n‸", "- a\n- b‸"],
        ["takes one extra blank line per press", "Para\n\n\n\n‸", "Para\n\n\n‸"],
        ["at the start of a document, one line", "\n\n‸Text", null],
        ["below a table only takes an extra line", "| a |\n|---|\n\n\n‸", "| a |\n|---|\n\n‸"],
        ["right below a table does nothing", "| a |\n|---|\n\n‸", null],
        ["right below a fence does nothing", "```\nx\n```\n\n‸", null],
        ["is not for a line with text", "Para\n\n‸Text", null],
    ] as const) {
        it(name, () => expect(run(blankLineBackspace, before)).toBe(after));
    }
});

describe("Enter on a blank line", () => {
    it("adds a line, the caret on it, each time", () => {
        expect(run(blankLineEnter, "Para\n\n‸")).toBe("Para\n\n\n‸");
        expect(run(blankLineEnter, "Para\n\n\n‸\n\nNext")).toBe("Para\n\n\n\n‸\n\nNext");
    });
    it("is not for a line with text", () => expect(run(blankLineEnter, "Para‸")).toBeNull());
    it("counts the blank lines above a line", () => {
        expect(blankLinesAbove(["a", "", "", ""], 3)).toBe(2);
        expect(blankLinesAbove(["", "", ""], 2)).toBe(2);
        expect(blankLinesAbove(["a", "b"], 1)).toBe(0);
    });
});
