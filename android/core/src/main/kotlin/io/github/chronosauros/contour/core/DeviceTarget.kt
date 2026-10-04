package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*

/**
 * The DAC the app talks to: a WalkPlay DAC ([walkplay]: Protocol Micro, Protocol Max - 1.3.x code, unchanged) or the
 * FiiO KA15 ([fiio]: native FiiO HID protocol, hardware-tested on a Pixel 04.10.2026). Family identity is separate
 * from WalkPlay's wire representation; no native register coercion.
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
    val preampMin: Double get() = fiio?.minGainDb ?: walkplay!!.preampMin.toDouble()
    val preampMax: Double get() = fiio?.maxGainDb ?: walkplay!!.preampMax.toDouble()
    val preampStep: Double get() = 0.1
    val caps: DeviceCapabilities get() = walkplay?.caps ?: requireNotNull(fiio).let { f ->
        DeviceCapabilities(f.productName, f.capture?.vendorId ?: 0, f.capture?.productId ?: 0, f.maxFilters,
            f.minGainDb, f.maxGainDb, f.minQ, f.maxQ, f.minFrequencyHz.toDouble(), f.maxFrequencyHz.toDouble(),
            f.typeCodes.keys, false)
    }
    /** The USER slot a send goes to when the app has not chosen one (KA15: USER1). */
    val destinationSlot: Int? get() = fiio?.userSlots?.minOrNull()
    fun shelfOffset(bands: List<Band>): Double = walkplay?.shelfOffset(bands) ?: 0.0
    fun shownPreamp(profile: Profile): Double = if (native) profile.effectivePreampDb() else walkplay!!.shownPreamp(profile)
    fun issues(profile: Profile): List<String> {
        if (walkplay != null) return (walkplay.plan(profile) as? DevicePlan.Rejected)?.issues.orEmpty()
        val f = requireNotNull(fiio)
        return runCatching {
            require(f.codecBlockers.isEmpty()) { f.codecBlockers.joinToString("; ") }
            require(f.userSlots.isNotEmpty()) { "No proven writable user slot" }
            require(profile.bands.size <= caps.bands) { "${profile.bands.size} bands; ${caps.name} accepts ${caps.bands}; no truncation" }
            profile.bands.forEachIndexed { i, b ->
                require(b.type in caps.types && b.freqHz.isFinite() && b.gainDb.isFinite() && b.q.isFinite() &&
                    b.freqHz in caps.freqMinHz..caps.freqMaxHz && b.gainDb in caps.gainMinDb..caps.gainMaxDb && b.q in caps.qMin..caps.qMax) { "Band ${i + 1}: unsupported type/range" }
            }
            val gain = profile.effectivePreampDb()
            require(gain.isFinite() && gain in preampMin..preampMax) { "Pregain outside $preampMin..$preampMax dB" }
            FiioCodec(f).planOnExplicitSend(profile.copy(preampDb = gain), destinationSlot!!, true)
        }.exceptionOrNull()?.let { listOf(it.message ?: "Unrepresentable EQ") }.orEmpty()
    }
    companion object {
        val MICRO = DeviceTarget(walkplay = DeviceProtocol.MICRO)
        val MAX = DeviceTarget(walkplay = DeviceProtocol.MAX)
        val KA15 = DeviceTarget(fiio = FiioCatalog.KA15)
        fun of(protocol: DeviceProtocol) = if (protocol == DeviceProtocol.MICRO) MICRO else MAX

        /**
         * Strict VID:PID allow-list, the only discovery API: Protocol Micro 3302:C20F, Protocol Max 3302:43CC,
         * FiiO KA15 2972:0104. Every other identity is unrecognised (NO DAC).
         */
        fun find(vendorId: Int, productId: Int): DeviceTarget? {
            DeviceProtocol.find(vendorId, productId)?.let { return of(it) }
            val ka15 = requireNotNull(FiioCatalog.KA15.capture)
            return if (vendorId == ka15.vendorId && productId == ka15.productId) KA15 else null
        }
    }
}
