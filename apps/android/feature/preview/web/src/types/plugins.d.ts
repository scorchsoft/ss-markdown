// markdown-it plugins that ship without type declarations.
// An import() type keeps this file a global declaration file (a top-level import would make it a module).
// eslint-disable-next-line @typescript-eslint/consistent-type-imports
type MdPlugin = (md: import("markdown-it").MarkdownIt, options?: Record<string, unknown>) => void;

declare module "markdown-it-abbr" {
    const plugin: MdPlugin;
    export default plugin;
}
declare module "markdown-it-deflist" {
    const plugin: MdPlugin;
    export default plugin;
}
declare module "markdown-it-footnote" {
    const plugin: MdPlugin;
    export default plugin;
}
declare module "markdown-it-emoji" {
    export const full: MdPlugin;
    export const light: MdPlugin;
    export const bare: MdPlugin;
}
declare module "markdown-it-mark" {
    const plugin: MdPlugin;
    export default plugin;
}
declare module "markdown-it-sub" {
    const plugin: MdPlugin;
    export default plugin;
}
declare module "markdown-it-sup" {
    const plugin: MdPlugin;
    export default plugin;
}
declare module "markdown-it-task-lists" {
    const plugin: MdPlugin;
    export default plugin;
}
