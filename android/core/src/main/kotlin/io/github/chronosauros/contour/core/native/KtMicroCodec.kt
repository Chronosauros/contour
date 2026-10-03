package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import kotlin.math.floor

/** Independent pure-JVM implementation of KT register framing, based on pinned devicePEQ
 * 0617f382 ktmicroUsbHidHandler.js L43-107,181-307 and capture data. Payloads EXCLUDE ID.
 * No USB I/O, implicit enable, clear-on-connect, firmware headroom or write-pregain assumption.
 */
object KtMicroCodec {
    const val REPORT_ID = 0x4B
    const val PAYLOAD_BYTES = 10
    const val READ = 0x52
    const val WRITE = 0x57
    const val SAVE = 0x53
    const val CLEAR = 0x43
    const val SLOT_REGISTER = 0x24
    const val PREGAIN_REGISTER = 0x66

    class Frame internal constructor(bytes: ByteArray, val mutating: Boolean,
                                     val delayAfterMs: Int = 0, val expectsReply: Boolean = false) {
        private val bytes = bytes.copyOf()
        val payload: ByteArray get() = bytes.copyOf()
        val reportId: Int get() = REPORT_ID
        /** Transport must discover Output/Input sizes in descriptor, pad separately, never assume 64. */
        val minimumDescriptorPayloadBytes: Int get() = PAYLOAD_BYTES
    }
    data class Reply(val deviceKey: String, val reportId: Int, val payload: ByteArray)
    data class RegisterValue(val register: Int, val value: List<Int>) {
        init { require(register in 0..255 && value.size == 4 && value.all { it in 0..255 }) }
    }
    data class RawBand(val index: Int, val gainTenths: Int, val storedFrequencyHz: Int,
                       val qMilli: Int, val nativeType: Int, val qReservedByte: Int = 0) {
        init {
            require(index in 0..4 && gainTenths in Short.MIN_VALUE..Short.MAX_VALUE)
            require(storedFrequencyHz in 0..65535 && qMilli in 0..65535 && qReservedByte in 0..255)
            type(nativeType)
        }
    }
    class Snapshot internal constructor(val model: KtMicroCatalog.Model, val deviceKey: String,
        val bands: List<RawBand>, val registers: List<RegisterValue>, val slot: Int, val pregainDb: Int) {
        /** Exact import preserves order and disabled EQ state separately; no flat-fill or hidden compensation. */
        fun importProfile(id: String, name: String, timestamp: Long): Profile = Profile(
            id = id, name = name, bands = bands.map {
                Band("kt-${it.index}", type(it.nativeType),
                    it.storedFrequencyHz * (if (model.compensate2X) 2.0 else 1.0),
                    it.gainTenths / 10.0, it.qMilli / 1000.0)
            }, preampDb = pregainDb.toDouble(), createdAt = timestamp, updatedAt = timestamp)
    }
    class Plan internal constructor(val model: KtMicroCatalog.Model, val deviceKey: String,
        val frames: List<Frame>, val expectedRegisters: List<RegisterValue>,
        val expectedBands: List<RawBand>, val expectedSlot: Int, val verificationQueries: List<Frame>) {
        val reconnectAfterSave: Boolean get() = model.reconnectAfterSave
    }

    private fun packet(reg: Int, cmd: Int, value: List<Int> = listOf(0, 0, 0, 0)): ByteArray {
        require(reg in 0..255 && value.size == 4 && value.all { it in 0..255 })
        return byteArrayOf(reg.toByte(), 0, 0, 0, cmd.toByte(), 0,
            value[0].toByte(), value[1].toByte(), value[2].toByte(), value[3].toByte())
    }
    fun readRegister(register: Int): Frame = Frame(packet(register, READ,
        if (register == SLOT_REGISTER) listOf(3, 0, 0, 0) else listOf(0, 0, 0, 0)),
        mutating = false, expectsReply = true)
    fun readQueries(model: KtMicroCatalog.Model): List<Frame> =
        listOf(readRegister(SLOT_REGISTER)) + model.registers.bands.flatMap {
            listOf(readRegister(it.gainFrequency), readRegister(it.qType))
        } + readRegister(PREGAIN_REGISTER)

    /** Caller binds pending request and response to the SAME connection generation/deviceKey. */
    fun parseRegister(reply: Reply, deviceKey: String, register: Int, command: Int = READ): RegisterValue {
        require(reply.deviceKey == deviceKey && reply.reportId == REPORT_ID) { "Wrong KT device/report ID" }
        require(command == READ || command == WRITE) { "Only correlated register responses" }
        val p = reply.payload
        require(p.size >= PAYLOAD_BYTES && u(p[0]) == register && u(p[4]) == command) { "Wrong KT register/command/length" }
        require(listOf(1, 2, 3, 5).all { p[it] == 0.toByte() }) { "Malformed KT header" }
        return RegisterValue(register, (6..9).map { u(p[it]) })
    }
    fun parseSlot(value: RegisterValue): Int {
        require(value.register == SLOT_REGISTER && value.value[0] in setOf(2, 3)) { "Unqualified KT slot (including ASCII year/slot 1)" }
        return value.value[0]
    }
    fun parsePregain(value: RegisterValue): Int {
        require(value.register == PREGAIN_REGISTER)
        return value.value[0].let { if (it > 127) it - 256 else it }
    }
    fun decodeBand(index: Int, map: KtMicroCatalog.RegisterMap, gf: RegisterValue, qt: RegisterValue): RawBand {
        require(index in map.bands.indices)
        require(gf.register == map.bands[index].gainFrequency && qt.register == map.bands[index].qType)
        val gain = le(gf.value, 0).let { if (it > 32767) it - 65536 else it }
        return RawBand(index, gain, le(gf.value, 2), le(qt.value, 0), qt.value[2], qt.value[3])
    }
    /** Low-level roundtrip preserves all four register bytes, including reserved capture values.
     * It does NOT authorize writing an out-of-policy captured value or a blocked model.
     */
    fun encodeRawBand(map: KtMicroCatalog.RegisterMap, band: RawBand): List<RegisterValue> {
        val pair = map.bands[band.index]
        return listOf(RegisterValue(pair.gainFrequency, word(band.gainTenths) + word(band.storedFrequencyHz)),
            RegisterValue(pair.qType, word(band.qMilli) + listOf(band.nativeType, band.qReservedByte)))
    }
    fun snapshot(model: KtMicroCatalog.Model, deviceKey: String, replies: List<Reply>): Snapshot {
        val expected = readQueries(model).map { u(it.payload[0]) }.toSet()
        require(replies.size == expected.size) { "KT snapshot needs every register exactly once" }
        val values = replies.map {
            require(it.payload.isNotEmpty())
            val reg = u(it.payload[0]); require(reg in expected)
            parseRegister(it, deviceKey, reg)
        }
        require(values.map { it.register }.toSet() == expected) { "Missing/duplicate KT register" }
        val byReg = values.associateBy { it.register }
        val bands = model.registers.bands.mapIndexed { i, pair ->
            decodeBand(i, model.registers, byReg.getValue(pair.gainFrequency), byReg.getValue(pair.qType)).also {
                require(type(it.nativeType) in model.nativeTypes) { "KT native type not allowed by model" }
            }
        }
        return Snapshot(model, deviceKey, bands, values.sortedBy { it.register },
            parseSlot(byReg.getValue(SLOT_REGISTER)), parsePregain(byReg.getValue(PREGAIN_REGISTER)))
    }

    /** Full preflight before ANY frame is returned. Unsupported pregain must never be silently dropped. */
    fun compile(profile: Profile, before: Snapshot, slot: Int = 3): Plan {
        val model = before.model
        require(model in KtMicroCatalog.models && model.admitted) { "Blocked/unqualified KT model" }
        require(slot == 3 && before.slot in setOf(2, 3)) { "Only validated KT Custom 3" }
        require(profile.bands.size <= 5) { "KT supports five bands; no truncation" }
        profile.bands.forEach { validateBand(model, it) }
        val requestedPregain = profile.effectivePreampDb()
        require(requestedPregain.isFinite() && requestedPregain == 0.0) { "KT cannot implement manual/AUTO attenuation" }
        // No 0x66 write is justified. A nonzero device pregain cannot implement an explicit zero profile.
        require(before.pregainDb == 0) { "Nonzero device pregain is read-only; cannot realize requested 0 dB" }
        val bands = List(5) { i ->
            val b = profile.bands.getOrNull(i)
            if (b == null || !b.enabled) RawBand(i, 0, if (model.compensate2X) 50 else 100, 1000, 0)
            else RawBand(i, quantize(b.gainDb * 10), quantize(b.freqHz / if (model.compensate2X) 2 else 1),
                quantize(b.q * 1000), typeCode(b.type))
        }
        val encoded = bands.flatMap { encodeRawBand(model.registers, it) }
        val writes = mutableListOf<Frame>()
        if (before.slot == 2) writes += Frame(packet(SLOT_REGISTER, WRITE, listOf(3, 0, 0, 0)),
            mutating = true, expectsReply = true)
        writes += encoded.map { Frame(packet(it.register, WRITE, it.value), mutating = true) }
        writes += Frame(packet(0, SAVE), mutating = true, delayAfterMs = 1000)
        val expected = before.registers.map { reg ->
            encoded.singleOrNull { it.register == reg.register } ?: if (reg.register == SLOT_REGISTER && before.slot == 2)
                RegisterValue(SLOT_REGISTER, listOf(3) + reg.value.drop(1)) else reg
        }.sortedBy { it.register }
        return Plan(model, before.deviceKey, writes.toList(), expected, bands, 3, readQueries(model))
    }
    /** Complete raw comparison, including preserved pregain/slot tail. Not a persistence/audio proof. */
    fun verify(plan: Plan, replies: List<Reply>): Snapshot {
        val actual = snapshot(plan.model, plan.deviceKey, replies)
        require(actual.slot == plan.expectedSlot && actual.registers == plan.expectedRegisters) { "KT raw readback mismatch" }
        return actual
    }
    private fun validateBand(model: KtMicroCatalog.Model, b: Band) {
        require(b.type in model.nativeTypes) { "Unsupported KT filter type" }
        require(b.freqHz.isFinite() && b.freqHz in 20.0..20000.0)
        require(b.gainDb.isFinite() && b.gainDb in -10.0..10.0)
        require(b.q.isFinite() && b.q in 0.1..5.0)
    }
    private fun quantize(v: Double): Int {
        require(v.isFinite() && v in -32768.0..65535.0)
        return floor(v + 0.5).toInt() // source Math.round, including negative half-ties
    }
    fun type(code: Int): FilterType = when (code) {
        0 -> FilterType.PEAK; 3 -> FilterType.LOW_SHELF; 4 -> FilterType.HIGH_SHELF
        else -> throw IllegalArgumentException("Unknown KT native type $code")
    }
    fun typeCode(type: FilterType): Int = when (type) {
        FilterType.PEAK -> 0; FilterType.LOW_SHELF -> 3; FilterType.HIGH_SHELF -> 4
        else -> throw IllegalArgumentException("Unsupported KT type $type")
    }
    private fun u(b: Byte) = b.toInt() and 255
    private fun le(v: List<Int>, at: Int) = v[at] or (v[at + 1] shl 8)
    private fun word(v: Int) = listOf(v and 255, (v ushr 8) and 255)
}
