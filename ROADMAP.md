# ROADMAP - Contour

Ideas and devices for later. None of these is enabled until it is confirmed on real hardware.

## FiiO KA15
- Supported since Contour 1.3.2, tested on the developer's own hardware (Pixel, 04.10.2026): read on connect, USER1-3 slot picker above HOLD TO SEND, writes only to USER slots (never the stock presets), slot names read from and written to the DAC (up to 7 letters and digits), long-press a slot to rename it.
- Protocol: HID, report ID 7, 10 bands, +-12 dB, PEAK, LOW SHELF and HIGH SHELF, plus HIGH PASS, LOW PASS, BAND PASS and ALL PASS since 1.6.0 (see "High pass, low pass, band pass, all pass" below). USB `2972:0104` matched exactly. The KA15 answers HID only while a USB audio stream runs, so Contour plays a silent stream during reads and writes.
- 1.6.0: DAC page (VOLUME, VOLUME STEPS, BALANCE, DAC FILTER, OUTPUT, DRE, BRIGHTNESS, SCREEN OFF, ROTATION, LANGUAGE; USB AUDIO read-only; sample rate and firmware in the header) and FACTORY RESET that saves the USER slots, their names and the settings on the phone first and offers to write them back. A Q just outside the range is read instead of blocking the slot.
- Open: one save out of ten on 04.10 got no answer until the dongle was replugged; not reproduced since.

## FiiO K13 R2R
- Supported since Contour 1.4.1, tested on the developer's own hardware (Pixel, 10.10.2026): read on connect, USER1-USER10 slot picker above HOLD TO SEND (one row numbered 1-10 since 1.5.0; the firmware has no slot names, since 1.5.1 Contour shows the profile last sent to each slot), writes only to USER slots (160-169), never the stock presets or BYPASS (240). First reported by a community tester in advBeta 1.3.1-advbeta1: recognised, but every send blocked by INVALID EQ.
- Protocol: the KA15's FiiO HID protocol, report ID 7, 33-byte reports, USB `2972:0120` matched exactly. 10 bands, -24..+12 dB, preamp -24..+12 dB, PEAK, LOW SHELF and HIGH SHELF, plus HIGH PASS, LOW PASS, BAND PASS and ALL PASS (see "High pass, low pass, band pass, all pass" below). Hardware quirks are in `research/protocol/PROTOCOL.md`: two identical USB configurations, report 7 on Generic Desktop / Undefined, a band count below 10 reads back as 10 (Contour pads with flat bands), and writes need 300 ms between them to survive a power cycle.
- 1.6.0: the EQ is measured on a test rig. The K13 plays a PEAK with Q divided by the square root of 2, so Contour writes Q x 1.41 and the PEAK plays as set; shelves and the pass filters play the register as Q. A read-back that is one Q step lower no longer ends in a false NOT VERIFIED.
- Not yet: a DAC page for the K13.

## SnowSky Melody and Tiny A
- Supported since Contour 1.5.0, tested on the developer's own hardware (Pixel and PC, 13-14.10.2026): read on connect, USER1-USER3 slot picker above HOLD TO SEND, writes only to USER slots (160-162), never the stock presets or Close EQ (240); the written slot becomes the active one. Since 1.5.1 the USER slot names are read from the DAC. Both were first reported working by a community tester in advBeta 1.3.1-advbeta1.
- Protocol: the FiiO HID protocol, report ID 7, USB `2972:0126` (Melody) and `2972:0147` (Tiny A) matched exactly. 10 bands, -12..+12 dB, preamp -12..+12 dB, PEAK, LOW SHELF and HIGH SHELF, plus HIGH PASS, LOW PASS, BAND PASS and ALL PASS on both (see "High pass, low pass, band pass, all pass" below). devicePEQ lists the Tiny A with 5 bands; the hardware reads 10. Firmware quirks are in `research/protocol/PROTOCOL.md`: the slot reply goes stale after band or preamp traffic on a USER slot, SAVE answers without an echo, and a band count below 10 reads back as 10 (Contour pads with flat bands).
- 1.6.0: the EQ is measured on a test rig; shelves play the register Q divided by the square root of 2, so Contour writes Q x 1.41, and the preamp goes in whole dB, as the DAC keeps it. DAC page - Melody: VOLUME, VOLUME STEPS, BALANCE, FILTER, GAIN, OUTPUT, DRE, IN-LINE MIC, LIGHT, BUTTONS; Tiny A: VOLUME, VOLUME STEPS, BALANCE, IN-LINE MIC, LIGHT; USB AUDIO read-only on both. FACTORY RESET on the Melody saves a copy first; after the reset the DAC is unplugged and plugged back in, and Contour waits for that with no time limit (STOP WAITING ends the wait). The Tiny A has no reset: its DAC (firmware 1.2.0) ignores the command. A slot name is written as the whole field.

## TRN Black Pearl (WalkPlay SchemeNo16)
- Supported since Contour 1.5.2, tested on the developer's own hardware (PC and Pixel, 19.10.2026): read on connect, 10 bands, PEAK, LOW SHELF and HIGH SHELF as native filters, +-10 dB, preamp -30..0 dB; every send is read back and stays after unplugging, preamp included. A/B off.
- Protocol: WalkPlay HID, report ID `0x4B`, USB `3302:43E8` matched exactly (product string "TE-C"). The write sequence follows FreeDSP-Studio (MIT); details in `research/protocol/PROTOCOL.md`. The Black Pearl enumerates on USB only with headphones plugged into it.
- 1.6.0: the EQ is measured on a test rig; shelves play the register Q divided by the square root of 2, so Contour writes Q x 1.41.
- Not yet: LOW PASS and HIGH PASS (stored by the firmware, codes 4 and 5, proven on the registers, but not offered), the DAC filter, settings, hardware volume and reset.

## High pass, low pass, band pass, all pass
- Done in 1.6.0: HIGH PASS, LOW PASS, BAND PASS and ALL PASS on the FiiO KA15, FiiO K13 R2R, SnowSky Melody and Tiny A (FiiO type bytes PK 0, LS 1, HS 2, BP 3, LP 4, HP 5, AP 6, captured from FiiO's web app on 14.10.2026); LOW PASS and HIGH PASS on the CrinEar Protocol Micro, Moondrop Dawn Pro 2 and Echo-A. Contour keeps the gain of a pass filter at 0 dB and refuses to import one that the DAC holds with a gain. Evidence table: research/filter-support-2026-10-14/FINDINGS.md.
- Not offered: the Protocol Max (no unit to test on), the TRN Black Pearl (the firmware stores LOW PASS and HIGH PASS, but they are not offered yet) and the Kiwi Ears Allegro Mini (PEAK only).
- Open: a test rig measures amplitude, and ALL PASS changes only the phase, so for ALL PASS the evidence is that it leaves the amplitude alone.

## CrinEar Protocol Max (WalkPlay SchemeNo16)
- 10 bands, +-10 dB, low and high shelf, no frequency/Q compensation, pregain via command 0x03.
- Supported since Contour 1.3.0: 10-band PEQ with native shelves, host pregain, strict read-back; A/B off. Tested by a community member on 02.10.2026 (8 bands, peak and shelf filters, no crashes; a small pop when sending is normal - the dongle saves to its memory). Pre-releases are tested in the `beta` build type ("Contour Beta").

## Moondrop Dawn Pro 2 (Moondrop protocol)
- Supported since Contour 1.6.0, tested on the developer's own hardware: USB `35D8:011D` with the name `DAWN PRO2` matched exactly. 8 bands, PEAK, LOW SHELF, HIGH SHELF, LOW PASS and HIGH PASS, -18..+12 dB, Q 0.1-10, with a preamp (pre-gain) in the profile. Contour sends the frames of Moondrop Hub to the active PEQ bank (EQ index 7 or 9), reads them back, and writes nothing while the DAC is on one of its own presets. The EQ is measured on a test rig (25.10.2026).
- The DAC page shows EQ INDEX, GLOBAL GAIN and PRE GAIN, read-only: the DAC ignores a GLOBAL GAIN write. The custom EQ must be switched on in the DAC; Moondrop's product page says it is off after every plug-in and is toggled with both volume buttons held for 2 seconds. No slot names, no hardware volume, no factory reset.
- The name `DAWN PRO 2` (with a space) is a different, WalkPlay-routed device; Contour 1.6.0 does not match it.

## Moondrop Echo-A (WalkPlay SchemeNo11)
- Supported since Contour 1.6.0, tested on the developer's own hardware: USB `3302:12C5`, name `ECHO-A`. 8 bands, PEAK, LOW SHELF, LOW PASS and HIGH PASS, +-10 dB, Q 0.1-10, preamp up to 0 dB. HIGH SHELF is made from a LOW SHELF and the preamp as on the Micro, so its gain goes in whole dB. The DSP runs the filters at 93 750 Hz and Contour computes them for it. The EQ is measured on a test rig (stream 48 kHz, 5 of 5 cases).
- Not yet: a positive preamp, settings, hardware volume and reset (WalkPlay's own tool is needed to see what the DAC offers).

## KT Micro driver (Kiwi Ears Allegro Mini, Allegro PRO)
- Kiwi Ears Allegro Mini supported since Contour 1.6.0, tested on the developer's own hardware: USB `31B2:0111`, name `Kiwi Ears-Allegro Mini`. 5 bands, PEAK only (the only type Kiwi's own tool offers), -12..+12 dB, Q 0.1-10, no preamp. The DAC page has the EQ switch (OFF / ON) and GLOBAL GAIN (-18 to +3 dB in 1.5 dB steps). The DAC designs its filters for 24 kHz and plays them at the stream rate, so Contour assumes a 48 kHz stream and writes the frequency converted (the editor offers 40-20000 Hz). The Allegro Mini drops off USB for about a second after each save.
- Requested by u/ScissorFight42069 on Reddit (26.09.2026).
- Allegro PRO: same protocol family, no unit tested - not enabled.
- Second protocol family, separate from WalkPlay. devicePEQ matches VID `0x31B2` plus the USB product name ("Kiwi Ears-Allegro Mini", with a hyphen; "Kiwi Ears-Allegro PRO").
- Register protocol over HID: report ID `0x4B`, frames `<reg> 00 00 00 <cmd>`, base register offset `0x26` on both Allegros (confirmed from a USB capture in devicePEQ).
- 5 bands, +-12 dB, PEAK only in Kiwi's own tool (devicePEQ lists low and high shelf too), no pregain. One writable slot ("Custom", id `0x03`); `0x02` = EQ off. The device disconnects after save.
- Not in our copy yet: `ktmicroUsbHidHandler.js` has to be pulled from devicePEQ (MIT) into `research/protocol/devicepeq-ref/`.
- Other `0x31B2` devices (e.g. "Space Gaming IEM") do not answer this protocol - leave them out.

## Other brands, grouped by driver
Source: devicePEQ `usbDeviceConfig.js` (26.09.2026). One driver unlocks the whole group, but each device still needs its own confirmation. devicePEQ marks some devices `experimental` - treat those as unconfirmed even there.

- **WalkPlay (the driver Contour already has)** - only IDs and capability profiles to add: Tanchjim (Space Pro, Ola II DSP, Stargate II), NiceHCK Octave, Truthear KeyX, ddHiFi DSP Cable, Moondrop Dawn Pro 2 under the WalkPlay name `DAWN PRO 2` (not the supported `DAWN PRO2`), CrinEar Protocol Max, generic CS43131 / CS43198 / ES9039 dongles. Experimental there: BGVP MX1, Letshuoer DT04, Moondrop MD-QT-042 / HiFi with PD. Band layouts vary per scheme: 5, 6, 8 or 10 bands, +-10 dB, some peaking only.
- **KT Micro (the driver above)** - besides both Allegros: Kiwi Ears Chorus and other KT_* chips (matched by PID, registers laid out differently per chip), JCally KT02H20, Tanchjim One DSP / Bunny DSP / Fission, Moondrop CDSP and Chu 2 DSP. All 5 bands, +-12 dB, no pregain.
- **FiiO / JadeAudio** - the largest group, framed HID protocol (`fiioUsbHidHandler.js` is already in our copy): KA15, KA17, K13 R2R, K15, K17, K19, BTR13, BTR17, BT11, QX11, QX13, FX17, Oak Nano, Retro Nano, Air Link, Air Amp, FP3, FG3, BR15 R2R, JadeAudio JA11 / JIEZI, Snowsky Tiny B. 5 to 31 bands, user slots differ per model (see FiiO KA15, supported since 1.3.2, K13 R2R, and SnowSky Melody and Tiny A above).
- **Moondrop (own protocol)** - Rays, AG Rays, Marigold, FreeDSP Mini / Pro, Moonriver 3, Dawn Pro 2 (supported since 1.6.0), Echo A (a WalkPlay device, supported since 1.6.0), DHA15, Deco, ddHiFi DSP IEM. Mostly 8 bands, +-12 dB. Old Fashioned uses an older, separate Moondrop protocol (5 bands, max +3 dB).
- **Conexant** - Moondrop FreeDSP and Echo-B (9 bands, +-12 dB).
- **Fosi Audio / Topping** (VID `0x152A`, shared with Topping) - Fosi DS3: 8 bands, +-12 dB, all filter types, no pregain. The driver is experimental in devicePEQ.

Suggested order after 1.6.0: more FiiO models (the handler is already in our copy, largest group), then other Moondrop and KT Micro devices as testers confirm them. Leave Conexant and Fosi until someone asks for them.

## Live preview on the DAC - dropped (owner, 29.09.2026)
- Ear test 29.09 confirmed that band writes + TEMP_WRITE (0x0A) apply live from RAM (`research/protocol/PROTOCOL.md`, "Ear test, 2026-09-29"). Owner decided not to build live preview; only A/B uses this path.

## Other WalkPlay SchemeNo11 devices
- About 140 PIDs share the scheme in devicePEQ's table. Enable them after a tester confirms one.

## Reddit feedback 27.09.2026 (freestyler7) - to check later
- Clear EQ - done in 1.1.1 (CLEAR EQ in EDIT PROFILE).
- A screen with the DAC's own settings (01.10) - done in 1.6.0 as the DAC page on the KA15, Melody, Tiny A, Dawn Pro 2 and Allegro Mini. Hardware volume (30.09) - done there as VOLUME on the KA15, Melody and Tiny A; not yet on the Protocol Micro, which needs WalkPlay's own tool.
- Quick A/B button: bypass EQ but keep the preamp. Concern: Micro pops when saving a profile while music plays, so A/B through flash writes would be jarring. **Planned for the next release (owner, 29.09).** Switch through RAM: send only the changed bands + TEMP_WRITE, no preamp, no ramp - the quietest method in the ear test. A/B must never commit to flash; after unplug the DAC falls back to the last sent EQ. Placement chosen 29.09: raised "A/B" button in the top-right corner of the graph card; in B it turns accent and reads "EQ OFF  PREAMP KEPT" (owner), the curve lies flat with a ghost of the EQ, band controls dim, preamp row stays bright (`docs/changes/2026-09-29-ab-toggle/variant-graph-*.png`).
- Question: does the DAC have an in-memory (RAM) mode? Answered 29.09: yes, TEMP_WRITE applies band writes live and is lost on unplug. CLEAR EQ stays as it is (flash write) unless the owner decides otherwise.

## Header layout after the undo buttons
- 1.1.1 shrinks the name 30 -> 17 sp before the ellipsis. A full header rebuild waits for A/B, which also wants header space.
