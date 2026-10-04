package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.*

/** Family identity is separate from WalkPlay's wire representation. No native register coercion. */
data class DeviceTarget private constructor(
    val walkplay: DeviceProtocol? = null,
    val fiio: FiioConfig? = null,
    val kt: KtMicroCatalog.Model? = null,
    val fosi: Boolean = false,
    val moondrop: MoondropCatalog.Model? = null,
    val runtimeCandidate: Boolean = false,
) {
    val supportsAb: Boolean get() = walkplay == DeviceProtocol.MICRO
    val experimental: Boolean get() = !supportsAb
    /** Micro and Protocol Max: the stable 1.3.0 targets, with 1.3.0 editor/import/preamp policy in every build. */
    val stable: Boolean get() = walkplay == DeviceProtocol.MICRO || walkplay == DeviceProtocol.MAX
    val descriptorRequired: Boolean get() = !supportsAb
    val native: Boolean get() = walkplay == null
    val preampMin: Double get() = if (moondrop != null) DeviceProtocol.OFFLINE.preampMin.toDouble() else fiio?.preampMinDb ?: if (native) 0.0 else walkplay!!.preampMin.toDouble()
    val preampMax: Double get() = if (moondrop != null) DeviceProtocol.OFFLINE.preampMax.toDouble() else fiio?.preampMaxDb ?: if (native) 0.0 else walkplay!!.preampMax.toDouble()
    val preampStep: Double get() = if (fiio != null || walkplay != null || moondrop != null) 0.1 else 1.0
    val caps: DeviceCapabilities get() = walkplay?.caps ?: moondrop?.let { model ->
        // Read-only diagnostics: retain local editor ranges, not Fosi/native write assumptions.
        DeviceProtocol.OFFLINE.caps.copy(name = "${model.productName} — READ ONLY", bands = model.bandCount,
            types = model.nativeTypes.map { when (it) { 1 -> FilterType.LOW_SHELF; 2 -> FilterType.PEAK; else -> FilterType.HIGH_SHELF } }.toSet())
    } ?: DeviceCapabilities(
        fiio?.productName ?: kt?.productName ?: FosiCodec.PRODUCT_NAME,
        kt?.let { KtMicroCatalog.VENDOR_ID } ?: if (fosi) FosiCodec.VENDOR_ID else 0,
        if (fosi) FosiCodec.PRODUCT_ID else 0,
        fiio?.maxFilters ?: kt?.bandCount ?: FosiCodec.BAND_COUNT,
        fiio?.minGainDb ?: kt?.minGainDb ?: FosiCodec.MIN_GAIN_DB,
        fiio?.maxGainDb ?: kt?.maxGainDb ?: FosiCodec.MAX_GAIN_DB,
        fiio?.minQ ?: kt?.minQ ?: FosiCodec.MIN_Q,
        fiio?.maxQ ?: kt?.maxQ ?: FosiCodec.MAX_Q,
        fiio?.minFrequencyHz?.toDouble() ?: 20.0, fiio?.maxFrequencyHz?.toDouble() ?: 20000.0,
        fiio?.typeCodes?.keys ?: kt?.nativeTypes ?: FosiCodec.nativeTypes, fiio == null)
    val destinationSlot: Int? get() = fiio?.userSlots?.minOrNull() ?: if (kt != null) 3 else if (fosi) 7 else null
    val destinationLabel: String? get() = destinationSlot?.let { slot ->
        "${caps.name}: ${fiio?.slotLabels?.get(slot) ?: if (fosi) "Custom 1" else "Custom 3"} (slot $slot)"
    }
    val writeBlocker: String? get() = (if (fosi) FosiCodec.READ_ONLY_REASON else null) ?: moondrop?.reasonReadOnly ?: fiio?.let {
        when { it.codecBlockers.isNotEmpty() -> it.codecBlockers.joinToString("; ")
            it.bestGuessProductName -> "Best-guess USB identity is read-only"
            it.userSlots.isEmpty() -> "No proven writable user slot"
            else -> null }
    }
    fun shelfOffset(bands: List<Band>): Double = walkplay?.shelfOffset(bands) ?: 0.0
    fun shownPreamp(profile: Profile): Double = if (this == OFFLINE || native) profile.effectivePreampDb() else walkplay!!.shownPreamp(profile)
    fun issues(profile: Profile): List<String> {
        if (this == OFFLINE) return emptyList()
        if (walkplay != null) return (walkplay.plan(profile) as? DevicePlan.Rejected)?.issues.orEmpty()
        writeBlocker?.let { return listOf(it) }
        return runCatching {
            require(profile.bands.size <= caps.bands) { "${profile.bands.size} bands; ${caps.name} accepts ${caps.bands}; no truncation" }
            profile.bands.forEachIndexed { i, b ->
                require(b.type in caps.types && b.freqHz.isFinite() && b.gainDb.isFinite() && b.q.isFinite() &&
                    b.freqHz in caps.freqMinHz..caps.freqMaxHz && b.gainDb in caps.gainMinDb..caps.gainMaxDb) { "Band ${i + 1}: unsupported type/range" }
                // Shelves have their own intended Q range when the DAC stores a scaled Q (KA15: up to 7.07).
                val f = fiio
                val shelf = f != null && b.type != FilterType.PEAK && f.shelfQScale != 1.0
                val (qLo, qHi) = if (shelf) f!!.shelfQMin to f.shelfQMax else caps.qMin to caps.qMax
                require(b.q in qLo..qHi) {
                    "Band ${i + 1}: ${if (shelf) "shelf " else ""}Q %.2f outside %.2f-%.2f".format(java.util.Locale.ROOT, b.q, qLo, qHi)
                }
            }
            val gain = profile.effectivePreampDb()
            require(gain.isFinite() && gain in preampMin..preampMax) { if (fiio == null) "Manual/AUTO attenuation unsupported; required preamp $gain dB cannot be sent" else "Pregain %.1f outside %.1f..%.1f dB".format(java.util.Locale.ROOT, gain, preampMin, preampMax) }
            fiio?.let { FiioCodec(it).planOnExplicitSend(profile.copy(preampDb = gain), destinationSlot!!, true) }
        }.exceptionOrNull()?.let { listOf(it.message ?: "Unrepresentable EQ") }.orEmpty()
    }
    companion object {
        val MICRO = DeviceTarget(walkplay = DeviceProtocol.MICRO)
        val OFFLINE = DeviceTarget(walkplay = DeviceProtocol.OFFLINE)
        fun of(protocol: DeviceProtocol) = if (protocol == DeviceProtocol.MICRO) MICRO else if (protocol == DeviceProtocol.OFFLINE) OFFLINE else DeviceTarget(walkplay = protocol)
        fun find(vid: Int, pid: Int, advanced: Boolean, name: String?): DeviceTarget? {
            if (blockedReason(vid, pid, name) != null) return null
            if (vid !in 0..0xFFFF || pid !in 0..0xFFFF) return null
            if (!advanced) return DeviceProtocol.find(vid, pid, false, name)?.let { of(it) }
            MoondropCatalog.find(vid, pid, name).takeIf { it.readOnlyCandidate }?.model?.let {
                return DeviceTarget(moondrop = it, runtimeCandidate = true)
            }
            if (name != null) {
                FiioCatalog.capturedRoute(vid, pid, name)?.let { return DeviceTarget(fiio = it) }
                FiioCatalog.sourceRule(vid, name)?.let { return DeviceTarget(fiio = it, runtimeCandidate = true) }
                KtMicroCatalog.find(vid, pid, name)?.takeIf { it.admitted }?.let { return DeviceTarget(kt = it, runtimeCandidate = it.provenProductIds.isEmpty()) }
                if (FosiCodec.matches(vid, pid, name)) return DeviceTarget(fosi = true)
            }
            return DeviceProtocol.find(vid, pid, true, name)?.let { of(it) }
        }
        fun blockedReason(vid: Int, pid: Int, name: String?): String? =
            BlockedUsbCatalog.reason(vid, pid, name) ?: MoondropCatalog.find(vid, pid, name).let { match ->
                if (vid == 0x35D8 && pid == 0x011C || match.model?.blockedReason != null) match.reason else null
            }
        fun candidate(vid: Int, pid: Int, name: String?, advanced: Boolean): Boolean =
            (advanced && (blockedReason(vid, pid, name) != null ||
                // Permission may reveal a descriptor name. Diagnostic discovery is NOT a HID route.
                (name == null && MoondropCatalog.models.any { vid in it.vendorIds }))) || find(vid, pid, advanced, name) != null || WalkPlayCatalog.candidate(vid, pid, name, advanced) ||
                (advanced && (FiioCatalog.rules.any { it.capture?.let { c -> c.vendorId == vid && c.productId == pid } == true } ||
                    (vid == KtMicroCatalog.VENDOR_ID && KtMicroCatalog.models.any { pid in it.provenProductIds }) ||
                    (vid == FosiCodec.VENDOR_ID && pid == FosiCodec.PRODUCT_ID)))
    }
}
