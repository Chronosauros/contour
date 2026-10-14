# SnowSky Tiny A: cold-plug slot reply, USER names, filter type bytes (14.10.2026)

The maintainer's SnowSky Tiny A (2972:0147, fw 1.10). Pixel logs are kept locally, not published.
PC capture: FiiO's official web app (fiiocontrol.fiio.com) in Brave on Windows, WebHID hook on
`HIDDevice.prototype.sendReport` plus an `inputreport` listener.

## Cold plug (Pixel, stable 1.5.0 r29)
- Plugged in while USER2 (161) plays: the first `0x16` reply is `00`, after the reads of bands 0-9 it says `09`.
  1.5.0 took 0 for the stock preset Jazz, kept the closing slot check and failed every connect read with
  "Active bank changed during read" (also the read that starts HOLD TO SEND), so nothing worked until READ (a select).
- Fix (1.5.1): on the SnowSky path, an opening reply that changes to 7..9 after the band reads is a USER slot whose
  number is unknown; a stock preset answers the same both times. Pixel: two cold plugs read fine.

## USER slot names (command 0x30) - stored in the DAC
- Read: `bb 0b 00 SEQ 30 01 <slot> CRC ee`, slot = the preset id (`a0`-`a2` = USER1-3), NOT the KA15 position index.
  Reply `bb 0b 00 SEQ 30 09 <slot> <8-byte name, NUL padded> CRC ee`:
  `30 09 a0 46 44 31 35 00 00 00 00` = FD15, `a1` = FF5, `a2` = FH3 (owner's names).
- Asked with index 0-2 (KA15 style) the Tiny A answers `30 09 00` + zeros: no name.
- Write: `aa 0a 00 SEQ 30 <1+n> <slot> <name, n bytes, no padding> CRC ee`, n <= 8. Echo `aa 0a .. 30 09 <slot>` + the stored
  8-byte field: `TeaPro2x` came back `TEAPRO2X` (the DAC upper-cases), `FH3` came back `FH3` + 5 zeros (old tail cleared).
- FiiO web app: names up to 8, "Only English letters and numbers". USER3 renamed TEAPRO2X and back to FH3.
- K13 R2R (Pixel, test build): the KA15-style query `30 01 00` got no reply and the next output report failed
  (-1/33) - never send name queries to the K13. FiiO web app on the K13 (PC, same day): USER1-USER10 have no rename
  pencil and the app sends no 0x30 at all - the K13 stores no names. Contour keeps them itself.

## Filter type byte (band write `aa 0a .. 15 08 <idx> <gain2> <freq2> <q2> <type>`)
- FiiO web app on the Tiny A, band 3 (0 dB, 100 Hz, Q 1.00), every write echoed:
  PK 0, LS 1, HS 2, **BP 3, LP 4, HP 5, AP 6**. (The BLE handler's 3 = LP / 4 = HP does not apply over USB.)
- K13 R2R (fw 1.15), FiiO web app, USER10 band 3 (0 dB, 100 Hz, Q 1.00): the same bytes (BP 3, LP 4, HP 5, AP 6).
  The K13 sends no write echo; a read after HP returned `15 08 02 00 00 00 64 00 64 05` (HP kept). On BYPASS the app
  sends no band writes at all.
- Both put back to PK, nothing saved; the K13 back on BYPASS (240). Acoustic shape (Q, gain use for LP/HP) not measured.

## Reading a USER slot that is not playing
- Tiny A (Pixel, Contour TEMP PROBE build, 14.10 15:48): band 0 query with an extra slot byte
  `bb 0b 00 SEQ 15 02 00 <slot> 00 ee` for 160, 161, 162 returns the PLAYING slot's band 0 every time
  (`15 08 00 ff e2 05 78 00 50 00`, only SEQ differs). The slot byte is ignored: an inactive slot can only be read
  by selecting it (0x16), which the user hears. A select + full read takes ~1.5 s on the Tiny A.
  Contour therefore scans the USER slots in the background on connect and switches from memory afterwards.
