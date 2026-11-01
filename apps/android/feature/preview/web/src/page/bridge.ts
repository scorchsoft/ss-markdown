/**
 * The page end of the WebMessagePort bridge.
 *
 * Kotlin posts one message, "ssmd:init", carrying a MessagePort. The page
 * accepts exactly one port, ever, so nothing that runs later can swap the
 * channel. No JavaScript interface is exposed to page content: the only way
 * in or out is this port.
 */
import { PROTOCOL_VERSION, type HostMessage, type PageMessage } from "../protocol";

export const INIT_MESSAGE = "ssmd:init";

export class Bridge {
    private port: MessagePort | null = null;
    private readonly outbox: PageMessage[] = [];

    constructor(private readonly onMessage: (message: HostMessage) => void) {
        const accept = (event: MessageEvent): void => {
            if (this.port || event.data !== INIT_MESSAGE || event.ports.length !== 1) return;
            const port = event.ports[0];
            if (!port) return;
            window.removeEventListener("message", accept);
            this.port = port;
            port.onmessage = (e: MessageEvent) => this.receive(e.data);
            port.start();
            this.post({ type: "ready", version: PROTOCOL_VERSION });
            for (const message of this.outbox.splice(0)) port.postMessage(JSON.stringify(message));
        };
        window.addEventListener("message", accept);
    }

    post(message: PageMessage): void {
        if (this.port) this.port.postMessage(JSON.stringify(message));
        else this.outbox.push(message);
    }

    private receive(data: unknown): void {
        if (typeof data !== "string") return;
        let parsed: unknown;
        try {
            parsed = JSON.parse(data);
        } catch {
            this.post({ type: "error", message: "Unreadable message from the app." });
            return;
        }
        if (isHostMessage(parsed)) this.onMessage(parsed);
        else this.post({ type: "error", message: "Unknown message from the app." });
    }
}

const HOST_TYPES = new Set<string>([
    "load",
    "edit",
    "ack",
    "format",
    "select",
    "theme",
    "settings",
    "scrollToLine",
    "tableNav",
    "tableOp",
    "endEditing",
    "exportImage",
    "preparePrint",
    "finishPrint",
]);

function isHostMessage(value: unknown): value is HostMessage {
    return typeof value === "object" && value !== null && HOST_TYPES.has(String((value as { type?: unknown }).type));
}
