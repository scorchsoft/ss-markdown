package com.scorchsoft.ssmarkdown.theme.contrast

import com.scorchsoft.ssmarkdown.theme.ThemeColor
import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastTest {
    private fun c(hex: String) = requireNotNull(ThemeColor.parse(hex))

    @Test
    fun `black on white is 21 to 1 and identical colours are 1 to 1`() {
        assertEquals(21.0, Contrast.ratio(ThemeColor.BLACK, ThemeColor.WHITE), 1e-9)
        assertEquals(1.0, Contrast.ratio(c("#777777"), c("#777777")), 1e-9)
    }

    @Test
    fun `matches published reference values`() {
        // The classic AA boundary: #767676 passes on white, #777777 does not.
        assertEquals(4.54, Contrast.ratio(c("#767676"), ThemeColor.WHITE), 0.005)
        assertEquals(4.48, Contrast.ratio(c("#777777"), ThemeColor.WHITE), 0.005)
        // WebAIM: #0000FF on white is 8.59:1; #FF0000 on white is 4.0:1.
        assertEquals(8.59, Contrast.ratio(c("#0000FF"), ThemeColor.WHITE), 0.005)
        assertEquals(4.0, Contrast.ratio(c("#FF0000"), ThemeColor.WHITE), 0.005)
    }

    @Test
    fun `ratio is symmetric`() {
        assertEquals(Contrast.ratio(c("#1D5DBF"), c("#EAF1FC")), Contrast.ratio(c("#EAF1FC"), c("#1D5DBF")), 0.0)
    }

    @Test
    fun `luminance endpoints`() {
        assertEquals(0.0, Contrast.relativeLuminance(ThemeColor.BLACK), 0.0)
        assertEquals(1.0, Contrast.relativeLuminance(ThemeColor.WHITE), 1e-12)
    }

    @Test
    fun `levels`() {
        assertEquals(4.5, WcagLevel.AA.required(large = false), 0.0)
        assertEquals(3.0, WcagLevel.AA.required(large = true), 0.0)
        assertEquals(7.0, WcagLevel.AAA.required(large = false), 0.0)
    }
}
