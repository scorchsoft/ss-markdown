package com.scorchsoft.ssmarkdown.fonts.bundled

import com.scorchsoft.ssmarkdown.fonts.FontCategory

/**
 * One file of a bundled family. Every bundled file is a variable font
 * covering [weights], so a family needs at most an upright and an italic.
 *
 * @property file the name under [BundledFonts.ASSET_DIR].
 * @property italic true for the italic file.
 * @property weights the `wght` axis range.
 */
data class BundledFile(
    val file: String,
    val italic: Boolean,
    val weights: IntRange,
)

/**
 * A family that ships inside the APK, so the built-in themes and the code
 * font never wait for a download.
 *
 * @property family the family name themes use.
 * @property category its picker category and fallback.
 * @property files its upright and (usually) italic files.
 * @property licence the OFL text under `fonts/licences/`, for the
 *   Open-source licences screen.
 */
data class BundledFont(
    val family: String,
    val category: FontCategory,
    val files: List<BundledFile>,
    val licence: String,
)

/**
 * The bundled families. This list is the source of truth;
 * `tools/fetch-fonts.sh` pins where each file comes from and writes
 * `MANIFEST.tsv`, and `BundledFontsTest` fails if the two disagree.
 *
 * Six are the brief's set; Atkinson Hyperlegible Next backs the Contrast
 * theme (designed for low-vision readers), and Fira Code is the code font
 * the ux-spec theme example names.
 */
object BundledFonts {
    /** Where the files live in the APK's assets. */
    const val ASSET_DIR: String = "fonts/bundled"

    /** Where the OFL texts live in the APK's assets. */
    const val LICENCE_DIR: String = "fonts/licences"

    /** The generated manifest: file names, sizes and SHA-256 of what was bundled. */
    const val MANIFEST: String = "$ASSET_DIR/MANIFEST.tsv"

    /** The UI and Clean theme font, and the last fallback for anything missing. */
    val INTER: BundledFont = family("Inter", FontCategory.SANS, "inter", 100..900, "inter")

    /** The code font. */
    val JETBRAINS_MONO: BundledFont =
        family("JetBrains Mono", FontCategory.MONO, "jetbrains-mono", 100..800, "jetbrainsmono")

    /** The serif fallback. */
    val SOURCE_SERIF_4: BundledFont =
        family("Source Serif 4", FontCategory.SERIF, "source-serif-4", 200..900, "sourceserif4")

    /** The Reader theme's face. */
    val LITERATA: BundledFont = family("Literata", FontCategory.SERIF, "literata", 200..900, "literata")

    /** A warm serif; shipped unmodified (Reserved Font Name "Lora"). */
    val LORA: BundledFont = family("Lora", FontCategory.SERIF, "lora", 400..700, "lora")

    /** The Compact theme's face; shipped unmodified (Reserved Font Name "Plex"). */
    val IBM_PLEX_SANS: BundledFont =
        family("IBM Plex Sans", FontCategory.SANS, "ibm-plex-sans", 100..700, "ibmplexsans")

    /** The Contrast theme's face. */
    val ATKINSON_HYPERLEGIBLE_NEXT: BundledFont =
        family(
            "Atkinson Hyperlegible Next",
            FontCategory.SANS,
            "atkinson-hyperlegible-next",
            200..800,
            "atkinsonhyperlegiblenext",
        )

    /** A ligature code font; upright only, as upstream publishes it. */
    val FIRA_CODE: BundledFont =
        BundledFont(
            family = "Fira Code",
            category = FontCategory.MONO,
            files = listOf(BundledFile("fira-code.ttf", italic = false, weights = 300..700)),
            licence = "$LICENCE_DIR/firacode-OFL.txt",
        )

    /** Every bundled family, sans first, as the picker lists them under "On this phone". */
    val ALL: List<BundledFont> =
        listOf(
            INTER,
            IBM_PLEX_SANS,
            ATKINSON_HYPERLEGIBLE_NEXT,
            SOURCE_SERIF_4,
            LITERATA,
            LORA,
            JETBRAINS_MONO,
            FIRA_CODE,
        )

    /** The bundled family named [family] (case-insensitive), or null. */
    fun byFamily(family: String): BundledFont? = ALL.firstOrNull { it.family.equals(family.trim(), ignoreCase = true) }

    /**
     * What to show while [category]'s font is missing: the same kind of
     * face, so a serif theme stays serif while Lora downloads.
     */
    fun fallbackFor(category: FontCategory?): BundledFont =
        when (category) {
            FontCategory.SERIF -> SOURCE_SERIF_4
            FontCategory.MONO -> JETBRAINS_MONO
            else -> INTER
        }

    private fun family(
        family: String,
        category: FontCategory,
        stem: String,
        weights: IntRange,
        licenceDir: String,
    ): BundledFont =
        BundledFont(
            family = family,
            category = category,
            files =
                listOf(
                    BundledFile("$stem.ttf", italic = false, weights = weights),
                    BundledFile("$stem-italic.ttf", italic = true, weights = weights),
                ),
            licence = "$LICENCE_DIR/$licenceDir-OFL.txt",
        )
}
