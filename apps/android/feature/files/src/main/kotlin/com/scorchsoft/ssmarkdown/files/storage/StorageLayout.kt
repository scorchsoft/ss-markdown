package com.scorchsoft.ssmarkdown.files.storage

import java.io.File

/**
 * Where everything the app keeps lives, decided in one place so the
 * Storage screen's promise holds (ux-spec "Explaining Android's buttons"):
 * Android's *Clear cache* removes only the rebuildable preview cache,
 * because nothing else is in `cacheDir`.
 *
 * Recovery data (journals and base copies) goes in `noBackupFilesDir`:
 * it describes this device's open buffers and must never be restored by
 * Auto Backup onto another phone's files.
 *
 * @param filesDir `Context.getFilesDir()`.
 * @param noBackupFilesDir `Context.getNoBackupFilesDir()`.
 * @param cacheDir `Context.getCacheDir()`.
 */
class StorageLayout(
    filesDir: File,
    noBackupFilesDir: File,
    cacheDir: File,
) {
    /** Version history (written by core/history). */
    val history: File = File(filesDir, "history")

    /** Untitled drafts. */
    val drafts: File = File(filesDir, "drafts")

    /** Downloaded Google Fonts. */
    val fonts: File = File(filesDir, "fonts")

    /** Recovery journals. */
    val journals: File = File(noBackupFilesDir, "journal")

    /** Base copies: the backup and merge base of each open document. */
    val baseCopies: File = File(noBackupFilesDir, "base")

    /** Diagram and preview cache: safe to lose. */
    val previewCache: File = File(cacheDir, "preview")
}
