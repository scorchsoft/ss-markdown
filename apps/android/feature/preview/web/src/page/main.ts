/**
 * Entry point of the preview page. The worker is a module worker next to this
 * file, so both share the bundle's split chunks.
 */
import { PreviewApp } from "./previewApp";

const root = document.getElementById("doc");
if (root) {
    const worker = new Worker(new URL("./worker.js", import.meta.url), { type: "module" });
    new PreviewApp(root, worker);
}
