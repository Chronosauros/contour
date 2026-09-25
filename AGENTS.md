# Contour - rules for AI agents and contributors

Open-source (Apache-2.0) Android app: a tactile parametric-EQ editor plus a library of profiles that are sent
to USB DACs (first: CrinEar Protocol Micro). If `AGENTS.local.md` exists, read it too: it holds the
maintainer's machine-specific rules and is not part of the repo.

## Rules
- Android only. Native Kotlin + Jetpack Compose, modules `:core` (pure JVM: model, DSP, codecs, device
  protocol) and `:app`. No KMP, no NDK.
- No test harness: no UI or screenshot tests, no Robolectric, no lint rule sets. The only automated check is
  the device-protocol and codec parity test in `:core` (`cd android && ./gradlew :core:test`). Gates:
  compile, the parity test, a look on a real phone.
- Device safety: the app writes to a DAC only on an explicit user action, always reads back and compares,
  and sends only commands documented in `research/protocol/PROTOCOL.md`. Never send experimental commands to
  a device.
- Protected paths - change only with a clear reason: `android/core` (protocol, DSP, file format), the DAC
  write path (`model/Sender.kt`, `usb/DeviceController.kt`), `model/ProfileStore.kt`, `ProtocolParityTest`.
- Keep the concept: two pages, Tune | Library, swiped; sending happens only in Tune (HOLD TO SEND); the
  Library only chooses and manages profiles. Touch targets of at least 48 dp.
- Line endings LF (`.gitattributes`).
- Credits: devicePEQ (0BSD) for the WalkPlay protocol and coefficient maths. Any new third-party code, font,
  icon or data goes into `THIRD_PARTY_NOTICES.md` (and `NOTICE` when its licence asks for attribution).
