# Device PEQ filter types - manufacturer evidence (14.10.2026)

Levels: A = manufacturer states it (page, manual, FAQ, release notes); B = manufacturer's own control software offers it for that device; C = third party only (devicePEQ, reviews, forums); D = nothing found. "C (none)" = third party says NOT supported.

## Table

| Device | PK | LS | HS | LP | HP | BP | AP | Other (notch) |
|---|---|---|---|---|---|---|---|---|
| CrinEar Protocol Micro (3302:C20F) | C | C | C (none) | C | C | C (none) | C (none) | C (none) |
| CrinEar Protocol Max (3302:43CC) | C | C | C | C (none) | C (none) | C (none) | C (none) | C (none) |
| FiiO KA15 (2972:0104) | B | B | B | C | C | C | C | C (none) |
| FiiO K13 R2R (2972:0120) | B | B | B | B | B | B | B | C (none) |
| SnowSky Melody (2972:0126) | C | C | C | C | C | C | C | C (none) |
| SnowSky Tiny A (2972:0147) | B | B | B | B | B | B | B | C (none) |

No cell reaches level A: no official product page, FAQ or manual found names any filter type for any of the six devices.

## Per-device notes

### FiiO KA15
- A (band count only): fiio.com/ka15 - "ten-band high-precision lossless PEQ"; no types listed (ambiguous). https://www.fiio.com/ka15
- B: PK/LS/HS bytes captured from FiiO's web app fiiocontrol.fiio.com on 03.10.2026 (PROTOCOL.md, "FiiO filter type byte" table). LP/HP/BP/AP were not captured on the KA15 ("not captured, not used") - so whether the web app's menu offers them for the KA15 is not recorded locally.
- C: devicePEQ `peq10Band12dBAllFilters7` - "all 7 FiiO filter types (PK/LS/HS/LP/HP/BP/AP)", notch false.
- Headfonics review mentions "various filter types" in the app without listing them (C, ambiguous). https://headfonics.com/fiio-ka15-review/

### FiiO K13 R2R
- A (band count only): FiiO FAQ "The K13 R2R supports a 10-band global PEQ" - no types. https://fiio.com/newsinfo/1087834.html ; product page https://www.fiio.com/k13r2r likewise.
- B: FINDINGS.md - FiiO web app on K13 fw 1.15, USER10 band 3: wrote BP 3, LP 4, HP 5, AP 6 (plus PK/LS/HS); a read after HP returned type 05.
- C: devicePEQ `peq10Band12dBWideAllFilters` - all 7 FiiO types, notch false.

### SnowSky Tiny A
- A: nothing naming types; reviews/retailers only say 10-band PEQ via FiiO Control (porta-fi.com/snowsky-tiny-review, C).
- B: FINDINGS.md - FiiO web app on the Tiny A fw 1.10, band 3: PK 0, LS 1, HS 2, BP 3, LP 4, HP 5, AP 6, every write echoed.
- C: devicePEQ `peq5Band12dBAllFilters7` (lists 5 bands; the device actually holds 10).

### SnowSky Melody
- A: nothing found naming types.
- B: none. The web app was not captured on the Melody. Contour's own VERIFIED send of BP/LP/HP/AP (PROTOCOL.md) is our software, not the maker's - device accepted and read back the bytes, nothing more. Resemblance to the Tiny A ("same firmware family") is INFERENCE, not support.
- C: devicePEQ "SNOWSKY Melody" -> `peq10Band12dBAllFilters7`.

### CrinEar Protocol Max
- A (band count only): crinacle.com user guide - "supports up to 10 bands of PEQ"; no types. https://crinacle.com/2025/10/05/crinear-protocol-max-user-guide-specifications/5
- B: none found. The official route is the Hangout.Audio graph tool's DeviceEQ plugin, which is devicePEQ itself (jeromeof's code hosted by Crinacle) - treat as C with an endorsement, not as maker data.
- C: devicePEQ "Protocol Max" -> `peq10Band10dBFullShelves`: PK + LS + HS, LP/HP/BP/AP/notch false. The WalkPlay handler maps PK 2, LSQ 1, HSQ 3, LP 4, HP 5 for the whole WalkPlay family, but this constraint profile disables LP/HP for the Max.

### CrinEar Protocol Micro
- A (band count only): Linsoul listing (retailer, repeats maker copy) - "integrated 8-band Parametric EQ", no types.
- B: none found.
- C: devicePEQ SchemeNo11 -> `walkplayPeq8Band10dBLsLowpass`: "low-shelf + LP/HP (no high-shelf)". PROTOCOL.md L71: "No BP/notch/allpass."

### Where "vendor WalkPlay tool codes 4 and 5" came from
ROADMAP.md L22: "WalkPlay has LP 4 and HP 5 in the vendor tool". The only local source is devicePEQ `walkplayHidHandler.js` L250 (`{"PK": 2, "LSQ": 1, "HSQ": 3, "LP": 4, "HP": 5}`) and L435-436 (decode 4 = LP, 5 = HP). devicePEQ comments cite `walkplayJS/walkplay-online.js` as the official WalkPlay web app it was checked against, but no local copy or capture of that tool exists. Level: C (third-party code that claims to mirror the vendor tool), not B. It is a family-wide wire mapping, not a per-device claim; for the Max the same project's constraints say LP/HP unsupported.

### What FINDINGS.md proves and does not
- Proves (B): FiiO's own web app, connected to the Tiny A and the K13 R2R, offered and wrote type bytes BP 3, LP 4, HP 5, AP 6 (and PK/LS/HS); the Tiny A echoed each write, the K13 read back HP.
- Does not prove: acoustic behaviour (Q and gain use for LP/HP/BP/AP not measured), anything about the Melody or KA15, or official documentation of those types.

## Open gaps
- Capture the FiiO web app type menu on the KA15 and Melody (B would then be possible for LP/HP/BP/AP).
- Find the official WalkPlay app/web tool (walkplay-online.js) and check its type menu for SchemeNo11 (Micro) and SchemeNo16 (Max).
- Ask CrinEar/FiiO support or find manuals that name types; nothing level A exists today.
- Acoustic sweep of LP/HP/BP/AP on any FiiO/SnowSky unit.
- Raw source of C: https://github.com/jeromeof/devicePEQ/blob/master/devicePEQ/peqConstraintsConfig.json (fetched 14.10.2026).
