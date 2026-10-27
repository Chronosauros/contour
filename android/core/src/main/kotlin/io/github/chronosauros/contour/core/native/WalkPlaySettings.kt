package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.WalkPlay

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
 * (hardware and the CAP reply agree). The Protocol Max (SchemeNo16) has no rows: not studied.
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

    /** The rows of [profile]'s DAC Settings (see the class comment): empty for a DAC the plan does not cover. */
    fun of(profile: WalkPlayProfile): List<DacSetting> = when (profile.id) {
        "trn" -> listOf(dacFilter, workMode, gainMode, balance, readOnlyMic)
        "micro", "echo-a" -> listOf(readOnlyMic)
        else -> emptyList()
    }

    /**
     * The rows the app puts on the DAC Settings page for [profile]: [of] where the row is proven or the build runs unproven features ([Proven.on]). A release shows the TRN's
     * four DAC Filter tab rows (the mic stays in review builds) and no page for the Micro or the Echo-A.
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
            MIC -> listOf(WalkPlay.report(WalkPlay.WRITE, CMD_MIC, 0x02, 0x80, value and 0xFF))
            else -> throw IllegalArgumentException("${s.label} has no write")
        }
    }

    /** `01 01 00`: the flash save the vendor adds after a mic write (and after a noise-reduction write, which Contour never makes). */
    fun saveReport(): ByteArray = WalkPlay.report(WalkPlay.WRITE, CMD_SAVE, WalkPlay.END)
}
