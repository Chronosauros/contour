package io.github.chronosauros.contour.core

/** What a DAC accepts. The editor greys out everything outside it; [ProtocolMicro.plan] refuses it. */
data class DeviceCapabilities(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val types: Set<FilterType>,
    /** The device stores the preamp in whole dB only. */
    val preampWholeDb: Boolean,
)

/** A profile translated for the device: 8 band writes plus the preamp, or the reasons it does not fit. */
sealed interface DevicePlan {
    data class Ready(val bands: List<WalkPlay.BandWrite>, val preampDb: Int) : DevicePlan
    data class Rejected(val issues: List<String>) : DevicePlan
}

/** CrinEar Protocol Micro (WalkPlay SchemeNo11). Unofficial; not affiliated with CrinEar. */
object ProtocolMicro {
    val CAPABILITIES = DeviceCapabilities(
        name = "CrinEar Protocol Micro",
        vendorId = WalkPlay.VENDOR_ID,
        productId = WalkPlay.PRODUCT_ID,
        bands = WalkPlay.BANDS,
        gainMinDb = -10.0,
        gainMaxDb = 10.0,
        qMin = 0.1,
        qMax = 10.0,
        freqMinHz = 20.0,
        freqMaxHz = 20_000.0,
        // HIGH_SHELF has no native register type we trust on SchemeNo11: it is sent as an emulated LOW_SHELF
        // plus preamp (see [plan]). LP/HP unverified, so not offered.
        types = setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF),
        preampWholeDb = true,
    )

    /** Device preamp limits (whole dB). */
    const val PREAMP_MIN_DB: Int = -30
    const val PREAMP_MAX_DB: Int = 0

    private fun fmt(x: Double): String = if (x == Math.rint(x)) x.toLong().toString() else x.toString()

    /**
     * A HIGH SHELF band as the Protocol Micro gets it: a LOW SHELF with the same frequency and Q and the
     * negated gain. Exact up to a constant by the RBJ identity H_HS(A) = A^2 * H_LS(1/A) (it survives the
     * bilinear transform and devicePEQ's shelf-slope alpha, which is symmetric in A and 1/A); the constant,
     * the HS gain, goes into the device preamp.
     */
    fun deviceBands(bands: List<Band>): List<Band> = bands.map {
        if (it.type == FilterType.HIGH_SHELF) it.copy(type = FilterType.LOW_SHELF, gainDb = -it.gainDb) else it
    }

    /** Sum of the gains of the enabled HIGH SHELF bands (what the emulation moves into the preamp). */
    fun highShelfGainSum(bands: List<Band>): Double =
        bands.filter { it.enabled && it.type == FilterType.HIGH_SHELF }.sumOf { it.gainDb }

    /**
     * The device preamp for [bands] with the user's preamp [preampDb] (null = AUTO), in whole dB:
     * AUTO -> `-ceil(max(device-domain curve) - 1e-6)` when that max is > 0, else 0;
     * manual -> `floor(preamp + sum of HS gains + 1e-6)`; then clamped to [-30, 0].
     */
    fun devicePreamp(bands: List<Band>, preampDb: Double?): Int {
        val raw = if (preampDb == null) {
            Preamp.auto(deviceBands(bands))
        } else {
            kotlin.math.floor(preampDb + highShelfGainSum(bands) + 1e-6).toInt()
        }
        return raw.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB)
    }

    /**
     * Maps [bands] to the 8 device slots in order: enabled bands take slots 0..n-1 (HIGH SHELF emulated as
     * LOW SHELF, see [deviceBands]), disabled and unused slots become that slot's factory flat band.
     * [preampDb] = null means AUTO (see [devicePreamp]).
     */
    fun plan(bands: List<Band>, preampDb: Double?, caps: DeviceCapabilities = CAPABILITIES): DevicePlan {
        val issues = ArrayList<String>()
        val active = bands.filter { it.enabled }
        if (active.size > caps.bands) issues += "${active.size} enabled bands; ${caps.name} has ${caps.bands}"
        bands.forEachIndexed { i, b ->
            if (!b.enabled) return@forEachIndexed
            val n = "Band ${i + 1}"
            if (b.type !in caps.types) issues += "$n: ${b.type} is not supported by ${caps.name}"
            if (!(b.freqHz >= caps.freqMinHz && b.freqHz <= caps.freqMaxHz)) {
                issues += "$n: ${fmt(b.freqHz)} Hz outside ${fmt(caps.freqMinHz)}-${fmt(caps.freqMaxHz)} Hz"
            }
            if (!(b.gainDb >= caps.gainMinDb && b.gainDb <= caps.gainMaxDb)) {
                issues += "$n: ${fmt(b.gainDb)} dB outside ${fmt(caps.gainMinDb)}..${fmt(caps.gainMaxDb)} dB"
            }
            if (!(b.q >= caps.qMin && b.q <= caps.qMax)) {
                issues += "$n: Q ${fmt(b.q)} outside ${fmt(caps.qMin)}-${fmt(caps.qMax)}"
            }
        }
        if (preampDb != null && !preampDb.isFinite()) issues += "Preamp $preampDb dB is not a number"
        if (issues.isNotEmpty()) return DevicePlan.Rejected(issues)
        val dev = deviceBands(active)
        val writes = List(caps.bands) { slot ->
            if (slot < dev.size) WalkPlay.bandWrite(slot, dev[slot]) else WalkPlay.factoryFlat(slot)
        }
        return DevicePlan.Ready(writes, devicePreamp(active, preampDb))
    }

    fun plan(profile: Profile, caps: DeviceCapabilities = CAPABILITIES): DevicePlan =
        plan(profile.bands, profile.preampDb, caps)

    /** True when the device registers [regs] (8 bands) and [preampDb] are exactly what [plan] writes. */
    fun matches(plan: DevicePlan.Ready, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        regs.size == plan.bands.size && plan.preampDb == preampDb &&
            plan.bands.indices.all { plan.bands[it].registers() == regs[it] }

    /** "This profile is on the DAC": its plan produces exactly the registers read back. */
    fun matches(profile: Profile, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        (plan(profile) as? DevicePlan.Ready)?.let { matches(it, regs, preampDb) } ?: false

    /** The factory flat state (every slot flat, preamp 0). */
    fun isFlat(regs: List<WalkPlay.Registers>, preampDb: Int): Boolean = matches(flatPlan(), regs, preampDb)

    /** Factory flat on every slot, preamp 0: what the device held on 25.09.2026. */
    fun flatPlan(): DevicePlan.Ready = DevicePlan.Ready(List(WalkPlay.BANDS) { WalkPlay.factoryFlat(it) }, 0)
}
