package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Profile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** DS3-only pure codec. Independent implementation of devicePEQ 0617f382
 * fosiAudioUsbHidHandler.js L30-110,171-235,239-335,499-594 and compensation.js L275-316.
 * Every payload excludes report ID. No transport I/O or speculative 32-band/Custom2..5 support.
 */
object FosiCodec {
    const val REPORT_ID = 1
    const val PAYLOAD_BYTES = 63
    const val BAND_COUNT = 8 // Diagnostic prefix, NOT a qualified user-bank count.
    const val READ_ONLY_REASON = "DS3 user-bank count/layout UNKNOWN (8 vs 32; firmware >= 1.4.15 uses 32). First eight bands are partial diagnostics only; firmware/layout response evidence required before writes or whole-profile import."
    /** No physical layout is qualified. This explicit fixture-only value preserves isolated codec tests. */
    enum class Layout { UNKNOWN, SYNTHETIC_EIGHT_BAND_CUSTOM1 }
    const val CUSTOM1 = 7
    const val VENDOR_ID = 0x152A
    const val PRODUCT_ID = 0x88DB
    const val PRODUCT_NAME = "Fosi Audio DS3"
    const val MIN_GAIN_DB = -12.0
    const val MAX_GAIN_DB = 12.0
    const val MIN_Q = 0.1
    const val MAX_Q = 10.0
    const val MIN_FREQUENCY_HZ = 20.0
    const val MAX_FREQUENCY_HZ = 20000.0
    val nativeTypes: Set<FilterType> = setOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF)
    val writablePresets: Set<Int> = setOf(CUSTOM1)
    const val SUPPORTS_MANUAL_PREAMP = false
    const val SOURCE_COMMIT = "0617f382e76629792a5933e6933e4b396a756a93"
    fun matches(vendorId: Int, productId: Int, productName: String?): Boolean =
        vendorId == VENDOR_ID && productId == PRODUCT_ID && productName == PRODUCT_NAME

    enum class ReportKind { OUTPUT, FEATURE }
    enum class Operation { SET_REPORT, GET_REPORT }
    enum class Response { NONE, ACK, STATE, SAMPLE_FORMAT, BAND, ENABLE_ACK }
    class Frame internal constructor(bytes: ByteArray, val kind: ReportKind, val operation: Operation,
        val mutating: Boolean, val delayAfterMs: Int = 0, val response: Response = Response.NONE,
        val expectedCommand: Int? = null, val expectedMode: Int? = null, val expectedBand: Int? = null) {
        private val bytes = bytes.copyOf()
        val payload: ByteArray get() = bytes.copyOf()
        val reportId: Int get() = REPORT_ID
        /** Descriptor must expose BOTH 63-byte Feature and Output payloads, ID separately. */
        val requiredDescriptorPayloadBytes: Int get() = PAYLOAD_BYTES
    }
    data class Reply(val deviceKey: String, val reportId: Int, val kind: ReportKind, val payload: ByteArray)
    data class State(val enabled: Boolean, val mode: Int) { init { require(mode in 0..255) } }
    data class SampleFormat(val sampleRateHz: Int, val dsdMode: Int) {
        init { require(sampleRateHz in 8000..768000 && dsdMode in 0..255) }
    }
    /** Bits, not approximate floats, are the readback identity; bandwidth is preserved as well. */
    data class RawBand(val preset: Int, val index: Int, val nativeType: Int,
        val frequencyBits: Int, val qBits: Int, val bandwidthBits: Int, val gainBits: Int) {
        val frequencyHz: Double get() = Float.fromBits(frequencyBits).toDouble()
        val q: Double get() = Float.fromBits(qBits).toDouble()
        val bandwidth: Double get() = Float.fromBits(bandwidthBits).toDouble()
        val gainDb: Double get() = Float.fromBits(gainBits).toDouble()
        init {
            require(preset in 0..255 && index in 0 until BAND_COUNT)
            require(nativeType in setOf(0, 2, 9, 10)) { "Unknown/unrepresentable DS3 native type" }
            require(listOf(frequencyHz, q, bandwidth, gainDb).all { it.isFinite() })
            require(frequencyHz >= 0 && q >= 0 && bandwidth >= 0)
            if (nativeType != 0) require(frequencyHz > 0 && q > 0)
        }
    }
    class Snapshot internal constructor(val deviceKey: String, val state: State,
        val sampleFormat: SampleFormat, val bands: List<RawBand>, val layout: Layout = Layout.UNKNOWN) {
        internal fun requireCompleteLayout() {
            require(layout == Layout.SYNTHETIC_EIGHT_BAND_CUSTOM1 && state.mode == CUSTOM1 &&
                bands.size == BAND_COUNT && bands.map { it.index } == (0 until BAND_COUNT).toList() &&
                bands.all { it.preset == CUSTOM1 }) { READ_ONLY_REASON }
        }
        fun importProfile(id: String, name: String, timestamp: Long): Profile {
            requireCompleteLayout()
            require(bands.all { it.bandwidth == 0.0 }) { "Profile cannot represent nonzero DS3 bandwidth" }
            return Profile(id = id, name = name,
                bands = bands.map { decodeBand(it, sampleFormat) }, preampDb = 0.0,
                createdAt = timestamp, updatedAt = timestamp)
        }
    }
    class Plan internal constructor(val deviceKey: String, val frames: List<Frame>,
        val expectedBands: List<RawBand>, val expectedState: State,
        val sampleFormat: SampleFormat, val verificationQueries: List<Frame>)

    private fun packet(command: Int, index: Int = 0, band: Int = 0): ByteArray =
        ByteArray(PAYLOAD_BYTES).also { it[0] = 0x77; it[1] = command.toByte(); it[2] = index.toByte(); it[3] = band.toByte() }
    private fun set(command: Int, index: Int, kind: ReportKind, mutating: Boolean, delay: Int = 0, band: Int = 0) =
        Frame(packet(command, index, band), kind, Operation.SET_REPORT, mutating, delay)
    private fun get(command: Int, response: Response, mode: Int? = null, band: Int? = null) =
        Frame(ByteArray(0), ReportKind.FEATURE, Operation.GET_REPORT, false, response = response,
            expectedCommand = command, expectedMode = mode, expectedBand = band)
    fun stateQueries(): List<Frame> = listOf(set(0x9E, 0, ReportKind.OUTPUT, false), get(0x9E, Response.STATE))
    fun sampleFormatQueries(): List<Frame> = listOf(set(0x9F, 0, ReportKind.OUTPUT, false), get(0x9F, Response.SAMPLE_FORMAT))
    /** Reads named bank directly with Feature 8E; NEVER selects bank with 8A during a read. */
    fun bandReadQueries(preset: Int, band: Int): List<Frame> {
        require(preset in 0..255 && band in 0 until BAND_COUNT)
        return listOf(set(0x8E, preset, ReportKind.FEATURE, false, 20, band), get(0x8E, Response.BAND, preset, band))
    }
    fun readQueries(state: State): List<Frame> = stateQueries() + sampleFormatQueries() +
        (0 until BAND_COUNT).flatMap { bandReadQueries(state.mode, it) }

    /** Raw normalization is explicit: accept exactly one ID prefix, never auto-strip twice. */
    fun replyFromRaw(deviceKey: String, kind: ReportKind, raw: ByteArray): Reply {
        require(raw.size == PAYLOAD_BYTES + 1 && u(raw[0]) == REPORT_ID) { "DS3 raw report ID/length" }
        require(u(raw[1]) == 0x77)
        return Reply(deviceKey, REPORT_ID, kind, raw.copyOfRange(1, raw.size))
    }
    private fun checked(reply: Reply, deviceKey: String, command: Int): ByteArray {
        require(reply.deviceKey == deviceKey && reply.reportId == REPORT_ID && reply.kind == ReportKind.FEATURE) { "Wrong DS3 connection/report kind/ID" }
        val p = reply.payload
        require(p.size == PAYLOAD_BYTES && u(p[0]) == 0x77 && u(p[1]) == command) { "Wrong DS3 header/length/command" }
        return p
    }
    fun parseState(reply: Reply, deviceKey: String): State {
        val p = checked(reply, deviceKey, 0x9E)
        require(u(p[2]) in 0..1) { "Malformed enable value" }
        return State(u(p[2]) == 1, u(p[3]))
    }
    fun parseSampleFormat(reply: Reply, deviceKey: String): SampleFormat {
        val p = checked(reply, deviceKey, 0x9F)
        return SampleFormat(view(p).getInt(2), u(p[6]))
    }
    fun validateAck(reply: Reply, deviceKey: String, command: Int) {
        require(command in setOf(0x91, 0x8A, 0x8D, 0x92))
        checked(reply, deviceKey, command) // no invented status layout for these generic acknowledgements
    }
    fun validateEnableAck(reply: Reply, deviceKey: String, expected: State) {
        val p = checked(reply, deviceKey, 0x9D)
        require(u(p[2]) == 0 && u(p[3]) == if (expected.enabled) 1 else 0) { "DS3 rejected enable" }
        require(u(p[4]) == expected.mode) { "DS3 enable mode mismatch" }
    }
    fun parseBand(reply: Reply, deviceKey: String, preset: Int, index: Int): RawBand {
        val p = checked(reply, deviceKey, 0x8E)
        require(u(p[2]) == preset && u(p[3]) == index) { "DS3 bank/index mismatch" }
        val v = view(p)
        return RawBand(preset, index, u(p[4]), v.getInt(5), v.getInt(9), v.getInt(13), v.getInt(17))
    }
    fun encodeRawBand(band: RawBand): ByteArray = packet(0x8D, band.preset, band.index).also { p ->
        p[4] = band.nativeType.toByte()
        view(p).apply { putInt(5, band.frequencyBits); putInt(9, band.qBits); putInt(13, band.bandwidthBits); putInt(17, band.gainBits) }
    }
    fun snapshot(deviceKey: String, stateReply: Reply, sampleReply: Reply, bandReplies: List<Reply>,
        layout: Layout = Layout.UNKNOWN): Snapshot {
        val state = parseState(stateReply, deviceKey)
        val sample = parseSampleFormat(sampleReply, deviceKey)
        require(bandReplies.size == BAND_COUNT) { "Eight DS3 diagnostic band reads required; whole-bank count UNKNOWN" }
        val bands = bandReplies.map {
            require(it.payload.size == PAYLOAD_BYTES)
            parseBand(it, deviceKey, state.mode, u(it.payload[3]))
        }.sortedBy { it.index }
        require(bands.map { it.index } == (0 until BAND_COUNT).toList()) { "Missing/duplicate DS3 band" }
        bands.forEach { decodeBand(it, sample) } // inverse must be representable; no partial flat-fill
        return Snapshot(deviceKey, state, sample, bands, layout).also {
            if (layout != Layout.UNKNOWN) it.requireCompleteLayout()
        }
    }

    /** Full preflight, including last band and compensation, before selection/write/save/enable frames. */
    fun compile(profile: Profile, before: Snapshot, preset: Int = CUSTOM1, enabled: Boolean = true): Plan {
        before.requireCompleteLayout() // Before selection/enable/parameter/save frames can even be built.
        require(preset == CUSTOM1) { "Only evidenced Custom 1=7; factory and assumed 8..11 excluded" }
        require(profile.bands.size <= BAND_COUNT) { "DS3 eight bands only; no truncation" }
        require(before.sampleFormat.dsdMode == 0) { "DSD DSP semantics unqualified" }
        profile.bands.forEach { validateBand(it) }
        val pregain = profile.effectivePreampDb()
        require(pregain.isFinite() && pregain == 0.0) { "DS3 cannot implement manual/AUTO attenuation; no assumed headroom" }
        val bands = List(BAND_COUNT) { index -> encodeBand(profile.bands.getOrNull(index), index, before.sampleFormat) }
        val target = State(enabled, preset)
        val frames = mutableListOf<Frame>()
        frames += set(0x91, 0, ReportKind.FEATURE, true, 50)
        frames += get(0x91, Response.ACK)
        frames += set(0x8A, preset, ReportKind.FEATURE, true, 30)
        frames += get(0x8A, Response.ACK)
        // Integrator must check mode=7 here BEFORE continuing with parameter writes.
        frames += set(0x9E, 0, ReportKind.OUTPUT, false)
        frames += Frame(ByteArray(0), ReportKind.FEATURE, Operation.GET_REPORT, false,
            response = Response.STATE, expectedCommand = 0x9E, expectedMode = preset)
        bands.forEach { b ->
            frames += Frame(encodeRawBand(b), ReportKind.FEATURE, Operation.SET_REPORT, true, 20)
            frames += get(0x8D, Response.ACK)
            frames += set(0x8E, preset, ReportKind.OUTPUT, true, 20, b.index)
            frames += get(0x8E, Response.BAND, preset, b.index)
        }
        frames += set(0x92, preset, ReportKind.FEATURE, true, 50)
        frames += get(0x92, Response.ACK)
        frames += set(0x9D, if (enabled) 1 else 0, ReportKind.OUTPUT, true, 30)
        frames += get(0x9D, Response.ENABLE_ACK, preset)
        return Plan(before.deviceKey, frames.toList(), bands, target, before.sampleFormat, readQueries(target))
    }
    /** Validate each GET_REPORT before the integrator advances to the next planned step.
     * In particular, selection must read mode=7 BEFORE the first band mutation.
     * Generic ack status offsets are unknown; only the documented 9D status is interpreted.
     */
    fun validateResponse(plan: Plan, frame: Frame, reply: Reply) {
        require(frame in plan.frames || frame in plan.verificationQueries) { "Frame does not belong to DS3 plan" }
        require(frame.operation == Operation.GET_REPORT)
        when (frame.response) {
            Response.ACK -> validateAck(reply, plan.deviceKey, requireNotNull(frame.expectedCommand))
            Response.STATE -> {
                val actual = parseState(reply, plan.deviceKey)
                frame.expectedMode?.let { require(actual.mode == it) { "DS3 selection did not take" } }
                if (frame in plan.verificationQueries) require(actual == plan.expectedState)
            }
            Response.SAMPLE_FORMAT -> require(parseSampleFormat(reply, plan.deviceKey) == plan.sampleFormat) {
                "DS3 sample format changed during transaction"
            }
            Response.BAND -> {
                val index = requireNotNull(frame.expectedBand)
                val actual = parseBand(reply, plan.deviceKey, requireNotNull(frame.expectedMode), index)
                require(actual == plan.expectedBands[index]) { "DS3 per-band raw readback mismatch" }
            }
            Response.ENABLE_ACK -> validateEnableAck(reply, plan.deviceKey, plan.expectedState)
            Response.NONE -> throw IllegalArgumentException("Frame does not request a response")
        }
    }
    fun verify(plan: Plan, stateReply: Reply, sampleReply: Reply, bandReplies: List<Reply>,
        layout: Layout = Layout.UNKNOWN): Snapshot {
        val actual = snapshot(plan.deviceKey, stateReply, sampleReply, bandReplies, layout)
        actual.requireCompleteLayout()
        require(actual.state == plan.expectedState && actual.sampleFormat == plan.sampleFormat && actual.bands == plan.expectedBands) { "DS3 raw readback mismatch" }
        return actual
    }
    private fun encodeBand(input: Band?, index: Int, sample: SampleFormat): RawBand {
        val b = if (input == null || !input.enabled) Band("spare", FilterType.PEAK, 1000.0, 0.0, 1.0) else input
        validateBand(b)
        val a = 10.0.pow(abs(b.gainDb) / 40.0)
        val q = if (b.type == FilterType.PEAK) b.q * a else b.q
        val frequency = shelfFrequency(b.freqHz, b.gainDb, b.type, sample.sampleRateHz, writing = true)
        require(q.isFinite() && q in 0.1..10.0) { "Compensated DS3 Q outside native range" }
        require(frequency.isFinite() && frequency in 20.0..20000.0 && frequency < sample.sampleRateHz / 2.0) { "Compensated DS3 frequency not representable" }
        return RawBand(CUSTOM1, index, typeCode(b.type), bits(frequency), bits(q), bits(0.0), bits(b.gainDb))
    }
    private fun decodeBand(raw: RawBand, sample: SampleFormat): Band {
        val type = type(raw.nativeType)
        if (raw.nativeType == 0) return Band("fosi-${raw.index}", type, raw.frequencyHz, raw.gainDb, raw.q, enabled = false)
        require(sample.dsdMode == 0) { "DSD DSP semantics unqualified" }
        val freq = shelfFrequency(raw.frequencyHz, raw.gainDb, type, sample.sampleRateHz, writing = false)
        val q = if (type == FilterType.PEAK) raw.q / 10.0.pow(abs(raw.gainDb) / 40.0) else raw.q
        require(freq.isFinite() && q.isFinite())
        return Band("fosi-${raw.index}", type, freq, raw.gainDb, q)
    }
    private fun shelfFrequency(freq: Double, gain: Double, type: FilterType, fs: Int, writing: Boolean): Double {
        require(freq >= 0 && freq < fs / 2.0) { "DS3 Nyquist boundary; no fallback rate" }
        if (type == FilterType.PEAK || gain == 0.0) return freq
        val m = 10.0.pow(abs(gain) / 80.0)
        val direction = if (type == FilterType.LOW_SHELF) m else 1.0 / m
        val warped = tan(PI * freq / fs)
        return fs / PI * atan(if (writing) warped / direction else warped * direction)
    }
    private fun validateBand(b: Band) {
        typeCode(b.type)
        require(b.freqHz.isFinite() && b.freqHz in (if (b.enabled) 20.0 else 0.0)..20000.0)
        require(b.gainDb.isFinite() && b.gainDb in -12.0..12.0)
        require(b.q.isFinite() && b.q in (if (b.enabled) 0.1 else 0.0)..10.0)
    }
    fun type(code: Int): FilterType = when (code) {
        0, 2 -> FilterType.PEAK; 9 -> FilterType.LOW_SHELF; 10 -> FilterType.HIGH_SHELF
        else -> throw IllegalArgumentException("Unsupported DS3 native type $code")
    }
    fun typeCode(type: FilterType): Int = when (type) {
        FilterType.PEAK -> 2; FilterType.LOW_SHELF -> 9; FilterType.HIGH_SHELF -> 10
        else -> throw IllegalArgumentException("Unsupported DS3 type $type")
    }
    private fun bits(value: Double): Int {
        require(value.isFinite() && value.toFloat().isFinite())
        return value.toFloat().toRawBits()
    }
    private fun u(b: Byte) = b.toInt() and 255
    private fun view(p: ByteArray): ByteBuffer = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
}
