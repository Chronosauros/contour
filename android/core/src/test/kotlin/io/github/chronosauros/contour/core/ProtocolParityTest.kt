package io.github.chronosauros.contour.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The project's only automated check: the device protocol and the text codecs against real captures.
 * Inputs (read-only): research/protocol/ (device read 25.09.2026, walkplay.py dry runs) and the
 * maintainer's profiles in a sibling eq-library folder. That folder is not part of the repo: without it
 * tests 4-6 are skipped and 1-3 still check the protocol.
 */
class ProtocolParityTest {
    private val repo = File(System.getProperty("contour.repoRoot") ?: error("contour.repoRoot not set"))
    private val protocol = File(repo, "research/protocol")
    private val library = File(repo.parentFile, "eq-library")

    /** Tests 4-6 need the maintainer's eq-library next to the repo; elsewhere they are skipped. */
    private fun needLibrary() = assumeTrue(library.isDirectory, "no eq-library next to the repo ($library) - skipped")

    private fun hex(s: String): ByteArray = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /** Log lines "  12.3 ms RX 4b ..." / "TX (dry-run, not sent) 4b ...": (time ms, bytes). */
    private fun reports(file: File, direction: String): List<Pair<Double, ByteArray>> =
        file.readLines().mapNotNull { line ->
            val m = Regex("""^\s*([\d.]+) ms $direction(?: \(dry-run, not sent\))? ((?:[0-9a-f]{2} ?)+)$""").find(line)
                ?: return@mapNotNull null
            m.groupValues[1].toDouble() to hex(m.groupValues[2])
        }

    private fun roundTo(x: Double, decimals: Int): Double {
        val p = Math.pow(10.0, decimals.toDouble())
        return WalkPlay.jsRound(x * p) / p
    }

    private val backup: JsonObject by lazy {
        Json.parseToJsonElement(File(protocol, "device-backup-2026-09-25.json").readText()).jsonObject
    }

    private fun backupFilters() = backup["filters"]!!.jsonArray.map { it.jsonObject }

    /** The 8 band replies of the raw read (the bulk slot read answers with band 0 first; take the last 8). */
    private fun bandReplies(): List<ByteArray> {
        val rx = reports(File(protocol, "raw-read-2026-09-25.log"), "RX").map { it.second }
        return rx.filter { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) }.takeLast(8)
    }

    @Test
    fun `1 - decoding the device's band replies gives the backup JSON`() {
        val replies = bandReplies()
        val filters = backupFilters()
        assertEquals(8, replies.size)
        var ok = 0
        replies.forEachIndexed { i, buf ->
            val d = WalkPlay.parseBand(buf)
            val f = filters[i]
            val raw = f["raw"]!!.jsonObject
            assertEquals(f["index"]!!.jsonPrimitive.int, d.registers.index)
            assertEquals(f["enabled"]!!.jsonPrimitive.boolean, d.enabled)
            assertEquals(f["type"]!!.jsonPrimitive.content, ApoType.of(d.type))
            assertEquals(f["freq_hz"]!!.jsonPrimitive.double, roundTo(d.freqHz, 1))
            assertEquals(f["gain_db"]!!.jsonPrimitive.double, roundTo(d.gainDb, 2))
            assertEquals(f["q"]!!.jsonPrimitive.double, roundTo(d.q, 3))
            assertEquals(raw["freq"]!!.jsonPrimitive.int, d.registers.freq)
            assertEquals(raw["q_x256"]!!.jsonPrimitive.int, d.registers.q256)
            assertEquals(raw["gain_x256"]!!.jsonPrimitive.int, d.registers.gain256)
            assertEquals(raw["type"]!!.jsonPrimitive.int, d.registers.typeCode)
            assertEquals(raw["slot_byte"]!!.jsonPrimitive.int, d.slotByte)
            assertEquals(raw["biquad_hex"]!!.jsonPrimitive.content, hex(d.biquad))
            ok++
        }
        println("PARITY 1 decode band replies = backup JSON: $ok/8")
        assertEquals(8, ok)
        // the rest of the read: version, slot, preamp
        val rx = reports(File(protocol, "raw-read-2026-09-25.log"), "RX").map { it.second }
        assertEquals(backup["firmware_version"]!!.jsonPrimitive.content, WalkPlay.parseVersion(rx.first { WalkPlay.isReply(it, WalkPlay.CMD_VERSION) }))
        assertEquals(backup["current_slot"]!!.jsonPrimitive.int, WalkPlay.parseSlot(rx.first { WalkPlay.isReply(it, WalkPlay.CMD_PEQ) }))
        assertEquals(backup["preamp_db"]!!.jsonPrimitive.int, WalkPlay.parsePreamp(rx.first { WalkPlay.isReply(it, WalkPlay.CMD_GLOBAL_GAIN) }))
    }

    @Test
    fun `2 - encoding the factory flat band of each slot gives the device's own bytes`() {
        val replies = bandReplies()
        var ok = 0
        for (i in 0 until 8) {
            val w = WalkPlay.factoryFlat(i)
            val report = WalkPlay.bandWriteReport(w, slot = 0)
            val reply = replies[i]
            // freq, Q, gain, type, 00, slot at buf[28..36]; biquad at buf[8..27]; index at buf[5]
            assertEquals(hex(reply.copyOfRange(28, 37)), hex(report.copyOfRange(28, 37)), "slot $i metadata")
            assertEquals(hex(reply.copyOfRange(8, 28)), hex(report.copyOfRange(8, 28)), "slot $i biquad")
            assertEquals(reply[5], report[5])
            assertEquals(WalkPlay.parseBand(reply).registers, w.registers())
            ok++
        }
        println("PARITY 2 factory flat encode = device bytes: $ok/8")
        assertEquals(8, ok)
    }

    /** write_test.py main() in --dry-run: modified state (band 1 gain -0.5 dB), then the original. */
    private fun dryRunSequence(commit: Boolean): List<WalkPlay.Step> {
        val orig = backupFilters().map {
            val r = it["raw"]!!.jsonObject
            WalkPlay.BandWrite(
                index = it["index"]!!.jsonPrimitive.int,
                freq = r["freq"]!!.jsonPrimitive.int.toDouble(),
                gainDb = r["gain_x256"]!!.jsonPrimitive.int / 256.0,
                q = r["q_x256"]!!.jsonPrimitive.int / 256.0,
                typeCode = r["type"]!!.jsonPrimitive.int,
            )
        }
        val g = backupFilters()[0]["raw"]!!.jsonObject["gain_x256"]!!.jsonPrimitive.int - 128
        val mod = listOf(orig[0].copy(gainDb = g / 256.0)) + orig.drop(1)
        val preamp = backup["preamp_db"]!!.jsonPrimitive.double
        val slot = backup["current_slot"]!!.jsonPrimitive.int
        return WalkPlay.writeSequence(mod, preamp, slot, commit) + WalkPlay.writeSequence(orig, preamp, slot, commit)
    }

    private fun checkLog(name: String, commit: Boolean): Pair<Int, Int> {
        val log = reports(File(protocol, name), "TX")
        val ours = dryRunSequence(commit)
        assertEquals(log.size, ours.size, "$name: number of reports")
        var same = 0
        ours.forEachIndexed { i, step ->
            assertEquals(hex(log[i].second), hex(step.report), "$name report ${i + 1}")
            same++
        }
        // pacing: within each step the gap in the log equals our pause (the log adds < 3 ms of overhead)
        val perStep = ours.size / 2
        for (half in 0..1) {
            for (k in 0 until perStep - 1) {
                val i = half * perStep + k
                val gap = log[i + 1].first - log[i].first
                assertTrue(gap - ours[i].delayAfterMs in -0.5..3.0, "$name gap after report ${i + 1}: $gap ms vs ${ours[i].delayAfterMs}")
            }
        }
        return same to log.size
    }

    @Test
    fun `3 - write and commit reports reproduce the walkplay_py dry runs byte for byte`() {
        val (v, vn) = checkLog("dry-run-volatile.log", commit = false)
        println("PARITY 3a dry-run-volatile.log reports reproduced: $v/$vn")
        val (d, dn) = checkLog("dry-run-devicepeq.log", commit = true)
        val commits = reports(File(protocol, "dry-run-devicepeq.log"), "TX").count { it.second[1].toInt() == WalkPlay.WRITE && it.second[2].toInt() != WalkPlay.CMD_PEQ && it.second[2].toInt() != WalkPlay.CMD_GLOBAL_GAIN }
        println("PARITY 3b dry-run-devicepeq.log reports reproduced: $d/$dn (commit reports $commits/$commits)")
        assertEquals(vn, v)
        assertEquals(dn, d)
    }

    @Test
    fun `4 - APO text round trip is lossless and Nightfall's auto preamp is -3 dB`() {
        needLibrary()
        val files = library.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.name.endsWith(".txt") } }
            .sortedBy { it.path }
        assertTrue(files.isNotEmpty(), "no profiles in $library")
        var ok = 0
        for (f in files) {
            val first = ApoText.parse(f.readText())
            assertTrue(first.bands.isNotEmpty(), "${f.name}: no bands")
            val text = ApoText.format(first.bands, first.preampDb ?: 0.0)
            val second = ApoText.parse(text)
            assertEquals(first, second, "${f.parentFile.name}/${f.name}")
            println("  ${f.parentFile.name}/${f.name}: ${first.bands.size} bands, preamp ${first.preampDb}, auto ${Preamp.auto(first.bands)}")
            ok++
        }
        println("PARITY 4 APO parse -> format -> parse lossless: $ok/${files.size}")
        assertEquals(files.size, ok)

        val nightfallDir = File(library, "crinear-nightfall")
        val txt = ApoText.parse(nightfallDir.listFiles()!!.first { it.name.endsWith(".txt") }.readText())
        val json = EqByEarJson.parse(nightfallDir.listFiles()!!.first { it.name.endsWith(".json") }.readText())
        assertNull(json.preampDb, "preampAuto: true must read as auto")
        assertEquals(txt.bands, json.bands)
        val auto = Preamp.auto(json.bands)
        println("PARITY 4 Nightfall auto preamp: $auto dB")
        assertEquals(-3, auto)
    }

    // ---- 5: HIGH SHELF emulation on the Protocol Micro -------------------------------------------------

    /** Magnitude in dB of a device biquad (the 20 Q30 bytes exactly as written) at [f], fs 96 kHz. */
    private fun q30Db(bytes: ByteArray, f: Double): Double {
        fun i32(k: Int) = (bytes[k].toInt() and 0xFF) or ((bytes[k + 1].toInt() and 0xFF) shl 8) or
            ((bytes[k + 2].toInt() and 0xFF) shl 16) or (bytes[k + 3].toInt() shl 24)
        val c = DoubleArray(5) { i32(it * 4) / 1073741824.0 } // b0 b1 b2 -a1 -a2
        val w = 2 * Math.PI * f / WalkPlay.DESIGN_FS
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a1 = -c[3]; val a2 = -c[4]
        val nr = b0 + b1 * Math.cos(w) + b2 * Math.cos(2 * w); val ni = -(b1 * Math.sin(w) + b2 * Math.sin(2 * w))
        val dr = 1 + a1 * Math.cos(w) + a2 * Math.cos(2 * w); val di = -(a1 * Math.sin(w) + a2 * Math.sin(2 * w))
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** The curve the device realises from a list of band writes plus a preamp (dB), on [freqs]. */
    private fun deviceCurve(writes: List<WalkPlay.BandWrite>, preamp: Double, freqs: DoubleArray): DoubleArray {
        val coeffs = writes.map { WalkPlay.computeIir(it.freq, it.gainDb, it.q, it.typeCode) }
        return DoubleArray(freqs.size) { i -> preamp + coeffs.sumOf { q30Db(it, freqs[i]) } }
    }

    /**
     * Max deviation from its mean of (emulated device curve - intended curve), 1 024 log points. Both curves
     * in the device's own maths (the Q30 biquads devicePEQ would write, same compensation): the intended one
     * with native HIGH SHELF biquads and the user's preamp, the emulated one = ProtocolMicro.plan.
     */
    private fun hsDeviation(bands: List<Band>, preampDb: Double?): Double {
        val plan = ProtocolMicro.plan(bands, preampDb) as DevicePlan.Ready
        assertTrue(plan.bands.none { it.typeCode == WalkPlay.TYPE_HSQ }, "a HIGH SHELF reached the wire")
        val freqs = Dsp.logFreqs(1024)
        val active = bands.filter { it.enabled }
        val intendedWrites = List(8) { if (it < active.size) WalkPlay.bandWrite(it, active[it]) else WalkPlay.factoryFlat(it) }
        val intended = deviceCurve(intendedWrites, preampDb ?: Preamp.auto(active).toDouble(), freqs)
        val device = deviceCurve(plan.bands, plan.preampDb.toDouble(), freqs)
        val diff = DoubleArray(freqs.size) { device[it] - intended[it] }
        val mean = diff.average()
        return diff.maxOf { Math.abs(it - mean) }
    }

    @Test
    fun `5 - HIGH SHELF emulation keeps the curve shape exactly`() {
        needLibrary()
        val mega = ApoText.parse(File(library, "hisenior-mega5est").listFiles()!!.first { it.name.endsWith(".txt") }.readText())
        assertTrue(mega.bands.any { it.type == FilterType.HIGH_SHELF }, "Mega5EST has no HS band")
        val synthetic = listOf(
            Band("s1", FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7),
            Band("s2", FilterType.PEAK, 3000.0, -3.0, 2.0),
            Band("s3", FilterType.LOW_SHELF, 100.0, 2.0, 0.7),
        )
        val dMega = hsDeviation(mega.bands, null)
        val dSyn = hsDeviation(synthetic, null)
        val dSynManual = hsDeviation(synthetic, -6.0)
        println("PARITY 5 HS emulation max deviation: Mega5EST %.2e dB, synthetic %.2e dB (manual preamp %.2e dB)".format(dMega, dSyn, dSynManual))
        // the preamp rules: AUTO over the device-domain curve, manual floor(preamp + sum HS)
        val synPlan = ProtocolMicro.plan(synthetic, -6.0) as DevicePlan.Ready
        assertEquals(-2, synPlan.preampDb, "manual -6 dB + HS +4 dB")
        println("  preamp: Mega5EST AUTO ${(ProtocolMicro.plan(mega.bands, null) as DevicePlan.Ready).preampDb} dB, synthetic AUTO ${(ProtocolMicro.plan(synthetic, null) as DevicePlan.Ready).preampDb} dB, synthetic manual -6 -> ${synPlan.preampDb} dB")
        assertTrue(dMega < 0.01 && dSyn < 0.01 && dSynManual < 0.01)
    }

    // ---- 6: which profile is on the DAC -----------------------------------------------------------

    private fun profileOf(name: String, eq: ImportedEq) = Profile(name, name, bands = eq.bands, preampDb = null, createdAt = 0, updatedAt = 0)

    /** What a read-back of [plan] would decode to: each write report parsed as a band reply (same layout). */
    private fun readBack(plan: DevicePlan.Ready): List<WalkPlay.Registers> =
        plan.bands.map { WalkPlay.parseBand(WalkPlay.bandWriteReport(it, slot = 0)).registers }

    @Test
    fun `6 - matching the DAC's registers to a profile`() {
        needLibrary()
        val nightfall = profileOf("nightfall", ApoText.parse(File(library, "crinear-nightfall").listFiles()!!.first { it.name.endsWith(".txt") }.readText()))
        val plan = ProtocolMicro.plan(nightfall) as DevicePlan.Ready
        var ok = 0
        if (ProtocolMicro.matches(nightfall, readBack(plan), plan.preampDb)) ok++ else println("  Nightfall does not match its own read-back")
        val nudged = nightfall.copy(bands = nightfall.bands.mapIndexed { i, b -> if (i == 1) b.copy(gainDb = b.gainDb + 0.1) else b })
        if (!ProtocolMicro.matches(nudged, readBack(plan), plan.preampDb)) ok++ else println("  Nightfall +0.1 dB still matches")
        val flatRegs = bandReplies().map { WalkPlay.parseBand(it).registers }
        val flatPreamp = backup["preamp_db"]!!.jsonPrimitive.int
        val seeded = library.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            dir.listFiles().orEmpty().filter { it.name.endsWith(".txt") }.maxByOrNull { it.name }?.let { profileOf(dir.name, ApoText.parse(it.readText())) }
        }
        assertTrue(
            seeded.size >= 5,
            "found ${seeded.size} seeded profiles (need 5) in ${library.absolutePath}: " +
                library.listFiles().orEmpty().joinToString { it.name + if (it.isDirectory) "/" else "" },
        )
        assertTrue(ProtocolMicro.isFlat(flatRegs, flatPreamp), "the factory read is not FLAT")
        if (seeded.none { ProtocolMicro.matches(it, flatRegs, flatPreamp) }) ok++ else println("  the factory flat read matches a seeded profile")
        println("PARITY 6 matching: $ok/3 (flat read = FLAT, ${seeded.size} seeded profiles checked)")
        assertEquals(3, ok)
    }
}

/** walkplay.py's type names, for comparing with the backup JSON. */
private object ApoType {
    fun of(t: FilterType): String = when (t) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LSQ"
        FilterType.HIGH_SHELF -> "HSQ"
        FilterType.LOW_PASS -> "LP"
        FilterType.HIGH_PASS -> "HP"
    }
}
