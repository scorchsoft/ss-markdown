package com.scorchsoft.ssmarkdown.fonts

import com.scorchsoft.ssmarkdown.fonts.bundled.BundledFont
import com.scorchsoft.ssmarkdown.fonts.catalogue.CatalogueEntry
import com.scorchsoft.ssmarkdown.fonts.store.InstalledFont

/**
 * Where a theme's font name leads, and so what the picker row says
 * (mockup FontPicker: "Built in", "Google Fonts · cached", "Get").
 */
sealed interface FontResolution {
    /** The family asked about. */
    val family: String

    /** Usable right now, with no network. */
    sealed interface Available : FontResolution

    /**
     * Ships in the APK.
     *
     * @property font the bundled family.
     */
    data class Bundled(
        val font: BundledFont,
    ) : Available {
        override val family: String get() = font.family
    }

    /**
     * Downloaded or imported earlier.
     *
     * @property font the installed family.
     */
    data class Installed(
        val font: InstalledFont,
    ) : Available {
        override val family: String get() = font.family
    }

    /** Not usable yet; text falls back to [fallback] meanwhile. */
    sealed interface Missing : FontResolution {
        /** The bundled family used until this one is available. */
        val fallback: BundledFont
    }

    /**
     * On Google Fonts; one tap to download.
     *
     * @property entry the catalogue entry, with its size estimate.
     */
    data class Downloadable(
        val entry: CatalogueEntry,
        override val fallback: BundledFont,
    ) : Missing {
        override val family: String get() = entry.family
    }

    /** Not bundled, installed or in the catalogue: a typo, or an import that was deleted. */
    data class Unknown(
        override val family: String,
        override val fallback: BundledFont,
    ) : Missing
}
