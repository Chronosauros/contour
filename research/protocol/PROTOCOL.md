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

## Experimental TRN Black Pearl (advBeta only)

- Allow-list: **3302:43E8 only**, gated by `BuildConfig.ADVANCED`; no name/VID-wide matching and no
  HiFi188 `262A:0001` clone support. Stable USB attach filter lists only the Micro and the Max.
- The pinned [WalkPlay catalog](https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/walkplayPreprocessor/walkplay.json#L1843-L1868)
  identifies TRN Black Pearl as SchemeNo16. UC L702-708 maps PID 43E8 to
  [peq10Band10dBFullShelves](https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/peqConstraintsConfig.json#L733-L758):
  10 filters, +/-10 dB, Q 0.1..10, PK/LS/HS (native codes 2/1/3), not LP/HP. Catalog `ChannelNum: 8`
  is not the PEQ filter count. Sources use the same permissive devicePEQ licence credited above.
- Use report 0x4B with lengths proved by the device's report descriptor (no blanket 64-byte assumption), raw frequency/Q, 96 kHz RBJ/Q30 coefficients (WH L742-837),
  shelf Q as slope S; reject non-finite inputs/coefficients. No SchemeNo11 compensation and no
  Micro HIGH SHELF -> negated LOW SHELF/preamp emulation. CMD03 is whole signed int8 dB (WH L480-517).
  Contour conservatively restricts preamp to -30..0 dB; this is policy, not a proven TRN firmware limit.
- Read each HID report descriptor, select a single vendor Input/Output report 0x4B collection and
  matching interrupt IN endpoint; multiple suitable interfaces fail closed. SET_REPORT uses the actual interface ID. No audio interface is claimed
  for PEQ. VERSION, bulk slot, ten individual band replies and preamp must all validate before the first
  write. Missing/short/wrong-direction/wrong-index/unsupported-type data never produces a partial snapshot.
  No fallback reset, alternate command probes or write-on-connect.
- Explicit HOLD TO SEND (including a user-selected flat profile) writes **all ten slots**, including deterministic neutral PK
  spares (1 kHz, 0 dB, Q 0.75; not claimed TRN factory captures). Echo the bulk-read slot byte, not UI
  preset 101. Do not interpret individual-band slot fields as reliable preset echoes. Encode the complete
  payload before transmission, then use documented WH L132-188 commit/delays. Read every slot and preamp
  back and compare all registers plus the bulk slot; missing or mismatching data is failure, not ON DAC.
- Register read-back is not proof of audible DSP response or coefficient identity: firmware may recompute
  coefficients. No TRN has been connected or written during this implementation. Native shelves/design
  rate, timing, interface layout, persistence and audible response still require owner hardware validation.
  The GPL BlackPearlControl-Android comparator reports a different design rate/peaks-only behavior;
  its implementation was not copied, and that conflict is not resolved by software tests.
- A/B is disabled: only Micro's TEMP_WRITE path has RAM-only evidence. Hardware volume is also unavailable
  for this experimental target rather than assuming Micro's UAC Feature Unit/range. No extra DAC commands.
  Connecting, disconnecting or switching targets never rewrites/truncates saved profiles.

## WalkPlay catalog support (advBeta3 only, source-backed beta recipes)

`WalkPlayCatalog.kt` retains all 367 catalog rows from devicePEQ commit
`0617f382e76629792a5933e6933e4b396a756a93`, 366 valid unique exact pairs, all 29 literal
WalkPlay/KT USB names and the 18 alternate-handler exclusions. Data/config sources:
[walkplay.json](https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/walkplayPreprocessor/walkplay.json),
[usbDeviceConfig.js](https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/usbDeviceConfig.js),
[constraints](https://github.com/jeromeof/devicePEQ/blob/0617f382e76629792a5933e6933e4b396a756a93/devicePEQ/peqConstraintsConfig.json).
These are identifiers/command recipes, NOT hardware-tested compatibility or a count of DACs.

- Discovery is exact catalog/captured VID:PID or exact USB productName under its source vendor set.
  No VID Cartesian product, PID-wide inference, chipset/marketing-title matching or generic VID fallback.
  Three additional captured pairs absent from the catalog are 3302:4367 (SPACE PRO), 3302:39C3
  (Octave), 3302:43D4 (STARGATE II); these still require the exact literal model name.
  Protocol Max 3302:43CC is not a catalog route: it resolves to the stable 1.3.0 MAX target in every
  build, before the catalog (section below). Micro and TRN retain dedicated identities. Stable builds
  resolve only Micro and Max and keep the stable attach resource.
- Before USB permission, an exact pair is only a candidate. Permission/name acquisition is read-only.
  If the USB name is absent after permission, fail closed rather than ignoring a model override.
  Literal names are case/space-sensitive, including `ES9039 ` and `TANCHJIM-FISSION  DSP`.
  Name overrides choose capabilities before catalog defaults; OLA II inherits the known pair's scheme.
- Schemes 10/11/15/21 have 8 slots, 13/16/18/20 have 10, 17 has 5 and 19 has 6.
  Scheme 10/20/21 are PK-only; Scheme11 admits PK/LS; other documented schemes admit PK/LS/HS.
  SPACE PRO/STARGATE II are PK-only, OLA II is PK-only/Q<=5. LP/HP are always blocked:
  upstream's LP/HP coefficient path is the PK formula, not a validated LP/HP codec.
- Hold unknown schemes (1/24), malformed literal `0x43H1` (never repaired), catalog/first-group
  conflicts 0666:0883, 0663:0880, 3302:4302, 373B:129F, conflicting named catalog rows and
  alternate-handler routes. All ten KT names are retained but blocked in this WalkPlay driver.
  Upstream-experimental names may use their unambiguous recipe only as beta after exact matching;
  an experimental catalog row is admitted only with a same-scheme exact name, never from its label.
- Contour intentionally omits unverified acoustic frequency/Q compensation for every new Scheme11
  target: raw metadata uses the same explicit policy as Micro. This is an application policy, not
  proof of acoustic parity. New targets quantize frequency/Q/gain to representable registers before
  coefficient calculation so coefficients and metadata share the same device-domain values.
  Native shelves have NO Micro HS offset. Micro's arithmetic/factory-fill/report/commit/A-B bytes
  and raw frequency/Q policy remain unchanged.
- Validate all input values/types/ranges (including disabled saved filters), finite coefficients and
  the whole encoded sequence before the first mutation. Write exactly the capability's slot count,
  with explicit neutral PK spares (1 kHz, Q0.75, 0 dB), not fabricated factory captures. CMD03 whole-dB
  preamp uses conservative floor[-30,0] policy; files retain the user's fractional preamp intent.
- New targets require GET_DESCRIPTOR(report) with the configuration-declared length, a complete
  vendor report 0x4B Input/Output collection and one unambiguous matching HID interface. Input must
  have exactly the descriptor-proved report length; output pads/trims only proven zero padding.
  No audio interface is claimed. Micro's captured transport selection remains unchanged.
- A complete VERSION + bulk slot + every band + preamp strict read is mandatory before the first
  mutating report. Unknown native type, disabled/uninitialized slot, malformed header/range/length or
  missing reply aborts without a partial snapshot. Explicit HOLD TO SEND uses the
  existing documented band pacing/preamp/commit recipe; full readback compares every register,
  preamp and bulk slot. New non-TRN targets additionally compare coefficient bytes. Individual-band slot
  fields are read but not treated as authoritative; the bulk slot alone is echoed.
  A mismatch is failure, never ON DAC. Register readback is NOT proof of DSP response/persistence.
- New targets have no A/B, UAC volume, service test/reset writes or extras. No write on connect, EQ drag or profile selection.
  Multiple candidate DACs disable operations; no firstOrNull retarget. Every queued operation,
  report, pause and snapshot/error/final publication is guarded by physical attachment generation.
  Volume coalescing stays bound to its original Micro session and is cleared on detach/replacement.
- The adv editor permits 31 local filters even with a smaller DAC attached; target capabilities determine send limits.
  Connecting/importing never truncates/clamps/quantizes/drops saved data. Incompatible saved types
  and values remain visible; sends are rejected with reasons. Clipboard imports preserve fractional
  preamp and all filters, including those that cannot be sent to the connected target.
- No new target has been connected or hardware-tested during this implementation. Source-backed
  tests exercise codec/planning/safety only. Hardware descriptor layouts, DSP response and persistence
  remain owner validation gates. Build identity is 1.3.0-advbeta1, versionCode 22 (on stable 1.3.0); no stable version change.

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

## Native FiiO / KT Micro / Fosi integration (advBeta3 only)

All recipes/catalogs and original test fixtures are pinned to devicePEQ
`0617f382e76629792a5933e6933e4b396a756a93` (0BSD). No new DAC was physically tested.
`DeviceTarget` routes independently of WalkPlay; `NativeState` retains native integers/float bits and
fractional preamp without constructing WalkPlay band writes. Android calls the same guarded `NativeSession`
used by explicitly synthetic fake-port tests. Reads never select a bank, enable EQ, clear or save.

- Six exact FiiO capture VID/PID/name routes precede WalkPlay (including KA15 2972:0104).
  Other literal `sourceRule` names under their exact vendor sets are runtime candidates only: explicit
  Connect consent, descriptor qualification and a complete read are mandatory. Best-guess names and
  codec blockers remain read-only; no VID-wide/PID-wide writer fallback or invented PID.
  Only `config.userSlots` are destinations; the lowest admitted slot and label are visible before HOLD.
  Conflicting KA17-style maps exclude stock 7 and allow only 8/9. Full preflight -> select -> confirm
  slot -> complete destination backup -> parameter writes -> save -> complete raw readback.
  Native tenths pregain and model-specific -24 dB ranges are retained. AA/BB payloads exclude report ID;
  transport pads to descriptor size, prefixes once and validates exact raw Input length.
- KT routes only literal admitted models/known PIDs; CDSP's unknown PID is runtime-qualified only.
  Custom 3 only, selection acknowledgement before bands; exact maps and JCally-only 2X retained.
  No write to pregain 0x66, clear command or assumed headroom. Manual/AUTO profiles needing nonzero
  attenuation are rejected. Full raw readback includes all ten band registers, pregain and slot tails.
- Fosi only 152A:88DB plus literal `Fosi Audio DS3`, READ ONLY. Report 1 Output and Feature each
  require 63-byte descriptor payloads (64 raw), same collection. State 9E, format 9F and the first eight
  bands of the current bank are partial diagnostics, read without selection. Pinned handler L54-57 says
  firmware >=1.4.15 has 32-band user banks while factory banks remain eight; no evidenced 0xA6 response
  layout/count qualification exists here. Never infer Custom 1=7 completeness from eight reads. No
  mode/enable/parameter/save mutations, whole-profile import, profile match or sent/verified status.
  Eight-band codec plans remain isolated explicitly SYNTHETIC fixtures, never runtime qualification.
- Disconnect-on-save JA11/Allegro retains an in-memory immutable complete quantized plan immediately
  before SAVE is attempted. It binds exact VID/PID/name, optional serial, original profile/id/updatedAt,
  original generation and explicit HOLD intent. SAVE-triggered detach does not discard this intent,
  but it aborts every later USB operation/publication. No resend, retry or write occurs on reconnect.
  Complete explicit Connect/Read can resolve it only against the original unmodified profile and target:
  FiiO bank/count/pregain/all native fields; KT all registers including pregain/slot/reserved bytes.
  Serial-less matching requires explicit user verification with a same-physical-unit warning; only a
  matching serial permits automatic verification after a new attachment. New read/current generation
  is checked before publication. Wrong target, changed profile, partial read or mismatch stays PENDING.
  Only the receipt-verified snapshot can match these families ON DAC and mark the original LAST SENT.
  App restart loses the receipt; there is no new profile schema or durable persistence claim. A matching
  register read proves neither flash survival nor DSP/audio behaviour. Another HOLD is blocked while
  the original save remains unresolved.
- Input/Output/Feature sizes and IDs are independent. HID only is claimed, never audio. Each request,
  report, pause and publication is generation guarded. HOLD binds its original generation/profile to
  prevent replacement-DAC retargeting mid-gesture. Multiple candidates disable operations. Micro
  codec/A-B/UAC stay separate; Micro-only mutation controls are hidden for other families. Local data
  never changes on attach/detach/import; incompatible types, excess bands and fractional preamp survive.

## FiiO KA15: measured acoustic behaviour (04.10.2026)
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

## Native Moondrop read-only and blocked routing (advBeta3 only)

- The 14 literal pinned source rules retain 12 conditional read-only candidates and two exclusions:
  Old Fashioned has unproven response echo semantics; MOONDROP Marigold conflicts with the captured
  WalkPlay 35D8:011C recipe. Exact source VID plus case/space-sensitive name is necessary; unknown PID
  candidates always require explicit Connect consent. A missing pre-permission name permits only
  permission/diagnostic discovery, not HID reads or a wildcard writer.
- Native report 4B needs vendor Input/Output in the same collection: Output payload >=6 bytes and
  Input >=36 complete coefficient/metadata bytes. KT 10-byte payloads are not Moondrop qualification.
  Reads bracket all eight indexed bands and observed 03 offset with slot reads, without bank selection.
  Transport discards queued stale input and has one request outstanding; parsers require exact actual
  descriptor length and native opcode/index/bank echo. Missing echoes fail closed, never WalkPlay.
- Effective pregain stays nullable/UNKNOWN_EFFECTIVE, not zero, AUTO or a value inferred from 23.
  Raw native coefficients, metadata, slot and every response remain diagnostic only. READ ONLY and
  import/write reasons are shown; HOLD, FROM DAC, native matching, UAC, A/B and service resets cannot
  mutate or claim an equivalent profile. No native Moondrop hardware/RX qualification is claimed.
- BlockedUsbCatalog and known Moondrop exclusions precede every positive DeviceTarget route, including
  unreadable names for known pairs: Conexant 35D8:1496/149B, Qudelix 0A12:4005, Topping 152A:8750,
  serial FiiO 1A86:55D3/JDS 152A:88FA and Marigold 35D8:011C. Literal Space Gaming/DM15 exclusions
  are diagnostic, not invented PID evidence. Permission must not lead to HID probing for blocked
  identities. Legitimate same-VID Micro/FiiO/DS3 routes remain independent; stable admits Micro only.

## Historical FiiO research note (superseded by admission rules above)
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
