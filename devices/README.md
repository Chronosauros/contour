# Device records

One strict-JSON file per supported or candidate device, named `<vendor>-<model>.json`. A record states what is
known about a device: how it is found on USB, which driver family talks to it, what the PEQ can do, which
settings the vendor software offers, and how well each claim is evidenced. Records are data only, and every device
fact the app uses lives here once: the USB identity of all ten devices, the capabilities, preamp policy, factory flats
and read bounds of the WalkPlay devices, the registers, ranges and delays of the Moondrop and KT Micro drivers, and
the FiiO configuration are generated from the records at build time (below). Codecs, write sequences, parsers and the
Micro high-shelf emulation stay code. A golden test keeps the whole catalog from drifting.

## Devices

| id | family | status |
|---|---|---|
| fiio-ka15 | fiio | stable |
| fiio-k13-r2r | fiio | stable |
| snowsky-melody | fiio | stable |
| snowsky-tiny-a | fiio | stable |
| crinear-protocol-micro | walkplay | stable |
| crinear-protocol-max | walkplay | stable |
| trn-black-pearl | walkplay | stable |
| moondrop-dawn-pro-2 | moondrop | stable |
| moondrop-echo-a | walkplay (hypothesis) | records-only (writes since 25.10.2026, see below) |
| kiwi-ears-allegro-mini | ktmicro | stable |

`stable` means Contour already writes to the device on the 1.6 line; the record is derived from the code with
every value traced to a file and line. `records-only` means no write path ships; the record is derived from the
vendor software research and the PC bench runs, and only fixes the driver family.

The Echo-A is the one exception: its EQ write ships since 25.10.2026 (`WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED`, run 2026-10-25-moondrop-echo-a-05-mac-eqtest-48k), through the Protocol Micro's code, but
its record still holds `"unknown"` for the fields a `stable` walkplay record needs (band length, write sequence, preamp policy, slots, pacing), so it stays `records-only` and the generator builds the
`ReadOnlyWalkPlayData` for it. It becomes `stable` when those fields are measured. The Dawn Pro 2 and the Allegro Mini write in 1.6 and are `stable`.

## Schema summary

- Identity: `id`, `name`, `shortName`, `productName`, `vendor`, `status`, `family`.
- `usb`: `vid`, `pid`, `productName`, `hidInterface`, `matchByName`, `nameAliases` (further exact product names a
  name-matched device reports), `recognisedBy` (`find`: VID:PID only, the 1.5 list; `findDevice`: the 1.6 lookup, which
  needs the product name for a record with `matchByName`), `listOrder` (position in the generated Android attach filter;
  append new devices at the end), free-text `notes`.
- `driver`: family-specific protocol values (report id, `schemeNo`, band length, write sequence, strict parse, import
  mode, A/B, design rate; for FiiO also `hardwareVerified` and `capture`; for Moondrop the register commands, delays
  and `reportBytes`; for KT Micro the payload length, read/save commands, `bandRegisters`, `writeAck` and the EQ and
  gain registers).
- `peq`: `bands`, `fixedBandCount`, `freqHz`, `gainDb`, `q`, `types` (offered, with their code), `heldTypes`
  (known to the code but not offered), `shelfQScale`, `peakQScale` (FiiO records), `coefficientRateHz` (WalkPlay records), `emulation`.
- `preamp`: `present`, `db`, `step`, `wholeDb` (the DAC keeps whole dB of the preamp only, so the plan rounds it down to a whole dB: WalkPlay Micro, Max, TRN and, since 1.6, the SnowSky Melody and Tiny A), `registerOffsetDb`.
- `slots`: `user`, `declaredWritable`, `stock`, `bypass`, `labels`, `picker`, `names`; for WalkPlay also
  `slotByte` and `factoryFlat`; for Moondrop also `writableIndexes` (every EQ index whose bank Contour writes, with evidence per index; `driver.peqBank` is the one the Hub's first frame
  asks for and must be in the set; on any other index the unit is on one of its own presets and nothing is written).
- `peq.designRateHz` / `peq.assumedStreamHz` (KT Micro records, positive numbers of Hz): the DSP designs its biquads for `designRateHz` (24 000 Hz, measured) and runs them at the stream rate, so a band plays at register f x stream / designRateHz. Contour assumes a stream of `assumedStreamHz` (48 000 Hz) and writes register f = intended f x designRateHz / assumedStreamHz; a read is converted back. `peq.freqHz` is the REGISTER range (the vendor tool's 20-20000 Hz); the editor offers the intended range, that range x assumedStreamHz / designRateHz capped at the upper edge (40-20000 Hz).
- `peq.shelfQScale` / `peq.peakQScale`: the Q convention measured on the DAC for that type, a positive number or `"sqrt2"`: the register holds intended Q x the scale (the DAC plays
  Q = register / scale), so the editor offers the register range divided by it. FiiO records have both; a WalkPlay record has `shelfQScale` (the TRN: `"sqrt2"`). 1.0 = the register is the Q.
  `peq.coefficientRateHz` (WalkPlay records, a positive number of Hz): the rate the DSP runs the coefficient bytes Contour sends at; they are designed for it (`driver.designRateHz` stays the
  vendor tool's 96 000 Hz). The Protocol Micro and the Echo-A measure 93 750 Hz; the Max, the TRN (which computes its shelves from the registers) and the Dawn Pro 2 are 96 000 Hz. The evidence map
  of the record holds the run.
- `pacing`: delays and flags of the transport (write gap, echoes, checksum, audio stream, probe gap, save wait;
  WalkPlay also `sequence`). `readback`: Q slack and, for WalkPlay, read bounds.
- `settings`, `info`, `preview`: the non-PEQ controls the vendor software offers and how they are shown.
- `reset.offered`: the vendor software offers a reset. Contour 1.6.0 ships FACTORY RESET for the FiiO KA15 and the SnowSky Melody, with a saved copy first. The Tiny A ignores the command, so it has none in the app; the resets of the other DACs stay off in the release build until they are proven on hardware.
- `hwVolume.inScope`: whether a hardware volume control belongs to the vendor scope.
- `conflicts`: disagreements between sources, each with `field`, `values`, `src` and `resolution`.
- `evidence`: per claim, six axes - `source`, `vendorApp`, `pcBench`, `persistence`, `phone`, `acoustic` -
  filled only where citable, `null` otherwise; `firmware` names the build the evidence belongs to.
- `src`: field name to the references that support it.

## Rules

- A value that is not known is the literal string `"unknown"`. It is never guessed, never defaulted and never
  left out.
- When two sources disagree, the record keeps the value the code uses (or `"unknown"`) and adds an entry to
  `conflicts` with both values, the references and how it was resolved or that it is open.
- A pass filter or shelf that the code knows but does not offer is listed in `heldTypes`, not in `types`.
- References are public and stable: `core/<file>:<line>` (relative to `android/core/src/main/kotlin/.../core/`
  at the commit this branch is cut from), `android/app/...`, repository paths with a line, public URLs, commit
  hashes, `run:<id>` for a bench run, `scope-*.md:<line>` for the research scope files. No serial numbers,
  no local paths and no personal names.
- Evidence ranks: HW (seen on hardware) > VB (vendor binary or bundle) > OS (open source) > MP (marketing page)
  > CM (community).

## How generation works

`:core:generateDeviceCatalog` (`android/core/devices.gradle.kts`, applied from `android/core/build.gradle.kts`) reads
`devices/*.json` and writes `GeneratedDevices.kt` into the build directory (`core/build/generated/devices/kotlin`, or under
`contour.buildRoot`). That directory is a Kotlin source dir of `:core`, so `compileKotlin` runs the task first; its inputs
(the records) and output are declared, so it is skipped when nothing changed. No plugin and no network: the script parses
the records with `groovy.json.JsonSlurper`, which ships with Gradle, so offline builds (F-Droid) work. The generator lives
in `android/core`, not in `android/tools`, because the public export drops `android/tools`.

What is generated today (ids are upper-cased with dashes as underscores, e.g. `FIIO_KA15`, `MOONDROP_DAWN_PRO_2`):

- Every record: `<ID>_USB` (a `UsbIdentity`: vid, pid, product name, name match, aliases, `recognisedBy`), the list
  `USB_IDS` in `usb.listOrder`, and the `<ID>_VENDOR_ID` and `<ID>_PRODUCT_ID` constants. `DeviceTarget.find` and
  `findDevice`, `MoondropCatalog.find` and `KtMicroCatalog.find` read these; no VID or PID is typed in Kotlin.
- Family `fiio`: `GeneratedDevices.<ID>` as a `FiioConfig` with every field set from the record, the capture identity, the DAC
  Settings placement (`SettingPlacement` rows: id, section, group, writeVerified), header info ids and preview values.
  `shelfQScale` and `peakQScale` `"sqrt2"` are emitted as `sqrt(2.0)`, never a rounded literal.
- Family `walkplay`, status `stable` (Micro, Max, TRN): `WalkPlayData` (name, ids, `schemeNo`, band count, gain and Q range,
  frequency range, offered types, shelf Q scale, coefficient rate, preamp policy, factory flat of the spare slots, TRN read bounds) and the constants
  `<ID>_REPORT_ID`, `_BANDS`, `_BAND_LEN`, `_DESIGN_RATE_HZ`, `_PREAMP_MIN_DB`, `_PREAMP_MAX_DB`. Status `records-only` with a
  hypothesised family (Echo-A): `ReadOnlyWalkPlayData` (with `coefficientRateHz`), which the read-only Echo-A profile is built from.
- Family `moondrop` (Dawn Pro 2): `MoondropData` (bands, ranges, native and offered types, pre-gain and global gain ranges,
  writable bank, report size) and the register, delay and design-rate constants of `MoondropCodec`.
- Family `ktmicro` (Allegro Mini): `KtMicroData` (bands, ranges, band register layout, write ack, global gain steps) and the
  register and command constants of `KtMicroCodec`.
- `:app:generateUsbDeviceFilter` (`android/app/build.gradle.kts`): the Android USB attach filter `res/xml/device_filter.xml`
  (decimal ids, one `<usb-device>` per record in `usb.listOrder`). It is a generated resource directory of every `:app`
  variant, so there is no hand-written filter and no second activity alias. `:core:test` runs it first and compares it with
  the app's recognition.

What stays hand-written: the choice and order of the FiiO rules (`FiioCatalog.rules`), the setting templates with their
commands and decoders (`DacSettings`), codecs, write sequences, parsers, the Micro high-shelf emulation, the never-send lists
and the session logic. A record picks a known template by id; it cannot add behaviour. `core/devices/PendingRecords.kt` holds
the few values a driver needs while the record still says `"unknown"` (each with the reason); the day a record gets a sourced
value, it moves into the record and out of that file.

The gate is the generator itself: it throws, and the build fails, when a record misses a key, holds the placeholder
`"unknown"` in any field its driver needs (the fiio driver: driver, peq, preamp, slots, pacing, readback, settings, info,
preview, upstream, usb id and name; the other drivers: the fields listed above), has a wrong type, or has `preamp.db` that
differs from `peq.gainDb` minus `preamp.registerOffsetDb`. It also refuses two records with one `usb.listOrder` or one VID:PID,
a `find` record that matches by name, and records of one WalkPlay protocol that disagree on report id or band length. Free text
(notes, conflicts, evidence, reset, hwVolume, src) is not read. Values in `"unknown"` form stay legal wherever no driver reads them.

## Add a FiiO-family device

1. Copy the closest FiiO record to `devices/<vendor>-<model>.json` and fill every field from evidence; keep `"family": "fiio"`.
   Mapping to `FiioConfig`: `slots.names` is `device-by-index` (names by position, KA15), `device-by-slot` (by slot id, SnowSky),
   `app` or `none` (names kept in the app); `readback.qSlack` is `fiio-1+q/250` or `none`; `pacing.checksum` is `checksumFrames`;
   `pacing.consumeEchoes` is `consumeWriteEchoes`; `upstream.status` is the `FiioConfig.status` label; `driver.capture` is
   `{ "fixturePath": ... }` or `null` (a device with a capture is routed by VID, PID and name; the others by product id in `DeviceTarget`).
2. `settings` rows may only use ids that have a template in `DacSettings` (volume, volume-steps, balance, mic, light, uac, filter,
   gain, output, dre, buttons, brightness, screen-off, rotation, language); an unknown id fails at class load, which the golden test
   and `:core:test` catch. A setting with a new command needs a template first (code), then the record may list it.
3. Add `GeneratedDevices.<ID>` to `FiioCatalog.rules` (order matters to the tests), a `DeviceTarget` for it and its entry in
   `DeviceTarget.byRecord`. The USB filter and the VID:PID lookup come from the record's `usb` section (`recognisedBy`,
   `listOrder`); nothing else to type.
4. A deliberate catalog change also changes the golden: regenerate it as below and review the diff.

## Golden file

`android/core/src/test/resources/catalog/golden.json` is the dump of the catalog as the code sees it (all values now come from the generated catalog): every
device, its capabilities and the encoded frames of a fixed set of profiles. The Dawn Pro 2, Allegro Mini and Echo-A are in
the `families` section (config, capabilities, the replayed bench reads and the planned frames); `identity` holds the generated
filter and the `findDevice` answers. `CatalogGoldenTest` compares a fresh
dump from `CatalogDump` with the committed file byte for byte, so any change in the catalog or the codecs fails
the test until the change is deliberate.

Regenerate the golden only when the catalog is meant to change, and review the diff:

    CONTOUR_REGENERATE_GOLDEN=1 android/wsl-build.sh :core:cleanTest :core:test --tests '*CatalogGoldenTest*'

A normal test run never writes the golden.
