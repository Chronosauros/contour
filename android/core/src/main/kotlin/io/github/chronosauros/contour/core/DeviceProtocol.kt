package io.github.chronosauros.contour.core

/**
 * Capability-configured WalkPlay target.
 * - MICRO delegates verbatim to the 1.2.2 code ([ProtocolMicro], [WalkPlay]), as in stable 1.3.0.
 * - MAX (CrinEar Protocol Max, WalkPlay SchemeNo16, 3302:43CC) is the stable 1.3.0 strict fail-closed path,
 *   byte for byte, in every build. A community member tested it on 02.10.2026: 8 bands, peak and shelf
 *   filters, no crashes.
 * - MAX_PASS is the same target in advanced builds with LOW PASS / HIGH PASS added; the stable build never resolves it.
 * - TRN and catalog targets (advanced builds only) keep the advanced-beta policy; TRN also offers LOW PASS / HIGH PASS.
 */
data class DeviceProtocol private constructor(val caps: DeviceCapabilities, val scheme: Int,
    val upstreamExperimental: Boolean = false) {
    /** Identity by wire id, not by caps: [MAX_PASS] extends the caps of [MAX] and is still the strict Protocol Max path. */
    val isMax: Boolean get() = scheme == 16 && caps.vendorId == 0x3302 && caps.productId == 0x43CC
    val isTrn: Boolean get() = scheme == 16 && caps.vendorId == 0x3302 && caps.productId == 0x43E8
    private val nativeCodes: Set<Int> get() = caps.types.map { WalkPlay.typeCode(it) }.toSet()
    /** True for protocols on a strict fail-closed path (everything but MICRO); the name is historical. */
    val experimental: Boolean get() = this != MICRO
    val descriptorRequired: Boolean get() = this != MICRO && !isMax
    val supportsAb: Boolean get() = this == MICRO
    val preampMin: Int get() = ProtocolMicro.PREAMP_MIN_DB
    val preampMax: Int get() = ProtocolMicro.PREAMP_MAX_DB

    /** Micro: HIGH SHELF gains its emulation folds into the preamp. Native shelves (Max, TRN, catalog): none. */
    fun shelfOffset(bands: List<Band>): Double = if (this == MICRO) ProtocolMicro.highShelfGainSum(bands) else 0.0

    fun devicePreamp(bands: List<Band>, preampDb: Double?): Int = if (this == MICRO) {
        ProtocolMicro.devicePreamp(bands, preampDb)
    } else {
        require(preampDb == null || preampDb.isFinite())
        (if (preampDb == null) Preamp.auto(bands) else kotlin.math.floor(preampDb + 1e-6).toInt())
            .coerceIn(preampMin, preampMax)
    }

    /**
     * Does this explicit curve-domain [preampDb] fit the device with these [bands]? Exactly the rule [plan] applies:
     * Micro: register with the high-shelf offset; Max and TRN: `floor(preamp + 1e-6)` in the policy range; catalog targets: the value itself.
     */
    fun preampFits(bands: List<Band>, preampDb: Double): Boolean = preampDb.isFinite() && (
        when {
            this == MICRO -> ProtocolMicro.preampFits(bands, preampDb)
            isMax || isTrn -> kotlin.math.floor(preampDb + 1e-6) in preampMin.toDouble()..preampMax.toDouble()
            else -> preampDb in preampMin.toDouble()..preampMax.toDouble() // catalog targets: unchanged plain range
        })

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

    /** Micro factory bytes are unchanged. Max/TRN spare slots are explicit neutral PKs, not claimed factory captures. */
    private fun flatBand(index: Int): WalkPlay.BandWrite = if (this == MICRO) WalkPlay.factoryFlat(index)
        else WalkPlay.BandWrite(index, 1000.0, 0.0, WalkPlay.FACTORY_Q, WalkPlay.TYPE_PK)

    fun flatPlan(): DevicePlan.Ready = if (this == MICRO) ProtocolMicro.flatPlan()
        else DevicePlan.Ready(List(caps.bands) { flatBand(it) }, 0)
    data class ReadState(val firmware: String, val slot: Int, val bands: List<WalkPlay.DeviceBand>, val preampDb: Int)

    /**
     * No partial state escapes: all required replies must parse in one guarded operation. The request order and
     * predicates are those of 1.3.0's read: version, bulk slot ([isSlotReply]), every band, preamp.
     */
    fun readComplete(guard: () -> Unit,
        request: (ByteArray, String, (ByteArray) -> Boolean) -> ByteArray): ReadState {
        fun get(report: ByteArray, what: String, matches: (ByteArray) -> Boolean): ByteArray {
            guard()
            val reply = request(report, what, matches)
            guard()
            require(matches(reply)) { "Invalid $what reply" }
            return reply
        }
        val version = parseVersion(get(WalkPlay.versionRequest(), "version") { isReply(it, WalkPlay.CMD_VERSION) })
        val slot = parseSlot(get(WalkPlay.slotRequest(), "slot") { isSlotReply(it) })
        val bands = List(caps.bands) { i -> parseBand(get(WalkPlay.bandRequest(i), "band $i") { isBandReply(it, i) }, i) }
        val preamp = parsePreamp(get(WalkPlay.preampRequest(), "preamp") { isReply(it, WalkPlay.CMD_GLOBAL_GAIN) })
        guard()
        return ReadState(version, slot, bands, preamp)
    }

    fun plan(profile: Profile): DevicePlan = plan(profile.bands, profile.preampDb)
    fun plan(bands: List<Band>, preampDb: Double?): DevicePlan {
        if (this == MICRO) return ProtocolMicro.plan(bands, preampDb)
        if (isMax) return planMax(bands, preampDb)
        val issues = ArrayList<String>()
        val active = bands.filter { it.enabled }
        if (active.size > caps.bands) issues += "${active.size} enabled bands; ${caps.name} has ${caps.bands}"
        bands.forEachIndexed { i, b ->
            // Even disabled input must be finite; nothing invalid can reach a write sequence.
            if (!b.freqHz.isFinite() || !b.gainDb.isFinite() || !b.q.isFinite()) issues += "Band ${i + 1}: non-finite values"
            if (b.type !in caps.types || b.freqHz !in caps.freqMinHz..caps.freqMaxHz ||
                    b.gainDb !in caps.gainMinDb..caps.gainMaxDb || b.q !in caps.qMin..caps.qMax)
                issues += "Band ${i + 1}: outside ${caps.name} supported types/ranges"
            runCatching {
                if (isTrn) WalkPlay.computeIir(b.freqHz, b.gainDb, b.q, WalkPlay.typeCode(b.type))
                else WalkPlayQ30Preflight.requireRepresentable(b.freqHz, b.gainDb, b.q, WalkPlay.typeCode(b.type))
            }.exceptionOrNull()?.let {
                // TRN writes enabled bands only: a disabled band that does not fit is never sent, so it does not block.
                if (!isTrn || it !is WalkPlay.Q30Overflow) issues += "Band ${i + 1}: ${it.message}"
                else if (b.enabled) issues += SendBlocked.band(caps.name, i + 1, b.type)
            }
        }
        if (preampDb != null) preampRefusal(active, preampDb)?.let { issues += it }
        if (issues.isNotEmpty()) return DevicePlan.Rejected(issues)
        return runCatching {
            val writes = List(caps.bands) { i -> if (i < active.size) {
                val b = active[i]
                if (isTrn) WalkPlay.bandWrite(i, b) else WalkPlay.BandWrite(i,
                    kotlin.math.floor(b.freqHz), WalkPlay.jsRound(b.gainDb * 256) / 256,
                    WalkPlay.jsRound(b.q * 256) / 256, WalkPlay.typeCode(b.type))
            } else flatBand(i) }
            // Preflight the entire payload, including native shelves, before the first USB write.
            // Slots follow the enabled bands in order; the editor numbers every band, enabled or not.
            val rows = bands.indices.filter { bands[it].enabled }
            val blocked = ArrayList<String>()
            writes.forEachIndexed { i, w ->
                if (!isTrn) runCatching {
                    WalkPlayQ30Preflight.requireRepresentable(w.freq, w.gainDb, w.q, w.typeCode)
                }.getOrElse { throw IllegalArgumentException("Band ${i + 1} (quantized): ${it.message}") }
                // TRN: bandWriteReport also checks the values its registers would decode to (the writes are unquantized).
                try { WalkPlay.bandWriteReport(w, 0) } catch (e: WalkPlay.Q30Overflow) {
                    if (!isTrn) throw e
                    blocked += SendBlocked.band(caps.name, (rows.getOrNull(i) ?: i) + 1, active.getOrNull(i)?.type ?: FilterType.PEAK)
                }
            }
            if (blocked.isNotEmpty()) return@runCatching DevicePlan.Rejected(blocked)
            val gain = if (preampDb == null) Preamp.auto(active) else kotlin.math.floor(preampDb + 1e-6).toInt()
            // AUTO that needs more than the device takes is refused with the number, not sent as -30.
            if (preampDb == null && gain < preampMin) return@runCatching DevicePlan.Rejected(listOf(SendBlocked.auto(gain, preampMin)))
            require(gain in preampMin..preampMax) { "AUTO preamp outside supported policy" }
            DevicePlan.Ready(writes, gain)
        }.getOrElse { DevicePlan.Rejected(listOf("Invalid EQ: ${it.message}")) }
    }

    /** Protocol Max: exactly the stable 1.3.0 plan. */
    private fun planMax(bands: List<Band>, preampDb: Double?): DevicePlan {
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

    /** Entire command sequence is encoded/validated before any callback can mutate USB.
     * Caller must perform a complete strict read first; guard is checked around every report/pause.
     * MICRO and MAX send exactly the 1.3.0 sequence (validated by their plans); catalog targets re-validate here. */
    fun executeWrite(plan: DevicePlan.Ready, slot: Int, guard: () -> Unit,
        send: (ByteArray) -> Unit, delay: (Long) -> Unit) {
        if (this != MICRO && !isMax) {
            require(slot in 0..255 && plan.preampDb in preampMin..preampMax)
            require(plan.bands.size == caps.bands)
            plan.bands.forEachIndexed { i, w ->
                require(w.index == i && w.typeCode in caps.types.map { WalkPlay.typeCode(it) })
                require(w.freq.isFinite() && w.gainDb.isFinite() && w.q.isFinite())
                require(w.freq in caps.freqMinHz..caps.freqMaxHz && w.gainDb in caps.gainMinDb..caps.gainMaxDb &&
                    w.q in (caps.qMin - 1.0 / 256)..caps.qMax)
                WalkPlayQ30Preflight.requireRepresentable(w.freq, w.gainDb, w.q, w.typeCode)
            }
        }
        // MICRO and MAX (validated by their plans) are covered here too: every report, including the quantised-value
        // check, is encoded before the first callback, so an overflow aborts the whole write.
        val steps = WalkPlay.writeSequence(plan.bands, plan.preampDb.toDouble(), slot, commit = true)
        for (step in steps) {
            guard(); send(step.report); guard()
            if (step.delayAfterMs > 0) { guard(); delay(step.delayAfterMs); guard() }
        }
    }

    fun matches(plan: DevicePlan.Ready, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        if (this == MICRO) ProtocolMicro.matches(plan, regs, preampDb)
        else plan.bands.size == caps.bands && ProtocolMicro.matches(plan, regs, preampDb)

    fun matches(profile: Profile, regs: List<WalkPlay.Registers>, preampDb: Int): Boolean =
        if (this == MICRO) ProtocolMicro.matches(profile, regs, preampDb)
        else (plan(profile) as? DevicePlan.Ready)?.let { matches(it, regs, preampDb) } ?: false

    fun matchesReadback(plan: DevicePlan.Ready, state: ReadState, expectedSlot: Int): Boolean {
        if (state.slot != expectedSlot || !matches(plan, state.bands.map { it.registers }, state.preampDb)) return false
        if (this == MICRO || isMax || isTrn) return true // Preserve established register-only policy.
        return plan.bands.indices.all { i ->
            val w = plan.bands[i]
            WalkPlay.computeIir(w.freq, w.gainDb, w.q, w.typeCode).contentEquals(state.bands[i].biquad)
        }
    }

    fun importExact(bands: List<WalkPlay.DeviceBand>, preampDb: Int): DacImport {
        if (this == MICRO) return ProtocolMicro.importExact(bands, preampDb)
        if (bands.size != caps.bands || bands.indices.any { bands[it].registers.index != it })
            return DacImport.Rejected(listOf("Expected all ${caps.bands} DAC slots in order"))
        if (preampDb !in preampMin..preampMax) return DacImport.Rejected(listOf("DAC preamp outside supported policy"))
        val last = maxOf(0, bands.indexOfLast { it.registers != flatBand(it.registers.index).registers() })
        val reconstructed = bands.take(last + 1).map { b ->
            val r = b.registers
            if (!b.enabled || r.typeCode !in nativeCodes)
                return DacImport.Rejected(listOf("DAC band ${r.index + 1}: disabled or unsupported type"))
            Band("dac${r.index}", b.type, r.freq.toDouble(), r.gain256 / 256.0, r.q256 / 256.0)
        }
        val eq = ImportedEq(reconstructed, preampDb.toDouble())
        val candidate = plan(eq.bands, eq.preampDb)
        if ((isMax || isTrn) && candidate is DevicePlan.Rejected && candidate.issues.all { SendBlocked.isBandRefusal(it) }) {
            // The DAC already holds a band Contour would refuse to send (written by another tool): load what it holds,
            // with the refusal as a warning; the plan stays Rejected, so sending it back stays blocked.
            val writes = List(caps.bands) { i -> if (i < reconstructed.size) WalkPlay.bandWrite(i, reconstructed[i]) else flatBand(i) }
            if (matches(DevicePlan.Ready(writes, preampDb), bands.map { it.registers }, preampDb))
                return DacImport.Ready(eq, candidate.issues.joinToString(" ") + " Sending it back stays blocked until you change the band.")
        }
        return if (candidate is DevicePlan.Ready && matches(candidate, bands.map { it.registers }, preampDb)) DacImport.Ready(eq)
            else DacImport.Rejected(listOf("DAC state cannot be re-encoded exactly"))
    }

    // ---- receive side. MICRO: exactly the 1.2.2 predicates and parsers. MAX: 1.3.0 strict, fail closed. ------

    /** Strict receive validation; never interpret a write echo, short packet or unknown native type.
     * Max/TRN: a full 64-byte input report, read direction. */
    fun isReply(buf: ByteArray, cmd: Int): Boolean = if (this == MICRO) WalkPlay.isReply(buf, cmd)
        else buf.size >= (if (isMax || isTrn) WalkPlay.REPORT_SIZE else when (cmd) {
            WalkPlay.CMD_PEQ -> 37
            WalkPlay.CMD_VERSION -> 7
            WalkPlay.CMD_GLOBAL_GAIN -> 6
            else -> 3
        }) && WalkPlay.isReply(buf, cmd) && (buf[1].toInt() and 0xFF) == WalkPlay.READ

    fun isBandReply(buf: ByteArray, index: Int): Boolean = if (this == MICRO) WalkPlay.isBandReply(buf, index)
        else index in 0 until caps.bands && isReply(buf, WalkPlay.CMD_PEQ) && WalkPlay.isBandReply(buf, index)

    /** The bulk slot read: Micro accepts any PEQ reply (1.2.2); every other target requires a valid band 0 reply. */
    fun isSlotReply(buf: ByteArray): Boolean = if (this == MICRO) WalkPlay.isReply(buf, WalkPlay.CMD_PEQ)
        else isBandReply(buf, 0)

    fun parseBand(buf: ByteArray, index: Int): WalkPlay.DeviceBand {
        if (this == MICRO) return WalkPlay.parseBand(buf)
        require(isBandReply(buf, index)) { "Invalid band $index reply" }
        require(buf[3].toInt() == 0 && buf[4].toInt() == 0) { "Invalid PEQ reply header" }
        if (isMax) {
            val b = WalkPlay.parseBand(buf)
            require(b.registers.typeCode in nativeCodes) { "Unsupported ${caps.name} native type" }
            if (b.enabled) require(b.registers.freq.toDouble() in caps.freqMinHz..caps.freqMaxHz &&
                b.registers.q256 / 256.0 in (caps.qMin - 1.0 / 256)..(caps.qMax + 1.0 / 256) &&
                b.registers.gain256 / 256.0 in caps.gainMinDb..caps.gainMaxDb) { "Malformed ${caps.name} registers" }
            return b
        }
        if (!isTrn) require(buf.size >= 38 && buf[6].toInt() == 0 &&
            buf[7].toInt() == 0 && buf[35].toInt() == 0 && buf[37].toInt() == 0) { "Malformed PEQ reserved/end fields" }
        val b = WalkPlay.parseBand(buf)
        require(b.registers.typeCode in nativeCodes) { "Unsupported native type" }
        require(b.enabled) { "Uninitialized/disabled DAC register slot" }
        require(b.registers.freq.toDouble() in caps.freqMinHz..caps.freqMaxHz &&
            b.registers.q256 / 256.0 in (caps.qMin - 1.0 / 256)..(caps.qMax + 1.0 / 256) &&
            b.registers.gain256 / 256.0 in caps.gainMinDb..caps.gainMaxDb) { "Malformed TRN registers" }
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
        if (isMax) return WalkPlay.parsePreamp(buf)
        return WalkPlay.parsePreamp(buf).also { require(it in preampMin..preampMax) { "Malformed preamp register" } }
    }

    companion object {
        val MICRO = DeviceProtocol(ProtocolMicro.CAPABILITIES, 11)
        private val WITH_PASS = ProtocolMicro.CAPABILITIES.types + FilterType.LOW_PASS + FilterType.HIGH_PASS
        val MAX = DeviceProtocol(ProtocolMicro.CAPABILITIES.copy(name = "CrinEar Protocol Max", productId = 0x43CC, bands = 10), 16)
        /** [MAX] plus LOW PASS and HIGH PASS (vendor tool codes 4 and 5): advanced builds only, the same strict path ([isMax]). */
        val MAX_PASS = DeviceProtocol(MAX.caps.copy(types = WITH_PASS), 16)
        val TRN = DeviceProtocol(ProtocolMicro.CAPABILITIES.copy(name = "TRN Black Pearl", productId = 0x43E8, bands = 10, types = WITH_PASS), 16, true)
        val OFFLINE = DeviceProtocol(ProtocolMicro.CAPABILITIES.copy(name = "Offline editor", vendorId = 0, productId = 0, bands = 31), 0)
        fun walkplay(caps: DeviceCapabilities, scheme: Int, upstreamExperimental: Boolean = false): DeviceProtocol {
            require(scheme in setOf(10, 11, 13, 15, 16, 17, 18, 19, 20, 21))
            require(caps.bands in setOf(5, 6, 8, 10) && caps.types.isNotEmpty() &&
                caps.types.all { it in setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF) })
            return DeviceProtocol(caps, scheme, upstreamExperimental)
        }
        fun find(vendorId: Int, productId: Int, advanced: Boolean, productName: String? = null): DeviceProtocol? =
            (WalkPlayCatalog.resolve(vendorId, productId, productName, advanced) as? WalkPlayCatalog.Resolution.Ready)?.protocol
        /** Stable-build discovery (the 1.3.0 API): strict VID/PID pairs only, MICRO and MAX. */
        fun find(vendorId: Int, productId: Int): DeviceProtocol? = find(vendorId, productId, advanced = false)
    }
}
