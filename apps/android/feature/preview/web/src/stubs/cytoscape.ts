/**
 * Build-time stand-in for cytoscape and its layout plugins (about 630 KB),
 * which Mermaid uses only for mindmap and architecture diagrams. Leaving
 * them out keeps the preview bundle inside its size budget
 * (docs/quality-bar.md: APK < 15 MB). Those two diagram types show an inline
 * "couldn't draw" box with their source instead; everything else is unchanged.
 */
function unavailable(): never {
    throw new Error("Mindmap and architecture diagrams aren't included in SS Markdown's preview yet.");
}
unavailable.use = (): void => undefined;

export default unavailable;
