package com.scorchsoft.ssmarkdown.platform

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.fixtures.FileProviderCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** SEC-1 and SEC-8: names and sizes from outside the app are never trusted. */
@RunWith(RobolectricTestRunner::class)
class PlatformActionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actions = PlatformActions(context, Dispatchers.Unconfined)

    @Before
    fun freshFileProvider() = FileProviderCache.reset()

    @Test
    fun `share as file keeps a hostile display name inside cache-shared (SEC-1)`() {
        val target = File(context.filesDir, "settings.xml")
        runBlocking { actions.shareAsFile("../../files/settings.xml", "attacker text") }
        assertFalse("nothing was written outside cache/shared", target.exists())
        val shared = File(context.cacheDir, "shared").listFiles().orEmpty()
        assertEquals(1, shared.size)
        assertEquals("attacker text", shared.single().readText())
        val chooser = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser?.action)
    }

    @Test
    fun `share as file with an unshareable name does nothing rather than crash`() {
        runBlocking { actions.shareAsFile("..", "x") }
        runBlocking { actions.shareAsFile("a/b\u0000c", "y") }
        File(context.cacheDir, "shared").listFiles().orEmpty().forEach { assertTrue(it.name, !it.name.contains("/")) }
    }

    @Test
    fun `bounded copies stop at the limit (SEC-8)`() {
        val out = ByteArrayOutputStream()
        assertTrue(BoundedRead.copy(ByteArrayInputStream(ByteArray(100)), out, 100))
        assertEquals(100, out.size())
        assertFalse(BoundedRead.copy(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), 100))
        assertNull(BoundedRead.bytes(ByteArrayInputStream(ByteArray(11)), 10))
    }
}
