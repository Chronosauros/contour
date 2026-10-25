package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.devices.PendingRecords
import kotlin.math.roundToInt

/**
 * The DAC Settings page rows of the Contour 1.6 families, in the page's own [DacSetting] language. Kept apart from [DacSettings] (the FiiO and
 * WalkPlay lists); [DeviceTarget.settings] asks here for a target that has neither.
 *
 * The Dawn Pro 2 rows are reads. Its Pre Gain has a write path ([MoondropSession.writeGain], 0.1 dB, values in tenths of a dB here) and keeps [DacSetting.writeVerified] false until a unit
 * has proven the frame: enabling it later is that one flag. Its Global Gain is read only in every build (the unit ignores the write). The Allegro Mini's EQ switch (register 0x24) and its Global Gain
 * (register 0x3B, steps 1-15) have write paths ([KtMicroSession.writeSwitch] and [KtMicroSession.writeGlobalGain]); each counts as done only after the DAC is back on the bus and a read agrees.
 * Both passed the PC bench and the phone (EQ switch OFF/ON and Global Gain 1/15/13, each VERIFIED after the re-enumeration, and persistence over a replug: Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone), so [DacSetting.writeVerified] is true. The readers are not
 * [SettingRead.FiiO]-style frames: the controller fills the rows from the read state ([FamilyState.readings]); the [SettingRead] is only the
 * page's "this row has a reader" mark.
 */
object FamilySettings {
    const val EQ_INDEX = "eq-index"
    const val GLOBAL_GAIN = "global-gain"
    const val PRE_GAIN = "pre-gain"
    const val EQ_SWITCH = "eq-switch"

    /** The page's mark for a row whose value comes from the family read; [command] is the register the value lives in. */
    private fun mark(command: Int) = SettingRead.WalkPlayGet(command) { null }

    private val dawn = listOf(
        // The active EQ index (register 0x0F). Read on the owner's unit 21.10.2026: 9. The range is provisional: only the index the DAC reports is shown.
        DacSetting(EQ_INDEX, "EQ INDEX", SettingKind.LEVEL, EvidenceRank.HW, min = PendingRecords.DAWN_EQ_INDEX_RANGE.first, max = PendingRecords.DAWN_EQ_INDEX_RANGE.last, group = 0, read = mark(MoondropCodec.CMD_EQ_ACTIVE)),
        // Register 0x03, the manufacturer's GLOBAL GAIN (the DAC offset); the range is MoondropCatalog's, the value is in tenths of a dB (0.1 dB step).
        // Read only in every build, like the TRN microphone ([WalkPlaySettings]): the unit ignores `01 03 02 lo hi` (PC T14, runs/2026-10-21-moondrop-dawn-pro-2-02, RUN-DP3; the phone only read it,
        // RUN-DPP1), so no row writes it. No [DacSetting.writeCommand]. The EQ write still sends the register with the value read just before, as MOONDROP Hub's Write Cfg does
        // ([MoondropCodec.writeSteps]), which changes nothing on the unit; the bench's `setting-plan global-gain` stays a bench tool ([MoondropSession.writeGain]).
        DacSetting(GLOBAL_GAIN, "GLOBAL GAIN", SettingKind.LEVEL, EvidenceRank.HW, min = (MoondropCatalog.dawnPro2.globalGainMinDb * 10).roundToInt(),
            max = (MoondropCatalog.dawnPro2.globalGainMaxDb * 10).roundToInt(), unit = "dB", group = 0,
            read = mark(MoondropCodec.CMD_GLOBAL_GAIN), writeCommand = null, writeVerified = false, scale = 10),
        // Register 0x23, the manufacturer's PRE-GAIN (the preamp), MoondropCatalog's range in 0.1 dB. The read layout and the write are from the vendor tool, not yet proven on the unit.
        DacSetting(PRE_GAIN, "PRE GAIN", SettingKind.LEVEL, EvidenceRank.VB, min = (MoondropCatalog.dawnPro2.preGainMinDb * 10).roundToInt(),
            max = (MoondropCatalog.dawnPro2.preGainMaxDb * 10).roundToInt(), unit = "dB", group = 0,
            read = mark(MoondropCodec.CMD_PRE_GAIN), writeCommand = MoondropCodec.CMD_PRE_GAIN, writeVerified = false, scale = 10),
    )

    private val allegro = listOf(
        // Register 0x24. writeVerified here means: a write is accepted by the page and then proven by a read after the DAC re-enumerates
        // (KtMicroSession.writeSwitch). PC bench passed; the first phone run (runs/2026-10-22-kiwi-ears-allegro-mini-01-phone) could not open the 10-byte HID report, fixed in 289fbfb, and the phone then
        // switched EQ OFF and ON with a VERIFIED read-back each time (Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone): on in every build.
        DacSetting(EQ_SWITCH, "EQ", SettingKind.TOGGLE, EvidenceRank.HW, options = listOf("OFF", "ON"), group = 0,
            read = mark(KtMicroCodec.EQ_ENABLE_REGISTER), writeCommand = KtMicroCodec.EQ_ENABLE_REGISTER, writeVerified = true),
        // Register 0x3B, the vendor tool's GLOBAL GAIN (analog DAC gain): the record's steps (13 = 0 dB per the vendor tool); the page shows the step as its dB (1-15 = -18.0..+3.0 dB in 1.5 dB steps), the value and the write stay the step. The write (KtMicroSession.writeGlobalGain,
        // step 0 never) passed the PC bench: runs/2026-10-21-kiwi-ears-allegro-mini-02 T14, 13 -> 12 -> 13, each written, saved and read back after the DAC re-enumerated. Like the EQ switch,
        // the phone then wrote steps 1, 15 and 13 with a VERIFIED read-back each (Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone), so writeVerified is true.
        DacSetting(GLOBAL_GAIN, "GLOBAL GAIN", SettingKind.LEVEL, EvidenceRank.VB, min = KtMicroCatalog.models.first().globalGainUserSteps.first,
            max = KtMicroCatalog.models.first().globalGainUserSteps.last, unit = "dB", group = 1,
            read = mark(KtMicroCodec.GLOBAL_GAIN_REGISTER), writeCommand = KtMicroCodec.GLOBAL_GAIN_REGISTER, writeVerified = true, valueText = ::globalGainText),
    )

    fun of(target: DeviceTarget): List<DacSetting> = when {
        target.moondrop != null -> dawn
        target.ktMicro != null -> allegro
        else -> emptyList()
    }
}

/** The Allegro Mini's Global Gain step as its dB, the way the other dB rows read: `-18.0`, `0.0`, `+3.0` (the unit is the row's); a value outside the steps stays the number. */
internal fun globalGainText(step: Int): String {
    if (step !in KtMicroCodec.GLOBAL_GAIN_STEP_MIN..KtMicroCodec.GLOBAL_GAIN_STEP_MAX) return step.toString()
    val db = KtMicroCodec.globalGainDb(step)
    return if (db > 0) String.format(java.util.Locale.ROOT, "+%.1f", db) else String.format(java.util.Locale.ROOT, "%.1f", db + 0.0)
}
