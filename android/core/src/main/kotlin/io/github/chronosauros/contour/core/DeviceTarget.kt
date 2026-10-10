package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*

/**
 * The DAC the app talks to: a WalkPlay DAC ([walkplay]: Protocol Micro, Protocol Max - 1.3.x code, unchanged) or a
 * FiiO ([fiio]: native FiiO HID protocol; the KA15 was hardware-tested on a Pixel 04.10.2026, the K13 R2R is routed
 * the same way). Family identity is separate from WalkPlay's wire representation; no native register coercion.
 */
data class DeviceTarget private constructor(
    val walkplay: DeviceProtocol? = null,
    val fiio: FiioConfig? = null,
) {
    val supportsAb: Boolean get() = walkplay == DeviceProtocol.MICRO
    val experimental: Boolean get() = !supportsAb
    /** Micro and Protocol Max: the 1.3.x targets, with their editor/import/preamp policy. */
    val stable: Boolean get() = walkplay != null
    val native: Boolean get() = walkplay == null
    /** KA15: the intended (acoustic) range, register range minus [FiioConfig.preampOffsetDb] (-24..0 dB). */
    val preampMin: Double get() = fiio?.preampMinDb ?: walkplay!!.preampMin.toDouble()
    val preampMax: Double get() = fiio?.preampMaxDb ?: walkplay!!.preampMax.toDouble()
    val preampStep: Double get() = 0.1
    val caps: DeviceCapabilities get() = walkplay?.caps ?: requireNotNull(fiio).let { f ->
        DeviceCapabilities(f.productName, f.capture?.vendorId ?: 0, f.capture?.productId ?: 0, f.maxFilters,
            f.minGainDb, f.maxGainDb, f.minQ, f.maxQ, f.minFrequencyHz.toDouble(), f.maxFrequencyHz.toDouble(),
            f.typeCodes.keys, false)
    }
    /** The USER slot a send goes to when the app has not chosen one (KA15: USER1). */
    val destinationSlot: Int? get() = fiio?.userSlots?.minOrNull()
    fun shelfOffset(bands: List<Band>): Double = walkplay?.shelfOffset(bands) ?: 0.0
    /** Does this stored explicit preamp fit the device with these bands? The planner's own rule (WalkPlay) or the KA15 range. */
    fun preampFits(bands: List<Band>, preampDb: Double): Boolean =
        walkplay?.preampFits(bands, preampDb) ?: (preampDb.isFinite() && preampDb in preampMin..preampMax)
    fun shownPreamp(profile: Profile): Double = if (native) profile.effectivePreampDb() else walkplay!!.shownPreamp(profile)
    fun issues(profile: Profile): List<String> {
        if (walkplay != null) return (walkplay.plan(profile) as? DevicePlan.Rejected)?.issues.orEmpty()
        val f = requireNotNull(fiio)
        return runCatching {
            require(f.codecBlockers.isEmpty()) { f.codecBlockers.joinToString("; ") }
            require(f.userSlots.isNotEmpty()) { "No proven writable user slot" }
            require(profile.bands.size <= caps.bands) { "${profile.bands.size} bands; ${caps.name} accepts ${caps.bands}; no truncation" }
            // Every concrete problem is listed (each field of each band, then the preamp): the first one is what HOLD TO SEND shows.
            val found = mutableListOf<String>()
            profile.bands.forEachIndexed { i, b ->
                val n = i + 1
                // Shelves have their own intended Q range when the DAC stores a scaled Q (KA15: up to 7.07).
                val shelf = b.type != FilterType.PEAK && f.shelfQScale != 1.0
                val (qLo, qHi) = if (shelf) f.shelfQMin to f.shelfQMax else caps.qMin to caps.qMax
                if (b.type !in caps.types) found += "Band $n: type unsupported"
                if (!b.freqHz.isFinite() || b.freqHz !in caps.freqMinHz..caps.freqMaxHz)
                    found += "Band $n: freq ${num(b.freqHz)} Hz outside ${num(caps.freqMinHz)}-${num(caps.freqMaxHz)} Hz"
                if (!b.gainDb.isFinite() || b.gainDb !in caps.gainMinDb..caps.gainMaxDb)
                    found += "Band $n: gain ${num(b.gainDb)} dB outside ${num(caps.gainMinDb)}..${num(caps.gainMaxDb)} dB"
                if (!b.q.isFinite() || b.q !in qLo..qHi)
                    found += "Band $n: ${if (shelf) "shelf " else ""}Q %.2f outside %.2f-%.2f".format(java.util.Locale.ROOT, b.q, qLo, qHi)
            }
            val gain = profile.effectivePreampDb()
            if (!(gain.isFinite() && gain in preampMin..preampMax))
                found += "Pregain %.1f outside %.1f..%.1f dB".format(java.util.Locale.ROOT, gain, preampMin, preampMax)
            if (found.isNotEmpty()) return found
            FiioCodec(f).planOnExplicitSend(profile.copy(preampDb = gain), destinationSlot!!, true)
        }.exceptionOrNull()?.let { listOf(it.message ?: "Unrepresentable EQ") }.orEmpty()
    }
    companion object {
        /** A number in an issue text: whole values without decimals ("0", "25000"), others to one place. */
        private fun num(x: Double): String =
            if (x.isFinite() && x == Math.rint(x) && Math.abs(x) < 1e9) x.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, x)
        private const val BLOCK_LABEL_MAX = 30 // as wide as the longest label HOLD TO SEND already shows
        private const val BLOCK_LABEL_GENERIC = "INVALID EQ - EDIT BAND"
        private val bandIssue = Regex("""^Band (\d+): (.*?)(?: outside .*)?$""")
        private val bandCountIssue = Regex("""^(\d+) bands; .* accepts (\d+);""")
        /**
         * The text HOLD TO SEND shows for the first of [issues] (short, uppercase), e.g. "BAND 10: FREQ 0 HZ - EDIT BAND"
         * or "PREAMP OUT OF RANGE". Anything it does not recognise, or that would not fit, stays the generic label.
         */
        fun blockLabel(issues: List<String>): String {
            val issue = issues.firstOrNull()?.trim() ?: return BLOCK_LABEL_GENERIC
            if (issue.startsWith("Pregain") || issue.contains("preamp", ignoreCase = true)) return "PREAMP OUT OF RANGE"
            bandCountIssue.find(issue)?.let { return "${it.groupValues[1]} BANDS - MAX ${it.groupValues[2]}" }
            val m = bandIssue.find(issue) ?: return BLOCK_LABEL_GENERIC
            val base = "BAND ${m.groupValues[1]}: ${m.groupValues[2].uppercase(java.util.Locale.ROOT)}"
            return when {
                "$base - EDIT BAND".length <= BLOCK_LABEL_MAX -> "$base - EDIT BAND"
                base.length <= BLOCK_LABEL_MAX -> base
                else -> BLOCK_LABEL_GENERIC
            }
        }
        val MICRO = DeviceTarget(walkplay = DeviceProtocol.MICRO)
        val MAX = DeviceTarget(walkplay = DeviceProtocol.MAX)
        val KA15 = DeviceTarget(fiio = FiioCatalog.KA15)
        val K13 = DeviceTarget(fiio = FiioCatalog.K13)
        fun of(protocol: DeviceProtocol) = if (protocol == DeviceProtocol.MICRO) MICRO else MAX

        /**
         * Strict VID:PID allow-list, the only discovery API: Protocol Micro 3302:C20F, Protocol Max 3302:43CC,
         * FiiO KA15 2972:0104, FiiO K13 R2R 2972:[FiioCatalog.K13_R2R_PRODUCT_ID]. Every other identity is unrecognised (NO DAC).
         */
        fun find(vendorId: Int, productId: Int): DeviceTarget? {
            DeviceProtocol.find(vendorId, productId)?.let { return of(it) }
            val ka15 = requireNotNull(FiioCatalog.KA15.capture)
            if (vendorId == ka15.vendorId && productId == ka15.productId) return KA15
            return if (vendorId == FIIO_VENDOR_ID && productId == FiioCatalog.K13_R2R_PRODUCT_ID) K13 else null
        }
        private const val FIIO_VENDOR_ID = 0x2972
    }
}
