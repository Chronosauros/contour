package io.github.chronosauros.contour.model

import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.EqByEarJson
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
        protocol.walkplay?.let { return fitWalkPlay(eq, it) }
        // FiiO KA15: preserve all file intent (including fractional preamp); import never truncates, clamps,
        // quantizes or drops library data. What does not fit is reported and blocks HOLD TO SEND.
        val issues = protocol.issues(Profile("import", "import", bands = eq.bands, preampDb = eq.preampDb, createdAt = 0, updatedAt = 0)).takeIf { it.isNotEmpty() }
        return eq to issues?.joinToString("; ")?.let { "Preserved unchanged; send blocked: $it" }
    }

    /** Micro/Max rules: device ranges, supported types, the selected device slot count. */
    private fun fitWalkPlay(eq: ImportedEq, protocol: DeviceProtocol): Pair<ImportedEq, String?> {
        val caps = protocol.caps
        val notes = ArrayList<String>()
        var bands = eq.bands
        val unsupported = bands.count { it.type !in caps.types }
        if (unsupported > 0) {
            bands = bands.filter { it.type in caps.types }
            notes += "$unsupported unsupported filter${if (unsupported > 1) "s" else ""} dropped"
        }
        if (bands.size > caps.bands) {
            notes += "${bands.size - caps.bands} band${if (bands.size - caps.bands > 1) "s" else ""} over ${caps.bands} dropped"
            bands = bands.take(caps.bands)
        }
        var clamped = 0
        bands = bands.map { b ->
            val f = Math.round(b.freqHz.coerceIn(caps.freqMinHz, caps.freqMaxHz)).toDouble()
            val g = Preamp.floorTo(b.gainDb.coerceIn(caps.gainMinDb, caps.gainMaxDb)) // down: boosts never grow
            val q = round2(b.q.coerceIn(caps.qMin, caps.qMax))
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

