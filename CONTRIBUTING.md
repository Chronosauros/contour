# Contributing

Thanks for helping. Contour is a small project with a clear idea, so please read this before you start.

## Reports from other DACs

The most useful contribution is a report from a DAC that Contour does not support yet: its USB vendor and
product ID, the chip if you know it, and what devicePEQ says about it. Please do not send experimental write
commands to your device; Contour enables a device only after its protocol is confirmed on real hardware.

## Code

- Two modules: `android/core` is pure Kotlin/JVM (model, DSP, codecs, device protocol, no Android
  dependencies); `android/app` is the Jetpack Compose app. No Kotlin Multiplatform, no NDK.
- The only automated check is the parity test: `cd android && ./gradlew :core:test`. It must pass. Tests 4-6
  need the maintainer's profile folder and are skipped without it.
- Device safety: the app writes to a DAC only on an explicit user action, reads every write back and compares
  it, and sends only commands documented in `research/protocol/PROTOCOL.md`.
- Keep the existing look and gestures unless an issue agrees on a change first.
- Code from other projects must have a licence compatible with Apache-2.0. Add it to `THIRD_PARTY_NOTICES.md`
  and, when its licence asks for attribution, to `NOTICE`.

By contributing you agree that your contribution is licensed under the Apache License, Version 2.0.
