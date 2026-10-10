package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.FiioCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The .txt files of squig.link, graph.hangout.audio and AutoEQ in, Contour's own text out. */
class TextFilesTest {
    private fun res(name: String): String =
        checkNotNull(javaClass.getResource("/txt/$name")) { "missing test file $name" }.readText()

    // squig.link's own import regex (graphtool.js), run over every line Contour writes.
    private val squigImport = Regex("""Filter\s*\d+:\s*(\S+)\s*(\S+)\s*Fc\s*(\S+)\s*Hz\s*Gain\s*(\S+)\s*dB(\s*Q\s*(\S+))?""")

    private fun band(i: Int, type: FilterType, f: Double, g: Double, q: Double, on: Boolean = true) = Band("b$i", type, f, g, q, on)

    @Test fun `a file of only 0 dB filters keeps one and moves it into range, never touching a filter with gain`() {
        val flat = ApoText.parseImport("Preamp: -3 dB\nFilter 1: ON PK Fc 0 Hz Gain 0 dB Q 0\nFilter 2: ON PK Fc 0 Hz Gain 0 dB Q 0\n")
        assertEquals(1, flat.bands.size)
        assertEquals(20.0, flat.bands[0].freqHz)
        assertEquals(0.1, flat.bands[0].q)
        assertEquals(0.0, flat.bands[0].gainDb)
        val single = ApoText.parseImport("Filter 1: ON PK Fc 30000 Hz Gain 0 dB Q 50\n")
        assertEquals(listOf(20000.0, 10.0), listOf(single.bands[0].freqHz, single.bands[0].q))
        val fine = ApoText.parseImport("Filter 1: ON PK Fc 1000 Hz Gain 0 dB Q 1.5\n")
        assertEquals(listOf(1000.0, 1.5), listOf(fine.bands[0].freqHz, fine.bands[0].q))
        // a filter with gain keeps its values, and the 0 dB zero filter is dropped as before
        val mixed = ApoText.parseImport("Filter 1: ON PK Fc 100 Hz Gain 2 dB Q 1\nFilter 2: ON PK Fc 0 Hz Gain 0 dB Q 0\n")
        assertEquals(1, mixed.bands.size)
        assertEquals(listOf(100.0, 2.0, 1.0), listOf(mixed.bands[0].freqHz, mixed.bands[0].gainDb, mixed.bands[0].q))
    }

    // ---- squig.link two-channel export -----------------------------------------------------------------------

    @Test fun `squig two-channel file imports the left block only and says so`() {
        val eq = ApoText.parseImport(res("squig-two-channel.txt"))
        assertEquals(5, eq.bands.size)
        assertEquals(-4.2, eq.preampDb)
        assertEquals(105.0, eq.bands[0].freqHz)
        assertEquals(FilterType.LOW_SHELF, eq.bands[1].type)
        assertFalse(eq.bands[3].enabled, "OFF stays a disabled band")
        assertEquals(listOf("left channel imported (file has separate L/R)"), eq.notes)
        // ids are unique even though the lines came from one block
        assertEquals(5, eq.bands.map { it.id }.toSet().size)
    }

    @Test fun `squig two-channel file with identical blocks has no note and no doubled filters`() {
        val eq = ApoText.parseImport(res("squig-two-channel-identical.txt"))
        assertEquals(2, eq.bands.size)
        assertEquals(-3.0, eq.preampDb)
        assertTrue(eq.notes.isEmpty())
    }

    @Test fun `a block for all channels wins over L and R`() {
        val text = "Channel: L\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\nChannel: R\nFilter 1: ON PK Fc 200 Hz Gain 2 dB Q 1\n" +
            "Channel: all\nPreamp: -2 dB\nFilter 1: ON PK Fc 300 Hz Gain 3 dB Q 1\n"
        val eq = ApoText.parseImport(text)
        assertEquals(listOf(300.0), eq.bands.map { it.freqHz })
        assertEquals(-2.0, eq.preampDb)
        assertEquals(1, eq.notes.size)
        val lr = ApoText.parseImport("Channel: L R\nPreamp: -1 dB\nFilter 1: ON PK Fc 300 Hz Gain 3 dB Q 1\nChannel: L\nFilter 1: ON PK Fc 90 Hz Gain 1 dB Q 1\n")
        assertEquals(listOf(300.0), lr.bands.map { it.freqHz })
    }

    @Test fun `a preamp before the first Channel header is used when the block has none`() {
        val eq = ApoText.parseImport("Preamp: -7 dB\nChannel: L\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\nChannel: R\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\n")
        assertEquals(-7.0, eq.preampDb)
        assertEquals(1, eq.bands.size)
        assertTrue(eq.notes.isEmpty(), "identical L and R: no note")
    }

    @Test fun `numbered channels map 1 to left 2 to right and 1 2 to both`() {
        val lr = ApoText.parseImport("Channel: 1\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\nChannel: 2\nFilter 1: ON PK Fc 200 Hz Gain 2 dB Q 1\n")
        assertEquals(listOf(100.0), lr.bands.map { it.freqHz })
        assertEquals(listOf("left channel imported (file has separate L/R)"), lr.notes)
        val both = ApoText.parseImport("Channel: 1 2\nPreamp: -2 dB\nFilter 1: ON PK Fc 300 Hz Gain 3 dB Q 1\nChannel: 1\nFilter 1: ON PK Fc 90 Hz Gain 1 dB Q 1\n")
        assertEquals(listOf(300.0), both.bands.map { it.freqHz })
        assertEquals(-2.0, both.preampDb)
        val right = ApoText.parseImport("Channel: 2\nFilter 1: ON PK Fc 500 Hz Gain -2 dB Q 1\n")
        assertEquals(listOf(500.0), right.bands.map { it.freqHz })
    }

    @Test fun `other channel blocks are ignored when an L R or both block exists, else imported as both with a note`() {
        val withLeft = ApoText.parseImport("Channel: C\nFilter 1: ON PK Fc 700 Hz Gain 5 dB Q 1\nChannel: L\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\n")
        assertEquals(listOf(100.0), withLeft.bands.map { it.freqHz })
        assertTrue(withLeft.notes.isEmpty())
        val withGlobal = ApoText.parseImport("Filter 1: ON PK Fc 120 Hz Gain 1 dB Q 1\nChannel: LFE\nFilter 1: ON PK Fc 40 Hz Gain 4 dB Q 1\n")
        assertEquals(listOf(120.0), withGlobal.bands.map { it.freqHz })
        val onlyOther = ApoText.parseImport("Preamp: -1 dB\nChannel: C\nFilter 1: ON PK Fc 700 Hz Gain 5 dB Q 1\nChannel: SL SR\nFilter 1: ON PK Fc 900 Hz Gain -3 dB Q 2\n")
        assertEquals(listOf(700.0, 900.0), onlyOther.bands.map { it.freqHz })
        assertEquals(-1.0, onlyOther.preampDb)
        assertEquals(1, onlyOther.notes.size)
        assertTrue(onlyOther.notes[0].contains("C") && onlyOther.notes[0].contains("no L or R"), onlyOther.notes[0])
        assertEquals(2, onlyOther.bands.map { it.id }.toSet().size)
    }

    @Test fun `a file with only a right block imports it`() {
        val eq = ApoText.parseImport("Channel: R\nPreamp: -3 dB\nFilter 1: ON PK Fc 500 Hz Gain -2 dB Q 1\n")
        assertEquals(listOf(500.0), eq.bands.map { it.freqHz })
        assertEquals(-3.0, eq.preampDb)
    }

    @Test fun `a file without Channel lines is read as before`() {
        val text = res("autoeq-parametric.txt")
        val eq = ApoText.parse(text)
        assertEquals(5, eq.bands.size)
        assertEquals(-6.3, eq.preampDb)
        assertTrue(eq.notes.isEmpty())
        assertEquals(eq.copy(notes = emptyList()), ApoText.parseImport(text))
    }

    // ---- graph.hangout.audio: ten slots, unused ones at 0 dB -----------------------------------------------------

    @Test fun `hangout ten-filter file drops the zero-gain slots and counts them`() {
        val eq = ApoText.parseImport(res("hangout-10-filters.txt"))
        assertEquals(listOf(62.0, 250.0, 1000.0, 2000.0, 4000.0), eq.bands.map { it.freqHz })
        assertEquals(listOf("5 empty filters skipped"), eq.notes)
        assertEquals(-2.6, eq.preampDb)
        assertEquals(5, eq.bands.map { it.id }.toSet().size)
        // the plain parser keeps all ten
        assertEquals(10, ApoText.parse(res("hangout-10-filters.txt")).bands.size)
    }

    @Test fun `one empty filter is singular and a disabled filter with gain is kept`() {
        val eq = ApoText.parseImport(
            "Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1\nFilter 2: OFF PK Fc 200 Hz Gain -3 dB Q 1\nFilter 3: ON LSC Fc 90 Hz Gain 2 dB Q 0.7\n",
        )
        assertEquals(listOf(200.0, 90.0), eq.bands.map { it.freqHz })
        assertFalse(eq.bands[0].enabled)
        assertEquals(listOf("1 empty filter skipped"), eq.notes)
    }

    @Test fun `zero-gain shelves go too but pass filters stay`() {
        val eq = ApoText.parseImport(
            "Filter 1: ON LSC Fc 90 Hz Gain 0 dB Q 0.7\nFilter 2: ON HSC Fc 9000 Hz Gain 0.0 dB Q 0.7\n" +
                "Filter 3: ON HP Fc 20 Hz Q 0.7\nFilter 4: ON PK Fc 1000 Hz Gain 1.5 dB Q 1\n",
        )
        assertEquals(listOf(FilterType.HIGH_PASS, FilterType.PEAK), eq.bands.map { it.type })
        assertEquals(listOf("2 empty filters skipped"), eq.notes)
    }

    @Test fun `a file of only empty filters keeps the first so a flat export imports as flat`() {
        val flat = ApoText.format(listOf(band(1, FilterType.PEAK, 1000.0, 0.0, 0.71)), 0.0)
        val eq = ApoText.parseImport(flat)
        assertEquals(1, eq.bands.size)
        assertEquals(0.0, eq.bands[0].gainDb)
        val two = ApoText.parseImport("Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1\nFilter 2: ON PK Fc 200 Hz Gain 0 dB Q 1\n")
        assertEquals(listOf(100.0), two.bands.map { it.freqHz })
        assertEquals(listOf("1 empty filter skipped"), two.notes)
    }

    @Test fun `text with no filters is empty`() {
        assertTrue(ApoText.parseImport("hello\nworld").bands.isEmpty())
        assertTrue(ApoText.parseImport("").bands.isEmpty())
    }

    // ---- AutoEQ ParametricEQ.txt, decimal commas, BOM-free CRLF ---------------------------------------------------

    @Test fun `AutoEQ ParametricEQ txt reads exactly`() {
        val eq = ApoText.parseImport(res("autoeq-parametric.txt"))
        assertEquals(
            listOf(
                Triple(FilterType.LOW_SHELF, 105.0, 6.1), Triple(FilterType.PEAK, 21.0, 5.5), Triple(FilterType.PEAK, 2400.0, -4.4),
                Triple(FilterType.PEAK, 5600.0, 2.7), Triple(FilterType.HIGH_SHELF, 10000.0, -2.0),
            ),
            eq.bands.map { Triple(it.type, it.freqHz, it.gainDb) },
        )
        assertEquals(0.5, eq.bands[1].q)
        assertTrue(eq.notes.isEmpty())
    }

    @Test fun `decimal commas and CRLF still parse`() {
        val eq = ApoText.parseImport("Preamp: -3,5 dB\r\nFilter 1: ON PK Fc 1000 Hz Gain 2,5 dB Q 1,2\r\n")
        assertEquals(-3.5, eq.preampDb)
        assertEquals(2.5, eq.bands.single().gainDb)
        assertEquals(1.2, eq.bands.single().q)
    }

    // ---- export ---------------------------------------------------------------------------------------------------

    @Test fun `squig import regex matches every PK LSC and HSC line Contour writes`() {
        val bands = listOf(
            band(1, FilterType.PEAK, 105.0, -3.5, 0.7),
            band(2, FilterType.LOW_SHELF, 80.0, 2.25, 0.71),
            band(3, FilterType.HIGH_SHELF, 10000.0, -1.5, 0.707),
            band(4, FilterType.PEAK, 8200.5, 0.0, 3.125, on = false),
            band(5, FilterType.PEAK, 20000.0, 12.0, 10.0),
        )
        for (text in listOf(ApoText.format(bands, -6.1234), ApoText.fileText(Profile("p", "P", bands = bands, createdAt = 0, updatedAt = 0)))) {
            val filters = text.lines().filter { it.startsWith("Filter") }
            assertEquals(5, filters.size)
            for (line in filters) {
                val m = checkNotNull(squigImport.find(line)) { "squig regex must match: $line" }
                assertEquals(0, m.range.first, line)
                assertTrue(m.groupValues[6].isNotEmpty(), "Q captured: $line")
            }
            assertTrue(text.startsWith("Preamp: "), text)
        }
    }

    @Test fun `file text is CRLF and parses back to the same bands`() {
        val bands = listOf(band(1, FilterType.PEAK, 105.0, -3.5, 0.7), band(2, FilterType.HIGH_SHELF, 9000.0, 2.0, 0.71, on = false))
        val p = Profile("p", "P", bands = bands, preampDb = -4.5, createdAt = 0, updatedAt = 0)
        val file = ApoText.fileText(p)
        assertTrue(file.contains("\r\n"))
        assertFalse(file.replace("\r\n", "").contains('\n'), "no bare LF in the file")
        val back = ApoText.parse(file)
        assertEquals(bands.map { it.copy(id = "") }, back.bands.map { it.copy(id = "") })
        assertEquals(-4.5, back.preampDb)
        // the same text goes to SHARE, with LF
        assertEquals(ApoText.formatProfile(p), file.replace("\r\n", "\n"))
    }

    @Test fun `a KA15-targeted profile exports its stored Q and preamp, never the device values`() {
        // The KA15 stores shelf Q x sqrt(2) and preamp + 12 dB in its registers; the profile and its text must not.
        val ka15 = FiioCatalog.KA15
        assertTrue(ka15.shelfQScale != 1.0 && ka15.preampOffsetDb != 0.0, "the correction exists, so this test can catch a leak")
        val bands = listOf(
            band(1, FilterType.LOW_SHELF, 105.0, 4.0, 0.7),
            band(2, FilterType.PEAK, 3000.0, -2.0, 1.4),
            band(3, FilterType.HIGH_SHELF, 9000.0, -3.0, 0.8),
        )
        val p = Profile("p", "KA", bands = bands, preampDb = -5.0, createdAt = 0, updatedAt = 0)
        assertEquals(emptyList(), DeviceTarget.KA15.issues(p), "the profile is valid for the KA15")
        for (text in listOf(ApoText.formatProfile(p), ApoText.fileText(p).replace("\r\n", "\n"))) {
            assertEquals(
                "Preamp: -5.0 dB\n" +
                    "Filter 1: ON LSC Fc 105 Hz Gain 4.0 dB Q 0.70\n" +
                    "Filter 2: ON PK Fc 3000 Hz Gain -2.0 dB Q 1.40\n" +
                    "Filter 3: ON HSC Fc 9000 Hz Gain -3.0 dB Q 0.80\n",
                text,
            )
        }
        // AUTO preamp: the auto value of the user's curve, again without any register offset
        val auto = p.copy(preampDb = null)
        assertEquals("Preamp: ${"%.1f".format(java.util.Locale.ROOT, Preamp.auto(bands).toDouble())} dB", ApoText.formatProfile(auto).lines().first())
    }

    // ---- file names -------------------------------------------------------------------------------------------------

    @Test fun `profile name from a squig file name`() {
        assertEquals("MOONDROP DUSK", ApoText.nameFromFile("Moondrop Dusk Filters.txt", 18))
        assertEquals("MOONDROP DUSK", ApoText.nameFromFile("Moondrop Dusk filters.TXT", 18))
        assertEquals("KIWI EARS QUINTET", ApoText.nameFromFile("Kiwi Ears Quintet.txt", 18))
        assertEquals("A VERY LONG HEADPHON", ApoText.nameFromFile("a very long headphone name Filters.txt", 20))
        assertEquals("PARAMETRICEQ", ApoText.nameFromFile("primary:Download/ParametricEQ.txt", 18))
        assertEquals("X", ApoText.nameFromFile("content/X.txt", 18))
        assertNull(ApoText.nameFromFile(".txt", 18))
        assertNull(ApoText.nameFromFile("  .txt", 18))
        assertNull(ApoText.nameFromFile(null, 18))
    }

    @Test fun `suggested file name follows squig and avoids forbidden characters`() {
        assertEquals("MOONDROP DUSK Filters.txt", ApoText.fileNameFor("MOONDROP DUSK"))
        assertEquals("A B Filters.txt", ApoText.fileNameFor("A/B"))
        assertEquals("Profile Filters.txt", ApoText.fileNameFor("  "))
        // and it names the same profile again
        assertEquals("MOONDROP DUSK", ApoText.nameFromFile(ApoText.fileNameFor("MOONDROP DUSK"), 18))
    }
}
