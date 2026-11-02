package com.scorchsoft.ssmarkdown.platform

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The graph is built on the main thread from [AppDirs], which works the
 * paths out with no disk access; they must be the very directories the
 * platform hands out, or the app would write its data somewhere else.
 */
@RunWith(AndroidJUnit4::class)
class AppDirsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun thePathsAreThePlatformsOwn() {
        val dirs = AppDirs.of(context)
        assertEquals(context.filesDir.canonicalFile, dirs.files.canonicalFile)
        assertEquals(context.noBackupFilesDir.canonicalFile, dirs.noBackup.canonicalFile)
        assertEquals(context.cacheDir.canonicalFile, dirs.cache.canonicalFile)
    }

    @Test
    fun ensureCreatesThem() {
        val dirs = AppDirs.of(context)
        listOf(dirs.files, dirs.noBackup, dirs.cache).forEach(File::deleteRecursively)
        AppDirs.ensure(context)
        listOf(dirs.files, dirs.noBackup, dirs.cache).forEach { assertEquals(true, it.isDirectory) }
    }
}
