# ROADMAP - Contour

Ideas and devices for later. None of these is enabled until it is confirmed on real hardware.

## FiiO KA15
- Supported since Contour 1.3.2, tested on the developer's own hardware (Pixel, 04.10.2026): read on connect, USER1-3 slot picker above HOLD TO SEND, writes only to USER slots (never the stock presets), slot names read from and written to the DAC (up to 7 letters and digits), long-press a slot to rename it.
- Protocol: HID, report ID 7, 10 bands, +-12 dB, PEAK, LOW SHELF and HIGH SHELF (the other FiiO filter types are not used). USB `2972:0104` matched exactly. The KA15 answers HID only while a USB audio stream runs, so Contour plays a silent stream during reads and writes.
- Open: one save out of ten on 04.10 got no answer until the dongle was replugged; not reproduced since.

## FiiO K13 R2R
- Supported since Contour 1.4.1, tested on the developer's own hardware (Pixel, 10.10.2026): read on connect, USER1-USER10 slot picker above HOLD TO SEND (two rows of five, no names, no rename), writes only to USER slots (160-169), never the stock presets or BYPASS (240). First reported by a community tester in advBeta 1.3.1-advbeta1: recognised, but every send blocked by INVALID EQ.
- Protocol: the KA15's FiiO HID protocol, report ID 7, 33-byte reports, USB `2972:0120` matched exactly. 10 bands, -24..+12 dB, preamp -24..+12 dB, PEAK, LOW SHELF and HIGH SHELF. Hardware quirks are in `research/protocol/PROTOCOL.md`: two identical USB configurations, report 7 on Generic Desktop / Undefined, a band count below 10 reads back as 10 (Contour pads with flat bands), and writes need 300 ms between them to survive a power cycle.
- Open: the sound is not measured yet. The KA15 needed shelf-Q and preamp compensation; the K13 may too.

## CrinEar Protocol Max (WalkPlay SchemeNo16)
- 10 bands, +-10 dB, low and high shelf, no frequency/Q compensation, pregain via command 0x03.
- Supported since Contour 1.3.0: 10-band PEQ with native shelves, host pregain, strict read-back; A/B off. Tested by a community member on 02.10.2026 (8 bands, peak and shelf filters, no crashes; a small pop when sending is normal - the dongle saves to its memory). Pre-releases are tested in the `beta` build type ("Contour Beta").

## KT Micro driver (Kiwi Ears Allegro Mini, Allegro PRO)
- Second protocol family, separate from WalkPlay. devicePEQ matches VID `0x31B2` plus the USB product name ("Kiwi Ears-Allegro Mini", with a hyphen; "Kiwi Ears-Allegro PRO").
- Register protocol over HID: report ID `0x4B`, frames `<reg> 00 00 00 <cmd>`, base register offset `0x26` on both Allegros (confirmed from a USB capture in devicePEQ).
- 5 bands, +-12 dB, low and high shelf, no pregain. One writable slot ("Custom", id `0x03`); `0x02` = EQ off. The device disconnects after save.
- Not in our copy yet: `ktmicroUsbHidHandler.js` has to be pulled from devicePEQ (MIT) into `research/protocol/devicepeq-ref/`.
- App needs: 5-band capability, profiles without preamp (warn when a profile needs more than 5 bands or pregain), reconnect after save.
- Other `0x31B2` devices (e.g. "Space Gaming IEM") do not answer this protocol - leave them out.
- Requested by a Reddit user with an Allegro Mini (26.09.2026). Enable only after they or another tester confirm it on the device.

## Other brands, grouped by driver
Source: devicePEQ `usbDeviceConfig.js` (26.09.2026). One driver unlocks the whole group, but each device still needs its own confirmation. devicePEQ marks some devices `experimental` - treat those as unconfirmed even there.

- **WalkPlay (the driver Contour already has)** - only IDs and capability profiles to add: Tanchjim (Space Pro, Ola II DSP, Stargate II), NiceHCK Octave, Truthear KeyX, ddHiFi DSP Cable, Moondrop Dawn Pro 2, CrinEar Protocol Max, generic CS43131 / CS43198 / ES9039 dongles. Experimental there: BGVP MX1, Letshuoer DT04, Moondrop MD-QT-042 / HiFi with PD. Band layouts vary per scheme: 5, 6, 8 or 10 bands, +-10 dB, some peaking only.
- **KT Micro (the driver above)** - besides both Allegros: Kiwi Ears Chorus and other KT_* chips (matched by PID, registers laid out differently per chip), JCally KT02H20, Tanchjim One DSP / Bunny DSP / Fission, Moondrop CDSP and Chu 2 DSP. All 5 bands, +-12 dB, no pregain.
- **FiiO / JadeAudio** - the largest group, framed HID protocol (`fiioUsbHidHandler.js` is already in our copy): KA15, KA17, K13 R2R, K15, K17, K19, BTR13, BTR17, BT11, QX11, QX13, FX17, Oak Nano, Retro Nano, Air Link, Air Amp, FP3, FG3, BR15 R2R, JadeAudio JA11 / JIEZI, Snowsky Melody / Tiny A / Tiny B. 5 to 31 bands, user slots differ per model (see FiiO KA15, supported since 1.3.2, and K13 R2R above).
- **Moondrop (own protocol)** - Rays, AG Rays, Marigold, FreeDSP Mini / Pro, Moonriver 3, Dawn Pro 2, Echo A, DHA15, Deco, ddHiFi DSP IEM. Mostly 8 bands, +-12 dB. Old Fashioned uses an older, separate Moondrop protocol (5 bands, max +3 dB).
- **Conexant** - Moondrop FreeDSP and Echo-B (9 bands, +-12 dB).
- **Fosi Audio / Topping** (VID `0x152A`, shared with Topping) - Fosi DS3: 8 bands, +-12 dB, all filter types, no pregain. The driver is experimental in devicePEQ.

Suggested order: WalkPlay IDs first (no new code), then KT Micro (a user is asking), then FiiO (the handler is already in our copy, largest group), then Moondrop. Leave Conexant and Fosi until someone asks for them.

## Live preview on the DAC - dropped (owner, 29.09.2026)
- Ear test 29.09 confirmed that band writes + TEMP_WRITE (0x0A) apply live from RAM (`research/protocol/PROTOCOL.md`, "Ear test, 2026-09-29"). Owner decided not to build live preview; only A/B uses this path.

## Other WalkPlay SchemeNo11 devices
- About 140 PIDs share the scheme in devicePEQ's table. Enable them after a tester confirms one.

## Reddit feedback 27.09.2026 (freestyler7) - to check later
- Clear EQ - done in 1.1.1 (CLEAR EQ in EDIT PROFILE).
- Quick A/B button: bypass EQ but keep the preamp. Concern: Micro pops when saving a profile while music plays, so A/B through flash writes would be jarring. **Planned for the next release (owner, 29.09).** Switch through RAM: send only the changed bands + TEMP_WRITE, no preamp, no ramp - the quietest method in the ear test. A/B must never commit to flash; after unplug the DAC falls back to the last sent EQ. Placement chosen 29.09: raised "A/B" button in the top-right corner of the graph card; in B it turns accent and reads "EQ OFF  PREAMP KEPT" (owner), the curve lies flat with a ghost of the EQ, band controls dim, preamp row stays bright (`docs/changes/2026-09-29-ab-toggle/variant-graph-*.png`).
- Question: does the DAC have an in-memory (RAM) mode? Answered 29.09: yes, TEMP_WRITE applies band writes live and is lost on unplug. CLEAR EQ stays as it is (flash write) unless the owner decides otherwise.

## Header layout after the undo buttons
- 1.1.1 shrinks the name 30 -> 17 sp before the ellipsis. A full header rebuild waits for A/B, which also wants header space.
