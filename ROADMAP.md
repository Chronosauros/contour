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

## Live preview on the DAC
- Band writes without the flash commit may apply live. To be confirmed by ear. If yes: stream band changes while dragging (at most 20-30 writes per second), commit to flash only on send.

## Other WalkPlay SchemeNo11 devices
- About 140 PIDs share the scheme in devicePEQ's table. Enable them after a tester confirms one.
