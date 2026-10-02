# ROADMAP - Contour

Ideas and devices for later. None of these is enabled until it is confirmed on real hardware.

## FiiO KA15
- Supported by devicePEQ (`research/protocol/devicepeq-ref/fiioUsbHidHandler.js`): HID, report ID 7, 10 bands, +-12 dB, 7 filter types (PK, LS, HS, LP, HP, BP, AP).
- Writable user slots 7-9 (USER1-3), "Close EQ" = 10. Save command `AA 0A 00 00 19 01 <slot> 00 EE`.
- devicePEQ matches FiiO devices by VID plus the USB product name, not by PID. Its handler does not stop writes to stock presets, so Contour must allow only user slots.
- App needs: a slot picker, 10-band capability, slot names kept in Contour (FiiO does not store names).

## FiiO K13 R2R
- devicePEQ: 10 bands, -24..+12 dB, all FiiO types, user slots 160-169, bypass 240. Product-name match unverified.

## CrinEar Protocol Max (WalkPlay SchemeNo16)
- 10 bands, +-10 dB, low and high shelf, no frequency/Q compensation, pregain via command 0x03.
- Contour Max beta (separate `maxbeta` build, 1.2.2-maxbeta1): experimental 10-band PEQ with native shelves, host pregain, strict read-back; A/B off. Untested on hardware.

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
- **FiiO / JadeAudio** - the largest group, framed HID protocol (`fiioUsbHidHandler.js` is already in our copy): KA15, KA17, K13 R2R, K15, K17, K19, BTR13, BTR17, BT11, QX11, QX13, FX17, Oak Nano, Retro Nano, Air Link, Air Amp, FP3, FG3, BR15 R2R, JadeAudio JA11 / JIEZI, Snowsky Melody / Tiny A / Tiny B. 5 to 31 bands, user slots differ per model (see FiiO KA15 and K13 R2R above).
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
