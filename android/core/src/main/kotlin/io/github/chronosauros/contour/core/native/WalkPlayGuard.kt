package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceProtocol
import io.github.chronosauros.contour.core.WalkPlay

/**
 * The WalkPlay frames that are never sent, whatever a caller asks (walkplay-plan.md, "Lista zakazow"). [WalkPlaySession] checks every request and every
 * send against this single list before the port sees it, so a frame that is on it cannot leave through the session whoever built it.
 *
 * Refused in every context:
 *  - the unresolved reset and probe candidates of other WalkPlay classes: `05 01 02 00 FF`, `05 01 01 00 FF` (MT2000), `02 00 00 01 FF` (CB1100AU), `80 00 00 00 FF`;
 *  - the CB1300D frames (`01 60`, `80 DA 00`, `01 22`, `01 D3`, `01 24`, `80 22`, `80 D3`, `80 24`, `80 26`, `80 25`) and the firmware update (`01 FF`);
 *  - the noise-reduction write `01 1B` (disabled for class CB; never on the TRN).
 *
 * Refused outside a ticketed factory reset ([Context.RESET], see [WalkPlayReset]):
 *  - `01 05 01` .. `01 05 04` (meaning unknown; the vendor sends them only inside its reset). `01 05 xx` with any other `xx` is refused too;
 *  - `01 05 00` and `01 17 00` on the TRN (its write never sends them). On the Micro, the Max and the Echo-A they are steps of [WalkPlay.COMMIT_SEQUENCE]
 *    (devicePEQ's commit, hardware-proven on the Micro): they stay allowed there, as part of that commit, and nowhere else is one built;
 *  - a POSITIVE preamp (`01 03 02 00 g`, g above 0) unless the profile's [WalkPlayProfile.preampMaxDb] is above 0 (never in the app: [ProtocolMicro.MICRO_POSITIVE_PREAMP_VERIFIED], [ProtocolMicro.ECHO_A_POSITIVE_PREAMP_VERIFIED]).
 *
 * Settings writes follow the tabs WalkPlay shows for the scheme: `01 11`, `01 16` and `01 1D` on the TRN and the Protocol Max (SchemeNo16, the DAC Filter tab, see
 * [WalkPlaySettings]), `01 19` (gain mode) on the TRN only (the Max has a physical Eco / Boost switch that wins after every replug); `01 02` (mic) on every WalkPlay DAC (the MIC tab; the Max
 * exposes no audio input, its row is vendor parity); nothing else. `01 01 00` (the flash save after a mic write) goes to the Max only in [Context.MIC_SAVE]: there `01 01 x` is also the EQ enable / flash frame.
 */
object WalkPlayGuard {
    /** [MIC_SAVE]: the flash save `01 01 00` right after a mic write ([WalkPlaySession.writeSetting]); the one place a Max may be sent it. */
    enum class Context { NORMAL, RESET, MIC_SAVE }

    private val CANDIDATES: List<List<Int>> = listOf(
        listOf(0x05, 0x01, 0x02, 0x00, 0xFF), listOf(0x05, 0x01, 0x01, 0x00, 0xFF), listOf(0x02, 0x00, 0x00, 0x01, 0xFF), listOf(0x80, 0x00, 0x00, 0x00, 0xFF),
    )
    private val CB1300D_WRITES = setOf(0x60, 0x22, 0xD3, 0x24)
    private val CB1300D_READS = setOf(0xDA, 0x22, 0xD3, 0x24, 0x26, 0x25)

    private fun u(p: ByteArray, i: Int) = if (i < p.size) p[i].toInt() and 255 else 0

    /** Why [payload] (the bytes after the report id, `d[0]` first) must not leave in [context] for [profile], or null. */
    fun refusal(payload: ByteArray, profile: WalkPlayProfile, context: Context): String? {
        val op = u(payload, 0)
        val cmd = u(payload, 1)
        for (c in CANDIDATES) if (c.indices.all { u(payload, it) == c[it] }) return "reset/probe candidate of another WalkPlay class (${hex(c)}): never sent"
        if (op == WalkPlay.WRITE) {
            if (cmd in CB1300D_WRITES) return "0x%02x is a CB1300D frame: never sent".format(cmd)
            if (cmd == 0xFF) return "01 FF is the firmware update: never sent"
            if (cmd == 0x1B) return "01 1B (noise reduction) is disabled for class CB: never sent"
            val trn = profile.protocol == DeviceProtocol.TRN
            val schemeNo16 = trn || profile.protocol == DeviceProtocol.MAX
            when (cmd) {
                0x05 -> {
                    val sub = u(payload, 2)
                    if (sub in 1..4) { if (context != Context.RESET) return "01 05 0$sub is the factory reset's: sent only inside a confirmed reset" }
                    else if (sub != 0 || (trn && context != Context.RESET)) return "01 05 %02x is not a step of ${profile.name}'s write".format(sub)
                }
                0x17 -> if (u(payload, 2) != 0 || (trn && context != Context.RESET)) return "01 17 is not a step of ${profile.name}'s write"
                WalkPlay.CMD_GLOBAL_GAIN -> {
                    val g = payload.getOrNull(4)?.toInt() ?: 0
                    if (g > 0 && profile.preampMaxDb <= 0) return "a positive preamp ($g dB) is not verified on a unit: never sent"
                }
                WalkPlaySettings.CMD_GAIN_MODE ->
                    if (!trn) return "0x%02x (gain mode) is not a setting of ${profile.name}: ".format(cmd) + if (profile.protocol == DeviceProtocol.MAX) "its physical Eco / Boost switch sets it and wins after every replug" else "WalkPlay shows it for SchemeNo16 only"
                WalkPlaySettings.CMD_DAC_FILTER, WalkPlaySettings.CMD_WORK_MODE, WalkPlaySettings.CMD_BALANCE ->
                    if (!schemeNo16) return "0x%02x is not a setting of ${profile.name} (WalkPlay shows it for SchemeNo16 only)".format(cmd)
                // the vendor's flash save after a mic write: on a Max `01 01 x` is also the EQ enable / flash frame, so `01 01 00` goes out only as that save (it kept the mic over a replug and left the EQ alone, dac-lab run 2026-11-11-crinear-protocol-max-23)
                0x01 -> if (profile.protocol == DeviceProtocol.MAX && u(payload, 2) == 0 && context != Context.MIC_SAVE) return "01 01 00 is sent to ${profile.name} only as the flash save after a mic write: its `01 01 x` is also the EQ enable / flash frame"
            }
        } else if (op == WalkPlay.READ && cmd in CB1300D_READS) return "0x%02x is a CB1300D read: never sent".format(cmd)
        return null
    }

    private fun hex(b: List<Int>) = b.joinToString(" ") { "%02X".format(it) }
}
