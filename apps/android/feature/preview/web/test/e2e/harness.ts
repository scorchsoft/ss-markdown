/**
 * Loads the BUILT bundle (src/main/assets/preview) in Chromium the way the app
 * does: served from one origin, driven only through a MessagePort.
 */
import { createReadStream, existsSync, readFileSync, statSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { extname, join, normalize, resolve } from "node:path";
import { chromium, type Browser, type Page } from "playwright";
import type { HostMessage, PageMessage } from "../../src/protocol";

export const ASSETS = resolve(__dirname, "../../../src/main/assets");
/**
 * feature/fonts' bundled files, at `/assets/fonts/`: the app serves the same
 * files from the page's own origin, so a theme test draws in the theme's real face.
 */
const FONTS = resolve(__dirname, "../../../../fonts/src/main/assets");
const TYPES: Record<string, string> = {
    ".html": "text/html",
    ".js": "text/javascript",
    ".css": "text/css",
    ".woff2": "font/woff2",
    ".txt": "text/plain",
    ".png": "image/png",
    ".ttf": "font/ttf",
};

export interface Harness {
    readonly browser: Browser;
    readonly server: Server;
    readonly base: string;
    close(): Promise<void>;
}

export async function startHarness(): Promise<Harness> {
    if (!existsSync(join(ASSETS, "preview/index.html"))) throw new Error("Run `npm run build` first.");
    const server = createServer((req, res) => {
        const path = normalize(decodeURIComponent((req.url ?? "/").split("?")[0] ?? "/"));
        const root = path.startsWith("/assets/fonts/") ? FONTS : ASSETS;
        const file = join(root, path.replace(/^\/assets\//, "/"));
        if (!file.startsWith(root) || !existsSync(file) || !statSync(file).isFile()) {
            res.writeHead(404).end();
            return;
        }
        res.writeHead(200, { "content-type": TYPES[extname(file)] ?? "application/octet-stream" });
        createReadStream(file).pipe(res);
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as AddressInfo).port;
    const browser = await chromium.launch();
    return {
        browser,
        server,
        base: `http://127.0.0.1:${port}/assets/preview/index.html`,
        async close() {
            await browser.close();
            await new Promise((r) => server.close(r));
        },
    };
}

/**
 * A page with the bridge connected. Messages from the page collect in
 * `window.__received`. Like Kotlin, the harness acknowledges every edit the
 * page sends with the next revision; set `window.__autoAck = false` to hold
 * acks back and play Kotlin by hand.
 */
export async function openPreview(
    h: Harness,
    viewport = { width: 412, height: 860 },
    device: { deviceScaleFactor?: number; hasTouch?: boolean } = {},
): Promise<Page> {
    const page = await h.browser.newPage({ viewport, ...device });
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    (page as Page & { errors?: string[] }).errors = errors;
    await page.goto(h.base);
    await page.evaluate(() => {
        const w = window as unknown as {
            __port: MessagePort;
            __received: unknown[];
            __rev: number;
            __autoAck: boolean;
        };
        const channel = new MessageChannel();
        w.__received = [];
        w.__port = channel.port1;
        w.__rev = 0;
        w.__autoAck = true;
        channel.port1.onmessage = (e) => {
            const m = JSON.parse(String(e.data)) as { type: string };
            w.__received.push(m);
            if (w.__autoAck && (m.type === "sourceEdit" || m.type === "taskToggle")) {
                channel.port1.postMessage(JSON.stringify({ type: "ack", rev: ++w.__rev }));
            }
        };
        window.postMessage("ssmd:init", "*", [channel.port2]);
    });
    await waitForMessage(page, (m) => m.type === "ready");
    return page;
}

export async function send(page: Page, message: HostMessage): Promise<void> {
    await page.evaluate((m) => {
        const w = window as unknown as { __port: MessagePort; __rev: number };
        if (m.type === "load" || m.type === "edit") w.__rev = m.rev;
        w.__port.postMessage(JSON.stringify(m));
    }, message);
}

/**
 * The messages received so far, and clears them in the same step. Reading
 * with [received] and then calling [clearReceived] loses any message that
 * arrives between the two calls.
 */
export async function takeReceived(page: Page): Promise<PageMessage[]> {
    return page.evaluate(() => {
        const w = window as unknown as { __received: PageMessage[] };
        const taken = w.__received;
        w.__received = [];
        return taken;
    });
}

export async function received(page: Page): Promise<PageMessage[]> {
    return page.evaluate(() => (window as unknown as { __received: PageMessage[] }).__received);
}

export async function clearReceived(page: Page): Promise<void> {
    await page.evaluate(() => ((window as unknown as { __received: unknown[] }).__received = []));
}

export async function waitForMessage(
    page: Page,
    match: (m: PageMessage) => boolean,
    timeout = 15_000,
): Promise<PageMessage> {
    const start = Date.now();
    for (;;) {
        const found = (await received(page)).find(match);
        if (found) return found;
        if (Date.now() - start > timeout) throw new Error("Timed out waiting for a page message");
        await page.waitForTimeout(25);
    }
}

/** Loads Markdown and waits until the first render has reached the page. */
export async function loadMarkdown(page: Page, text: string, rev = 1): Promise<void> {
    await clearReceived(page);
    await send(page, { type: "load", rev, text });
    await waitForMessage(page, (m) => m.type === "rendered");
    await page.waitForFunction(() => document.querySelectorAll("section.sec.mounted").length > 0);
}

export function corpus(name: string): string {
    return readFileSync(resolve(__dirname, "../corpus", name), "utf8");
}

/** Errors thrown in the page (uncaught exceptions). */
export function pageErrors(page: Page): string[] {
    return (page as Page & { errors?: string[] }).errors ?? [];
}

/** Waits until every queued diagram has been drawn or has failed. */
export async function diagramsSettled(page: Page): Promise<void> {
    await page.waitForFunction(
        () =>
            document.querySelectorAll(
                'pre.diagram-source:not([data-state="drawn"]):not([data-state="failed"]):not([data-state="deferred"])',
            ).length === 0,
        undefined,
        { timeout: 30_000 },
    );
}
