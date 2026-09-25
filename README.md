<p align="center"><img src="docs/images/icon-512.png" width="96" alt="Contour icon"></p>

# Contour

**An on-the-go EQ manager for the CrinEar Protocol Micro.** Keep all your EQ profiles on your phone, plug the
dongle in, pick one and send it. Plug in, send, unplug - that is the whole routine.

<p align="center">
  <img src="docs/images/tune.png" width="300" alt="EQ page: response graph, bands, sliders, preamp and HOLD TO SEND">
  &nbsp;&nbsp;
  <img src="docs/images/library.png" width="300" alt="Library: saved profiles with their response curves">
</p>

## Why it exists

The Protocol Micro has a parametric EQ built into the dongle itself. Whatever EQ it holds works with every
app and every device you plug it into - no system equaliser, no app running in the background. But
the official way to change it is CrinEar's browser tool at [eq.hangout.audio](https://eq.hangout.audio/),
which the maker lists for desktop (PC and macOS) browsers. There is no official mobile app, and phone
browsers lack the WebHID support the tool relies on - so in practice you need a computer nearby.

Contour puts that on your phone. It is deliberately a small, simple tool:

- **Your EQs are saved.** One profile per pair of earphones, or per mood. They live on the phone, ready
  whenever the dongle is plugged in.
- **Sending takes seconds.** Plug the dongle in, tap a profile, hold HOLD TO SEND. Contour writes it, reads
  it back to check every value, and stores it in the dongle's memory.
- **Then the phone is out of the picture.** Unplug the dongle and use it with your laptop, another phone or
  anything else. The EQ stays in it until you send another one.
- **Real examples to start from.** The first launch includes NIGHTFALL, the maintainer's own tuning for
  the CrinEar Nightfall, and DUSK, the default curve of Moondrop's DSP cable for the Moondrop x Crinacle
  DUSK (so the analog cable sounds like the DSP one), next to a flat PROFILE 1.
- **You can still tune on the go.** Drag bands on the graph, fine-tune with sliders, paste an AutoEQ
  profile from the clipboard, or read the current EQ off the dongle.

Contour does not process audio, does not need the internet and has no accounts. It only talks to the dongle.

**Read the [user guide](docs/USER-GUIDE.md)** for everything the app does, step by step.

## Supported hardware

- **CrinEar Protocol Micro** (USB `3302:C20F`, WalkPlay chip): 8 bands, PEAK, LOW SHELF and HIGH SHELF,
  gain -10 to +10 dB, Q 0.1 to 10, 20 Hz to 20 kHz, preamp -30 to 0 dB in whole dB.

Contour is unofficial and not affiliated with CrinEar. Other WalkPlay and FiiO dongles are possible later
(see `ROADMAP.md`); they are not enabled until someone confirms one on real hardware.

Needs Android 9 or newer and a phone with USB host (USB OTG) support.

## At a glance

Two pages, switched with the bar at the bottom or by swiping: **EQ** and **LIBRARY**.

- **EQ** edits the current profile: drag, tap, double-tap and pinch band nodes on the graph; PEAK, LOW
  SHELF and HIGH SHELF filters; FREQ, GAIN and Q sliders that are fine when you drag slowly and cover the
  whole range when you drag fast; tap any number to type it; PREAMP with AUTO anti-clipping.
- **HOLD TO SEND** (0.7 s hold) writes the profile to the dongle, reads it back, compares every register and
  only then saves it to the dongle's memory. The button then shows ON DAC.
- **LIBRARY** keeps your profiles: tap to open, long-press to rename, change the icon, duplicate or share;
  swipe to archive or delete, with UNDO. New profiles start flat, come from the clipboard (Equalizer APO /
  AutoEQ parametric text or EQ by Ear JSON) or are read from the dongle.

HIGH SHELF bands are sent to the Protocol Micro as a mirrored LOW SHELF plus preamp, which gives exactly the
same curve shape (the maths is in `android/core/src/main/kotlin/io/github/chronosauros/contour/core/Device.kt`).

## Safety

Contour writes to the DAC only when you hold HOLD TO SEND (or confirm an action on the service screen). It
sends only commands documented in `research/protocol/PROTOCOL.md`, checked on a real device, and verifies
every write by reading it back. Still, this is an unofficial tool talking to hardware: use it at your own
risk (see the warranty disclaimer in `LICENSE`).

## Install

Download the APK from [Releases](../../releases) and open it on your phone (Android asks you to allow
installs from that source). Each release lists the APK's SHA-256. The [user guide](docs/USER-GUIDE.md) takes
it from there.

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
component and its licence is listed in `THIRD_PARTY_NOTICES.md`; attributions are in `NOTICE`. The DUSK
example profile uses the DUSK-Default DSP curve
[published by Crinacle](https://crinacle.com/2024/04/10/moondrop-x-crinacle-dusk-eq-dsp-values/). In the app,
all of this is under **ABOUT & LICENCES** at the bottom of the Library.

Contour is an unofficial project, not affiliated with or endorsed by CrinEar or any other brand. CrinEar,
Protocol Micro, Nightfall, WalkPlay, Moondrop, Crinacle, DUSK and other product names belong to their owners
and are used only to say what Contour works with.
