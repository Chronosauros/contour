package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*

/**
 * The DAC the app talks to: a WalkPlay DAC ([walkplay]: Protocol Micro, Protocol Max, TRN Black Pearl) or a
 * FiiO ([fiio]: native FiiO HID protocol; the KA15 was hardware-tested on a Pixel 04.10.2026, the K13 R2R, the
 * SnowSky Melody and Tiny A are routed the same way). Family identity is separate from WalkPlay's wire representation; no native register coercion.
 */
data class DeviceTarget private constructor(
    val walkplay: DeviceProtocol? = null,
    val fiio: FiioConfig? = null,
) {
    val supportsAb: Boolean get() = walkplay == DeviceProtocol.MICRO
    val experimental: Boolean get() = !supportsAb
    /** Micro, Protocol Max and TRN Black Pearl: the WalkPlay targets, with their editor/import/preamp policy. */
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
    /** The Q this DAC takes for a [type] band: shelves have their own range where the DAC stores a scaled Q (KA15: up to 7.07). */
    fun qRange(type: FilterType): ClosedFloatingPointRange<Double> {
        val f = fiio
        return if (f != null && type != FilterType.PEAK && f.shelfQScale != 1.0) f.shelfQMin..f.shelfQMax else caps.qMin..caps.qMax
    }

    /** The graph's short headline for the first refusal; the detailed planner reasons stay untouched. */
    fun invalidEqHeadline(profile: Profile, reasons: List<String> = issues(profile)): String {
        // The graph shows this headline when it cannot draw the curve: that failure names its cause before any send blocker.
        val curveFailure = runCatching { Dsp.responseDb(profile.bands) }.exceptionOrNull()?.message
        val issue = (curveFailure ?: reasons.firstOrNull()).orEmpty().trim().lowercase(java.util.Locale.ROOT)
        val band = Regex("""^band (\d+):""").find(issue)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { profile.bands.getOrNull(it - 1) }
            ?: if ("no finite response" in issue) profile.bands.firstOrNull {
                it.enabled && (!it.freqHz.isFinite() || it.freqHz !in caps.freqMinHz..caps.freqMaxHz ||
                    !it.gainDb.isFinite() || it.gainDb !in caps.gainMinDb..caps.gainMaxDb ||
                    !it.q.isFinite() || it.q !in qRange(it.type))
            } else null
        val cause = when {
            Regex("""^\d+ (enabled )?bands;""").containsMatchIn(issue) -> "band count"
            // A shelf without a real response also fails the preamp calculation: name the shelf, not the preamp.
            band == null && "no finite response" in issue &&
                profile.bands.any { it.enabled && (it.type == FilterType.LOW_SHELF || it.type == FilterType.HIGH_SHELF) } -> "shelf gain or Q"
            issue.startsWith("pregain") || issue.startsWith("preamp") || issue.startsWith("auto preamp") ||
                issue.startsWith("manual/auto attenuation") || issue.startsWith("this eq needs") -> "preamp"
            // Max/catalog refusals group the fields into one range reason. Resolve that reason from its band.
            band != null && (!band.freqHz.isFinite() || band.freqHz !in caps.freqMinHz..caps.freqMaxHz) -> "frequency"
            band != null && (!band.gainDb.isFinite() || band.gainDb !in caps.gainMinDb..caps.gainMaxDb) -> "gain"
            band != null && (!band.q.isFinite() || band.q !in qRange(band.type)) -> "Q"
            "shelf" in issue || (band?.type in setOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF) &&
                "non-finite biquad" in issue) -> "shelf gain or Q"
            " hz outside " in issue || issue.startsWith("frequency") -> "frequency"
            " db outside " in issue -> "gain"
            Regex("""\bq\b""").containsMatchIn(issue) && "outside" in issue -> "Q"
            else -> return "INVALID EQ - EDIT BAND"
        }
        return "INVALID EQ - adjust $cause"
    }

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
            if (issue == "Read the DAC first") return "READ THE DAC FIRST"
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
        val TRN = DeviceTarget(walkplay = DeviceProtocol.TRN)
        val KA15 = DeviceTarget(fiio = FiioCatalog.KA15)
        val K13 = DeviceTarget(fiio = FiioCatalog.K13)
        val MELODY = DeviceTarget(fiio = FiioCatalog.MELODY)
        val TINY_A = DeviceTarget(fiio = FiioCatalog.TINY_A)
        fun of(protocol: DeviceProtocol) = when (protocol) {
            DeviceProtocol.MICRO -> MICRO
            DeviceProtocol.MAX -> MAX
            DeviceProtocol.TRN -> TRN
        }

        /**
         * Strict VID:PID allow-list, the only discovery API: Protocol Micro 3302:C20F, Protocol Max 3302:43CC,
         * TRN Black Pearl 3302:43E8, FiiO KA15 2972:0104, FiiO K13 R2R 2972:[FiioCatalog.K13_R2R_PRODUCT_ID], SnowSky Melody 2972:[FiioCatalog.MELODY_PRODUCT_ID],
         * SnowSky Tiny A 2972:[FiioCatalog.TINY_A_PRODUCT_ID]. Every other identity is unrecognised (NO DAC).
         */
        fun find(vendorId: Int, productId: Int): DeviceTarget? {
            DeviceProtocol.find(vendorId, productId)?.let { return of(it) }
            val ka15 = requireNotNull(FiioCatalog.KA15.capture)
            if (vendorId == ka15.vendorId && productId == ka15.productId) return KA15
            if (vendorId != FIIO_VENDOR_ID) return null
            return when (productId) {
                FiioCatalog.K13_R2R_PRODUCT_ID -> K13
                FiioCatalog.MELODY_PRODUCT_ID -> MELODY
                FiioCatalog.TINY_A_PRODUCT_ID -> TINY_A
                else -> null
            }
        }
        private const val FIIO_VENDOR_ID = 0x2972
    }
}
