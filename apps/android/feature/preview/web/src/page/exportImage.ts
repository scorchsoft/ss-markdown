/**
 * Exports a rendered diagram as SVG text or a PNG at 1×, 2× or 3×, for the
 * long-press sheet and "Export images…". Labels are SVG text (Mermaid runs
 * with htmlLabels off), so drawing to a canvas never taints it.
 */
export async function exportDiagram(
    box: HTMLElement,
    format: "svg" | "png",
    scale: number,
): Promise<{ mime: string; data: string }> {
    const svgEl = box.querySelector("svg");
    if (!svgEl) throw new Error("The diagram has not been drawn.");
    const clone = svgEl.cloneNode(true) as SVGSVGElement;
    clone.setAttribute("xmlns", "http://www.w3.org/2000/svg");
    const svg = new XMLSerializer().serializeToString(clone);
    if (format === "svg") return { mime: "image/svg+xml", data: svg };
    const rect = svgEl.getBoundingClientRect();
    const width = Math.max(1, Math.round(rect.width * scale));
    const height = Math.max(1, Math.round(rect.height * scale));
    const url = URL.createObjectURL(new Blob([svg], { type: "image/svg+xml" }));
    try {
        const img = new Image();
        img.src = url;
        await img.decode();
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d");
        if (!ctx) throw new Error("No canvas.");
        ctx.drawImage(img, 0, 0, width, height);
        const dataUrl = canvas.toDataURL("image/png");
        return { mime: "image/png", data: dataUrl.slice(dataUrl.indexOf(",") + 1) };
    } finally {
        URL.revokeObjectURL(url);
    }
}
