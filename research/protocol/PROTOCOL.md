# WalkPlay HID PEQ protocol - CrinEar Protocol Micro (3302:C20F, SchemeNo11)

Source: jeromeof/devicePEQ, commit `0617f382e76629792a5933e6933e4b396a756a93` (2026-08-26T13:32:14+01:00),
read 2026-09-25. License: `LICENSE.txt` = 0BSD-style ("Permission to use, copy, modify, and/or distribute
this software for any purpose with or without fee is hereby granted", Copyright 2024 Jerome O'Flaherty) -
compatible with an Apache-2.0 app; no attribution strictly required, courtesy credit recommended.
Files: `devicePEQ/walkplayHidHandler.js` (WH), `usbDeviceConfig.js` (UC), `compensation.js` (CO),
`usbHidConnector.js`, `peqConstraintsConfig.json` (PC). Line numbers refer to that commit.
Everything marked VERIFIED was observed on the owner's device on 2026-09-25 (firmware "0.2").

## Transport

- HID interface MI_03, single top-level collection Consumer Control (usage page 0x0C, usage 0x01).
  Report descriptor (92 bytes, VERIFIED, `report-descriptor.txt`):
  - ID 0x03 Input, 1 byte: consumer keys Vol+ (E9), Vol- (EA), Play/Pause (CD), CF - hardware buttons.
  - ID 0x4B Input 63 bytes (vendor page 0xFF01 usage 1) and ID 0x4B Output 63 bytes (usage 2) - **PEQ channel**.
  - ID 0x54 Input 63 bytes / Output 63 bytes (usages 3/4) - not used by devicePEQ. Do not send.
  - No Feature reports.
- Every command = Output report 0x4B, payload padded with zeros to 63 bytes (64 on the wire incl. ID).
  Replies = Input report 0x4B, 63 bytes. Below, byte offsets `d[n]` are WebHID-style (report ID stripped);
  hidapi / raw USB buffers are `d[n] == buf[n+1]`.
- Frame: `d[0]` = direction (0x80 READ, 0x01 WRITE), `d[1]` = command, `d[2]` = length or sub-arg, then args, 0x00 END.
  Replies echo `d[0]=0x80, d[1]=cmd`. Match replies on `d[1]` (WH L471); ignore report 0x03.
- All multi-byte integers little-endian.

## Commands

| Cmd | Name | Request payload (d[0..]) | Reply | Class |
|---|---|---|---|---|
| 0x0C | VERSION | `80 0C 00` | `80 0C 00 'x' '.' 'y'` ASCII at d[3..5] (VERIFIED "0.2") | READ-ONLY |
| 0x09 | PEQ read (bulk form) | `80 09 00` | filter-0 packet; devicePEQ reads slot at d[35] (WH L125). VERIFIED: identical to band-0 reply, d[35]=0 | READ-ONLY |
| 0x09 | PEQ read band i | `80 09 00 00 i 00` | filter packet (below) | READ-ONLY |
| 0x03 | global gain read | `80 03 00` | `80 03 02 00 g` g=int8 dB at d[4] (VERIFIED 0) | READ-ONLY |
| 0x09 | PEQ write band i | `01 09 18 00 i 00 00` + 20 B biquad + freq u16 + q u16 + gain s16 + type + `00` + slot + `00` (WH L153-163) | none awaited | MUTATING |
| 0x03 | global gain write | `01 03 02 00 g` (g = round(dB), int8) (WH L515) | none | MUTATING |
| 0x05 | commit step 1 | `01 05 00` (WH L182) | none | MUTATING |
| 0x17 | commit step 2 | `01 17 00` (WH L184) | none | MUTATING |
| 0x0A | TEMP_WRITE | `01 0A 04 00 00 FF FF 00` (WH L186) | none | MUTATING |
| 0x01 | FLASH_EQ / enable | `01 01 01 00` persist + PEQ on (WH L188); `01 01 en slot 00` = enablePEQ (WH L445), disable = `01 01 00 00 00` | none | MUTATING (flash) |
| 0x02 | mic gain | read `80 02 00`, write `01 02 02 lsb msb` | | read-only / mutating |
| 0x11,0x16,0x19,0x1B,0x1D | DAC filter, balance, gain mode, denoise, work mode | see WH L520-671 | | extras, not needed |

### Filter packet (read reply and write payload share the layout, VERIFIED)
| d[] | field |
|---|---|
| 0..3 | `80 09 00 00` (read) / `01 09 18 00` (write) |
| 4 | band index 0..7 |
| 5..6 | 00 00 |
| 7..26 | biquad: 5 x int32 LE, Q30 (x 2^30): b0, b1, b2, -a1, -a2 (normalised by a0), RBJ cookbook at fs = 96000 (WH L742-837) |
| 27..28 | freq u16, Hz (register value, already compensated) |
| 29..30 | Q u16 = round(Q x 256) (8.8) |
| 31..32 | gain s16 = round(dB x 256) (8.8) |
| 33 | type: 1 LSQ (low shelf), 2 PK, 3 HSQ, 4 LP, 5 HP (WH L249, L430) |
| 34 | 00 |
| 35 | slot / preset index (write: modelConfig.defaultIndex or slot from getCurrentSlot) |
| 36 | 00 END |

- Our Python port of computeIIRFilter reproduced all 8 stored biquads **bit-exact** from the stored
  freq/Q/gain registers, so the firmware stores what the host computed (devicePEQ comments suggest the
  chip may recompute from the metadata fields; unproven either way - always send both, consistent).
- LP/HP: devicePEQ computes the PK formula for the biquad even for LP/HP (only LSQ/HSQ have own branches).
  Likely wrong or ignored by firmware - unverified.
- Disabled band (devicePEQ): write all-zero biquad + freq 0, Q 0, gain 0, type PK (WH L144, L282).
  Read: band is "disabled" if freq is 0 or 0xFFFF, or Q 0; all-0xFF = never written (WH L403-418).
  There is no per-band enable bit. Gain 0 dB PK is acoustically neutral.

## Limits (SchemeNo11, PC L262-281, UC L690-700)
8 bands; gain -10..+10 dB; Q 0.1..10; types PK, LSQ, LP, HP. **No high shelf confirmed**
(`supportsHSFilter: false`), even though type code 3 exists in the shared handler. Pregain:
`deviceHandlesPregain: false` - the host computes it and writes CMD 0x03 as an integer dB (rounded!),
range int8 (UI practical range <= 0). No BP/notch/allpass.

## Compensation (SchemeNo11 only; `freqCompensation {ratio 0.9775}`, `qCompensation {cosNyquist, designFs 96000}`)
Applied on write AND inverted on read (CO; WH L281-303, L379-398). Order on write:
1. `f_sent = clamp(f_user / 0.9775, 20, 20000)`; written as `trunc(f_sent)` (JS `>>`), biquad uses the unrounded float.
2. `ratio = cos(pi * min(f_sent/96000, 0.5))`; for PK/LSQ/HSQ only: `q_sent = clamp(q_user / ratio, 0.1, 10)`.
   LP/HP: no Q compensation.
3. Read: `f_user = f_raw * 0.9775`; `q_user = round(q_raw/256, 2) * cos(pi * f_raw/96000)`.
- Rationale in WH L263-280: SchemeNo11 lands filters 2.2-2.5 % low (measured on EPZ TP13), Q realised lower
  towards Nyquist. It is a fixed factor, independent of the streamed sample rate ("96 kHz" is the firmware's
  design rate, not the stream rate). SchemeNo16 (Protocol Max) has no such offset.
- Compensation can be disabled globally in devicePEQ (verification bypass) - then raw values are shown.
  The app should store the user's intent and treat compensation as a per-scheme option.

## Sequences
- Connect: `open()` (no command) -> **getCurrentSlot**: VERSION (wait <= 2 s for d[1]=0x0C), bulk PEQ read
  (wait for d[1]=0x09, slot = d[35]).
- **pullFromDevice**: listener on; for i in 0..7: send `80 09 00 00 i 00`, sleep 50 ms; sleep 100 ms;
  poll until 8 bands or 10 s timeout (partial result allowed); then global gain read (100 ms timeout).
  VERIFIED: every reply arrives 4-8 ms after the request; whole read 520 ms, dominated by the 50 ms pacing.
- **pushToDevice** (WH L132-191): for each band: filter write, 20 ms; then 100 ms; global gain write, 50 ms;
  then `01 05`, 20 ms, `01 17`, 20 ms, `01 0A 04 00 00 FF FF`, 50 ms, `01 01 01`.
  No replies are awaited for writes. **The commit sequence persists to flash** (comment WH L178-181:
  "to persist the registers while leaving PEQ enabled"). devicePEQ has no volatile write path; whether
  band writes without the commit take effect live is unknown (write_test.py `--mode volatile` tests exactly that).
  After writing, devicePEQ does not re-read automatically (`disconnectOnSave: false`, device stays open).
- Slots: WalkPlay default config exposes one writable slot "Custom" id 101 (UC L676-684); the SchemeNo11
  group does not override it. Observed slot byte = 0 in every reply. Treat the slot byte as "echo what you read".

## Other WalkPlay groups (for extensibility)
Same handler/wire format for SchemeNo10-21 (WH L7). Differences are config only:
- SchemeNo10 (default): 8 bands, PK only.
- SchemeNo16 (incl. CrinEar Protocol Max, UC L940-950): 10 bands, +-10 dB, LS+HS, no freq/Q compensation, pregain via CMD 0x03 as well; extras gain mode 0x19.
- SchemeNo15: 8 bands full shelves; SchemeNo18 (NiceHCK Octave): 10 bands full shelves.
- Note PID 0x4302 appears in both SchemeNo11 and SchemeNo16 lists - first match (No11) wins in devicePEQ.
- Group match is by VID in the WalkPlay vendor list (incl. 0x3302) + PID in the group list; model-name entries override.

## Android mapping (UsbManager / UsbDeviceConnection)
- Claim only the HID interface (interface number 3, class 0x03); never touch the audio interfaces 0-2.
  `claimInterface(intf, true)` detaches the kernel usbhid driver from that interface only - volume
  buttons (report 0x03) stop working while claimed; release afterwards.
- Endpoints are NOT visible from Windows (hidapi exposes no endpoint descriptors). At runtime iterate
  `intf.getEndpoint(i)`: if an endpoint with type `USB_ENDPOINT_XFER_INT` and direction `USB_DIR_OUT`
  exists, send the 64-byte report (`4B` + 63 bytes) with `bulkTransfer`/`UsbRequest` on it; otherwise use
  SET_REPORT: `controlTransfer(0x21, 0x09, (0x02 << 8) | 0x4B, 3, buf64, 64, 1000)` (report type 2 = Output;
  include the report ID as the first data byte, as in a numbered report).
- Input: the interrupt IN endpoint (mandatory for HID). Read with a queued `UsbRequest` / `requestWait`
  or `bulkTransfer(epIn, buf, 64, timeout)`; buffer[0] = report ID (0x4B or 0x03 - filter on 0x4B and d[1]).
  Fallback GET_REPORT (0xA1, 0x01, (0x01<<8)|0x4B) is not what devicePEQ uses; avoid unless IN fails.
- Keep devicePEQ pacing (>= 20 ms between writes, 50 ms between band reads is generous; replies take ~8 ms,
  so request-reply lock-step without fixed sleeps should read all 8 bands in < 100 ms - untested).

## FiiO roadmap note (config/handler only; nothing was sent to the FiiO device)
- FiiO is matched by VID 0x2972 (and 0x0A12) + **USB product name string**, not by PID (UC L16, L360-400).
  "FIIO KA15": supported, 10 bands, +-12 dB, 7 types (PK/LS/HS/LP/HP/BP/AP), writable user slots 7-9
  (USER1-3), "Close EQ" = 10. "FIIO K13 R2R": supported, 10 bands, -24..+12 dB, all FiiO types, user slots
  160-169 (USER1-10), BYPASS 240, stock presets 0-6, 8-10. PID 0x0120 is not referenced anywhere - the
  match depends on the device reporting product name exactly "FIIO K13 R2R" (not checked).
- Transport: WebHID, report ID 7 (default), frames `AA 0A ...` (set) / `BB 0B ...` (get), end `EE`;
  write = global gain, filter count, per-filter params (0x15), then save `AA 0A 00 00 19 01 slot 00 EE`
  (fiioUsbHidHandler.js L462-469). The handler does not itself block stock-preset slots; only the config's
  firstWritableEQSlot/maxWritableEQSlots describe the user slots - the app must enforce that.

## Verified on the device, 2026-09-25 (read-only so far)
- Fresh read identical to device-backup-2026-09-25.json byte for byte (step1-read.log, step1.json).
- Lock-step read (next request right after reply, 200 ms timeout, no 50 ms pause; timing_read.py, timing.log):
  3 runs, 56-78 ms for version + slot + 8 bands + preamp, max single reply 8-22 ms, no missing or garbled
  replies, band data identical to the backup. The Android port can drop devicePEQ's 50 ms pause.
- Write test NOT run: the session's permission classifier blocked the device-writing command
  ("Real-World Transactions"). Still open: whether volatile writes apply without the commit, whether
  writes get acks, write->read-back latency, and the commit round-trip.
