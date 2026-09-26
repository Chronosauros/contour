package io.github.chronosauros.contour.model

import io.github.chronosauros.contour.core.ApoText
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.EqByEarJson
import io.github.chronosauros.contour.core.ImportedEq
import io.github.chronosauros.contour.core.ProtocolMicro
import io.github.chronosauros.contour.core.WalkPlay
import kotlin.math.roundToLong

internal fun round1(x: Double) = (x * 10).roundToLong() / 10.0
internal fun round2(x: Double) = (x * 100).roundToLong() / 100.0

/** Import rules: device ranges, supported types, at most 8 bands. Returns the bands and a one-line report. */
object Importer {
    fun fit(eq: ImportedEq): Pair<ImportedEq, String?> {
        val caps = ProtocolMicro.CAPABILITIES
        val notes = ArrayList<String>()
        var bands = eq.bands
        val unsupported = bands.count { it.type !in caps.types }
        if (unsupported > 0) {
            bands = bands.filter { it.type in caps.types }
            notes += "$unsupported unsupported filter${if (unsupported > 1) "s" else ""} dropped"
        }
        if (bands.size > caps.bands) {
            notes += "${bands.size - caps.bands} band${if (bands.size - caps.bands > 1) "s" else ""} over 8 dropped"
            bands = bands.take(caps.bands)
        }
        var clamped = 0
        bands = bands.map { b ->
            val f = Math.round(b.freqHz.coerceIn(caps.freqMinHz, caps.freqMaxHz)).toDouble()
            val g = round1(b.gainDb.coerceIn(caps.gainMinDb, caps.gainMaxDb))
            val q = round2(b.q.coerceIn(caps.qMin, caps.qMax))
            if (f != b.freqHz || g != b.gainDb || q != b.q) clamped++
            b.copy(freqHz = f, gainDb = g, q = q)
        }
        if (clamped > 0) notes += "$clamped band${if (clamped > 1) "s" else ""} rounded or clamped to the device ranges"
        val pre = eq.preampDb?.let {
            Math.round(it.coerceIn(ProtocolMicro.PREAMP_MIN_DB.toDouble(), ProtocolMicro.PREAMP_MAX_DB.toDouble())).toDouble()
        }
        return ImportedEq(bands, pre) to notes.joinToString("; ").ifEmpty { null }
    }

    /** APO / AutoEQ text, else EQ by Ear JSON; null when nothing parseable. */
    fun parse(text: String?): ImportedEq? {
        if (text.isNullOrBlank()) return null
        val apo = runCatching { ApoText.parse(text) }.getOrNull()
        if (apo != null && apo.bands.isNotEmpty()) return apo
        val j = runCatching { EqByEarJson.parse(text) }.getOrNull()
        return j?.takeIf { it.bands.isNotEmpty() }
    }

    /** Exact DAC conversion; never pass through the lossy clipboard fitter. */
    fun fromDac(bands: List<WalkPlay.DeviceBand>, preampDb: Int): DacImport =
        ProtocolMicro.importExact(bands, preampDb)
}

