package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.files.document.ReadOnlyReason
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A file that cannot be opened, or cannot be written back in its own
 * encoding, says what happened, why, and what to do next (M2;
 * quality-bar §3), never only "That didn't work".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenProblemsTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun fixture() = EditorFixture(temp.root, dispatcher)

    private fun EditorFixture.bytes(
        name: String,
        bytes: ByteArray,
    ): String {
        File(files, name).writeBytes(bytes)
        return EditorFixture.SCHEME + name
    }

    @Test
    fun `a missing file says it was moved or deleted and offers to open another`() {
        val f = fixture()
        f.vm.openDocument(EditorFixture.SCHEME + "gone.md")
        val banner = f.vm.uiState.value.banner
        assertEquals(EditorBanner.OpenFailed(EditorFixture.SCHEME + "gone.md", OpenProblem.Missing), banner)
        assertNull("no generic toast as well", f.vm.uiState.value.message)
        assertTrue("an editor is still there to type in", f.vm.active != null)

        f.vm.onAction(EditorAction.OpenAnotherFile)
        assertEquals(EditorEvent.PickFileToOpen, f.events.last())
        assertNull(f.vm.uiState.value.banner)
    }

    @Test
    fun `try again reopens the same file once it can be read`() {
        val f = fixture()
        val uri = EditorFixture.SCHEME + "later.md"
        f.vm.openDocument(uri)
        assertTrue(f.vm.uiState.value.banner is EditorBanner.OpenFailed)

        f.file("later.md", "# Back\n")
        f.vm.onAction(EditorAction.RetryOpen)
        assertEquals(
            uri,
            f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
        assertEquals(
            "# Back\n",
            f.vm.active
                ?.text
                ?.text
                ?.toString(),
        )
        assertNull(f.vm.uiState.value.banner)
    }

    @Test
    fun `every provider failure maps to a problem with a way forward`() {
        assertEquals(OpenProblem.Missing, OpenProblem.of(ProviderResult.NotFound))
        assertEquals(
            OpenProblem.NoPermission,
            OpenProblem.of(ProviderResult.PermissionDenied),
        )
        assertEquals(OpenProblem.TimedOut, OpenProblem.of(ProviderResult.TimedOut))
        assertEquals(
            OpenProblem.Unreadable,
            OpenProblem.of(ProviderResult.Failed(java.io.IOException("x"))),
        )
        // Missing and no permission: pick the file again. Timed out and unreadable: try again.
        assertTrue(OpenProblem.Missing.picksAnother && OpenProblem.NoPermission.picksAnother)
        assertTrue(!OpenProblem.TimedOut.picksAnother && !OpenProblem.Unreadable.picksAnother)
    }

    @Test
    fun `a UTF-16 file opens editable and saves back in UTF-16`() {
        val f = fixture()
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val uri = f.bytes("wide.md", bom + "# Wide\r\n".toByteArray(Charsets.UTF_16LE))
        f.vm.openDocument(uri)
        assertNull(
            f.vm.active
                ?.open
                ?.readOnly,
        )
        assertNull(f.vm.uiState.value.banner)
        assertEquals(
            "# Wide\n",
            f.vm.active
                ?.text
                ?.text
                ?.toString(),
        )
        checkNotNull(f.vm.active).surface.apply(listOf(EditOp(7, "", "more\n")), TextSelection.caret(12))
        f.vm.onAction(EditorAction.SaveTapped)
        assertEquals(
            (bom + "# Wide\r\nmore\r\n".toByteArray(Charsets.UTF_16LE)).toList(),
            File(f.files, "wide.md").readBytes().toList(),
        )
    }

    @Test
    fun `a UTF-32 file opens read-only, says why and offers Save as`() {
        val f = fixture()
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)
        val uri = f.bytes("utf32.md", bom + "# Hi\n".toByteArray(charset("UTF-32LE")))
        f.vm.openDocument(uri)
        assertEquals(
            ReadOnlyReason.UnsupportedEncoding("UTF-32LE"),
            f.vm.active
                ?.open
                ?.readOnly,
        )
        assertEquals(
            "# Hi\n",
            f.vm.active
                ?.text
                ?.text
                ?.toString(),
        )
        assertEquals(EditorBanner.EncodingReadOnly("UTF-32LE", invalid = false), f.vm.uiState.value.banner)
    }

    @Test
    fun `invalid UTF-8 says so too, and opening a good file clears it`() {
        val f = fixture()
        f.vm.openDocument(f.bytes("bad.md", byteArrayOf(0x61, 0xC3.toByte(), 0x28)))
        assertEquals(EditorBanner.EncodingReadOnly("UTF-8", invalid = true), f.vm.uiState.value.banner)
        f.vm.openDocument(f.file("good.md", "fine\n"))
        assertNull(f.vm.uiState.value.banner)
    }
}
