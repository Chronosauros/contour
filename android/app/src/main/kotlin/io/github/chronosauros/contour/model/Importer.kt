package io.github.chronosauros.contour.model

import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.EqByEarJson
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.Preamp
import io.github.chronosauros.contour.core.Profile
import io.github.chronosauros.contour.core.WalkPlay
import kotlin.math.roundToLong

internal fun round1(x: Double) = (x * 10).roundToLong() / 10.0
internal fun round2(x: Double) = (x * 100).roundToLong() / 100.0

/** Import rules: device ranges, supported types, the selected device slot count. Returns the bands and a one-line report. */
object Importer {
    /** [fitDevice] plus what the reader already did to the file (channel chosen, empty filters skipped), as one line. */
    fun fit(eq: ImportedEq, protocol: DeviceTarget = DeviceTarget.MICRO): Pair<ImportedEq, String?> {
        val (fitted, note) = fitDevice(eq, protocol)
        val report = (eq.notes + listOfNotNull(note)).joinToString("; ").ifEmpty { null }
        return fitted to report
    }

    private fun fitDevice(eq: ImportedEq, protocol: DeviceTarget): Pair<ImportedEq, String?> {
        // Micro/Max: the 1.3.x clamp-and-drop fit.
        if (protocol.usesWalkPlayRules) return fitWalkPlay(eq, protocol)
        // FiiO KA15: preserve all file intent (including fractional preamp); import never truncates, clamps,
        // quantizes or drops library data. What does not fit is reported and blocks HOLD TO SEND.
        // The one exception is a filter that does nothing (0 dB): the file import keeps those, so they must not be what blocks the send.
        val (settled, emptyNote) = settleEmpty(eq.bands, protocol)
        val issues = protocol.issues(Profile("import", "import", bands = settled, preampDb = eq.preampDb, createdAt = 0, updatedAt = 0)).takeIf { it.isNotEmpty() }
        val note = issues?.joinToString("; ")?.let { "Preserved unchanged; send blocked: $it" }
        return (if (settled == eq.bands) eq else eq.copy(bands = settled)) to listOfNotNull(emptyNote, note).joinToString("; ").ifEmpty { null }
    }

    /**
     * The file import keeps the filters at 0 dB (ApoText.parseImport), but a DAC has so many bands and so many types: for a native DAC (not the WalkPlay fit, which has its own rules) the
     * ones that do nothing are made to fit, because leaving them out or moving them changes no sound. A 0 dB filter of a type the DAC does not offer is left out; the others are moved into
     * the DAC's frequency and Q range; and when the file still has more filters than the DAC has bands, 0 dB ones are left out, last first, only as many as it takes ([ApoText.dropEmptyToFit]).
     * Filters with gain are never touched. Returns the bands and a note when any filter was left out.
     */
    private fun settleEmpty(bands: List<Band>, protocol: DeviceTarget): Pair<List<Band>, String?> {
        val caps = protocol.caps
        var left = 0
        val fitted = bands.mapNotNull { b ->
            when {
                !ApoText.doesNothing(b) -> b
                b.type !in caps.types -> { left++; null }
                else -> b.copy(freqHz = b.freqHz.coerceIn(caps.freqMinHz, caps.freqMaxHz), q = b.q.coerceIn(protocol.qRange(b.type)))
            }
        }
        val (fits, over) = ApoText.dropEmptyToFit(fitted, caps.bands)
        return fits to emptyNote(left + over)
    }

    private fun emptyNote(n: Int): String? = if (n == 0) null else "$n empty filter${if (n == 1) "" else "s"} skipped"

    /** Micro/Max rules: device ranges, supported types, the selected device slot count; the same rules for a writable Echo-A, held to its own caps. */
    private fun fitWalkPlay(eq: ImportedEq, protocol: DeviceTarget): Pair<ImportedEq, String?> {
        val caps = protocol.caps
        val notes = ArrayList<String>()
        var bands = eq.bands
        val unsupported = bands.count { it.type !in caps.types }
        if (unsupported > 0) {
            bands = bands.filter { it.type in caps.types }
            notes += "$unsupported unsupported filter${if (unsupported > 1) "s" else ""} dropped"
        }
        // The file import keeps the filters at 0 dB: they go first when the file has more filters than the DAC has bands.
        ApoText.dropEmptyToFit(bands, caps.bands).let { (fits, n) -> if (n > 0) { bands = fits; notes += emptyNote(n)!! } }
        if (bands.size > caps.bands) {
            notes += "${bands.size - caps.bands} band${if (bands.size - caps.bands > 1) "s" else ""} over ${caps.bands} dropped"
            bands = bands.take(caps.bands)
        }
        var clamped = 0
        bands = bands.map { b ->
            val f = Math.round(b.freqHz.coerceIn(caps.freqMinHz, caps.freqMaxHz)).toDouble()
            // down: boosts never grow. An emulated HIGH SHELF goes down to the preamp's grid, because its gain is folded into the preamp register (DeviceTarget.highShelfStepDb).
            val g = Preamp.floorTo(b.gainDb.coerceIn(caps.gainMinDb, caps.gainMaxDb), if (b.type == FilterType.HIGH_SHELF) protocol.highShelfStepDb ?: 0.1 else 0.1)
            val qRange = caps.qRange(b.type)
            // Q keeps the precision the file brought (0.707 stays 0.707); the family codec rounds it when it builds the register value.
            val q = b.q.coerceIn(qRange.start, qRange.endInclusive)
            if (f != b.freqHz || g != b.gainDb || q != b.q) clamped++
            b.copy(freqHz = f, gainDb = g, q = q)
        }
        if (clamped > 0) notes += "$clamped band${if (clamped > 1) "s" else ""} rounded or clamped to the device ranges"
        // Nothing is clamped and nothing rounds up: the preamp goes down to 0.1 dB (quieter, never louder); only when that
        // would flip the planner's verdict is the exact file value kept. A refused value is refused with its number.
        val pre = eq.preampDb?.let { protocol.fitImportedPreamp(bands, it) }
        if (pre != null && !protocol.preampFits(bands, pre)) {
            val shown = if (pre == Math.rint(pre) && Math.abs(pre) < 1e15) pre.toLong().toString() else pre.toString()
            notes += "preamp $shown dB is outside the device range and was kept; sending is blocked until it is replaced"
        }
        return ImportedEq(bands, pre) to notes.joinToString("; ").ifEmpty { null }
    }

    /** APO / AutoEQ / squig.link / hangout text, else EQ by Ear JSON; null when nothing parseable. */
    fun parse(text: String?): ImportedEq? {
        if (text.isNullOrBlank()) return null
        val apo = runCatching { ApoText.parseImport(text) }.getOrNull()
        if (apo != null && apo.bands.isNotEmpty()) return apo
        val j = runCatching { EqByEarJson.parse(text) }.getOrNull()
        return j?.takeIf { it.bands.isNotEmpty() }
    }

    /** Exact DAC conversion; never pass through the lossy clipboard fitter. */
    fun fromDac(bands: List<WalkPlay.DeviceBand>, preampDb: Int, protocol: DeviceProtocol = DeviceProtocol.MICRO): DacImport =
        protocol.importExact(bands, preampDb)
}

