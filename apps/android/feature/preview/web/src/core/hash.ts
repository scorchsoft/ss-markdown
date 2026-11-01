/**
 * Content hashing for section keys and diagram cache keys.
 *
 * Two independent 32-bit hashes (FNV-1a and a djb2 variant) plus the length
 * give a 64-bit+ key. Collisions only cost a stale render of one section, never
 * data, so a cryptographic hash would be wasted work on every keystroke.
 */
export function contentHash(text: string): string {
    let fnv = 0x811c9dc5;
    let djb = 5381;
    for (let i = 0; i < text.length; i++) {
        const c = text.charCodeAt(i);
        fnv ^= c;
        fnv = Math.imul(fnv, 0x01000193);
        djb = (Math.imul(djb, 33) ^ c) | 0;
    }
    return `${(fnv >>> 0).toString(36)}.${(djb >>> 0).toString(36)}.${text.length.toString(36)}`;
}
