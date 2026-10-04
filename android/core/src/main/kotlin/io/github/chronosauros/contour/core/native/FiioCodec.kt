package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Logical HID payloads only: report ID, descriptor size and padding belong to transport.
 * Source: devicePEQ@0617f382e76629792a5933e6933e4b396a756a93,
 * devicePEQ/fiioUsbHidHandler.js L321-535; compensation.js L418-442.
 * Replies contain opaque sequence/checksum bytes and sometimes stale trailing report data.
 * Neither is a proven acknowledgement, checksum algorithm, or persistence guarantee.
 */
enum class FiioEffect { READ_ONLY, SELECT_ACTIVE_USER_BANK, WRITE_ACTIVE_BANK, SAVE_USER_BANK, RENAME_USER_BANK }
data class FiioFrame(val reportId: Int, val bytes: List<Int>, val effect: FiioEffect) {
    fun payload(): ByteArray = bytes.map { it.toByte() }.toByteArray()
}
data class FiioRegisters(val index: Int, val frequencyHz: Int, val gainTenths: Int, val qHundredths: Int, val typeCode: Int)
data class FiioSnapshot(val productName: String, val activeSlot: Int, val count: Int, val preampTenths: Int, val bands: List<FiioRegisters>)
data class FiioExactEq(val bands: List<Band>, val preampDb: Double)
enum class FiioSaveVerification { POST_SAVE_READ_REQUIRED, RECONNECT_AND_READ_REQUIRED }
/** The selection barrier MUST complete before writes: select -> query slot -> complete backup.
 * No mutation on connect, automatic pull, local picker change, or plan compilation.
 * Caller must correlate report ID, command AND index; sequential queries are recommended.
 * Abort on timeout, duplicate/conflicting response, slot drift, or incomplete read.
 */
data class FiioWritePlan(
    val productName: String,
    val targetSlot: Int,
    val expected: FiioSnapshot,
    val selection: FiioFrame,
    val selectionVerification: FiioFrame,
    val backupQueries: List<FiioFrame>,
    val writes: List<FiioFrame>,
    val save: FiioFrame,
    val verificationQueries: List<FiioFrame>,
    val saveVerification: FiioSaveVerification,
    val delayAfterCountMs: Int = 100,
    val delayBeforeSaveMs: Int = 100,
    // KA15 stops answering for a while after a bank switch and after a flash save (200 ms was too short).
    val delayAfterSelectMs: Int = 300,
    val delayAfterSaveMs: Int = 800,
)
sealed interface FiioVerification {
    data object RegistersMatchPersistenceUnproven : FiioVerification
    data class Pending(val reason: String) : FiioVerification
    data class Mismatch(val reason: String) : FiioVerification
}

class FiioCodec(val config: FiioConfig) {
    companion object {
        const val BAND = 0x15
        const val SLOT = 0x16
        const val PREAMP = 0x17
        const val COUNT = 0x18
        const val NAME = 0x30
        const val NAME_LENGTH = 7
        private const val NAME_REPLY_LEN = 8
        private const val NAME_REPLY_DATA = 10
        /** A USER slot name the DAC is known to take: A-Z and 0-9 only (all the KA15 capture shows), at most 7. */
        fun slotName(text: String): String =
            java.text.Normalizer.normalize(text.replace('ł', 'l').replace('Ł', 'L'), java.text.Normalizer.Form.NFD)
                .uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(NAME_LENGTH)
        private fun u(b: Byte) = b.toInt() and 255
        private fun word(p: ByteArray, offset: Int) = (u(p[offset]) shl 8) or u(p[offset + 1])
        private fun signed(p: ByteArray, offset: Int) = word(p, offset).toShort().toInt()
        // JS Math.round tie direction, not Kotlin bankers rounding.
        private fun quantize(v: Double, scale: Int) = floor(v * scale + 0.5).toInt()
    }

    init {
        require(config.reportId in 1..255 && config.maxFilters in 1..255)
        require(config.saveCommand == 0x19 || config.saveCommand == 0x21)
        require(config.minGainDb.isFinite() && config.maxGainDb.isFinite() && config.minGainDb <= config.maxGainDb)
        require(config.minQ.isFinite() && config.maxQ.isFinite() && config.minQ > 0 && config.minQ <= config.maxQ)
        require(config.minFrequencyHz >= 1 && config.maxFrequencyHz <= 65535 && config.minFrequencyHz <= config.maxFrequencyHz)
        require(config.typeCodes.keys.all { it in setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF) })
        require(config.typeCodes.isNotEmpty() && config.typeCodes.values.toSet().size == config.typeCodes.size)
        require(config.typeCodes.values.all { it in 0..255 })
        require(config.userSlots.all { it in 0..255 && it in config.declaredWritableSlots && it !in config.stockSlots && it != config.bypassSlot })
    }

    private var sequence = 0
    private fun frame(cmd: Int, data: List<Int>, effect: FiioEffect): FiioFrame {
        require(data.all { it in 0..255 })
        val header = if (effect == FiioEffect.READ_ONLY) listOf(0xBB, 0x0B) else listOf(0xAA, 0x0A)
        if (!config.checksumFrames) return FiioFrame(config.reportId, header + listOf(0, 0, cmd, data.size) + data + listOf(0, 0xEE), effect)
        val body = header + listOf(0, sequence++ and 255, cmd, data.size) + data
        return FiioFrame(config.reportId, body + listOf(crc8Maxim(body), 0xEE), effect)
    }
    /** CRC-8/MAXIM (poly 0x31 reflected, init 0), as the official web app computes it. */
    private fun crc8Maxim(bytes: List<Int>): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor b
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc
    }
    fun querySlot() = frame(SLOT, emptyList(), FiioEffect.READ_ONLY)
    fun queryCount() = frame(COUNT, emptyList(), FiioEffect.READ_ONLY)
    fun queryPreamp() = frame(PREAMP, emptyList(), FiioEffect.READ_ONLY)
    fun queryBand(index: Int): FiioFrame {
        require(index in 0 until config.maxFilters) { "Filter index out of range" }
        return frame(BAND, listOf(index), FiioEffect.READ_ONLY)
    }
    /** Active bank only. No slot argument: upstream ignores it and cannot read inactive banks. */
    fun activeReadHeaderQueries() = listOf(querySlot(), queryCount(), queryPreamp())
    fun activeBandQueries(count: Int): List<FiioFrame> {
        validateCount(count)
        return List(count) { queryBand(it) }
    }
    /** Explicit full active-bank read recipe including an ending slot guard. */
    fun activeReadQueries(count: Int) = activeReadHeaderQueries() + activeBandQueries(count) + querySlot()

    private fun requireUserSlot(slot: Int) {
        require(config.codecBlockers.isEmpty()) { config.codecBlockers.joinToString("; ") }
        require(slot in config.userSlots) { "Not a proven writable user slot: $slot" }
    }
    /** Only use inside explicit Send, NEVER in a slot picker or automatic read. */
    fun selectUserSlot(slot: Int): FiioFrame {
        requireUserSlot(slot)
        return frame(SLOT, listOf(slot), FiioEffect.SELECT_ACTIVE_USER_BANK)
    }
    private fun nameIndex(slot: Int): Int {
        require(config.userSlotNames) { "${config.productName}: USER names are not proven" }
        requireUserSlot(slot)
        return config.userSlots.sorted().indexOf(slot)
    }
    fun queryName(slot: Int) = frame(NAME, listOf(nameIndex(slot)), FiioEffect.READ_ONLY)
    /** Always the full 7-byte field, NUL padded: a shorter write leaves the old name's tail on the DAC. */
    fun writeName(slot: Int, name: String): FiioFrame {
        require(name.isNotEmpty() && name == slotName(name)) { "Unsupported USER name: $name" }
        return frame(NAME, listOf(nameIndex(slot)) + name.map { it.code } + List(NAME_LENGTH - name.length) { 0 }, FiioEffect.RENAME_USER_BANK)
    }
    /** The name reply says LEN 8 but carries IDX plus a 9-byte field (CRC-checked on the Pixel, 04.10.2026).
     * Only its first 7 bytes are the name: after a save the last two held stale report bytes (`ab ee`). */
    fun parseName(payload: ByteArray, slot: Int): String {
        reply(payload, NAME, NAME_REPLY_DATA, NAME_REPLY_LEN)
        require(u(payload[6]) == nameIndex(slot)) { "Uncorrelated USER name index" }
        return (7 until 7 + NAME_LENGTH).map { u(payload[it]) }.takeWhile { it != 0 }
            .filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
    }
    private fun validateCount(count: Int) { require(count in 0..config.maxFilters) { "Invalid active count: $count" } }
    private fun range(value: Double, min: Double, max: Double, field: String) {
        require(value.isFinite() && value in min..max) { "$field outside [$min, $max]: $value" }
    }

    /** Strict logical reply, excluding report ID and padding. Opaque bytes 2/3 and trailer are
     * preserved by the caller's capture but not interpreted (non-zero in genuine captures).
     */
    private fun reply(payload: ByteArray, cmd: Int, dataLength: Int, lenField: Int = dataLength) {
        require(payload.size == dataLength + 8) { "Wrong logical reply length" }
        require(u(payload[0]) == 0xBB && u(payload[1]) == 0x0B) { "Wrong reply header/direction" }
        require(u(payload[4]) == cmd && u(payload[5]) == lenField) { "Wrong command/data length" }
        require(u(payload.last()) == 0xEE) { "Missing reply terminator" }
    }
    /** Explicit transport adapter: descriptor-derived actualPayloadSize, not a family default.
     * Validate the logical header/length/terminator before discarding opaque report tail.
     * Captures demonstrate non-zero stale tails, so a zero-padding requirement would be wrong.
     */
    fun logicalReply(reportId: Int, payload: ByteArray, actualPayloadSize: Int): ByteArray {
        require(reportId == config.reportId && payload.size == actualPayloadSize && actualPayloadSize >= 8)
        val cmd = u(payload[4])
        val n = when (cmd) { SLOT, COUNT -> 1; PREAMP -> 2; BAND -> 8; NAME -> NAME_REPLY_DATA; config.saveCommand -> 1; else -> throw IllegalArgumentException("Unsupported reply command: $cmd") }
        require(payload.size >= n + 8) { "Short transport payload" }
        val logical = payload.copyOf(n + 8)
        reply(logical, cmd, n, if (cmd == NAME) NAME_REPLY_LEN else n)
        return logical
    }
    fun parseSlot(payload: ByteArray): Int { reply(payload, SLOT, 1); return u(payload[6]) }
    fun parseCount(payload: ByteArray): Int {
        reply(payload, COUNT, 1)
        return u(payload[6]).also { validateCount(it) }
    }
    fun parsePreamp(payload: ByteArray): Int {
        reply(payload, PREAMP, 2)
        return signed(payload, 6).also { range(it / 10.0, config.minGainDb, config.maxGainDb, "Pregain") }
    }
    fun parseBand(payload: ByteArray, expectedIndex: Int, activeCount: Int): FiioRegisters {
        validateCount(activeCount)
        require(expectedIndex in 0 until activeCount)
        reply(payload, BAND, 8)
        require(u(payload[6]) == expectedIndex) { "Uncorrelated band index" }
        return FiioRegisters(expectedIndex, word(payload, 9), signed(payload, 7), word(payload, 11), u(payload[13])).also { validateRegisters(it) }
    }
    /** A save response only echoes the slot: not evidence of flash persistence. */
    fun parseSaveSlot(payload: ByteArray, expectedSlot: Int): Int {
        requireUserSlot(expectedSlot)
        reply(payload, config.saveCommand, 1)
        return u(payload[6]).also { require(it == expectedSlot) { "Save slot mismatch" } }
    }
    private fun validateRegisters(r: FiioRegisters) {
        require(r.index in 0 until config.maxFilters)
        require(r.typeCode in config.typeCodes.values) { "Unsupported native filter type ${r.typeCode}; never coerce to PK" }
        range(r.frequencyHz.toDouble(), config.minFrequencyHz.toDouble(), config.maxFrequencyHz.toDouble(), "Frequency")
        range(r.gainTenths / 10.0, config.minGainDb, config.maxGainDb, "Gain")
        range(r.qHundredths / 100.0, config.minQ, config.maxQ, "Native Q")
    }
    /** Complete active snapshot. Opening and closing slot queries must agree. No missing preamp
     * or count defaults, duplicate index overwrite, or inference of max capacity from active count.
     */
    fun snapshot(slotReply: ByteArray, countReply: ByteArray, preampReply: ByteArray,
                 bandReplies: List<ByteArray>, closingSlotReply: ByteArray): FiioSnapshot {
        val slot = parseSlot(slotReply)
        require(slot == parseSlot(closingSlotReply)) { "Active bank changed during read" }
        val count = parseCount(countReply)
        require(bandReplies.size == count) { "Incomplete/extra filters" }
        val indices = bandReplies.map { require(it.size >= 7); u(it[6]) }
        require(indices.toSet() == (0 until count).toSet() && indices.distinct().size == count) { "Duplicate/missing/out-of-range band indices" }
        val bands = bandReplies.map { parseBand(it, u(it[6]), count) }.sortedBy { it.index }
        return FiioSnapshot(config.productName, slot, count, parsePreamp(preampReply), bands)
    }
    private fun validateSnapshot(s: FiioSnapshot) {
        require(s.productName == config.productName)
        require(s.activeSlot in 0..255)
        validateCount(s.count)
        require(s.bands.size == s.count && s.bands.map { it.index }.toSet() == (0 until s.count).toSet())
        s.bands.forEach { validateRegisters(it) }
        range(s.preampTenths / 10.0, config.minGainDb, config.maxGainDb, "Pregain")
    }
    private fun amplitude(gain: Double) = 10.0.pow(abs(gain) / 40.0)
    private fun nativeQ(type: FilterType, q: Double, gain: Double): Double {
        if (type == FilterType.PEAK && config.peakingGainCompensation) return q * amplitude(gain)
        if (type != FilterType.PEAK && config.shelfAlphaCompensation) {
            val a = amplitude(gain)
            val radicand = (a + 1 / a) * (1 / q - 1) + 2
            require(radicand.isFinite() && radicand > 0) { "Unrepresentable shelf slope" }
            return 1 / sqrt(radicand)
        }
        return q
    }
    private fun intendedQ(type: FilterType, q: Double, gain: Double): Double {
        if (type == FilterType.PEAK && config.peakingGainCompensation) return q / amplitude(gain)
        if (type != FilterType.PEAK && config.shelfAlphaCompensation) {
            val a = amplitude(gain)
            val denominator = 1 + (1 / (q * q) - 2) / (a + 1 / a)
            require(denominator.isFinite() && denominator > 0) { "Unsupported stored shelf slope" }
            return 1 / denominator
        }
        return q
    }
    private fun compileBand(band: Band, index: Int): FiioRegisters {
        val code = config.typeCodes[band.type] ?: throw IllegalArgumentException("Unsupported filter type ${band.type}")
        range(band.freqHz, config.minFrequencyHz.toDouble(), config.maxFrequencyHz.toDouble(), "Frequency")
        range(band.gainDb, config.minGainDb, config.maxGainDb, "Gain")
        require(band.q.isFinite() && band.q > 0) { "Invalid Q" }
        // Disabled filters retain shape/frequency/Q but write exactly zero gain. Validate all input.
        // Source splitUnsignedValue and fiioGainBytesFromValue use JS bitwise truncation;
        // only pregain and Q use Math.round. Compensation uses the realised native gain.
        val gain = if (band.enabled) (band.gainDb * 10).toInt() else 0
        val q = nativeQ(band.type, band.q, gain / 10.0)
        // Only an ULP-scale inverse-transform allowance, never clamp an unrepresentable Q.
        // This lets exact native boundary registers survive floating-point inverse/import.
        require(q.isFinite() && q >= config.minQ - 4 * Math.ulp(config.minQ) &&
            q <= config.maxQ + 4 * Math.ulp(config.maxQ)) { "Compensated native Q out of range: $q" }
        return FiioRegisters(index, band.freqHz.toInt(), gain, quantize(q, 100), code).also { validateRegisters(it) }
    }
    private fun pair(v: Int) = listOf((v ushr 8) and 255, v and 255)
    private fun writeBand(r: FiioRegisters) = frame(BAND,
        listOf(r.index) + pair(r.gainTenths) + pair(r.frequencyHz) + pair(r.qHundredths) + listOf(r.typeCode), FiioEffect.WRITE_ACTIVE_BANK)

    /** All validation and compilation precede ANY returned mutation. Null preamp is not 0 or auto.
     * Writes exactly the explicit active count: lowering count disables the inactive tail; it does
     * not silently truncate the local profile. A later longer plan overwrites every new active index.
     * backupQueries are the destination read HEADER, not a complete backup: after COUNT use
     * activeBandQueries(actualCount) then closing querySlot, never assume max is active count.
     */
    fun planOnExplicitSend(profile: Profile, targetSlot: Int, explicitUserAction: Boolean): FiioWritePlan =
        planOnExplicitSend(profile.bands, profile.preampDb, targetSlot, explicitUserAction)

    fun planOnExplicitSend(bands: List<Band>, preampDb: Double?, targetSlot: Int, explicitUserAction: Boolean): FiioWritePlan {
        require(explicitUserAction) { "Mutation requires explicit user Send" }
        requireUserSlot(targetSlot)
        validateCount(bands.size)
        val preamp = requireNotNull(preampDb) { "Explicit pregain required; missing is not zero" }
        range(preamp, config.minGainDb, config.maxGainDb, "Pregain")
        val registers = bands.mapIndexed { index, band -> compileBand(band, index) }
        val expected = FiioSnapshot(config.productName, targetSlot, bands.size, quantize(preamp, 10), registers)
        val writes = listOf(frame(PREAMP, pair(expected.preampTenths), FiioEffect.WRITE_ACTIVE_BANK),
            frame(COUNT, listOf(expected.count), FiioEffect.WRITE_ACTIVE_BANK)) + registers.map { writeBand(it) }
        return FiioWritePlan(config.productName, targetSlot, expected, selectUserSlot(targetSlot), querySlot(),
            activeReadHeaderQueries(), writes, frame(config.saveCommand, listOf(targetSlot), FiioEffect.SAVE_USER_BANK),
            activeReadQueries(expected.count), if (config.disconnectOnSave) FiioSaveVerification.RECONNECT_AND_READ_REQUIRED else FiioSaveVerification.POST_SAVE_READ_REQUIRED)
    }
    /** Integration barrier: hold parameter writes until both the explicit selection query and a
     * complete destination backup prove this user bank is active. Data acquisition stays in the
     * caller; this method performs no I/O and cannot attest freshness of caller-supplied replies.
     */
    fun writesAfterSelection(plan: FiioWritePlan, selectedSlotReply: ByteArray, destinationBackup: FiioSnapshot): List<FiioFrame> {
        require(plan.productName == config.productName)
        requireUserSlot(plan.targetSlot)
        require(parseSlot(selectedSlotReply) == plan.targetSlot) { "Selection not confirmed" }
        validateSnapshot(destinationBackup)
        require(destinationBackup.activeSlot == plan.targetSlot) { "Backup is not the selected destination" }
        return plan.writes
    }
    /** Devices that always keep [FiioConfig.maxFilters] filters get a neutral peaking tail (0 dB, 100 Hz, Q 1.00,
     * the padding the upstream web tool captured on the KA15). Zero gain makes the tail inaudible. */
    fun padToDeviceCount(bands: List<Band>): List<Band> {
        if (!config.fixedBandCount || bands.size >= config.maxFilters) return bands
        return bands + List(config.maxFilters - bands.size) { Band("pad-${bands.size + it}", FilterType.PEAK, 100.0, 0.0, 1.0, enabled = true) }
    }

    /** Raw native-register comparison; no float tolerance hiding loss or mismatched count/slot.
     * Only configs with [FiioConfig.qReadbackSlack] accept the device's small Q readback drift, nothing else. */
    fun matches(expected: FiioSnapshot, actual: FiioSnapshot): Boolean {
        validateSnapshot(expected)
        validateSnapshot(actual)
        val e = expected.copy(bands = expected.bands.sortedBy { it.index })
        val a = actual.copy(bands = actual.bands.sortedBy { it.index })
        if (!config.qReadbackSlack) return e == a
        return e.copy(bands = emptyList()) == a.copy(bands = emptyList()) && e.bands.size == a.bands.size &&
            e.bands.zip(a.bands).all { (x, y) ->
                x.copy(qHundredths = 0) == y.copy(qHundredths = 0) && abs(x.qHundredths - y.qHundredths) <= 1 + x.qHundredths / 250
            }
    }
    fun verification(plan: FiioWritePlan, postSave: FiioSnapshot?, reconnected: Boolean = false): FiioVerification {
        require(plan.productName == config.productName && plan.expected.activeSlot == plan.targetSlot)
        if (postSave == null) return FiioVerification.Pending("Complete post-save active read required")
        if (!matches(plan.expected, postSave)) return FiioVerification.Mismatch("Slot/count/pregain/native registers differ")
        if (plan.saveVerification == FiioSaveVerification.RECONNECT_AND_READ_REQUIRED && !reconnected)
            return FiioVerification.Pending("Disconnect-on-save requires reconnect and complete read")
        return FiioVerification.RegistersMatchPersistenceUnproven
    }
    /** Exact inverse import. Unknown type or unrepresentable shelf is rejected, never mapped to PK.
     * Recompilation must recover ALL registers before returning an editable local EQ.
     */
    fun importExact(snapshot: FiioSnapshot): FiioExactEq {
        validateSnapshot(snapshot)
        val bands = snapshot.bands.sortedBy { it.index }.map { r ->
            val type = config.typeCodes.entries.single { it.value == r.typeCode }.key
            Band("fiio-${r.index}", type, r.frequencyHz.toDouble(), r.gainTenths / 10.0,
                intendedQ(type, r.qHundredths / 100.0, r.gainTenths / 10.0), enabled = true)
        }
        require(bands.mapIndexed { i, b -> compileBand(b, i) } == snapshot.bands.sortedBy { it.index }) { "Import cannot reproduce exact native registers" }
        return FiioExactEq(bands, snapshot.preampTenths / 10.0)
    }
}
