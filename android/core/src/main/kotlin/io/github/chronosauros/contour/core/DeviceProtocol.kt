package io.github.chronosauros.contour.core

/**
 * Explicit allow-list of DACs. MICRO delegates verbatim to the 1.2.2 code ([ProtocolMicro], [WalkPlay]).
 * MAX (CrinEar Protocol Max, WalkPlay SchemeNo16) is supported in every build; it runs the stricter, fail-closed path.
 * A community member tested it on 02.10.2026: 8 bands, peak and shelf filters, no crashes.
 */
enum class DeviceProtocol(val caps: DeviceCapabilities) {
    MICRO(ProtocolMicro.CAPABILITIES),
    MAX(ProtocolMicro.CAPABILITIES.copy(name = "CrinEar Protocol Max", productId = 0x43CC, bands = 10));

    /** True for protocols on the strict fail-closed path (MAX); the name is historical. */
    val experimental: Boolean get() = this != MICRO
    val supportsAb: Boolean get() = this == MICRO
    val preampMin: Int get() = ProtocolMicro.PREAMP_MIN_DB
    val preampMax: Int get() = ProtocolMicro.PREAMP_MAX_DB

    /** Micro: HIGH SHELF gains its emulation folds into the preamp. Max has native shelves: none. */
    fun shelfOffset(bands: List<Band>): Double = if (this == MICRO) ProtocolMicro.highShelfGainSum(bands) else 0.0

    fun devicePreamp(bands: List<Band>, preampDb: Double?): Int = if (this == MICRO) {
        ProtocolMicro.devicePreamp(bands, preampDb)
    } else {
        require(preampDb == null || preampDb.isFinite())
        (if (preampDb == null) Preamp.auto(bands) else kotlin.math.floor(preampDb + 1e-6).toInt())
            .coerceIn(preampMin, preampMax)
    }

    /**
     * The PREAMP row speaks in the domain of the drawn curve (like squig.link): the device register minus the
     * HIGH SHELF gains the Micro emulation folds out of the curve. Display only.
     */
    fun shownPreamp(profile: Profile): Double = devicePreamp(profile.bands, profile.preampDb) - shelfOffset(profile.bands)

    /** Micro factory bytes are unchanged. Max spare slots are explicit neutral PKs, not claimed factory captures. */
    private fun flatBand(index: Int): WalkPlay.BandWrite = if (this == MICRO) WalkPlay.factoryFlat(index)
        else WalkPlay.BandWrite(index, 1000.0, 0.0, WalkPlay.FACTORY_Q, WalkPlay.TYPE_PK)

    fun flatPlan(): DevicePlan.Ready = if (this == MICRO) ProtocolMicro.flatPlan()
        else DevicePlan.Ready(List(caps.bands) { flatBand(it) }, 0)

    fun plan(profile: Profile): DevicePlan = plan(profile.bands, profile.preampDb)
    fun plan(bands: List<Band>, preampDb: Double?): DevicePlan {
        if (this == MICRO) return ProtocolMicro.plan(bands, preampDb)
        val issues = ArrayList<String>()
        val active = bands.filter { it.enabled }
        if (active.size > caps.bands) issues += "${active.size} enabled bands; ${caps.name} has ${caps.bands}"
        bands.forEachIndexed { i, b ->
            // Even disabled input must be finite; nothing invalid can reach a write sequence.
            if (!b.freqHz.isFinite() || !b.gainDb.isFinite() || !b.q.isFinite()) issues += "Band ${i + 1}: non-finite values"
            if (b.enabled && (b.type !in caps.types || b.freqHz !in caps.freqMinHz..caps.freqMaxHz ||
                    b.gainDb !in caps.gainMinDb..caps.gainMaxDb || b.q !in caps.qMin..caps.qMax))
                issues += "Band ${i + 1}: outside ${caps.name} supported types/ranges"
        }
        if (preampDb != null && (!preampDb.isFinite() || kotlin.math.floor(preampDb + 1e-6) !in preampMin.toDouble()..preampMax.toDouble()))
            issues += "Preamp outside $preampMin..$preampMax dB policy"
        if (issues.isNotEmpty()) return DevicePlan.Rejected(issues)
        return runCatching {
            // Native shelves: HIGH SHELF goes out as HSQ (no Micro LOW SHELF + preamp emulation), no freq/Q compensation.
            val writes = List(caps.bands) { i -> if (i < active.size) WalkPlay.bandWrite(i, active[i]) else flatBand(i) }
            // Preflight the entire payload, including native shelves, before the first USB write.
            writes.forEach { WalkPlay.bandWriteReport(it, 0) }
            DevicePlan.Ready(writes, devicePreamp(active, preampDb))
        }.getOrElse { DevicePlan.Rejected(listOf("Invalid EQ: ${it.message}")) }
    }

    fun matches(plan: DevicePlan.Ready, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        if (this == MICRO) ProtocolMicro.matches(plan, regs, preampDb)
        else plan.bands.size == caps.bands && ProtocolMicro.matches(plan, regs, preampDb)

    fun matches(profile: Profile, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        if (this == MICRO) ProtocolMicro.matches(profile, regs, preampDb)
        else (plan(profile) as? DevicePlan.Ready)?.let { matches(it, regs, preampDb) } ?: false

    fun importExact(bands: List<WalkPlay.DeviceBand>, preampDb: Int): DacImport {
        if (this == MICRO) return ProtocolMicro.importExact(bands, preampDb)
        if (bands.size != caps.bands || bands.indices.any { bands[it].registers.index != it })
            return DacImport.Rejected(listOf("Expected all ${caps.bands} DAC slots in order"))
        if (preampDb !in preampMin..preampMax) return DacImport.Rejected(listOf("DAC preamp outside supported policy"))
        val last = maxOf(0, bands.indexOfLast { it.registers != flatBand(it.registers.index).registers() })
        val reconstructed = bands.take(last + 1).map { b ->
            val r = b.registers
            if (!b.enabled || r.typeCode !in setOf(WalkPlay.TYPE_PK, WalkPlay.TYPE_LSQ, WalkPlay.TYPE_HSQ))
                return DacImport.Rejected(listOf("DAC band ${r.index + 1}: disabled or unsupported type"))
            Band("dac${r.index}", b.type, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0)
        }
        val eq = ImportedEq(reconstructed, preampDb.toDouble())
        val candidate = plan(eq.bands, eq.preampDb)
        return if (candidate is DevicePlan.Ready && matches(candidate, bands.map { it.registers }, preampDb)) DacImport.Ready(eq)
            else DacImport.Rejected(listOf("DAC state cannot be re-encoded exactly"))
    }

    // ---- receive side. MICRO: exactly the 1.2.2 predicates and parsers. MAX: strict, fail closed. ----------

    /** Max: a full 64-byte input report, read direction; never a write echo, short packet or unknown type. */
    fun isReply(buf: ByteArray, cmd: Int): Boolean = if (this == MICRO) WalkPlay.isReply(buf, cmd)
        else buf.size >= WalkPlay.REPORT_SIZE && WalkPlay.isReply(buf, cmd) && (buf[1].toInt() and 0xFF) == WalkPlay.READ

    fun isBandReply(buf: ByteArray, index: Int): Boolean = if (this == MICRO) WalkPlay.isBandReply(buf, index)
        else index in 0 until caps.bands && isReply(buf, WalkPlay.CMD_PEQ) && WalkPlay.isBandReply(buf, index)

    /** The bulk slot read: Micro accepts any PEQ reply (1.2.2); Max requires a valid band 0 reply. */
    fun isSlotReply(buf: ByteArray): Boolean = if (this == MICRO) WalkPlay.isReply(buf, WalkPlay.CMD_PEQ)
        else isBandReply(buf, 0)

    fun parseBand(buf: ByteArray, index: Int): WalkPlay.DeviceBand {
        if (this == MICRO) return WalkPlay.parseBand(buf)
        require(isBandReply(buf, index)) { "Invalid band $index reply" }
        require(buf[3].toInt() == 0 && buf[4].toInt() == 0) { "Invalid PEQ reply header" }
        val b = WalkPlay.parseBand(buf)
        require(b.registers.typeCode in setOf(WalkPlay.TYPE_PK, WalkPlay.TYPE_LSQ, WalkPlay.TYPE_HSQ)) { "Unsupported ${caps.name} native type" }
        if (b.enabled) require(b.registers.freq.toDouble() in caps.freqMinHz..caps.freqMaxHz &&
            b.registers.q256 / 256.0 in (caps.qMin - 1.0 / 256)..(caps.qMax + 1.0 / 256) &&
            b.registers.gain256 / 256.0 in caps.gainMinDb..caps.gainMaxDb) { "Malformed ${caps.name} registers" }
        return b
    }

    fun parseSlot(buf: ByteArray): Int {
        if (this == MICRO) return WalkPlay.parseSlot(buf)
        require(isBandReply(buf, 0)) { "Invalid bulk slot reply" }
        parseBand(buf, 0)
        return WalkPlay.parseSlot(buf) // opaque byte, echoed unchanged; no assumption about preset 101
    }

    fun parseVersion(buf: ByteArray): String {
        if (this == MICRO) return WalkPlay.parseVersion(buf)
        require(isReply(buf, WalkPlay.CMD_VERSION)) { "Invalid version reply" }
        val version = WalkPlay.parseVersion(buf)
        require(version.isNotEmpty() && version.all { it.code in 0x20..0x7E }) { "Invalid firmware version" }
        return version
    }

    fun parsePreamp(buf: ByteArray): Int {
        if (this == MICRO) return WalkPlay.parsePreamp(buf)
        require(isReply(buf, WalkPlay.CMD_GLOBAL_GAIN)) { "Invalid preamp reply" }
        require(buf[3].toInt() == 2 && buf[4].toInt() == 0) { "Invalid preamp header" }
        return WalkPlay.parsePreamp(buf)
    }

    companion object {
        /** Strict VID/PID pairs only. */
        fun find(vendorId: Int, productId: Int): DeviceProtocol? =
            entries.firstOrNull { it.caps.vendorId == vendorId && it.caps.productId == productId }
    }
}
