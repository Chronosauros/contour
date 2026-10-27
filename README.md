<p align="center"><img src="docs/images/icon-512.png" width="96" alt="Contour icon"></p>

# Contour

**An on-the-go EQ manager for USB DACs from CrinEar, FiiO, SnowSky, TRN, Moondrop and Kiwi Ears.** Keep all your EQ profiles on your phone, plug the
DAC in, pick one and send it. Plug in, send, unplug - that is the whole routine. On DACs with their own settings, a DAC page changes those from the phone too.

**Website: [contoureq.app](https://contoureq.app/)** - a 1.5-minute demo film, the full guide film and the install page.

<p align="center">
  <img src="docs/images/eq-1.3.2.png" width="300" alt="EQ page on a FiiO KA15: a PEAK at 3 kHz, AUTO preamp, USER1-3 slot names, ON DAC">
  &nbsp;&nbsp;
  <img src="docs/images/library-1.3.2.png" width="300" alt="Library: eight profiles with their curves, TEA PRO OG on the DAC">
</p>

## Why it exists

Contour aims to be the only app you need for your DAC/amp: your EQ profiles on the phone, sent to whichever DAC
you plug in, and the DAC's own settings where it has them - no vendor app for each brand, no computer.

It started smaller: in September 2026 it was a phone stand-in for
[CrinEar's desktop browser tool](https://eq.hangout.audio/), for the Protocol Micro alone. A month later it works
with ten DACs from six makers.

Every one of them keeps its parametric EQ inside the DAC itself, so the EQ works with every app and every device
you plug the DAC into - no system equaliser, no app running in the background.

- **Your EQs are saved.** One profile per pair of headphones or IEMs, or per mood. They live on the phone, ready
  whenever the DAC is plugged in.
- **Sending takes seconds.** Plug the DAC in, tap a profile, hold HOLD TO SEND. Contour writes it, reads
  it back to check every value, and stores it in the DAC's memory.
- **Then the phone is out of the picture.** Unplug the DAC and use it with your laptop, another phone or
  anything else. The EQ stays in it until you send another one.
- **Examples to start from.** The first launch includes three general profiles: BASS BOOST, SOFTER VOCALS
  and TREBLE BOOST.
- **You can still tune on the go.** Drag bands on the graph, fine-tune with sliders, paste an AutoEQ
  profile from the clipboard, or read the current EQ off the DAC.

Contour does not process audio, does not need the internet and has no accounts. It only talks to the DAC.

**See the [guide](https://contoureq.app/guide/)**: a 6-minute film of the basics, with chapters, and the steps written out.

## Supported hardware

Ten DACs from six makers. Every one of them was tested on real hardware before Contour enabled it.

### EQ limits

| DAC | Bands | Filters | Gain | Preamp |
|---|---|---|---|---|
| CrinEar Protocol Micro | 8 | PK, LS, HS, LP, HP | -10 to +10 dB | -30 to 0 dB, whole dB |
| CrinEar Protocol Max | 10 | PK, LS, HS | -10 to +10 dB | -30 to 0 dB, whole dB |
| FiiO KA15 | 10 | PK, LS, HS, LP, HP, BP, AP | -12 to +12 dB | -24 to 0 dB, whole dB |
| FiiO K13 R2R | 10 | PK, LS, HS, LP, HP, BP, AP | -24 to +12 dB | -24 to +12 dB |
| SnowSky Melody | 10 | PK, LS, HS, LP, HP, BP, AP | -12 to +12 dB | -12 to +12 dB, whole dB |
| SnowSky Tiny A | 10 | PK, LS, HS, LP, HP, BP, AP | -12 to +12 dB | -12 to +12 dB, whole dB |
| TRN Black Pearl | 10 | PK, LS, HS | -10 to +10 dB | -30 to 0 dB, whole dB |
| Moondrop Dawn Pro 2 | 8 | PK, LS, HS, LP, HP | -18 to +12 dB | pre-gain, stored with the profile |
| Moondrop Echo-A | 8 | PK, LS, HS, LP, HP | -10 to +10 dB | up to 0 dB |
| Kiwi Ears Allegro Mini | 5 | PK | -12 to +12 dB | none |

PK = PEAK, LS = LOW SHELF, HS = HIGH SHELF, LP = LOW PASS, HP = HIGH PASS, BP = BAND PASS, AP = ALL PASS.
Q is 0.1 to 10 and frequency 20 Hz to 20 kHz on every DAC, with two exceptions: KA15 shelves go up to Q 7.07, and
the Allegro Mini starts at 40 Hz. The pass filters on the Micro, KA15, K13, Melody and Tiny A arrived in 1.6.0.

### Slots, settings and testing

| DAC | USB ID | Chip / protocol | Where the EQ goes | DAC page | Tested |
|---|---|---|---|---|---|
| CrinEar Protocol Micro | `3302:C20F` | WalkPlay | DAC memory | - | developer + test rig |
| CrinEar Protocol Max | `3302:43CC` | WalkPlay | DAC memory | - | Reddit community member |
| FiiO KA15 | `2972:0104` | FiiO | USER1 to USER3, named by Contour | yes, with factory reset | developer |
| FiiO K13 R2R | `2972:0120` | FiiO | USER1 to USER10 | - | developer + test rig |
| SnowSky Melody | `2972:0126` | FiiO | USER1 to USER3 | yes, with factory reset | developer + test rig |
| SnowSky Tiny A | `2972:0147` | FiiO | USER1 to USER3 | yes | developer + test rig |
| TRN Black Pearl | `3302:43E8` | WalkPlay | DAC memory | - | developer + test rig |
| Moondrop Dawn Pro 2 | `35D8:011D` | Moondrop | active PEQ bank | read-only | developer + test rig |
| Moondrop Echo-A | `3302:12C5` | WalkPlay | DAC memory | - | developer + test rig |
| Kiwi Ears Allegro Mini | `31B2:0111` | KT Micro | DAC memory | EQ switch and GLOBAL GAIN | developer + test rig |

What the DAC pages hold:

- **KA15:** VOLUME, VOLUME STEPS, BALANCE, FILTER, OUTPUT, DRE, BRIGHTNESS, SCREEN OFF, ROTATION and LANGUAGE (USB AUDIO
  shown, read-only).
- **Melody:** VOLUME, VOLUME STEPS, BALANCE, FILTER, GAIN, OUTPUT, DRE, IN-LINE MIC, LIGHT and BUTTONS (USB AUDIO
  shown, read-only).
- **Tiny A:** VOLUME, VOLUME STEPS, BALANCE, IN-LINE MIC and LIGHT (USB AUDIO read-only).
- **Dawn Pro 2:** EQ INDEX, GLOBAL GAIN and PRE GAIN, read-only (the DAC ignores a GLOBAL GAIN write).
- **Allegro Mini:** the EQ switch (OFF / ON) and GLOBAL GAIN from -18 to +3 dB in 1.5 dB steps; HOLD TO SEND leaves
  the switch where it is.

FACTORY RESET on the KA15 and Melody first saves a copy of the USER slots, their names and the settings on the
phone, then resets the DAC, shows what changed and offers WRITE THE SAVED COPY BACK. After a reset you unplug the
Melody and plug it back in; Contour waits for that with no time limit (STOP WAITING ends the wait).

### How we test a DAC

A DAC is enabled in Contour only after it passed these steps on real hardware. Others stay off until someone
confirms one.

1. **Research.** We start from the maker's own app and public sources, and keep every limit (bands, gain, Q,
   preamp) inside what the maker's app allows.
2. **Read first, back up, then write.** On a computer, the DAC is read in full and every slot is backed up before
   the first write. Writes go only to user slots, never to factory presets or bypass. After every write the DAC
   is read back and compared, and the DAC is turned off and on to check the EQ survives. At the end the original
   state is written back.
3. **Contour on a phone.** A full profile is sent with HOLD TO SEND, read back from the DAC and checked after
   unplugging.
4. **Measured on a test rig** (where the table says so). The DAC's analog output is recorded through an audio
   interface while a frequency sweep plays, with the EQ off and on, and the result is compared with the curve
   Contour shows. This is how we found the KA15 shelf Q, the K13 PEAK Q and the 93 750 Hz of the Micro and Echo-A
   (notes below); Contour now compensates for them.

Bug reports and confirmations from users of a DAC we do not own count too, as for the Protocol Max. The
protocol notes and measurements are in [`research/protocol/PROTOCOL.md`](research/protocol/PROTOCOL.md).

### Notes per DAC

<details>
<summary><b>CrinEar Protocol Micro</b></summary>

Since 1.6.0 the filters are computed for the 93 750 Hz the DAC's DSP runs at (before, every filter played about
2% too low). The DAC accepts a positive preamp but does not get louder, so Contour offers none. Its settings,
hardware volume and reset are not supported yet: they would need the maker's own tool (WalkPlay), which Contour
has no access to.
</details>

<details>
<summary><b>CrinEar Protocol Max</b></summary>

PEAK, LOW SHELF and HIGH SHELF are native filters, computed and sent by the app. Tested by a Reddit community
member on a real Protocol Max (8 bands, peak and shelf filters, no crashes). Large HIGH SHELF boosts at lower
frequencies don't fit the Max's filter format; since 1.4.0 Contour refuses them with a message instead of sending
them.
</details>

<details>
<summary><b>FiiO KA15</b></summary>

Pick USER1, USER2 or USER3 above HOLD TO SEND; Contour writes the profile to that slot and names the slot after it
(up to 7 letters and digits). Long-press a slot to rename it. The KA15 answers only while audio is streaming to
it, so Contour plays a silent stream to it while it reads or writes (no audio focus, your music keeps playing).
The KA15 plays its shelf filters with a Q 1.41 times lower than the value it stores, and every USER preset 12 dB
quieter than EQ off. Since 1.3.3 Contour compensates both, so the curve and preamp you see are what it plays
(measured on the device, see `research/protocol/PROTOCOL.md`). The FiiO app shows the stored values, so its shelf
Q and preamp look higher. Since 1.6.0 a Q just outside the range is read instead of blocking the slot.
</details>

<details>
<summary><b>FiiO K13 R2R</b></summary>

Desktop DAC/amp. Pick USER1 to USER10 above HOLD TO SEND; Contour writes the profile to that slot and makes it the
active one. The slots keep their USER names (Contour does not rename K13 slots). The K13 always holds ten bands, so
a shorter profile is filled up with flat 0 dB bands. Writes are paced 300 ms apart, so a send takes about five
seconds: faster writes read back fine but did not survive turning the K13 off. BYPASS and the stock presets are
chosen on the K13 itself. The K13 plays a PEAK with its Q divided by the square root of 2, so since 1.6.0 Contour
writes Q x 1.41 and the PEAK plays as set. The K13 has no DAC page yet.
</details>

<details>
<summary><b>SnowSky Melody</b></summary>

Pick USER1, USER2 or USER3 above HOLD TO SEND; Contour writes the profile to that slot, checks it and makes it the
active one. The Melody always holds ten bands, so a shorter profile is filled up with flat 0 dB bands. Since 1.6.0
shelves play as set (Q written x 1.41, like on the KA15).
</details>

<details>
<summary><b>SnowSky Tiny A</b></summary>

The same as the Melody: USER1, USER2 or USER3, ten bands, shelves with Q x 1.41. It has no factory reset in
Contour: the DAC (firmware 1.2.0) ignores the command.
</details>

<details>
<summary><b>TRN Black Pearl</b></summary>

**Plug your headphones into the Black Pearl first:** without headphones it does not show up on USB at all, so
Contour shows NO DAC. PEAK, LOW SHELF and HIGH SHELF are native filters. Every send is read back and stays in the
DAC after unplugging, preamp included. Since 1.6.0 shelves play as set (Q written x 1.41). LOW PASS, HIGH PASS,
the DAC filter, settings, hardware volume and reset are not supported yet.
</details>

<details>
<summary><b>Moondrop Dawn Pro 2</b></summary>

USB name `DAWN PRO2`. Contour sends the same frames as Moondrop's own tool (Moondrop Hub) to the DAC's active PEQ
bank, reads them back, and never writes over a factory preset. The DAC must have its custom EQ (tuning mode)
switched on; according to Moondrop it is off after every plug-in and is toggled by holding both volume buttons for
2 seconds. A Dawn Pro 2 that reports the name `DAWN PRO 2` (with a space) is a different version and is not
supported. Measured on the test rig on 25.10.2026.
</details>

<details>
<summary><b>Moondrop Echo-A</b></summary>

USB name `ECHO-A`. HIGH SHELF is made from a LOW SHELF and the preamp as on the Micro, so its gain goes in whole dB
and Contour may lower the preamp to fit it (for example -6 to -9). The filters are computed for 93 750 Hz, like
the Micro's. Every send is read back. A positive preamp, settings, hardware volume and reset are not supported
yet.
</details>

<details>
<summary><b>Kiwi Ears Allegro Mini</b></summary>

USB name `Kiwi Ears-Allegro Mini`. A profile with more bands, other filter types or a preamp has to be changed
before it can be sent; Contour says why. The DAC works out its filters as if the stream ran at 24 kHz, so Contour
assumes the usual 48 kHz USB stream and writes the frequency converted for it (a player that forces 44.1 kHz
shifts every band about 8% down). The Allegro Mini drops off USB for about a second after each save, and Contour
waits for it.
</details>

Contour is unofficial and not affiliated with CrinEar, FiiO, SnowSky, TRN, Moondrop or Kiwi Ears. Other WalkPlay, FiiO,
Moondrop and KT Micro DACs are possible later (see `ROADMAP.md`); they are not enabled until someone confirms one
on real hardware.

Needs Android 9 or newer and a phone with USB host (USB OTG) support.

## At a glance

Two pages, switched with the bar at the bottom or by swiping: **EQ** and **LIBRARY**. A DAC with its own settings
gets a third page, **DAC**, in front of them.

- **EQ** edits the current profile: drag, tap, double-tap and pinch band nodes on the graph; PEAK, LOW
  SHELF and HIGH SHELF filters, plus HIGH PASS, LOW PASS, BAND PASS and ALL PASS on the DACs that have them (Contour
  shows only the types your DAC takes); FREQ, GAIN and Q sliders that are fine when you drag slowly and cover the
  whole range when you drag fast; tap any number to type it; tap the selected band chip again to bypass or
  delete the band, or delete it with the minus chip; PREAMP with a MANUAL / AUTO anti-clipping bar; A/B to hear the curve off and on at the same volume (the preamp stays; Protocol Micro only for now).
- **HOLD TO SEND** (0.7 s hold) writes the profile to the DAC, reads it back, compares every register and
  only then saves it to the DAC's memory. The button then shows ON DAC, and the profile is saved. HOLD TO SEND,
  its caption and the slot picker sit in a fixed footer, and messages appear under the header, so they never
  cover the button. A send that is interrupted leaves SEND INTERRUPTED, and the DAC is read again when you plug it back in.
- **DAC** (KA15, Melody, Tiny A, Dawn Pro 2, Allegro Mini): the DAC's own settings, only the ones that DAC has
  (VOLUME, BALANCE, FILTER and the rest); USB AUDIO (UAC) is only shown. The header shows the sample rate and
  the firmware. **FACTORY RESET** (KA15, Melody) first reads the USER slots, their names and the settings and
  saves them on the phone, then resets the DAC, shows what changed and offers WRITE THE SAVED COPY BACK.
- **USER slots** (KA15, K13 R2R, Melody, Tiny A): tap a slot to switch the DAC to it and see the EQ it holds. If
  that EQ is already in the Library, Contour opens that profile; if not, it adds it as a new one. After you plug the
  DAC in, Contour reads every USER slot and goes back to the one that was playing. **FROM DAC** (the + card in the
  Library) saves what the DAC holds right now as a new profile.
- **Undo and redo** work like Ctrl+Z: one touch is one step, up to 100 steps per profile, kept after closing
  the app. **CLEAR EQ** leaves one flat band and is an undo step too.
- **LIBRARY** keeps your profiles: tap to open, long-press to rename, change the icon, duplicate or share.
  Every card shows the profile's SAVED version, and the EQ page edits a working copy, so CLEAR EQ never empties
  the card. Swipe a card right to SAVE or RESTORE the saved version (a full swipe right saves); swipe it left to
  archive or delete, with UNDO. New profiles start flat, come from the clipboard or a .txt file
  (Equalizer APO, AutoEQ, squig.link or graph.hangout.audio parametric text, or EQ by Ear JSON; Contour also
  shows up in Open with and Share for .txt files) or are read from the DAC. SAVE .TXT saves a profile as a
  file in the squig.link format. A user loaded that file in Topping's web interface (DX1ii): the peaking and
  shelf filters and the preamp came through.
- **PREAMP** moves in whole dB on a DAC that keeps whole dB only (the "whole dB" rows in the table above), so the
  number on screen is the number the DAC holds.

On the Protocol Micro and the Echo-A, a HIGH SHELF band is sent as a mirrored LOW SHELF plus preamp, which gives exactly
the same curve shape (the maths is in `android/core/src/main/kotlin/io/github/chronosauros/contour/core/Device.kt`).
The other DACs have native shelf filters, except the Allegro Mini, which takes PEAK only.

## Safety

Contour writes to the DAC only when you hold HOLD TO SEND (or confirm an action on the service screen). It
sends only commands documented in `research/protocol/PROTOCOL.md`, checked on a real device, and verifies
every write by reading it back. Every value is rounded toward quieter, never louder, and a band or preamp the
DAC can't store as asked is refused before anything is sent. Still, this is an unofficial tool talking to hardware: use it at your own
risk (see the warranty disclaimer in `LICENSE`).

## Install

**Step-by-step instructions: [contoureq.app/install](https://contoureq.app/install/)**

- **[Obtainium](https://obtainium.imranr.dev/)** (recommended, updates itself): in Obtainium tap Add app and
  paste `https://github.com/Chronosauros/contour`, or on your phone tap
  [Add Contour to Obtainium](https://contoureq.app/install/#obtainium).
- **APK**: download `contour-<version>.apk` from the [latest release](../../releases/latest) and open it on
  your phone (Android asks you to allow installs from that source).

Every official build is signed by the same key: package `io.github.chronosauros.contour`, certificate
SHA-256 `C8:C8:CA:EA:76:16:28:ED:9D:50:5F:F0:73:42:EE:5E:A0:D4:66:A4:AD:71:8D:0D:B0:77:42:2B:A3:F7:E6:01`
(CN=Chronosaur). Each release also lists the APK's SHA-256. The [guide](https://contoureq.app/guide/)
takes it from there.

## Build

Requirements: JDK 21 and the Android SDK (platform 37.2, build-tools 36 or newer).

```
cd android
./gradlew :core:test            # protocol and codec parity test
./gradlew :app:assembleRelease  # unsigned release APK in app/build/outputs/apk/release/
./gradlew :app:assemblePerf     # review build (package ...contour.perf, debug-signed, profileable)
```

`android/wsl-build.sh` wraps the same tasks for Linux / WSL with the paths from `android/toolchain.env`
(copy `toolchain.env.example`). The release APK is unsigned unless you pass your own key, see
`android/app/build.gradle.kts`.

Code layout:
- `android/core` - pure Kotlin/JVM: profile model, DSP, text codecs, the WalkPlay (Protocol Micro, Protocol Max, TRN Black Pearl, Moondrop Echo-A), FiiO (KA15, K13 R2R, SnowSky Melody and Tiny A), Moondrop (Dawn Pro 2) and KT Micro (Kiwi Ears Allegro Mini) device protocols, and the device catalog generated from the records in `devices/`;
- `android/app` - the Jetpack Compose app, USB HID transport, storage;
- `research/protocol` - protocol notes, Python reference tools and the device captures the parity test
  checks against.

## Privacy

Contour has no internet permission, no analytics and no accounts. Profiles stay on the phone. See
`PRIVACY.md`.

## Contributing

Issues and pull requests are welcome, especially reports from DACs Contour does not support yet (WalkPlay, FiiO,
Moondrop or KT Micro based). Read
`CONTRIBUTING.md` first.

## Licence and credits

Contour is licensed under the Apache License, Version 2.0 (`LICENSE`).

The WalkPlay and FiiO protocols and the biquad coefficient maths are ported from
[devicePEQ](https://github.com/jeromeof/devicePEQ) by Jerome O'Flaherty (0BSD). The TRN Black Pearl write sequence follows
[FreeDSP-Studio](https://github.com/EffectiveEquivalent/FreeDSP-Studio) by EffectiveEquivalent (MIT). Every third-party
component and its licence is listed in `THIRD_PARTY_NOTICES.md`; attributions are in `NOTICE`. In the app,
all of this is under **ABOUT & LICENCES** at the bottom of the Library.

Contour is an unofficial project, not affiliated with or endorsed by CrinEar, FiiO, SnowSky, TRN, Moondrop, Kiwi Ears or any other brand. CrinEar,
Protocol Micro, Protocol Max, FiiO, KA15, K13, SnowSky, Melody, Tiny A, TRN, Black Pearl, Moondrop, Dawn Pro 2, Echo-A, Kiwi Ears, Allegro Mini, KT Micro, WalkPlay and other product names belong to their owners
and are used only to say what Contour works with.
