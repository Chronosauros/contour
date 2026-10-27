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
Contour 1.6.0 offers LOW PASS (code 4) and HIGH PASS (code 5) on the Protocol Micro and the Moondrop Echo-A (same scheme), with the
coefficients designed for 93 750 Hz (see "Measured Q conventions" under the KA15). The Micro's DAC takes a positive preamp, but the level does not rise,
so Contour stays at 0 dB and below.

## Contour does NOT use the compensation below (decision 26.09.2026)
CrinEar's own tool - devicePEQ v0.20 on graph.hangout.audio/plugins/devicePEQ (walkplayHidHandler.js
Last-Modified 02.07.2026) - writes freq/Q raw (biquad and metadata) and reads them raw. With the compensation,
a Contour band at 6900 Hz showed as 7058 Hz there (Reddit user report, 26.09). The 2.2-2.5 % offset was measured
on another SchemeNo11 device (EPZ TP13), never on the Protocol Micro. Contour now writes and reads raw values.
v0.20 differences Contour keeps on purpose: v0.20 writes the PK biquad for every type (shelves become peaks in
the coefficients; Contour writes real shelf biquads) and writes the preamp as min(0, preamp + 5)
(`globalGainBuffer: -5`, removed in 0617f382 as not present in the official WalkPlay app); Contour writes the
preamp register as is. PK biquad bytes are identical to v0.20 (test `bands reach the wire as set...`).

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

## CrinEar Protocol Max (supported in Contour 1.3.0)

- Allow-list: **3302:43CC only** (devicePEQ `walkplayHidHandler.js` L9, `usbDeviceConfig.js` "Protocol Max"),
  recognised in every build type; the USB attach filter lists it next to the Micro. No VID-wide or name
  matching; no other SchemeNo16 PID.
- SchemeNo16 / `peq10Band10dBFullShelves`: 10 filters, +/-10 dB, Q 0.1..10, PK/LS/HS on their native codes
  2/1/3. Same 0x4B 64-byte format and 96 kHz RBJ/Q30 coefficients as the Micro, raw frequency and Q (no
  compensation), no Micro HIGH SHELF -> LOW SHELF + preamp emulation. Pregain is computed by the host and
  written with CMD 0x03 (`deviceHandlesPregain: false`); Contour keeps its -30..0 dB policy, not a proven
  Max limit. Spare slots are neutral PKs (1 kHz, 0 dB, Q 0.75), not Max factory captures.
- Fails closed: exactly one HID interface with a 64-byte interrupt IN endpoint, else no I/O. VERSION, bulk
  slot, all ten bands and the preamp must parse strictly (64-byte, read direction, index, header, native
  type, ranges) before the first write. A send writes all ten slots, echoes the bulk-read slot byte, commits
  as on the Micro, then reads everything back; any mismatch, including the slot byte, is failure, not ON DAC.
- Off on the Max: A/B (TEMP_WRITE is only proven RAM-only on the Micro) and the service screen's band-1 test
  write. No extra WalkPlay commands (gain mode 0x19, DAC filter, balance, mic gain).
- Hardware test: a community member connected a Protocol Max on 02.10.2026 (Max beta build, 1.2.2-maxbeta1): 8 bands, peak and
  shelf filters, no crashes. A small pop when sending is normal - the dongle saves the EQ to its memory.
  Not yet checked: A/B (stays off), long-term persistence details. The Protocol Micro path is unchanged from 1.2.2.

## TRN Black Pearl (3302:43E8)

Hardware facts: `research/black-pearl-pc-2026-10-19/FINDINGS.md` (written and read back from a PC on 19.10.2026).

- Allow-list: **3302:43E8 only**, recognised in every build; the USB attach filter lists it. SchemeNo16 like the Max
  (devicePEQ `peq10Band10dBFullShelves`): 10 filters, +/-10 dB, Q 0.1..10, PK/LS/HS on native codes 2/1/3, raw
  frequency and Q (no compensation, no Micro HIGH SHELF emulation). Preamp is whole signed int8 dB (CMD 0x03);
  Contour keeps its -30..0 dB policy, not a proven limit of the unit.
- Enumeration: USB product string "TE-C". **The unit does not enumerate at all without headphones plugged in**
  (nothing on the bus, not even a failed device); with headphones it appears. Interface 0 is HID (one collection,
  usage page 0x0C usage 0x01, report 0x4B), interface 1 is audio. Without headphones Contour sees nothing and shows NO DAC.
- Factory state (firmware 0.6): bulk slot byte 0, preamp 0 dB, ten PK bands at 0 dB, register Q 182/256
  (0.711) on 31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz. The stored biquads fit RBJ at
  **96 kHz** (Q 0.75 in that state; 44.1, 48 and 192 kHz do not fit), the design rate Contour uses. The firmware does
  not recompute coefficients: what is stored is what runs. Contour's spare slots are exactly this factory flat.
- **Write sequence** = `write_bank` of FreeDSP-Studio (`tools/app/src/dsp2.rs`, MIT, (c) 2026 EffectiveEquivalent, commit
  1b42f63; see THIRD_PARTY_NOTICES.md), scheme 16:
  1. every band `4B 01 09 21 00 i 00 00` + biquad (20) + freq u16 + Q*256 u16 + gain*256 s16 + type + `00` + slot,
     25 ms after each. **The length byte is 0x21**, not devicePEQ's 0x18. Slot byte = the bulk-read slot (0 here).
     Biquads are Contour's own (96 kHz, Q30); the unit stored those bytes unchanged.
  2. commit `01 0A 04 00 00 FF FF`, 50 ms
  3. preamp `01 03 02 00 g`, 50 ms
  4. `01 01 00` (EQ flash), 200 ms
  5. `01 04 00` (offset and preamp flash), 200 ms
  Not devicePEQ's commit (`01 05`, `01 17`, `01 01 01`). All ten bands are written on every send; unused ones are the
  factory flat above. Read-back compares every register of all ten bands, the preamp and the slot byte.
- Proven on the unit (registers and every biquad byte identical after the write): LOW SHELF, HIGH SHELF and peaks across the
  caps, PK 500 Hz **-10.4 dB Q 0.1 stored as sent** (no firmware clamp at +/-10 dB; the TRN's own tool stores -10.4 too),
  and the **pass filters: LOW PASS 18 kHz is code 4, HIGH PASS 30 Hz code 5, both kept as sent** (not coerced to PK).
  Pass filters are NOT offered for the TRN in 1.6.0 (they stay out until they are proven on the unit); a read still accepts codes 1 to 5 so a state written by the TRN's tool does not stop a send.
- Persistence: unplug and replug with the headphones kept in: the whole state came back identical, **the preamp
  (-12 dB) included**, after `01 04 00`. Not tested: whether the preamp also persists without `01 04 00`.
- Read: VERSION, bulk slot, ten band requests, preamp, as the Max. A stored band is checked against the register format
  (frequency 1..48000, gain +/-60 dB), not the +/-10 dB caps; disabled or erased slots (freq or Q 0, type 0 or 0xFF) are
  no read failure. FROM DAC loads an out-of-cap band with its real value and a warning (sending it back stays blocked).
  A band that did not come back is asked for once more, alone, after draining the input (on 19.10.2026 a stale reply
  made band 1 go missing once).
- Off on the TRN: A/B (TEMP_WRITE is proven RAM-only on the Micro only) and the service screen's band-1 test write.
- DAC settings (WalkPlay's "DAC Filter" tab, captured from peq.szwalkplay.com on 27.10.2026, `research/trn-settings-walkplay-2026-10-27/FINDINGS.md`).
  Reads: filter `80 11` -> d[3] 1..5, work mode `80 1D` -> d[3] (0 Class-H, 1 Class-AB), output gain mode `80 19` -> d[3] (0 Low, 1 High),
  balance `80 16 02 00 00` / `80 16 02 01 00` -> d[3] channel, d[5] attenuation byte (0 or 256 - steps). Writes, no reply, no flash save:
  filter `01 11 01 t` (1 FAST-LL, 2 FAST-PC, 3 SLOW-LL, 4 SLOW-PC, 5 NON OS), work mode `01 1D 01 t`, gain `01 19 01 m`, balance two frames
  `01 16 04 01 00 a1 00` + `01 16 04 00 00 a0 00` (channel 1 then channel 0). **Channel 0 is the left one, channel 1 the right one** (owner's listening
  test: the vendor slider at L attenuates channel 1 and the right ear goes quiet). The vendor's slider has 15 steps each side, no unit.
  The vendor's own centre pair `01 16 04 00 01 00 00` + `01 16 04 00 00 00 00` does **not** clear channel 1 (read back 15 after it); Contour sends
  two explicit zero frames for the centre. All four rows survived an unplug without `01 01 00`. On every connect the vendor page writes
  `01 03 02 00 00` (preamp 0 dB) before its first read.
- Contour on a phone (Pixel 9 Pro, 19.10.2026): recognised, HID interface 0 with IN 0x86 / OUT 0x05, read in about
  400 ms; a five-band profile with preamp -6 dB sent VERIFIED, and a PC read afterwards showed the same state.
- Audible response: measured on the test rig on 25.10.2026, 12 of 12 cases (see "Measured Q conventions" under the KA15).

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

## FiiO KA15 (2972:0104, supported in Contour 1.3.2)

Captured from FiiO's official web app (fiiocontrol.fiio.com, WebHID hook, 03.10.2026) and verified on a Pixel
and on Windows with a real KA15 (03-04.10.2026).

- Transport: HID interface 0, report ID 7, 64-byte reports. Exact match on `2972:0104`.
- Frame: `HDR1 HDR2 00 SEQ CMD LEN DATA... CRC EE`; `HDR` = `bb 0b` (get) or `aa 0a` (set). SEQ = host counter,
  +1 per frame. CRC = CRC-8/MAXIM (poly 0x31, reflected, init 0) over `HDR1`..last data byte. The KA15 also
  answers SEQ=0/CRC=0 frames (devicePEQ style). Replies arrive in a reused buffer: bytes after `EE` are stale,
  so parse by `LEN`, never to the end of the report.
- Commands: `0x15` band (index + 7 bytes), `0x16` active preset, `0x17` global gain, `0x18` filter count
  (always 10; a lower count is ignored, so unused bands are written as 0 dB peaks), `0x19` save,
  `0x30` USER slot name.
- Slots: USER1-3 = presets 7-9; stock presets 0-6 and "Close EQ" (10) are never written. `0x19` saves the
  *active* preset whatever its slot byte says, so writing another USER slot needs `aa 0a .. 16 01 <slot>`
  first. Contour switches only when the chosen slot is not active, like the official app on save.
- Save reply is malformed (`.. 00 ee 00 SEQ`, no `aa 0a` header); the KA15 is busy for about 2 s after a save.
  Band echoes round Q up by one or two steps, so read-back allows that slack.
- Names (missing upstream): read `bb 0b 00 SEQ 30 01 IDX CRC ee`, IDX 0-2 = USER1-3. The reply carries a
  9-byte field, but only the first 7 bytes are the name; bytes 8-9 can be the tail of the previous frame.
  Write `aa 0a 00 SEQ 30 08 IDX <name NUL-padded to 7> CRC ee` (a shorter, unpadded write leaves the old tail).
  Names are A-Z and 0-9. Renaming does not touch the EQ.
- On Android the KA15 answers HID only while a USB audio stream is open (on Windows the FiiO driver keeps it
  open). Contour plays a silent AudioTrack routed to the KA15 for each read or write session, without audio
  focus, so other playback continues.
- 04.10.2026 on the Pixel: ten saves with and without a preset switch, nine answered and read back; once
  the save got no reply and every later query timed out until a replug (the switch had stuck, the save had
  not). Not reproduced.

### Measured acoustic behaviour (04.10.2026)
Sweeps 10 Hz-22 kHz through the KA15 to a line input, each result minus Close EQ, median of 4 runs
(repeatability 0.06-0.08 dB), registers read back over HID, current firmware.
- LS/HS shelves play Q = register Q / sqrt(2) for every type, frequency, gain and Q tried (LS 80 Hz +4 dB 0.70 -> 0.501,
  HS 1 kHz -1.2 dB 1.40 -> 0.978, LS 200 Hz +10 dB 1.00 -> 0.708, HS 2 kHz -10 dB 2.00 -> 1.412): fit error 0.010-0.017
  dB rms, against 0.12-0.82 dB with the register Q. devicePEQ's shelf slope law is wrong here (0.588 dB at HS 2 kHz).
- PK bands play the register Q (factor 0.986), without gain-dependent bandwidth (Q/A law 0.107 dB, register Q 0.041 dB).
- With any USER preset active, output = input - 12 dB + preamp register (checked at -6, -4, -3, -1, 0, +12). Preamp
  +12 with flat bands at -1 dBFS equals Close EQ within 0.1 dB, without extra harmonics; a +12 dB shelf at preamp 0 does not clip.
- Contour writes shelf Q register = Q x sqrt(2) and preamp register = preamp + 12 dB (shown range -24..0 dB, shelf Q up
  to 7.07), so squig.link/AutoEQ profiles play as designed. FiiO's app and web app show the raw registers.

### Measured Q conventions and coefficient rate on other DACs (24-25.10.2026)
Same rule as the KA15 (owner precedent 04.10.2026): Contour corrects the measured behaviour at the register boundary (register = intended x scale, the editor and
every comparison stay in the intended domain); the vendor tools show the raw registers. UMC202HD rig, stream 44.1 kHz, runs in `dac-lab/runs`; models in
`dac-lab/contour-1.6/research/2026-10-25-shelf-law.md`, `2026-10-25-dsp-model-k13-allegro.md`, `2026-10-24-dsp-model-walkplay.md`. Records: `peq.shelfQScale`,
`peq.peakQScale`, `peq.coefficientRateHz` (devices/README.md).
- FiiO K13 R2R, 25.10.2026 (`2026-10-25-fiio-k13-r2r-02-mac-eqtest`): a PK plays RBJ with Q = register / sqrt(2) (6 of 6 cases within 0.136 dB; the register as Q: 0.55-0.98 dB).
  Shelves, LP, HP, BP and AP play the register as Q. Contour writes the PK register = Q x sqrt(2) (Q 2.00 -> 2.83; PK Q range 0.07-7.07).
- SnowSky Melody and Tiny A, 24.10.2026 (`2026-10-24-snowsky-melody-05-mac-eqtest`, `2026-10-24-snowsky-tiny-a-05-mac-eqtest`): shelves play Q = register / sqrt(2) like the
  KA15 (Melody 12 of 12 cases with it, 8 of 12 with the register as Q; Tiny A 7 of 7 and 6 of 7), PK plays the register as Q. Contour writes the shelf register = Q x sqrt(2)
  (Q 0.70 -> 0.99; shelf Q range 0.07-7.07).
- TRN Black Pearl, 25.10.2026 (`2026-10-25-trn-black-pearl-01-mac-eqtest`, 12 of 12): shelves play RBJ with Q = register / sqrt(2), computed by the DAC from the f / gain / Q
  registers at the stream rate; the coefficient bytes Contour sends in the same frame are not played (a fit with them at 96 kHz is 0.35 dB worse). PK plays the register as Q.
  Contour writes the shelf register = Q x sqrt(2) and still designs the coefficient bytes for the intended Q.
- CrinEar Protocol Micro and Moondrop Echo-A, 24.10.2026 (`2026-10-24-crinear-protocol-micro-04-mac-eqtest` and `-05-`, `2026-10-24-moondrop-echo-a-03-mac-eqtest` and `-04-`):
  the DSP plays the coefficient bytes, but at 93 750 Hz (96 000 x 125/128), while the vendor tool (`computeIIRFilter(..., a = 96e3)`) and Contour designed them for 96 000 Hz, so every
  filter landed 2.34 % low (PK Q5 up to 0.88 dB, LP 8000 0.56 dB). Coefficients designed for 93 750 Hz fit every valid case within 0.057 dB. Shelves play the slope form Contour uses
  (not rescaled). Contour designs the Micro's and the Echo-A's bytes for 93 750 Hz; the TRN, Max and Dawn Pro 2 stay at 96 000 Hz (not measured, or not played). Not checked at a 48 kHz stream.
- Kiwi Ears Allegro Mini, 25.10.2026 (`2026-10-25-kiwi-ears-allegro-mini-07-mac-eqtest-48k`, a 44.1 kHz stream despite the name, and `-08-` at 48 kHz): the DSP designs its RBJ biquads for a fixed 24 000 Hz and runs them at the stream rate, so a band plays at register f x stream / 24 000 (x1.84 at 44.1 kHz, x2.00 at 48 kHz; gain and Q as written; free fits 24 061 and 24 143 Hz). The vendor tool writes the UI frequency as it is. Contour assumes a 48 kHz stream (owner decision 25.10.2026) and writes register f = intended f x 24 000 / 48 000 (1000 Hz -> 500), converting a read back; the editor offers 40-20000 Hz (register 20-10000; a register below 20 was never written). A player that forces 44.1 kHz shifts every band about 8 % down. Open: a Q 5 band whose register sits at 6000 Hz plays wider than the model.
- Moondrop Dawn Pro 2, 24.10.2026 (MOONDROP Hub Beta 1.6.5 "Write Cfg" captured on the owner's unit, firmware 1.5, USB 35D8:011D; `2026-10-24-moondrop-dawn-pro-2-04-mac`, result `-05-mac`
  and `-06-mac`): the Hub does not check the EQ index. It sends `01 0F 01 07`, eight band frames (`01 09 21 00 i ...`, last byte the bank tag 07), `01 0A 04 00 00 FF FF`, `01 23 02 lo hi`,
  `01 03 02 lo hi`, `01 01 00`, 30-65 ms apart; the unit ignores the switch and stays on index 9, and the bands of bank 9 took the written values and kept them over a replug. Contour writes this
  sequence (global gain written back as read, the pre-gain from the profile) when the unit is on one of Contour's banks (EQ index 7 or 9; on a preset index nothing is sent) and verifies by reading the eight bands back, whatever index the unit reports. The unit's Global Gain register is ignored.

## FiiO K13 R2R (2972:0120, supported in Contour 1.4.1)

Measured on the owner's K13 R2R on a Pixel 9 Pro, 10.10.2026 (firmware 1.15, USB product "FIIO K13 R2R").

- Transport: the KA15 frame format and commands (`0x15` band, `0x16` active preset, `0x17` global gain,
  `0x18` filter count, `0x19` save), report ID 7 with 33-byte reports in and out, HID interface 3. The K13
  answers without a USB audio stream and does not echo writes, so Contour sends no keep-alive stream.
- USB: the device reports two identical configurations; Contour opens the first one only. Report 7 is declared
  in a top-level Application collection on Generic Desktop with Usage 0x00 (Undefined) instead of a vendor
  page; Contour accepts that layout on the FiiO paths only.
- Slots: USER1-USER10 = presets 160-169 (`a0`-`a9`), BYPASS = 240 (`f0`), stock presets 0-6 and 8-10 are never
  written. Writing a slot makes it the active one. The K13 stores no USER names: FiiO's web app shows USER1-USER10
  without rename and sends no `0x30`, and a KA15-style `30 01 00` query from Contour got no reply and broke the next
  output report (14.10.2026). Never send name commands to the K13; Contour remembers the names on the phone.
- Filter count: a count of 9 reads back as 10 and band 10 keeps its old registers, so a shorter profile would
  leave the slot's old band 10 audible. Contour always writes ten bands and pads with 0 dB peaks, as on the KA15.
- Persistence: writes 16 ms apart all read back correctly, but after a power cycle only the first one to three
  survived (USER10: band 1; USER9: preamp and bands 1-2). With 300 ms after every write, preamp and all ten bands
  survived a power cycle on USER9 and USER10, both on advBeta and on stable 1.4.1.
- Acoustic result: see "Measured Q conventions" under the KA15 (25.10.2026): a PK plays Q = register / sqrt(2), shelves play the register as Q. Not measured: the preamp
  offset (the KA15 plays USER presets 12 dB down), so the preamp register is written as shown.

## SnowSky Melody and Tiny A (2972:0126 and 2972:0147, supported in Contour 1.5.0)

Probed on the owner's units on the Windows PC, 13.10.2026 (Melody bcdDevice 1.20, USB product "SNOWSKY Melody";
Tiny A bcdDevice 1.10, "SNOWSKY TINY A"), then read, written and re-selected from a Pixel. Both run the same
firmware family and behave the same way.

- Transport: the KA15 frame format and commands, report ID 7, one HID collection (interface 0). Every write except
  SAVE echoes in about 4-12 ms; SAVE (`0x19`) answers with a short `00 EE` frame instead of an AA echo. Reply
  frames can carry stale bytes after the `EE` terminator, so parse up to the first `EE` only.
- Slots: USER1-USER3 = presets 160-162, Close EQ = 240, stock presets 0-6 are never written. Writing a slot makes
  it the active one.
- The slot query (`0x16`) is unreliable on a USER slot: after any band or preamp query or write it returns stale
  data (for example the last band index), and it is right again only straight after a select or a SAVE. Stock
  presets and Close EQ always answer correctly. Contour therefore always selects the destination slot and checks
  the slot right after the select and right after SAVE, never at the end of a read.
- Filter count: every slot holds ten filters (devicePEQ lists the Tiny A with 5). A count of 2 or 5 reads back as
  10 with the slot's previous bands still active, so Contour always writes ten bands and pads with 0 dB peaks.
- Read-back: preamp, frequency, gain and type match; Q 3.00 reads back as 3.01, so Q is compared with a small
  tolerance.
- Cold plug (Tiny A on a Pixel, 14.10.2026): with a USER slot playing, the first slot reply is a boot value (`00`) and
  after the band reads it says `09`. A stock preset answers the same both times, so a reply that changes from 0-6 to
  7-9 during one read means "a USER slot, number unknown" (Contour 1.5.0 failed that read; fixed in 1.5.1).
- USER names are stored in the DAC (FiiO web app capture on the Tiny A, 14.10.2026; Melody confirmed from a Pixel):
  read `bb 0b 00 SEQ 30 01 <slot> CRC ee` with the slot id (`a0`-`a2`), not the KA15 index. Reply LEN 9: slot id, 8 name
  bytes NUL padded, one stale byte. Write `aa 0a 00 SEQ 30 <1+n> <slot> <name, n <= 8, unpadded> CRC ee`; the echo carries
  the stored field, upper-case, with the old tail cleared. The FiiO app allows A-Z and 0-9 only. Contour reads the names
  after the slot and band reads (never between a select and its slot check). Details:
  `research/snowsky-names-types-2026-10-14/FINDINGS.md`.
- Filter type byte over USB (Tiny A and K13 R2R, FiiO web app, 14.10.2026): see "FiiO filter type byte" below.
- Selecting the already active USER slot again did not hang either unit.
- Not measured: the acoustic result (shelf Q scaling and preamp offset, which the KA15 needed). Contour writes the
  values as shown.

## FiiO DAC page and factory reset (KA15, Melody, Tiny A; Contour 1.6.0)

- The DAC page reads and writes the FiiO settings commands that the maker's app offers for the model: KA15 VOLUME, VOLUME STEPS, BALANCE, FILTER, OUTPUT, DRE,
  BRIGHTNESS, SCREEN OFF, ROTATION, LANGUAGE; Melody VOLUME, VOLUME STEPS, BALANCE, FILTER, GAIN, OUTPUT, DRE, IN-LINE MIC, LIGHT, BUTTONS; Tiny A VOLUME, VOLUME STEPS, BALANCE,
  IN-LINE MIC, LIGHT. USB AUDIO (UAC, setting 0x20) is read-only on all of them: switching it re-enumerates the DAC, so a read-back cannot close the loop. The K13 R2R has no settings list.
- Factory reset (KA15 and Melody) is the one place where FiiO command 0x21 is ever sent, and only after Contour has read the USER slots, their names and the settings and saved them on the phone.
  After the frame the DAC is read again only once it has been unplugged and plugged back in (the command does not restart the controller). The change list is the difference between the saved copy and
  the read after the reset; WRITE THE SAVED COPY BACK writes the slots, names and settings again, steps before volume. The Tiny A (firmware 1.2.0) ignores the command, so it has no reset in the app.

## Moondrop Dawn Pro 2 (35D8:011D, supported in Contour 1.6.0)

- Matched by VID:PID and the exact USB name `DAWN PRO2` (no space). The name `DAWN PRO 2` with a space is another device, routed as WalkPlay SchemeNo15 in advBeta, and is not matched.
- HID interface 0, report ID `0x4B` (64 bytes, vendor page 0xFF01). Frame after the report id: `OP CMD LEN payload`, OP read `0x80`, write `0x01`; values Q8.8 little-endian; band biquads for 96 kHz.
- Write sequence: the one MOONDROP Hub "Write Cfg" sends (captured 24.10.2026, Hub Beta 1.6.5, firmware 1.5): `01 0F 01 07`, eight band frames (`01 09 21 00 i ...`, last byte the bank tag 07),
  `01 0A 04 00 00 FF FF`, pre-gain `01 23 02 lo hi`, global gain `01 03 02 lo hi` (written back as read), `01 01 00`, 30-65 ms apart. Contour writes only while the unit's EQ index (register `0x0F`) is 7 or 9,
  and sends nothing while the unit is on one of its own presets; it verifies by reading the eight bands back. The unit ignores the Global Gain write.
- Filter types: PEAK 2, LOW SHELF 1, HIGH SHELF 3, LOW PASS 4, HIGH PASS 5. Shelves play Q = register / sqrt(2); measured on the test rig on 25.10.2026 (shelves, LOW PASS, HIGH PASS).
  8 bands, gain -18..+12 dB (the Hub's class defaults), Q 0.1-10, 20-20000 Hz; pre-gain register `0x23` -128..0 dB in 0.1 dB steps.
- Never sent: `01 05 01 04` (flash clear) and `01 FF` (firmware upgrade). No factory reset, no slot names, no hardware volume.
- The vendor tuning config (custom EQ) is switched on and off by holding both volume buttons for 2 seconds and, according to Moondrop's product page, is off after every plug-in. Whether register `0x0F` carries that state is not known.

## Moondrop Echo-A (3302:12C5, supported in Contour 1.6.0)

- WalkPlay SchemeNo11 through the Protocol Micro's code: HID interface 3, report ID `0x4B`, firmware 0.1, USB maker string "TTGK Technology". No vendor software lists the Echo-A.
- 8 bands, gain -10..+10 dB, Q 0.1-10, 20-20000 Hz (the Micro's range; no source names one for the Echo-A). PEAK, LOW SHELF, LOW PASS and HIGH PASS are written natively; HIGH SHELF is emulated as on the
  Micro (a LOW SHELF with the negated gain and the HIGH SHELF gain in the preamp, so in whole dB; Contour can lower the preamp to fit it, for example -6 to -9). Preamp up to 0 dB.
- The DSP runs the coefficient bytes at 93 750 Hz whatever the stream rate (checked at a 48 kHz stream, 25.10.2026), so Contour designs them for 93 750 Hz; shelves play the slope form.
- The microphone setting takes only some values and stays read-only. A positive preamp, settings, hardware volume and reset are not offered.

## Kiwi Ears Allegro Mini (31B2:0111, supported in Contour 1.6.0)

- KT Micro register protocol, HID interface 3, report ID `0x4B` with 10 data bytes. Matched by VID, PID and the exact USB name `Kiwi Ears-Allegro Mini`.
- Five bands from register `0x26` (two registers per band: gain and frequency, Q and type), EQ switch register `0x24` (3 = on, 2 = off), SAVE `0x53`, Global Gain register `0x3B`. PEAK only, -12..+12 dB, Q 0.1-10.
  There is no preamp: Contour accepts only 0 dB and refuses a profile with more than five bands, other filter types or a preamp, naming the reason. A send does not touch the EQ switch; the DAC page does.
- The DSP designs its biquads for 24 kHz and runs them at the stream rate; Contour assumes a 48 kHz stream and writes the register frequency = intended frequency / 2 (see "Measured Q conventions" under the KA15).
  The unit re-enumerates on USB for about 1.3 s after each save; Contour waits for it. Global Gain: -18..+3 dB in 1.5 dB steps.

## FiiO filter type byte (band write `aa 0a .. 15 08 <idx> <gain2> <freq2> <q2> <type>`)

| Type | Byte | KA15 | K13 R2R | SnowSky Tiny A | SnowSky Melody |
|---|---|---|---|---|---|
| PEAK (PK) | 0 | capture 03.10.2026 | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware 13.10.2026) |
| LOW SHELF (LS) | 1 | capture 03.10.2026 | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware 13.10.2026) |
| HIGH SHELF (HS) | 2 | capture 03.10.2026 | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware 13.10.2026) |
| BAND PASS (BP) | 3 | Contour sends (hardware, 1.6.0) | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |
| LOW PASS (LP) | 4 | Contour sends (hardware, 1.6.0) | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |
| HIGH PASS (HP) | 5 | Contour sends (hardware, 1.6.0) | capture 14.10.2026; a read after HP returned `15 08 02 00 00 00 64 00 64 05` | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |
| ALL PASS (AP) | 6 | Contour sends (hardware, 1.6.0) | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |

- Sources: FiiO's web app (fiiocontrol.fiio.com, WebHID hook) on the Tiny A (band 3, 0 dB, 100 Hz, Q 1.00, every write echoed) and on the K13 R2R fw 1.15 (USER10 band 3, same values; the K13 sends no write echo). Details: `research/snowsky-names-types-2026-10-14/FINDINGS.md`. The BLE handler's 3 = LP / 4 = HP does not apply over USB.
- Contour 1.6.0 sends BP/LP/HP/AP to all four FiiO-family DACs: the KA15, K13 R2R, Melody and Tiny A. FiiO's own app was captured offering them on the K13 R2R and Tiny A; for the KA15 and Melody the type bytes are the same family's and the types were checked on the DACs themselves.
- The gain register of a pass filter is written 0 and Contour keeps pass filters at 0 dB. A band read from a DAC with a pass type and a nonzero gain register is not loaded (the import names the band, type and gain). The test rig measures amplitude only, and ALL PASS changes just the phase, so for ALL PASS it shows only that the amplitude is left alone.

## FiiO roadmap note (config/handler only; written before the KA15 work above)
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

## Ear test, 2026-09-29 (live_test.py, owner listening on the PC, music playing)
- The Micro's HID interface was MI_02 on this PC (MI_03 on 25.09): walkplay.py now matches usage page 0x0C.
- Band 1 toggled between the DAC value and PK 2000 Hz Q 0.7 -10 dB, 4 cycles x 4 s, restore verified by read-back.
- `--apply none` (band writes + preamp, no commit command): no audible change. Read-back echoes the registers
  anyway, so read-back does not prove the DSP applied a write.
- `--apply temp` (band writes + preamp + TEMP_WRITE `0x0A` only, no flash): the cut applied live on every switch,
  with light pops/clicks at the switches.
- Persistence: `--apply temp --leave-modified` left the cut on the DAC; after unplug/replug read_device.py
  showed the pre-test state (device-backup-2026-09-29-224718.json: same freq/gain/Q/type for all 8 bands,
  preamp -3 dB; biquad low bytes differ slightly, the DAC recomputes them). TEMP_WRITE is RAM-only.
- Click test (`--apply temp --cut gain`: only band 7's gain toggles 0 -> -10 dB, freq/Q unchanged), owner's
  ranking: best `--writes changed` (only the changed band + TEMP_WRITE, no preamp write); second `--writes all`
  (8 bands + preamp + TEMP_WRITE); worst `--writes changed --ramp 4` (gain stepped in 5 writes ~30 ms apart -
  each step is its own click). Softest live change: one band write + TEMP_WRITE, in one jump, no ramp.
- For the app (1.1.x always sends 8 bands + preamp + 0x05, 0x17, 0x0A, 0x01 = flash, only on Send): live
  preview, A/B and CLEAR EQ can send only the changed bands + TEMP_WRITE (RAM, no flash wear); Save keeps
  the full commit. After unplug the DAC falls back to the last saved EQ. Owner decision 29.09: build only
  A/B this way; live preview dropped, CLEAR EQ unchanged (ROADMAP.md).
