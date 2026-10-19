package io.github.chronosauros.contour.core

/**
 * Explicit allow-list of DACs. MICRO delegates verbatim to the 1.2.2 code ([ProtocolMicro], [WalkPlay]).
 * MAX (CrinEar Protocol Max, WalkPlay SchemeNo16) is supported in every build; it runs the stricter, fail-closed path.
 * A community member tested it on 02.10.2026: 8 bands, peak and shelf filters, no crashes.
 * TRN (TRN Black Pearl, 3302:43E8, also SchemeNo16) runs the same strict path with its own write sequence
 * ([WalkPlay.trnWriteSequence]), its own factory flat for the spare slots and a read that accepts what the TRN's
 * own tool can store (see [parseBand]). Its registers were written and read back from a PC on 19.10.2026.
 */
enum class DeviceProtocol(val caps: DeviceCapabilities) {
    MICRO(ProtocolMicro.CAPABILITIES),
    MAX(ProtocolMicro.CAPABILITIES.copy(name = "CrinEar Protocol Max", productId = 0x43CC, bands = 10)),
    // LOW PASS and HIGH PASS (native codes 4 and 5) are proven on the TRN's registers (19.10.2026) but stay out of the
    // stable build: owner decision 14.10.2026, pass filters reach stable only with branch wip/pass-filters after the
    // UMC202HD measurement. They plug in here (add FilterType.LOW_PASS and HIGH_PASS to `types`) together with the
    // plain RBJ low-pass and high-pass biquads in WalkPlay.scaledCoefficients; nothing else in this file needs to change.
    TRN(ProtocolMicro.CAPABILITIES.copy(name = "TRN Black Pearl", productId = 0x43E8, bands = 10));

    /** True for protocols on the strict fail-closed path (MAX, TRN); the name is historical. */
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
     * Does this explicit curve-domain [preampDb] fit the device with these [bands]? Exactly the rule [plan] applies
     * (Micro: register with the high-shelf offset; Max: `floor(preamp + 1e-6)` in the policy range).
     */
    fun preampFits(bands: List<Band>, preampDb: Double): Boolean = preampDb.isFinite() && (
        if (this == MICRO) ProtocolMicro.preampFits(bands, preampDb)
        else kotlin.math.floor(preampDb + 1e-6) in preampMin.toDouble()..preampMax.toDouble())

    /** Why [preampDb] does not fit with these [bands] (null when it does): the text of the send refusal. */
    fun preampRefusal(bands: List<Band>, preampDb: Double): String? = when {
        preampFits(bands, preampDb) -> null
        this == MICRO && preampDb.isFinite() -> ProtocolMicro.preampRefusal(bands, preampDb)
        else -> "Preamp ${dbText(preampDb) { !preampFits(bands, it) }} dB outside $preampMin..$preampMax dB policy"
    }

    /**
     * Import of a file's explicit preamp: rounded DOWN to 0.1 dB (toward quieter, never louder). If that would flip
     * the planner's verdict (accepted to refused just above -30), the exact file value is kept instead. Nothing is
     * capped: a positive value stays and is refused when the device preamp would be 1 dB or more.
     */
    fun fitImportedPreamp(bands: List<Band>, preampDb: Double): Double {
        if (!preampDb.isFinite()) return if (preampDb.isNaN()) 0.0 else if (preampDb > 0) preampMax.toDouble() else preampMin.toDouble()
        val down = Preamp.floorTo(preampDb)
        return if (preampFits(bands, down) == preampFits(bands, preampDb)) down else preampDb
    }

    /**
     * The lowest 0.1 dB manual preamp whose register equals AUTO's, so AUTO to MANUAL never changes the sound
     * (AUTO itself is rounded toward quieter). Null when AUTO has no valid register.
     */
    fun manualForAuto(bands: List<Band>): Double? {
        val register = runCatching { devicePreamp(bands, null) }.getOrNull() ?: return null
        // The register tolerance (1e-6) applies before scaling to tenths: m + hs + 1e-6 >= register.
        val first = kotlin.math.ceil((register - shelfOffset(bands) - 1e-6) * 10).toLong()
        for (n in first..first + 3) {
            val m = n / 10.0
            if (kotlin.math.floor(m + shelfOffset(bands) + 1e-6).toInt() == register) return m
        }
        return null
    }

    /** AUTO's refusal for [bands] (the preamp it needs is below the floor), or null when AUTO fits or has no response. */
    fun autoRefusal(bands: List<Band>): String? {
        if (this == MICRO) return ProtocolMicro.autoRefusal(bands)
        val needed = runCatching { Preamp.auto(bands.filter { it.enabled }) }.getOrNull() ?: return null
        return if (needed < preampMin) SendBlocked.auto(needed, preampMin) else null
    }

    /**
     * The PREAMP row speaks in the domain of the drawn curve (like squig.link): the device register minus the
     * HIGH SHELF gains the Micro emulation folds out of the curve. Display only.
     */
    fun shownPreamp(profile: Profile): Double = devicePreamp(profile.bands, profile.preampDb) - shelfOffset(profile.bands)

    /**
     * Micro factory bytes are unchanged. Max spare slots are explicit neutral PKs, not claimed factory captures.
     * TRN spare slots are the unit's own factory flat, read from the owner's unit before any write (19.10.2026): PK, 0 dB,
     * register Q 182/256, 31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz.
     */
    private fun flatBand(index: Int): WalkPlay.BandWrite = when (this) {
        MICRO -> WalkPlay.factoryFlat(index)
        TRN -> WalkPlay.BandWrite(index, TRN_FACTORY_FREQS[index].toDouble(), 0.0, TRN_FACTORY_Q, WalkPlay.TYPE_PK)
        else -> WalkPlay.BandWrite(index, 1000.0, 0.0, WalkPlay.FACTORY_Q, WalkPlay.TYPE_PK)
    }

    /** The reports of one send and the pause after each: Micro and Max as 1.3.x, the TRN with its own sequence. */
    fun writeSequence(plan: DevicePlan.Ready, slot: Int): List<WalkPlay.Step> =
        if (this == TRN) WalkPlay.trnWriteSequence(plan.bands, plan.preampDb.toDouble(), slot)
        else WalkPlay.writeSequence(plan.bands, plan.preampDb.toDouble(), slot, commit = true)

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
        if (preampDb != null) preampRefusal(active, preampDb)?.let { issues += it }
        if (issues.isNotEmpty()) return DevicePlan.Rejected(issues)
        return runCatching {
            // Native shelves: HIGH SHELF goes out as HSQ (no Micro LOW SHELF + preamp emulation), no freq/Q compensation.
            val writes = List(caps.bands) { i -> if (i < active.size) WalkPlay.bandWrite(i, active[i]) else flatBand(i) }
            // Preflight the entire payload, including native shelves, before the first USB write. A band whose
            // biquad does not fit signed Q30 (original or quantised values) is refused, never wrapped. Slots follow
            // the enabled bands in order; the editor numbers every band, enabled or not.
            val rows = bands.indices.filter { bands[it].enabled }
            val blocked = ArrayList<String>()
            writes.forEachIndexed { i, w ->
                try { WalkPlay.bandWriteReport(w, 0) } catch (_: WalkPlay.Q30Overflow) {
                    blocked += SendBlocked.band(caps.name, (rows.getOrNull(i) ?: i) + 1, active.getOrNull(i)?.type ?: FilterType.PEAK)
                }
            }
            if (blocked.isNotEmpty()) return@runCatching DevicePlan.Rejected(blocked)
            // AUTO that needs more than the device takes is refused with the number, not sent as -30.
            if (preampDb == null) {
                val needed = Preamp.auto(active)
                if (needed < preampMin) return@runCatching DevicePlan.Rejected(listOf(SendBlocked.auto(needed, preampMin)))
            }
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
        if (this == TRN) return importExactTrn(bands, preampDb)
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
        if (candidate is DevicePlan.Rejected && candidate.issues.all { SendBlocked.isBandRefusal(it) }) {
            // The DAC already holds a band Contour would refuse to send (written by another tool): load what it holds,
            // with the refusal as a warning; the plan stays Rejected, so sending it back stays blocked.
            val writes = List(caps.bands) { i -> if (i < reconstructed.size) WalkPlay.bandWrite(i, reconstructed[i]) else flatBand(i) }
            if (matches(DevicePlan.Ready(writes, preampDb), bands.map { it.registers }, preampDb))
                return DacImport.Ready(eq, candidate.issues.joinToString(" ") + " Sending it back stays blocked until you change the band.")
        }
        return if (candidate is DevicePlan.Ready && matches(candidate, bands.map { it.registers }, preampDb)) DacImport.Ready(eq)
            else DacImport.Rejected(listOf("DAC state cannot be re-encoded exactly"))
    }

    /**
     * FROM DAC on the TRN Black Pearl: what the DAC holds, with the stored values as they are. A band outside the editor
     * caps (the TRN's own tool stores e.g. -10.4 dB against +-10) comes in with its real value and a warning; the plan
     * stays Rejected, so sending it back stays blocked until the band is changed. A disabled slot (freq or Q 0, or
     * erased) is an empty spare and is left out wherever it stands. Anything else the planner refuses, or that cannot
     * be re-encoded to the same registers, is rejected. A band of a type this build does not offer (a LOW PASS or HIGH
     * PASS from the TRN's tool) is rejected too, but the read itself succeeded, so a send can still replace it.
     */
    private fun importExactTrn(bands: List<WalkPlay.DeviceBand>, preampDb: Int): DacImport {
        if (bands.size != caps.bands || bands.indices.any { bands[it].registers.index != it })
            return DacImport.Rejected(listOf("Expected all ${caps.bands} DAC slots in order"))
        if (preampDb !in preampMin..preampMax) return DacImport.Rejected(listOf("DAC preamp outside supported policy"))
        val held = bands.filter { it.enabled }
        val skipped = bands.size - held.size
        if (held.isEmpty()) return DacImport.Rejected(listOf("DAC holds no enabled band"))
        // The registers as a send would leave them: the held bands in order, then the factory flat spares.
        val held10 = List(caps.bands) { i -> held.getOrNull(i)?.registers?.copy(index = i) ?: flatBand(i).registers() }
        // Keep one editable band when the whole DAC is factory flat.
        val last = maxOf(0, held.indices.indexOfLast { held10[it] != flatBand(it).registers() })
        val nativeCodes = caps.types.map { WalkPlay.typeCode(it) }.toSet()
        val reconstructed = held.take(last + 1).mapIndexed { i, b ->
            val r = b.registers
            if (r.typeCode !in nativeCodes) return DacImport.Rejected(listOf("DAC band ${r.index + 1}: unsupported type"))
            Band("dac$i", b.type, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0)
        }
        val eq = ImportedEq(reconstructed, preampDb.toDouble())
        val note = if (skipped == 0) "" else " $skipped disabled DAC slot${if (skipped == 1) "" else "s"} left out."
        val candidate = plan(eq.bands, eq.preampDb)
        if (candidate is DevicePlan.Ready)
            return if (matches(candidate, held10, preampDb)) DacImport.Ready(eq, note.trim().ifEmpty { null })
                else DacImport.Rejected(listOf("DAC state cannot be re-encoded exactly"))
        val refused = (candidate as DevicePlan.Rejected).issues
        if (refused.all { SendBlocked.isBandRefusal(it) || it.endsWith(": outside ${caps.name} supported types/ranges") }) {
            val writes = List(caps.bands) { i -> if (i < reconstructed.size) WalkPlay.bandWrite(i, reconstructed[i]) else flatBand(i) }
            if (matches(DevicePlan.Ready(writes, preampDb), held10, preampDb))
                return DacImport.Ready(eq, refused.joinToString(" ") { if (it.endsWith(".")) it else "$it." } +
                    " Sending it back stays blocked until you change the band.$note")
        }
        return DacImport.Rejected(listOf("DAC state cannot be re-encoded exactly"))
    }

    // ---- receive side. MICRO: exactly the 1.2.2 predicates and parsers. MAX, TRN: strict, fail closed. ----------

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
        if (this == TRN) {
            // A read reports what the DAC holds: the TRN's own tool can store values the editor refuses to send (a tester's
            // Black Pearl, 11.10.2026: 9300 Hz, Q 2.0, -10.4 dB against the +-10 dB caps), so an enabled band is checked
            // against the register format only (frequency below the 96 kHz design rate's Nyquist, gain +-60 dB), never
            // against the caps; sending keeps the caps ([plan]). The type may be any code the unit takes (1 to 5, the
            // pass filters included): a read must not fail on what the TRN's tool wrote, or Contour could not replace it.
            // A disabled spare slot is no read failure whatever its type byte (an erased slot reads 0xFF, a zeroed one 0).
            if (b.enabled) {
                require(b.registers.typeCode in WalkPlay.TYPE_LSQ..WalkPlay.TYPE_HP) { "Unsupported ${caps.name} native type" }
                require(b.registers.freq in 1..TRN_READ_MAX_HZ && b.registers.q256 in 1..0xFFFF &&
                    b.registers.gain256 in -TRN_READ_MAX_GAIN256..TRN_READ_MAX_GAIN256) { "Malformed TRN registers" }
            }
            return b
        }
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
        /** TRN read bounds (the register format, not the editor caps): frequency below the 96 kHz design rate's Nyquist, gain +-60 dB. */
        private const val TRN_READ_MAX_HZ = 48_000
        private const val TRN_READ_MAX_GAIN256 = 60 * 256
        private val TRN_FACTORY_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        private const val TRN_FACTORY_Q = 182 / 256.0 // register 182, so round(q * 256) = 182

        /** Strict VID/PID pairs only. */
        fun find(vendorId: Int, productId: Int): DeviceProtocol? =
            entries.firstOrNull { it.caps.vendorId == vendorId && it.caps.productId == productId }
    }
}
