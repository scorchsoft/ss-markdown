package com.scorchsoft.ssmarkdown.folder

import com.scorchsoft.ssmarkdown.core.ui.SSDarkColors
import com.scorchsoft.ssmarkdown.core.ui.SSLightColors
import com.scorchsoft.ssmarkdown.editor.chrome.Tone
import com.scorchsoft.ssmarkdown.editor.chrome.colorsIn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The folder panel's row tags speak the app's colour language
 * (quality-bar §3): "Unsaved: N" needs you, so it is red; amber means
 * "changed in another app" and only that.
 */
class FolderTagTest {
    @Test
    fun `unsaved is needs-you red and changed outside is caution amber`() {
        assertEquals(Tone.Error, FolderTag.Unsaved.tone)
        assertEquals(Tone.Caution, FolderTag.ChangedOutside.tone)
        for (c in listOf(SSLightColors, SSDarkColors)) {
            // The pair the contrast test already holds to AA: text-safe red on its own tint.
            assertEquals(c.needsYouText to c.needsYouBg, Tone.Error.colorsIn(c))
            assertEquals(c.caution to c.cautionBg, Tone.Caution.colorsIn(c))
        }
    }
}
