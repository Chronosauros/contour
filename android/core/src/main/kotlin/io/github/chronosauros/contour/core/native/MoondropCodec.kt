package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Profile

/** READ-ONLY, pure JVM. Payloads EXCLUDE report ID; transport owns descriptor discovery/padding.
 * Native new handler (request bytes/field offsets):
 * https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/moondropUsbHidHandler.js#L26-L145
 * Native coefficient/metadata layout (write layout, NOT a captured RX guarantee):
 * https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/moondropUsbHidHandler.js#L194-L312
 * Old Fashioned:
 * https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/moondropOldFashionedUsbHidHandler.js#L3-L101
 *
 * New native RX has no pinned hardware capture. Parsers REQUIRE echoed native header/index/bank;
 * do not relax them if a device does not echo those fields. That variant is blocked, not WalkPlay.
 * Caller supplies actual descriptor input payload length, NOT a guessed universal 63/64-byte length.
 * No transaction IDs exist in source. Use one outstanding request at a time, discard stale input,
 * bind deviceKey to a connection generation, and never reuse replies after reconnect/timeout.
 * These constraints are conditional beta diagnostics, not proof of hardware echo semantics.
 */
object MoondropCodec {
    const val REPORT_ID = 0x4B
    const val IMPORT_BLOCKED_REASON = "Import blocked: metadata/coefficient exact inversion is not demonstrated for native " +
        "Moondrop, especially shelf slope versus editor Q; read 03 offset is not a proven effective preamp " +
        "or the write 23 register. Automatic headroom/enable state and bank ownership remain unknown. " +
        "Raw snapshots must not be flattened, assigned AUTO/zero preamp, or saved as equivalent editor profiles."
    enum class Kind { SLOT, BAND, PREGAIN, VERSION, OLD_REGISTER }
    enum class PreampStatus { UNKNOWN_EFFECTIVE }
    class Frame internal constructor(val kind: Kind, bytes: ByteArray, val index: Int? = null,
                                     val delayAfterMs: Int = 0) {
        private val bytes = bytes.copyOf()
        val payload: ByteArray get() = bytes.copyOf()
        val reportId: Int get() = REPORT_ID
        val mutating: Boolean get() = false
        val expectsReply: Boolean get() = true
        val minimumDescriptorOutputPayloadBytes: Int get() = bytes.size
    }
    data class Reply(val deviceKey: String, val reportId: Int, val payload: ByteArray)
    data class RegisterValue(val kind: Kind, val index: Int?, val bytes: List<Int>)
    data class RawBand internal constructor(
        val index: Int,
        val frequencyHz: Int,
        val qQ8: Int,
        val gainQ8: Int,
        val nativeType: Int,
        val bank: Int,
        /** Signed little-endian Q30 words in source order [b0,b1,b2,-a1,-a2], NOT normalized. */
        val coefficientsQ30: List<Int>,
        val coefficientBytes: List<Int>,
    ) {
        val q: Double get() = qQ8 / 256.0
        val gainDb: Double get() = gainQ8 / 256.0
    }
    data class Pregain(val rawQ8: Int, val observedDb: Double,
                       val status: PreampStatus = PreampStatus.UNKNOWN_EFFECTIVE)
    data class Version(val components: List<Int>, val raw: List<Int>)
    class Snapshot internal constructor(
        val model: MoondropCatalog.Model,
        val deviceKey: String,
        val bands: List<RawBand>,
        /** Every full response, including the two bracket slot reads, reserved bytes and padding. */
        val registers: List<RegisterValue>,
        val slot: Int,
        val pregain: Pregain,
    ) {
        val importBlockedReason: String get() = IMPORT_BLOCKED_REASON
        @Suppress("UNUSED_PARAMETER")
        fun importProfile(id: String, name: String, timestamp: Long): Profile =
            throw IllegalStateException(importBlockedReason)
    }
    class OldSnapshot internal constructor(val deviceKey: String, val registers: List<OldRegister>,
                                           val bands: List<OldBand>) {
        val model: MoondropCatalog.Model get() = MoondropCatalog.models.single { it.family == MoondropCatalog.Family.OLD_FASHIONED }
        val observedSlot: Int? get() = null // Upstream returns synthetic 0; not a readback.
        val observedPregainDb: Double? get() = null // No source pregain register is established.
        val preampStatus: PreampStatus get() = PreampStatus.UNKNOWN_EFFECTIVE
        val importBlockedReason: String get() = MoondropCatalog.OLD_BLOCKED_REASON
        @Suppress("UNUSED_PARAMETER")
        fun importProfile(id: String, name: String, timestamp: Long): Profile =
            throw IllegalStateException(importBlockedReason)
    }
    data class OldRegister internal constructor(val register: Int, val bytes: List<Int>)
    data class OldBand internal constructor(val index: Int, val frequencyHz: Int,
                                           val gainTenths: Int, val qMilli: Int) {
        val gainDb: Double get() = gainTenths / 10.0
        val q: Double get() = qMilli / 1000.0
        val importBlockedReason: String get() = MoondropCatalog.OLD_BLOCKED_REASON
    }

    fun slotQuery(): Frame = Frame(Kind.SLOT, byteArrayOf(0x80.toByte(), 0x0F, 0))
    fun versionQuery(): Frame = Frame(Kind.VERSION, byteArrayOf(0x80.toByte(), 0x0C))
    fun pregainQuery(): Frame = Frame(Kind.PREGAIN, byteArrayOf(0x80.toByte(), 0x03))
    fun bandQuery(index: Int): Frame {
        require(index in 0..7) { "Native Moondrop has eight band indices, no PID-group expansion" }
        return Frame(Kind.BAND, byteArrayOf(0x80.toByte(), 9, 24, 0, index.toByte(), 0), index)
    }
    /** Sequence: slot, ALL eight bands in order, observed offset, slot again. No slot selector.
     * Version is optional separately: upstream numeric version semantics are not a device identity.
     */
    fun readQueries(model: MoondropCatalog.Model): List<Frame> {
        requireCandidate(model)
        return listOf(slotQuery()) + (0 until model.bandCount).map { bandQuery(it) } +
            listOf(pregainQuery(), slotQuery())
    }
    private fun requireCandidate(model: MoondropCatalog.Model) {
        require(model in MoondropCatalog.models && model.readOnlyCandidate && model.bandCount == 8 &&
            model.reportId == REPORT_ID) { model.blockedReason ?: "Unqualified native Moondrop model" }
    }
    private fun correlated(reply: Reply, deviceKey: String, inputPayloadBytes: Int,
                           minimum: Int, opcode: Int): ByteArray {
        require(deviceKey.isNotBlank() && reply.deviceKey == deviceKey) { "Wrong Moondrop connection/deviceKey" }
        require(reply.reportId == REPORT_ID) { "Wrong Moondrop report ID; payload must exclude ID" }
        require(inputPayloadBytes >= minimum && reply.payload.size == inputPayloadBytes) {
            "Wrong Moondrop payload length; require exact descriptor input length and complete fields"
        }
        val p = reply.payload.copyOf()
        require(u(p[0]) == 0x80 && u(p[1]) == opcode) { "Wrong native Moondrop read ID/opcode" }
        return p
    }
    fun parseSlot(reply: Reply, deviceKey: String, inputPayloadBytes: Int): Int {
        val p = correlated(reply, deviceKey, inputPayloadBytes, 4, 0x0F)
        require(p[2] == 0.toByte()) { "Wrong native slot header" }
        return u(p[3]) // Preserve literal ID; do NOT translate 101 into 7 or assume either is writable.
    }
    fun parseVersion(reply: Reply, deviceKey: String, inputPayloadBytes: Int): Version {
        val p = correlated(reply, deviceKey, inputPayloadBytes, 6, 0x0C)
        // Source interprets three NUMERIC bytes, not WalkPlay's ASCII firmware string.
        return Version((3..5).map { u(p[it]) }, p.map { u(it) })
    }
    fun parsePregain(reply: Reply, deviceKey: String, inputPayloadBytes: Int): Pregain {
        val p = correlated(reply, deviceKey, inputPayloadBytes, 5, 0x03)
        val raw = s16(p, 3)
        return Pregain(raw, requireFinite(raw / 256.0, "observed offset"))
    }
    fun parseBand(reply: Reply, deviceKey: String, model: MoondropCatalog.Model,
                  index: Int, expectedSlot: Int, inputPayloadBytes: Int): RawBand {
        requireCandidate(model)
        require(index in 0 until model.bandCount && expectedSlot in 0..255) { "Invalid band index/expected slot" }
        val p = correlated(reply, deviceKey, inputPayloadBytes, 36, 9)
        require(u(p[2]) == 24 && p[3] == 0.toByte() && u(p[4]) == index &&
            p[5] == 0.toByte() && p[6] == 0.toByte()) { "Native header/index echo missing or out of order" }
        require(u(p[35]) == expectedSlot) { "Band bank does not match bracket slot; 101/7 mapping is unproven" }
        val frequency = u16(p, 27)
        val q = s16(p, 29) // Preserve source signed Q8, never turn negative values into unsigned Q.
        val gain = s16(p, 31)
        val type = u(p[33])
        require(type in setOf(1, 2, 3) && type in model.nativeTypes) { "Unknown/model-disallowed native type $type" }
        require(frequency in 11..23999 && q > 0) { "Invalid native band, no disabled/flat filling" }
        requireFinite(q / 256.0, "Q"); requireFinite(gain / 256.0, "gain")
        return RawBand(index, frequency, q, gain, type, u(p[35]),
            (0..4).map { s32(p, 7 + it * 4) }, (7..26).map { u(p[it]) })
    }
    /** Exact ordered completion only. An uncorrelated/failed offset read is NOT zero preamp.
     * No partially initialized snapshot or fabricated flat bands can escape this function.
     */
    fun snapshot(model: MoondropCatalog.Model, deviceKey: String, replies: List<Reply>,
                 inputPayloadBytes: Int): Snapshot {
        val queries = readQueries(model)
        require(replies.size == queries.size) { "Moondrop snapshot needs every ordered response including both slot reads" }
        val slot = parseSlot(replies.first(), deviceKey, inputPayloadBytes)
        val after = parseSlot(replies.last(), deviceKey, inputPayloadBytes)
        require(slot == after) { "Active native bank changed during read" }
        val bands = (0 until model.bandCount).map { i ->
            parseBand(replies[i + 1], deviceKey, model, i, slot, inputPayloadBytes)
        }
        val pregain = parsePregain(replies[model.bandCount + 1], deviceKey, inputPayloadBytes)
        val registers = queries.zip(replies).map { (frame, reply) ->
            RegisterValue(frame.kind, frame.index, reply.payload.map { u(it) })
        }
        return Snapshot(model, deviceKey, bands, registers, slot, pregain)
    }

    /** Source-exact R packets for diagnostic/manual qualification ONLY; readQueries(Old Fashioned)
     * is blocked because upstream never establishes address/command response echo semantics.
     * No WalkPlay synthetic slot 0, no pregain read, no auto-import, and no W/S commands.
     */
    fun oldRegisterQuery(register: Int): Frame {
        require(register in 38..47) { "Only source five-band Old Fashioned register addresses" }
        return Frame(Kind.OLD_REGISTER,
            byteArrayOf(register.toByte(), 0, 0, 0, 0x52, 0, 0, 0, 0, 0), register, 100)
    }
    /** Conditional echo contract, NOT evidence the hardware echoes. Any missing echo fails closed. */
    fun parseOldRegister(reply: Reply, deviceKey: String, register: Int, inputPayloadBytes: Int): OldRegister {
        require(register in 38..47)
        require(deviceKey.isNotBlank() && reply.deviceKey == deviceKey && reply.reportId == REPORT_ID) {
            "Wrong Old Fashioned connection/report ID"
        }
        require(inputPayloadBytes >= 10 && reply.payload.size == inputPayloadBytes) { "Incomplete/wrong Old Fashioned payload length" }
        val p = reply.payload.copyOf()
        require(u(p[0]) == register && u(p[4]) == 0x52 && listOf(1, 2, 3, 5).all { p[it] == 0.toByte() }) {
            "Unproven/wrong Old Fashioned register/command echo; no accept-any-report fallback"
        }
        return OldRegister(register, p.map { u(it) })
    }
    fun decodeOldBand(index: Int, gainFrequency: OldRegister, qRegister: OldRegister): OldBand {
        require(index in 0..4 && gainFrequency.register == 38 + 2 * index &&
            qRegister.register == gainFrequency.register + 1) { "Wrong Old Fashioned index/register order" }
        val gf = gainFrequency.bytes.map { it.toByte() }.toByteArray()
        val qt = qRegister.bytes.map { it.toByte() }.toByteArray()
        val frequency = u16(gf, 8); val gain = gf[6].toInt(); val q = s16(qt, 6)
        require(frequency in 11..23999 && q > 0) { "Invalid Old Fashioned band; no flat filling" }
        return OldBand(index, frequency, gain, q)
    }
    /** Diagnostic-only assembly, never used by automatic model routing while echo proof is absent. */
    fun oldDiagnosticSnapshot(deviceKey: String, replies: List<Reply>, inputPayloadBytes: Int): OldSnapshot {
        require(replies.size == 10) { "Old Fashioned diagnostic snapshot needs all ten ordered registers" }
        val registers = (38..47).mapIndexed { i, reg -> parseOldRegister(replies[i], deviceKey, reg, inputPayloadBytes) }
        val bands = (0..4).map { i -> decodeOldBand(i, registers[2 * i], registers[2 * i + 1]) }
        return OldSnapshot(deviceKey, registers, bands)
    }
    // All actual wire fields are fixed-width integers, hence cannot encode IEEE NaN/Infinity.
    // Keep this guard if later transforms are added; never convert nonfinite values to flat bands.
    internal fun requireFinite(value: Double, field: String): Double {
        require(value.isFinite()) { "Nonfinite Moondrop $field" }; return value
    }
    private fun u(b: Byte) = b.toInt() and 255
    private fun u16(p: ByteArray, i: Int) = u(p[i]) or (u(p[i + 1]) shl 8)
    private fun s16(p: ByteArray, i: Int) = u16(p, i).toShort().toInt()
    private fun s32(p: ByteArray, i: Int) = u(p[i]) or (u(p[i + 1]) shl 8) or
        (u(p[i + 2]) shl 16) or (u(p[i + 3]) shl 24)
}
