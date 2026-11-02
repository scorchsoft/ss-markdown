import { describe, expect, it } from "vitest";
import { diagramKeyword, unsupportedDiagram } from "../../src/page/mermaidRunner";

describe("unsupported diagram types", () => {
    it("finds the keyword past front matter, directives and comments", () => {
        expect(diagramKeyword("---\ntitle: x\n---\n%%{init: {}}%%\n%% note\n\nmindmap\n  root")).toBe("mindmap");
        expect(diagramKeyword("graph TD;\nA-->B")).toBe("graph");
        expect(diagramKeyword("architecture-beta\n  group api")).toBe("architecture-beta");
    });

    it("names the types the bundle leaves out and passes every other type", () => {
        expect(unsupportedDiagram("mindmap\n  root")).toMatch(/^Mindmap diagrams aren't supported/);
        expect(unsupportedDiagram("architecture-beta\n  service a")).toMatch(/^Architecture diagrams/);
        for (const ok of ["flowchart LR", "sequenceDiagram", "classDiagram", "gantt", "pie", "erDiagram"]) {
            expect(unsupportedDiagram(`${ok}\n`)).toBeNull();
        }
    });
});
