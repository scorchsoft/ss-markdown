package com.scorchsoft.ssmarkdown.editor

import android.view.KeyCharacterMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** edge-cases "Dead key on a hardware keyboard": an accent key composes, never crashes. */
@RunWith(RobolectricTestRunner::class)
class DeadKeysTest {
    private val keys = DeadKeys()
    private val acute = KeyCharacterMap.COMBINING_ACCENT or ACUTE

    @Test
    fun `an accent then a letter types the accented letter`() {
        assertNull(keys.typed(acute))
        assertEquals("\u00e9", keys.typed('e'.code))
        assertEquals("e", keys.typed('e'.code))
    }

    @Test
    fun `an accent that cannot combine types both`() {
        assertNull(keys.typed(acute))
        assertEquals("\u00b4q", keys.typed('q'.code))
    }

    @Test
    fun `two accents in a row type the first`() {
        assertNull(keys.typed(acute))
        assertEquals("\u00b4", keys.typed(acute))
        assertEquals("\u00e1", keys.typed('a'.code))
    }

    @Test
    fun `an ordinary key types itself, astral planes included`() {
        assertEquals("x", keys.typed('x'.code))
        assertEquals("\uD83D\uDE00", keys.typed(0x1F600))
    }

    private companion object {
        /** What `getUnicodeChar` reports under the accent flag: the spacing accent ´. */
        const val ACUTE = 0x00B4
    }
}
