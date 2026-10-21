# JCally JM12 on the PC - 20-21.10.2026 (and on a phone, 21.10.2026)

A contributor's unit on stock firmware, Arch Linux, Python stdlib on `/dev/hidraw*` (root). Tool here: `jm12.py`
(read, `apply` an Equalizer APO file, `restore` a backup, `tone-test`; `--log` prints every report in and out with
wall-clock ms). States in `*.json` (raw read replies, d0..d3 = bytes 6..9), logs in `*.log`.
"Measured" below means seen on this unit; "source" means devicePEQ@0617f382 (`ktmicroUsbHidHandler.js`, the
`KT02H20 HIFI Audio` entry of `usbDeviceConfig.js` and `tests/captures/ktmicro_kt02h20_hifi_audio.json`).

## Enumeration (measured)
- USB `31B2:0111`, manufacturer "KTMicro", product **"KT02H20 HIFI Audio"**, serial "2020-02-20-0000-0000-0000",
  bcdDevice 0.01. UAC2 playback S16/S24_3/S32 44.1-384 kHz, mono mic 48 kHz.
- HID on interface 3, interrupt EP3 IN/OUT, 16 bytes. Report descriptor (70 bytes), one Application collection on
  the Consumer page: report 1 = media keys; vendor page `0xFF01`: report `0x4B` and report `0x54`, each 10 bytes In
  and 10 bytes Out.
  ```
  05 0c 09 01 a1 01 85 01 15 00 25 01 75 01 95 02 09 e9 09 ea 81 02 95 02 09 cd 09 cf 81 02 95 04
  81 01 06 01 ff 85 4b 75 08 95 0a 09 01 81 03 95 0a 09 02 91 02 85 54 75 08 95 0a 09 03 81 03 95
  0a 09 04 91 02 c0
  ```
- The same VID:PID and strings are used by other dongles: Linux applies its "MOONDROP JU Jiu" volume quirk to it,
  and devicePEQ lists Kiwi Ears Allegro Mini/PRO and TANCHJIM-ONE DSP on `31B2:0111` with other product names.
- Report `0x54` is the bootloader unlock in the JA11 cross-flash tools; it was never sent.

## Protocol (report 0x4B; source, every point below measured)
Payload `reg 00 00 00 cmd 00 d0 d1 d2 d3`; a reply echoes reg and cmd. Every reply is exactly 11 bytes with the ID.
- cmd `0x52` read, `0x57` write, `0x53` commit (reg `0x00`).
- reg `0x24`: EQ slot in d0, `02` = EQ off (factory), `03` = Custom. Writing `03` turns the EQ on.
- band i (0..4): reg `0x26+2i` = gain x10 (s16 LE, d0..d1) + stored frequency (u16 LE, d2..d3);
  reg `0x27+2i` = Q x1000 (u16 LE, d0..d1) + type in d2 (0 = PK).
- Factory (`factory-state.json`): slot 2; five PK bands at 0 dB, Q 0.707, stored 1000/2000/5000/8000/10000.
- Write ACK: `reg 00 00 00 57 00 03 00 00 00` about 1 ms after each write (`write-f1-crin.log`).
- Commit reply: `00 00 00 00 53 00 03 00 00 00` 16 ms after the commit.
- Reads answer in about 1 ms, **also with no audio stream** (`read-without-audio.log`, PCM status `closed`).

## The DAC plays twice the stored frequency (measured)
- `tone-test` (21.10): one notch at **stored** 1000 Hz (-10 dB, Q 4), then 1, 2 and 4 kHz tones played straight to
  the hardware (`aplay -D hw:`, no PipeWire resampling) at 48 kHz and at 96 kHz. At both rates the **2 kHz** tone
  was the quiet one: the factor is a fixed x2, not tied to the stream rate.
- Ear tests (20.10): F1-Crin.txt with the stored frequency / 2 sounded right, the raw value worse than no EQ; the same
  at a forced 44.1 kHz stream and at 96 kHz.
- devicePEQ sets `compensate2X: true` for this model; its capture comment says the opposite.

## Register 0x66 is not a preamp on the JM12 (measured)
devicePEQ reads `0x66` d0 as the pregain of other KT chips. Here it reads `fa 02 00 00` from the factory (devicePEQ's
capture of another JM12: `00 02`). A write of d0 -6 -> -12 dB was ACKed, read back and survived the commit and the
restart, and changed nothing audible, in RAM or after the commit. devicePEQ marks the model `NoPregain` and never
writes it. **Contour neither reads nor writes 0x66**; a profile's preamp is not sent and the PREAMP row is greyed out.

## Save (measured)
- RAM reads back byte for byte **before** the commit, so a bad write can be dropped (unplug) before it is saved.
- **The commit makes the DAC re-enumerate by itself**, every time (seven writes on 20.10, two on 21.10). In
  `write-f1-crin.log` + `kernel-2026-10-21.log`: commit sent 14:53:35.650, reply .666, `USB disconnect` .696,
  new enumeration 36.545, hidraw back 36.754 - about 1.1 s. The read on the new connection matched.
- Persistence: unplugged for 10 s (14:55:20 to 14:55:30) and read again: identical (`read-after-replug.log`).

## What Contour sends (the same frames jm12.py sent for F1-Crin.txt)
Read 0x24 and 0x26..0x2F; if the slot is 2, write `24 .. 57 00 03 00 00 00`; write the ten band registers; read them
back and commit only when all match; the read on the new connection (after the restart) decides ON DAC.

## Contour on a phone (measured, 21.10.2026)
Galaxy S25 FE (Android 16), Contour perf build, `adb logcat` over wireless debugging: `phone-2026-10-21.log`
(the app's `ContourUsb` lines plus the system's attach/detach lines; the USB serial is masked by Android).
- Android reports the product string "KT02H20 HIFI Audio" in the attach, before the USB permission: the allow-list works.
- Read on connect in about 200 ms (slot 3 and the ten band registers; `0x66` is not read).
- 15:22 and 15:27: two sends from EQ on (band 1 moved 60 -> 42 Hz, then a one-band flat profile); 15:35: a send of
  F1-Crin from the factory state (`jm12.py restore factory-state.json`, slot 2), which wrote `0x24` = `03` first. Each:
  RAM read back, commit, `Removed device` about 0.2 s later, back about 1.2 s after that, read on the new connection
  matched. The F1-Crin band frames equal `write-f1-crin.log` byte for byte.
- A replug through the PC and back kept the state. Six bands or a shelf cannot be made in the editor; FROM DAC
  gives preamp 0; the phone's volume keys work afterwards; the PREAMP row is greyed out only with the JM12.
- Android shows the app chooser (another installed USB audio app also claims the dongle) on every re-enumeration,
  also after each send, unless one app is set to Always.

## Not proven
- Shelves (types 3 and 4 in d2): devicePEQ turns them off for this model; not tried. Contour offers PEAK only.
- Limits: devicePEQ's +-10 dB, Q 0.1-5, 20-20000 Hz are used, not measured (the capture comment says +-12 dB).
- d1 of 0x66 (`02`): meaning unknown.
- Other firmware or other dongles on 31B2:0111 (Moondrop JU Jiu and the rest): not tested.
