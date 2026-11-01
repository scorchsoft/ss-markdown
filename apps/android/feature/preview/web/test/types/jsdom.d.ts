/** The slice of jsdom's API the unit tests use (jsdom ships no types of its own). */
declare module "jsdom" {
    export class JSDOM {
        constructor(html?: string);
        readonly window: Window & typeof globalThis;
    }
}
