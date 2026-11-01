/**
 * Build-time stand-in for cytoscape and its layout plugins (about 630 KB),
 * which Mermaid uses only for mindmap and architecture diagrams. Leaving
 * them out keeps the preview bundle inside its size budget
 * (docs/quality-bar.md: APK < 15 MB). The page recognises those two types
 * before Mermaid runs (mermaidRunner.ts, `unsupportedDiagram`) and shows an
 * inline box naming them, with a Show source button; this throw is the
 * backstop for any other path into cytoscape.
 */
function unavailable(): never {
    throw new Error("Mindmap and architecture diagrams aren't included in SS Markdown's preview yet.");
}
unavailable.use = (): void => undefined;

export default unavailable;
