package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.WalkPlay
import kotlin.math.floor

/**
 * The settings rows of the WalkPlay DACs, in the DAC Settings page's own [DacSetting] language, and the frames that read and write them. Every frame
 * is a line of walkplay-plan.md (the decoded WalkPlay web tool, class `s2`, which SchemeNo11 and SchemeNo16 both resolve to) and nothing else:
 *
 * | row | read (after report id 0x4B) | reply value | write |
 * |---|---|---|---|
 * | DAC filter (TRN) | `80 11` | `d[3] - 1` = index 0..4 | `01 11 01 t`, t = index + 1 |
 * | work mode (TRN) | `80 1D` | `d[3]` (0 Class-H, 1 Class-AB) | `01 1D 01 t`, t = index |
 * | gain mode (TRN) | `80 19` | `d[3]` (0 Low, 1 High) | `01 19 01 m`, m = index |
 * | balance (TRN) | `80 16 02 00 00` and `80 16 02 01 00` | `d[3]` channel (0 left, 1 right), `d[5]` r, r != 0 is attenuation 256 - r | two frames, `01 16 04 c 00 a 00` for channel 1 then channel 0, a = 256 - steps or 0 |
 * | mic gain (read only on the TRN, Micro and Echo-A) | `80 02 00` | `d[4]` as int8 | `01 02 02 80 e`, e = the value as int8 (the frame exists, no row writes it, see below) |
 *
 * Tabs: SchemeNo16 shows EQ, MIC, DAC Filter (with the work mode), Upgrade, Volume and RESET; SchemeNo11 shows EQ, MIC, Upgrade, Volume and RESET and no DAC Filter tab.
 * So the TRN (SchemeNo16) gets all five rows and the Micro and the Echo-A (SchemeNo11) the mic row only; the Micro does not answer 0x11/0x16/0x19/0x1B/0x1D
 * (hardware and the CAP reply agree). The Protocol Max (SchemeNo16, devicePEQ's extras table: 0x11, 0x16 and 0x1D for every scheme, 0x19 for SchemeNo16 and up) gets three of the DAC Filter tab
 * rows as the TRN's frames, FILTER, WORK MODE and BALANCE; owner rule 10.11.2026: Contour offers at least every setting the vendor's tool offers. On a unit (2026-11-10) the reads answered (filter 1,
 * work mode 1 = Class-AB as on the TRN, balance 0 / 0). Written on the Mac (dac-lab runs 14 and T14 run 03): FILTER NON-OS -2.6 dB at 20 kHz, BALANCE 1.00 dB per step with channel 1 the right one, WORK MODE
 * 1 -> 0 and back, read back each time (the bench rig at line level showed no level or noise difference between Class-H and Class-AB, which does not prove the mode does nothing). A phone then wrote
 * FILTER and BALANCE from the app and read them back (run 22): both were kept after an unplug and are verified (`writeVerified = true`, in a release too). WORK MODE is writable and unverified until a phone
 * shows it kept after an unplug: [MAX_WORK_MODE_VERIFIED] is the one line that releases it (a review build shows and writes it until then).
 * GAIN is not a row of the Max: the unit has a physical Eco / Boost switch that sets the gain mode and wins after every replug (run 22: LOW written and read back VERIFIED, HIGH again after the replug), and the
 * vendor's tool sends the same `01 19 01 m` with no save. So there is no GAIN row for the Max in any build and [WalkPlayGuard] refuses `01 19` there. The Max has a MIC GAIN row for vendor parity (owner 10.11.2026; the devicePEQ extras for its profile
 * list micGain): the unit exposes no audio input, so the setting has no audible path on this DAC. Its encoding is devicePEQ master's (`setMicGain` / `readMicGain`, whose header confirms the mic gain on SchemeNo11 and SchemeNo16, the Protocol Max):
 * a signed 16-bit little-endian value, write `01 02 02 lsb msb` with t = round(dB x 32767 / 15) clamped to +-0x7F00 (+15 is 0x7F00, -15 is 0x8100: devicePEQ's own 32767 / 32769 wrap on this unit), read `80 02 02 lsb msb` (value bytes buf[4], buf[5] of the full report) and dB = value x 15 / 32767
 * rounded to the nearest whole dB, range -15..+15 (the vendor's); the WalkPlay-capture encoding of the TRN (`01 02 02 80 e`, e as int8) stored wrong values on a Max (phone, perf f18f8fee: 10 read back, -1 read 0,
 * 2 read 3, -11 read as a 32-bit sign extension). The unit answered `80 02` with mic 0. Written alone it is lost after a replug (mic -7 read 0, dac-lab run 2026-11-11-crinear-protocol-max-23-mac-mic-save); followed by the WalkPlay web tool's flash save `01 01 00` it
 * reads back and is kept over an unplug, and the EQ is not affected (a PK 1000 Hz -12 Q2 marker measured identical before and after the save and after the replug, within 0.002 dB). So on the Max a mic write is
 * `01 02 02 lsb msb`, `01 01 00` (200 ms after, [WalkPlaySession]'s save gap), then the read-back. On a Max `01 01 x` is also devicePEQ's EQ enable / flash frame, so [WalkPlayGuard] lets `01 01 00` through only
 * as that save ([WalkPlayGuard.Context.MIC_SAVE], set by [WalkPlaySession.writeSetting] for the mic row alone) and refuses it everywhere else. The row is verified ([MAX_MIC_VERIFIED] true).
 *
 * The TRN's four DAC Filter tab rows are released (`writeVerified = true`) since the WalkPlay capture of 27.10.2026 (research/trn-settings-walkplay-2026-10-27/FINDINGS.md): the vendor's
 * page offers FILTER (five cards), DAC working mode (Class-H, Class-AB), output gain mode (Low, High) and the sound channel balance for the TRN, and sends exactly the frames above;
 * every value was read back, stayed after an unplug without `01 01 00`, and the owner heard the balance side. A write goes out through [WalkPlaySession.writeSetting] (the bench's
 * `setting-plan` and the app's DAC Settings page: [offered], [DacSetting.writeEnabled], [Proven]). `d[n]` is the plan's index after the report id; the decoders get the full 64-byte
 * report, where `buf[n] == d[n - 1]` ([WalkPlay]).
 *
 * The balance as captured: the vendor's slider has 15 steps each side and no unit. Towards L it sends `01 16 04 01 00 (256 - l) 00` + `01 16 04 00 00 00 00`, towards R
 * `01 16 04 01 00 00 00` + `01 16 04 00 00 (256 - r) 00`; with the slider at L the owner heard the right ear quieter, so channel 1 is the right one. The vendor's centre pair
 * `01 16 04 00 01 00 00` + `01 16 04 00 00 00 00` left channel 1 at 15 on the unit, so Contour's centre is the two zero frames (`01 16 04 01 00 00 00` + `01 16 04 00 00 00 00`),
 * read back 0 / 0. A DAC that holds both channels attenuated (only the vendor's centre leaves that) reads as their difference.
 *
 * Left read-only, because the unit does not take it:
 *  - the TRN's mic gain: the DAC ignores the write and keeps reading 0 (runs/2026-10-21-trn-black-pearl-01 and -02 T14: tried twice, 2.5 s apart), so the TRN's row has no write
 *    frame in any build. The Micro's and the Echo-A's mic rows are read only in every build as well: the DACs take a write for some values only (Echo-A phone: 0 and +1 accepted, -1 ignored;
 *    Micro phone: -3 accepted, -1 ignored; both PC runs agree), and a write that is taken only for some values is not offered. [readOnlyMic] has the runs.
 *  - whether a Micro or an Echo-A has a microphone path at all: the mic row is there because the vendor's tool shows the MIC tab for SchemeNo11; the Micro's CAP reply
 *    `4b 80 02 02 00` is most likely mic 0. The UI range -15..+15 is the vendor's; its unit is not known.
 * Not rows at all: the noise-reduction switch (`01 1B`, disabled for class CB, never on the TRN) and every frame of the forbidden list ([WalkPlayGuard]).
 */
object WalkPlaySettings {
    const val CMD_MIC = 0x02
    const val CMD_DAC_FILTER = 0x11
    const val CMD_BALANCE = 0x16
    const val CMD_GAIN_MODE = 0x19
    const val CMD_WORK_MODE = 0x1D
    /** The flash save the vendor adds after a mic write (`01 01 00`); whether the other rows need it is unknown. */
    const val CMD_SAVE = 0x01

    const val MIC = "mic"
    const val DAC_FILTER = "dac-filter"
    const val WORK_MODE = "work-mode"
    const val GAIN_MODE = "gain-mode"
    const val BALANCE = "balance"

    private fun i8(b: Byte) = b.toInt()
    private fun u(b: Byte) = b.toInt() and 255
    private fun reply(buf: ByteArray, size: Int) = buf.size >= size

    private val micDecode: (ByteArray) -> Int? = { buf -> if (reply(buf, 6)) i8(buf[5]) else null }
    /**
     * The Max's mic gain as devicePEQ's master reads it (`readMicGain`): a signed 16-bit little-endian value, dB = value x 15 / 32767, here rounded to the nearest whole dB (the unit may quantise). In the
     * full 64-byte reply `4b 80 02 02 lsb msb` the value is buf[4] (lsb) and buf[5] (msb): the unit answered a stored 0x0A80 with `80 02 02 80 0a`, and a stored 0xF580 with `80 02 02 80 f5 ff ff` (the two
     * bytes after it are stale sign extension and are not read). The TRN's and the Micro's byte (buf[5] as int8) gave wrong values here (phone, perf f18f8fee).
     */
    private val maxMicDecode: (ByteArray) -> Int? = { buf ->
        if (reply(buf, 6)) {
            val raw = u(buf[4]) or (u(buf[5]) shl 8)
            val signed = if (raw > 32767) raw - 65536 else raw
            floor(signed * 15.0 / 32767 + 0.5).toInt().takeIf { it in -15..15 }
        } else null
    }
    private val filterDecode: (ByteArray) -> Int? = { buf -> if (reply(buf, 5)) (u(buf[4]) - 1).takeIf { it in 0..4 } else null }
    private val workDecode: (ByteArray) -> Int? = { buf -> if (reply(buf, 5)) u(buf[4]).takeIf { it in 0..1 } else null }
    private val gainDecode: (ByteArray) -> Int? = { buf -> if (reply(buf, 5)) u(buf[4]).takeIf { it in 0..1 } else null }
    /** The attenuation (vendor steps, 0..[BALANCE_STEPS]) of the channel the reply is for; [balanceChannel] says which. */
    private val balanceDecode: (ByteArray) -> Int? = { buf -> if (reply(buf, 7)) u(buf[6]).let { r -> if (r == 0) 0 else 256 - r }.takeIf { it in 0..BALANCE_STEPS } else null }

    /** The vendor's balance slider: 15 steps each side (capture 27.10.2026). */
    const val BALANCE_STEPS = 15
    private const val LEFT = 0
    private const val RIGHT = 1

    /** The channel a balance reply is for (`d[3]`). */
    fun balanceChannel(buf: ByteArray): Int? = if (reply(buf, 5)) u(buf[4]).takeIf { it in 0..1 } else null

    private val mic = DacSetting(MIC, "MIC GAIN", SettingKind.LEVEL, EvidenceRank.VB, min = -15, max = 15, group = 1,
        readCommand = CMD_MIC, read = SettingRead.WalkPlayGet(CMD_MIC, micDecode), writeCommand = CMD_MIC, writeVerified = false)
    /** The Max's mic row: the mic row with devicePEQ's 16-bit encoding for the read ([maxMicDecode]) and the write ([maxMicReport]); [isMaxMic] tells it from the TRN's and the Micro's. */
    private val maxMic = mic.copy(read = SettingRead.WalkPlayGet(CMD_MIC, maxMicDecode))
    private fun isMaxMic(s: DacSetting) = (s.read as? SettingRead.WalkPlayGet)?.decode === maxMicDecode

    /**
     * `01 02 02 lsb msb`, devicePEQ master's `setMicGain` with one change: t = round(dB x 32767 / 15) clamped to +-[MIC_FULL_SCALE] (0x7F00), a negative value as two's complement; lsb = t & 255, msb = t >> 8.
     * The unit stores the value in steps of 0x80 and rounds, and full scale wraps: devicePEQ's +15 -> 32767 (`ff 7f`) read back `00 82` (-15) and its -15 -> 32769 (`01 80`) read `00 7e` (+15) (Mac sweep, dac-lab
     * run 22-phone raw/mic-sweep-mac.txt); the step-aligned 0x7F00 and 0x8100 read +15 and -15 (twice each). -14..+14 read back right. The WalkPlay-capture frame `01 02 02 80 e` of the TRN stored a wrong value on a Max.
     */
    private const val MIC_FULL_SCALE = 0x7F00

    private fun maxMicReport(value: Int): ByteArray {
        val t = Math.round(value * (32767.0 / 15)).toInt().coerceIn(-MIC_FULL_SCALE, MIC_FULL_SCALE).let { if (it < 0) it + 65536 else it }
        return WalkPlay.report(WalkPlay.WRITE, CMD_MIC, 0x02, t and 0xFF, (t shr 8) and 0xFF)
    }

    // The TRN's DAC Filter tab rows: the PC bench (runs/2026-10-21-trn-black-pearl-02 T14, filter 1 -> 2 -> 1) and the WalkPlay capture of 27.10.2026 (the vendor offers each row for the
    // TRN and sends these frames; read back, kept after an unplug) - research/trn-settings-walkplay-2026-10-27/FINDINGS.md.
    private val dacFilter = DacSetting(DAC_FILTER, "FILTER", SettingKind.CHOICE, EvidenceRank.VB, options = listOf("FAST-LL", "FAST-PC", "SLOW-LL", "SLOW-PC", "NON-OS"), group = 0,
        readCommand = CMD_DAC_FILTER, read = SettingRead.WalkPlayGet(CMD_DAC_FILTER, filterDecode), writeCommand = CMD_DAC_FILTER, writeVerified = true)
    private val workMode = DacSetting(WORK_MODE, "OUTPUT", SettingKind.CHOICE, EvidenceRank.VB, options = listOf("CLASS H", "CLASS AB"), group = 0,
        readCommand = CMD_WORK_MODE, read = SettingRead.WalkPlayGet(CMD_WORK_MODE, workDecode), writeCommand = CMD_WORK_MODE, writeVerified = true)
    private val gainMode = DacSetting(GAIN_MODE, "GAIN", SettingKind.CHOICE, EvidenceRank.VB, options = listOf("LOW", "HIGH"), group = 0,
        readCommand = CMD_GAIN_MODE, read = SettingRead.WalkPlayGet(CMD_GAIN_MODE, gainDecode), writeCommand = CMD_GAIN_MODE, writeVerified = true)
    /**
     * The mic gain as a read only row, in every build: no [DacSetting.writeCommand], so no build writes it. The TRN ignores the write (runs/2026-10-21-trn-black-pearl-01 and -02 T14). The Echo-A
     * and the Protocol Micro take it for some values only: Echo-A PC FAIL RUN-EA4 (runs/2026-10-22-moondrop-echo-a-01), phone 0 / +1 accepted, -1 ignored (runs/2026-10-22-moondrop-echo-a-01-phone/run.md
     * L96-106); Micro PC -1 ignored (runs/2026-10-22-crinear-protocol-micro-02, RUN-MIC2 L24), phone -3 accepted, -1 ignored (runs/2026-10-22-crinear-protocol-micro-01-phone/run.md L17).
     * A write that the DAC takes only for some values is not offered (owner rule, 1.6 brief: mic Echo-A read only, as on the TRN).
     */
    private val readOnlyMic = mic.copy(writeCommand = null)
    /** -15..15 in the vendor's steps (no unit): negative = the sound moves to the left, the right channel (1) is quieter. */
    private val balance = DacSetting(BALANCE, "BALANCE", SettingKind.BALANCE, EvidenceRank.VB, max = BALANCE_STEPS, group = 0,
        readCommand = CMD_BALANCE, read = SettingRead.WalkPlayGet(CMD_BALANCE, balanceDecode), writeCommand = CMD_BALANCE, writeVerified = true)

    /**
     * THE verification flag of the Max's WORK MODE row (`01 1D 01 t`, 0 = Class-H, 1 = Class-AB). True since the phone run 2026-11-10-crinear-protocol-max-22-phone: Class-H written from the app, read back, and kept after an unplug
     * (23:42). False would keep the row to review builds.
     */
    const val MAX_WORK_MODE_VERIFIED = true

    /**
     * The Protocol Max's DAC Filter tab rows: the TRN's frames. FILTER and BALANCE are verified (written from the app on a phone, read back, and kept after an unplug, dac-lab run
     * 2026-11-10-crinear-protocol-max-22-phone; their effect was measured on the Mac, run 14). WORK MODE is writable, verified by [MAX_WORK_MODE_VERIFIED]. No GAIN row: the unit's physical
     * Eco / Boost switch sets the gain mode and wins after every replug.
     */
    private val maxRows = listOf(dacFilter, workMode.copy(writeVerified = MAX_WORK_MODE_VERIFIED), balance, maxMic.copy(writeVerified = MAX_MIC_VERIFIED))

    /**
     * THE verification flag of the Max's MIC GAIN row (`01 02 02 lsb msb` then the flash save `01 01 00`, -15..+15). True since the Mac run 2026-11-11-crinear-protocol-max-23-mac-mic-save: mic -7 written with
     * the save was kept over an unplug and replug, and the EQ was unaffected (written without the save it was lost). One line to withdraw it: set this to false.
     */
    const val MAX_MIC_VERIFIED = true

    /** The rows of [profile]'s DAC Settings (see the class comment): empty for a DAC the plan does not cover. */
    fun of(profile: WalkPlayProfile): List<DacSetting> = when (profile.id) {
        "trn" -> listOf(dacFilter, workMode, gainMode, balance, readOnlyMic)
        "max" -> maxRows
        "micro", "echo-a" -> listOf(readOnlyMic)
        else -> emptyList()
    }

    /**
     * The rows the app puts on the DAC Settings page for [profile]: [of] where the row is proven or the build runs unproven features ([Proven.on]). A release shows the TRN's
     * four DAC Filter tab rows (the mic stays in review builds), the Max's FILTER, WORK MODE, BALANCE and MIC GAIN (each behind its own flag, [MAX_WORK_MODE_VERIFIED] and [MAX_MIC_VERIFIED], both true) and no page for the Micro or the Echo-A.
     */
    fun offered(profile: WalkPlayProfile): List<DacSetting> = of(profile).filter { Proven.on(it.writeVerified) }

    /** The row [id] of [profile], or the reason there is none. */
    fun row(profile: WalkPlayProfile, id: String): DacSetting =
        of(profile).firstOrNull { it.id == id } ?: throw IllegalArgumentException("${profile.name} has no setting \"$id\" (${of(profile).joinToString { it.id }.ifEmpty { "none" }})")

    /** The read requests of [s], in order: one report, two for the balance (one per channel). */
    fun readRequests(s: DacSetting): List<ByteArray> = when (s.id) {
        BALANCE -> listOf(WalkPlay.report(WalkPlay.READ, CMD_BALANCE, 0x02, 0x00, 0x00), WalkPlay.report(WalkPlay.READ, CMD_BALANCE, 0x02, 0x01, 0x00))
        else -> listOf(WalkPlay.report(WalkPlay.READ, requireNotNull(s.readCommand), WalkPlay.END))
    }

    /** True for a full reply report of [s]'s read (the strict predicate [DeviceProtocol.isReply] uses for the Max and the TRN). */
    fun isReply(s: DacSetting, buf: ByteArray, channel: Int? = null): Boolean =
        buf.size >= WalkPlay.REPORT_SIZE && WalkPlay.isReply(buf, requireNotNull(s.readCommand)) && (buf[1].toInt() and 255) == WalkPlay.READ &&
            (s.id != BALANCE || (channel != null && balanceChannel(buf) == channel))

    /** The value of [s] a reply holds (the balance: see [balance]), or null when the bytes make no sense. */
    fun decode(s: DacSetting, buf: ByteArray): Int? = (s.read as? SettingRead.WalkPlayGet)?.decode?.invoke(buf)

    /** The balance the two channel replies make, in the vendor's steps: channel 0 is the left one (the owner's listening test), so a positive value moves the sound to the right. */
    fun balanceValue(channel0: ByteArray, channel1: ByteArray): Int? {
        val left = balanceDecode(channel0) ?: return null
        val right = balanceDecode(channel1) ?: return null
        return left - right
    }

    /** `01 16 04 c 00 a 00`: [steps] of attenuation on channel [c] (0 = none). */
    private fun balanceFrame(c: Int, steps: Int) = WalkPlay.report(WalkPlay.WRITE, CMD_BALANCE, 0x04, c, 0x00, if (steps == 0) 0 else 256 - steps, 0x00)

    /** The reports that write [value] to [s] (not the flash save), or why that cannot be done. The balance is two, channel 1 first, as the vendor sends them. */
    fun writeReports(s: DacSetting, value: Int): List<ByteArray> {
        require(s.writeCommand != null) { "${s.label} is read only" }
        DacWrite.checkValue(s, value)
        return when (s.id) {
            DAC_FILTER -> listOf(WalkPlay.report(WalkPlay.WRITE, CMD_DAC_FILTER, 0x01, value + 1))
            WORK_MODE -> listOf(WalkPlay.report(WalkPlay.WRITE, CMD_WORK_MODE, 0x01, value))
            GAIN_MODE -> listOf(WalkPlay.report(WalkPlay.WRITE, CMD_GAIN_MODE, 0x01, value))
            BALANCE -> listOf(balanceFrame(RIGHT, maxOf(-value, 0)), balanceFrame(LEFT, maxOf(value, 0)))
            MIC -> listOf(if (isMaxMic(s)) maxMicReport(value) else WalkPlay.report(WalkPlay.WRITE, CMD_MIC, 0x02, 0x80, value and 0xFF))
            else -> throw IllegalArgumentException("${s.label} has no write")
        }
    }

    /** `01 01 00`: the flash save the vendor adds after a mic write (and after a noise-reduction write, which Contour never makes). */
    fun saveReport(): ByteArray = WalkPlay.report(WalkPlay.WRITE, CMD_SAVE, WalkPlay.END)
}
