# FiiO KA15 - capture of the official web app (fiiocontrol.fiio.com), 2026-10-03

Device: FIIO KA15 `2972:0104`, firmware query `0x0B` returns `03 01 01 06`. HID interface 0, usage page 0x0C, reportId 7, 64-byte reports.
Capture: WebHID hook (`HIDDevice.prototype.sendReport` + `inputreport`) in Chrome on Windows. Owner saved twice to USER1 and renamed USER1 to `TEST1` in the Device tab.

## Frame format (official app, both directions)

`HDR1 HDR2 00 SEQ CMD LEN DATA... CRC EE`

- `HDR` = `bb 0b` (get) or `aa 0a` (set).
- `SEQ` (byte 3): host counter +1 per frame, starts at 0 when the page connects. The device keeps its own independent counter in replies.
- `CRC`: CRC-8/MAXIM (poly 0x31, reflected in/out, init 0, xorout 0) over every byte from `HDR1` to the last data byte. Verified on 13 frames (`exchange/tmp/2026-10-03-contour-ka15/checksum.py`).
- Upstream devicePEQ sends `SEQ=0` and `CRC=0`. KA15 still applies such writes (Contour's USER1 EQ was found intact on the device), so the device does not reject a bad CRC on band writes.
- Replies are copied into a reused buffer: bytes after `EE` are stale leftovers. Parse by `LEN`, never by scanning to the end.

## User slot names (missing upstream)

- Read: `bb 0b 00 SEQ 30 01 IDX CRC ee`, where IDX = 0/1/2 = USER1/USER2/USER3 (preset 7/8/9).
  Reply: `bb 0b 00 s 30 08 IDX <9 bytes ASCII, NUL padded> CRC ee` e.g. `00 'HEDDD1'`, `01 'FF5'`, `02 'FH3'`.
  LEN says 8 but IDX + a 9-byte field follow (10 data bytes); CRC-8/MAXIM over the first 16 bytes checked on the Pixel 04.10.2026: `bb 0b 00 00 30 08 00 54 45 53 54 31 00 00 00 00 ac ee`.
  Only the first 7 bytes are the name. Bytes 8-9 can be stale report data: right after a save the Pixel read `4e 49 47 48 54 46 41 ab ee` (`NIGHTFA` + the tail of the previous frame), so parse at most 7 bytes.
- Write: `aa 0a 00 SEQ 30 LEN IDX <name bytes, no NUL> CRC ee`, LEN = 1 + name length. Captured: `aa 0a 00 1c 30 06 00 54 45 53 54 31 4a ee` (USER1 -> `TEST1`).
  Echo: `aa 0a 00 s 30 08 00 'TEST1' ...` (rest of 7-byte field is not guaranteed clean).
- Rename is independent from saving EQ (the official app renames from the Device tab). Max visible length from the reply field: 7 chars.
- `0x30` without SEQ/CRC (upstream-style `bb 0b 00 00 30 00 00 ee`) returns garbage - the index argument is required.

## Connect / read sequence (official app)

names 0,1,2 (`0x30`) -> filter count `0x18` (reply `0a` = 10) -> current preset `0x16` (reply `07`) -> bands `0x15` 0..9 -> global gain `0x17`. Roughly 100 ms between requests, each sent after the previous reply.

## Write sequence (official app)

- Live edit: a single `aa 0a .. 15 08 <band>` frame per change, no save.
- Save button: all 10 bands `aa 0a .. 15 08 i ...` (one per ~100 ms, each after its echo), then `aa 0a .. 19 01 07 CRC ee` sent ~20 ms after the last band frame, without waiting for its echo.
- It does NOT send preset switch `0x16`, filter count `0x18` or global gain `0x17` on save, and does NOT read anything back after save.
- Save reply is malformed: `00 ee 00 SEQ 00...` (no `aa 0a` header). A parser that waits for an `aa 0a 19` echo will time out.
- Band echoes return Q rounded by the device (sent 0x278 -> echo 0x27a, 0x187 -> 0x188, 0x25f -> 0x261), so Q verification needs slack.

## Diagnosis of the 2026-10-03 phone "hang" - KA15 answers HID only while USB audio is streaming

Proven on the Pixel 9 Pro, 22:43-22:45: with the player paused, fix1 and fix6 both send `bb 0b .. 16` and get no input report at all. With music playing through the KA15, fix1 (same code, same frames, SEQ=0 CRC=0) reads all 10 bands and the preset within ~25 ms per frame. On Windows the FiiO USB audio driver keeps the stream open, so the web app and hidapi always get replies.
The "hang after save" was the audio stream going idle, not the save. The device was never broken: USER1 kept exactly the EQ Contour wrote.

Second trigger, Pixel, 22:48 (music playing the whole time, owner confirmed audio kept playing): fix1 read everything, then sent preset switch `aa 0a 00 00 16 01 07 00 ee` (to the slot that was already active). No echo, and from then on no reply to any query, including a fresh connect a minute later.
On Windows (hidapi, 23:0x) the same switch does NOT silence the device: same slot, slot 7->8->7, and the Contour-style SEQ=0/CRC=0 frame all echo `aa 0a .. 16 01 <slot>` and keep answering every 0.5 s (`preset_silence.py`). So the preset switch is harmless on Windows and kills HID replies on the Pixel. Cause on Android not yet known (USB audio reconfiguration on preset change?).
Retracted: the web app's silence after its own preset switches was a stale WebHID handle (the KA15 had been moved to the phone and back; the device seq counter restarted), not firmware.

Rename verified on Windows (`rename.py`): IDX + name NUL-padded to 7 bytes (LEN=8) gives a clean name (`TEST1D` -> `TEST1`), 7 chars fit (`NIGHTFA`). USER1 left as `TEST1`.

Save target test on Windows (`save_other_slot.py`, 23:1x): with USER1 (7) active, a band write + `aa 0a 19 01 09` did NOT change USER3; the marker stayed in USER1 (still there after switching 7->9->7). So `0x19` saves the active preset whatever the slot byte says (send the active slot anyway, like the web app), and writing a different USER slot requires switching to it with `0x16` first. USER1 band 3 restored afterwards (`restore_user1_b3.py`).

## fix7 on the Pixel, 23:26 - read and save verified

`advbeta-ka15-fix7.apk` (sha256 a27ad74d...8d1d): SEQ + CRC frames, silent keep-alive AudioTrack routed to the KA15 for each HID session (`UsbAudioKeepAlive.kt`), no preset switch when USER1 is already active. With no music playing: full read OK; HOLD TO SEND of NIGHTFALL to USER1 -> every write echoed, `19 01 07` sent, device silent ~2 s after the save, then the read-back completed and the app shows ON DAC (band 3 went from the web app's +4.0 back to NIGHTFALL's +3.0).
Still open: writing USER2/USER3 (needs a preset switch, which silenced HID on the Pixel at 22:48 - retest with the keep-alive), reading/showing slot names, rename UI.

Fix in Contour (priority order):
0. Never send preset switch `0x16` when the target slot is already active (the official app doesn't on save). Switching to another slot needs a phone test first.
1. Keep the KA15 audio stream active for the whole HID session (silent AudioTrack routed to the USB device, started before the first query and stopped after the last), or at least detect "no reply while idle" and tell the user to start playback.
2. Save reply is `00 ee 00 SEQ` - do not wait for an `aa 0a 19` echo; no immediate read-back after save.
3. SEQ + CRC-8/MAXIM on TX for fidelity (not required: SEQ=0/CRC=0 frames are answered). Never validate CRC or header on RX.
4. USER names via `0x30`: read idx 0-2; write NUL-padded to 7 bytes (LEN=8), because a shorter write leaves the old tail (official app renamed USER1 to `TEST1` and the device and the web app both show `TEST1D`).

## Slot picker on the Pixel, 04.10.2026 00:38-00:53 - writes to USER1/2/3 work

`advbeta-ka15-slots2.apk` (sha256 472d9df9...71bd): names read on connect (TEST1 / FF5 / FH3), slot chosen in the app, HOLD TO SEND switches with `0x16` only when the chosen slot is not active, waits for the switch echo, writes 10 bands + preamp + count, saves with `0x19`, reads back, then writes the profile name with `0x30`.
- 00:38 NIGHTFALL to USER2 (switch 7->8): saved, still in USER2 after replug, name `NIGHTFA`.
- 00:38:59 flat profile to USER1 (switch 8->7): switch echoed, full re-read OK, every band/preamp/count write echoed, then `aa .. 19 01 07` got no reply and every later query timed out. After a replug the active preset was 7 (the switch stuck) and the name was still TEST1 (the save did not). The HID stopped at the save, 11 s after the previous save - not at the switch.
- 00:51-00:53, six writes in a row, each answered and read back: USER1 no switch, USER3 7->9, USER3 no switch, USER2 9->8, USER1 8->7 (the move that hung at 00:39), USER3 7->9. Owner: sound and DAC fine throughout.
So the preset switch on save is not what silences the KA15 on the Pixel; the 00:39 hang happened once and did not repeat. Save reply in these runs: `07 00 ee 00 SEQ` (the malformed reply above).
Q echo again rounded up by the device: sent 390/605/630, echoed 391/607/632.

## Rename by long press on the Pixel, 04.10.2026 01:08 - works

`advbeta-ka15-slots-r2.apk` (sha256 42c7f611...cefe): read name, write `aa 0a 00 00 30 08 IDX <name NUL-padded to 7> CRC ee`, echo, read again. USER2 `NIGHTFA` -> `HEDD` and USER1 `NIGHTFA` -> `TEST1`, both echoed and read back clean; the shorter name left no tail. The read before the first rename again carried a stale tail (`... 46 41 da ee`), the 7-byte cap handled it. A HOLD TO SEND right after (USER1, switch 9->7) saved normally.
