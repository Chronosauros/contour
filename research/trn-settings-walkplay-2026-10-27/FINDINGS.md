# TRN Black Pearl settings in WalkPlay - 27.10.2026

Owner's unit (USB `3302:43E8`, "TE-C", firmware 0.6, headphones plugged in) on the Mac Studio, WalkPlay's web tool
https://peq.szwalkplay.com/ in Chrome, signed in with the owner's account. Every report the page sent and received was
logged by a hook on `HIDDevice.prototype.sendReport` and an `inputreport` listener (report id 0x4B). The raw log with
timings is `walkplay-capture.txt` next to this file.

## What the vendor offers for the Black Pearl
The "DAC Filter" tab shows four controls for this DAC:
- DAC digital filter: five cards FAST-LL, FAST-PC, SLOW-LL, SLOW-PC, NON OS.
- DAC working mode: Class-H, Class-AB.
- Output gain mode: Low, High (two levels; walkplay-plan.md assumed 1..3 from the decoded code).
- Sound channel balance: a slider L..R with no numbers.

## Frames (payload after the report id)
| control | read | reply | write |
|---|---|---|---|
| filter | `80 11` | `80 11 xx t`, t 1..5 | `01 11 01 t` |
| working mode | `80 1D` | `80 1D xx t`, 0 Class-H, 1 Class-AB | `01 1D 01 t` |
| gain mode | `80 19` | `80 19 xx m`, 0 Low, 1 High | `01 19 01 m` |
| balance | `80 16 02 00 00`, `80 16 02 01 00` | `80 16 xx c 00 a`, a 0 or 256 - steps | `01 16 04 01 00 a1 00` + `01 16 04 00 00 a0 00` |

`xx` was 02 in the first session and 00 after a replug; nothing depends on it. No write gets a reply, and the vendor sends
no `01 01 00` after any of them.

Balance, as the slider sent it: one step left `01 16 04 01 00 ff 00`, fully left `01 16 04 01 00 f1 00` (15 steps), each
followed by `01 16 04 00 00 00 00`; fully right `01 16 04 01 00 00 00` + `01 16 04 00 00 f1 00`. The vendor's centre is
`01 16 04 00 01 00 00` + `01 16 04 00 00 00 00`.

## Checked on the unit
- Read before any change: FAST-LL, Class-AB, High, balance 0 / 0 - the page showed the same.
- Listening (owner): with the slider fully at L (channel 1 attenuated by 15) the RIGHT ear was quieter. So channel 0 is
  the left channel and channel 1 the right one.
- Persistence (owner unplugged and replugged, headphones kept in): SLOW-PC, Class-AB, Low and channel 1 = 15 all read
  back unchanged. No flash save needed.
- The vendor's centre pair did not clear channel 1: after it the DAC still read channel 1 = 15. Two explicit zero frames
  (`01 16 04 01 00 00 00`, `01 16 04 00 00 00 00`) read back 0 / 0.
- Left as found: FAST-LL, Class-AB, High, balance 0 / 0.
- Side effect of the vendor page: on every connect it writes `01 03 02 00 00` (preamp 0 dB) before reading.

## In Contour
`android/core/.../native/WalkPlaySettings.kt`: FILTER, OUTPUT, GAIN (LOW, HIGH) and BALANCE (-15..15, negative = sound
to the left) are written with these frames and read back; all four are `writeVerified`. The MIC row stays read only (the
TRN ignores the write, runs/2026-10-21). Contour's centre is the two zero frames, not the vendor's pair.

## Contour on the Pixel 9 Pro (28.10.2026 ~00:00, perf 1.6.3-perf / 36, sha256 d1c674925cab1a52, branch wip/1.6.4-trn-settings)
- Read on the DAC page: 5/5 rows answered in 213 ms (FAST-LL, CLASS AB, HIGH, CENTER, mic 0) = the unit's state.
- Writes from the page, each read back (`settings write: VERIFIED`): GAIN LOW `01 19 01 00`, OUTPUT CLASS H `01 1D 01 00`,
  FILTER SLOW-PC `01 11 01 04`, BALANCE L 8 `01 16 04 01 00 f8 00` + `01 16 04 00 00 00 00`.
- Owner listening at L 8: right ear quieter (matches the vendor's L).
- Owner unplugged / replugged from the Pixel: Contour read SLOW-PC, CLASS H, LOW, L 8 (198 ms).
- Restore from the page: L 5 `01 16 04 01 00 fb 00` + zero, R 1 `01 16 04 01 00 00 00` + `01 16 04 00 00 ff 00` (channel 0 read 1), CENTER two zero frames (both 0), CLASS AB, FAST-LL, HIGH - all VERIFIED.
- Release channel on the simulated TRN (`--es simulate trn --es channel release`): FILTER, OUTPUT, GAIN, BALANCE only - no mic row,
  no reset row (screens kept locally in backup/evidence/2026-10-28-trn-settings-pixel/, not exported).
