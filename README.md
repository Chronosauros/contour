<p align="center"><img src="docs/images/icon-512.png" width="96" alt="Contour icon"></p>

# Contour

A tactile parametric-EQ editor and profile library for Android. Contour writes your EQ straight into a USB DAC
over USB HID, so the sound changes inside the DAC itself: for every app, without a system equaliser, and it
stays on the DAC when you unplug it.

<p align="center">
  <img src="docs/images/tune.png" width="300" alt="Tune: response graph, bands, sliders, preamp and HOLD TO SEND">
  &nbsp;&nbsp;
  <img src="docs/images/library.png" width="300" alt="Library: profiles with their response curves">
</p>

## Supported hardware

- **CrinEar Protocol Micro** (USB `3302:C20F`, WalkPlay chip): 8 bands, PEAK, LOW SHELF and HIGH SHELF,
  gain -10 to +10 dB, Q 0.1 to 10, 20 Hz to 20 kHz, preamp -30 to 0 dB in whole dB.

Contour is unofficial and not affiliated with CrinEar. Other WalkPlay and FiiO dongles are possible later
(see `ROADMAP.md`); they are not enabled until someone confirms one on real hardware.

Needs Android 9 or newer and a phone with USB host (USB OTG) support.

## What it does

Two pages, swiped with a finger: **Tune** and **Library**. The bar at the bottom shows which one is open.

**Tune** edits the current profile:
- the response graph: drag a band's node, tap to select it, double-tap to set its gain to 0 dB,
  long-press an empty spot to add a band, pinch to change Q;
- band chips: tap to select, long-press to bypass or delete; `+` adds a band;
- filter type (PEAK, LOW SHELF, HIGH SHELF) and FREQ, GAIN and Q sliders. The sliders are relative:
  touching one never jumps the value, dragging moves it finely, and tapping the number lets you type it;
- PREAMP: AUTO keeps the curve from clipping; with AUTO off, drag the dB value sideways or tap it to type;
- **HOLD TO SEND**: hold for 0.7 s to write the profile to the DAC. Contour reads it back, compares every
  register and only then saves it to the DAC's memory. The button then shows ON DAC.

**Library** manages profiles:
- tap a profile to make it current and open it in Tune; long-press to rename it, pick an icon, duplicate
  it or share it as text;
- swipe left to archive or delete, with a 5-second UNDO;
- the empty slot at the end creates a new profile: empty, pasted from the clipboard (Equalizer APO /
  AutoEQ parametric text or EQ by Ear JSON), or read from the DAC.

HIGH SHELF bands are sent to the Protocol Micro as a mirrored LOW SHELF plus preamp, which gives exactly the
same curve shape (the maths is in `android/core/src/main/kotlin/io/github/chronosauros/contour/core/Device.kt`).

Long-press the device status (top right) for the service screen: the raw DAC state, a USB log, Restore flat
and the open-source licences.

## Safety

Contour writes to the DAC only when you hold HOLD TO SEND (or confirm an action on the service screen). It
sends only commands documented in `research/protocol/PROTOCOL.md`, checked on a real device, and verifies
every write by reading it back. Still, this is an unofficial tool talking to hardware: use it at your own
risk (see the warranty disclaimer in `LICENSE`).

## Install

Download the APK from [Releases](../../releases) and open it on your phone (Android asks you to allow
installs from that source). Each release lists the APK's SHA-256.

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
- `android/core` - pure Kotlin/JVM: profile model, DSP, text codecs, the WalkPlay device protocol;
- `android/app` - the Jetpack Compose app, USB HID transport, storage;
- `research/protocol` - protocol notes, Python reference tools and the device captures the parity test
  checks against.

## Privacy

Contour has no internet permission, no analytics and no accounts. Profiles stay on the phone. See
`PRIVACY.md`.

## Contributing

Issues and pull requests are welcome, especially reports from other WalkPlay-based DACs. Read
`CONTRIBUTING.md` first.

## Licence and credits

Contour is licensed under the Apache License, Version 2.0 (`LICENSE`).

The WalkPlay protocol and the biquad coefficient maths are ported from
[devicePEQ](https://github.com/jeromeof/devicePEQ) by Jerome O'Flaherty (0BSD). Every third-party
component and its licence is listed in `THIRD_PARTY_NOTICES.md`; attributions are in `NOTICE`.

CrinEar, Protocol Micro, WalkPlay and other product names belong to their owners and are used only to say
what Contour works with.
