# Contour advanced beta - supported devices

Contour is an Android EQ manager for USB DACs. The advanced beta (`1.4.1-advbeta2`, package suffix `.advbeta`) is a separate, experimental build: it has its own package name and ships as a GitHub pre-release on the `advbeta` branch. It recognises far more devices than the stable app, and most of them have never been seen on real hardware. This file says which devices the beta recognises, at which level, and which it refuses.

The list is generated from the code of the beta (catalog dump of 03.10.2026, with the changes since then written in by hand: the FiiO K13 R2R fix tested on hardware on 10.10.2026 and LOW PASS / HIGH PASS on the Protocol Max and TRN Black Pearl in code commit `8313c71`, plus one correction described under Known limitations). It describes what the code does, not what has been tested.

## Summary

| Level | Rules | What it means |
|---|---|---|
| Confirmed on hardware | 4 | Writes enabled, tested on real devices (in the stable app) |
| Implemented, needs a tester | 401 | Writes enabled, never seen on hardware |
| Read-only diagnostics | 18 | Reads the current EQ, never writes |
| Recognised but blocked | 52 | Detected, then refused with a reason |
| Not supported | - | No driver or transport in this beta |

The counts add up to 475 rules from the code. One physical device can match more than one rule, so these are rules, not devices. "Not supported" has no rules of its own: the three serial identities in it are the same entries that appear in the deny-list, and the families listed there have no code at all.

## How recognition works

Read this before trusting any name in the tables below.

- The 348 write-enabled WalkPlay catalog pairs are matched by USB vendor:product ID (a 349th, `3302:43CC`, now has its own route, see Confirmed on hardware). The USB product name the device reports can be any non-empty name; the catalog label in the tables is only the display name Contour shows, not a name the device has to report. There are three exceptions: (a) if the reported name equals one of the 19 WalkPlay name rules, that rule's scheme, bands and filter types win over the catalog row (a scheme-11, 8-band pair that reports `Octave` is written as scheme 18 with 10 bands); (b) if it equals a guard name or a KT Micro name, the pair is blocked; (c) pairs marked blocked or exact-name-only keep their own conditions.
- 19 name rules match the USB product name on any product ID of 19 vendor IDs (`0104`, `011B`, `011D`, `0661`, `0663`, `0666`, `0762`, `0909`, `0D8C`, `2FC6`, `3302`, `34BE`, `35D8`, `36A7`, `373B`, `60C1`, `60E1`, `B445`, `B44D`). Writing through them needs no extra confirmation step beyond the normal checks (HID descriptor check, complete read before write, hold to send).
- `3302:43EB`, `3302:39C1` and `3302:4353` accept any of the 13 scheme-15 WalkPlay names (listed under Implemented, needs a tester) and refuse every other name.
- Rules that match by name on any product ID are not in the Android USB attach filter, so plugging the device in does not launch Contour. They are detected only while the app is open.
- FiiO name rules on vendor IDs `2972` and `0A12`, the Moondrop read-only names and the KT Micro name `CDSP` also match on any product ID, and these ask for an explicit tap on Connect first.
- Names are written exactly as the code compares them, in code spans, with special whitespace called out. `DAWN PRO2` and `DAWN PRO 2` are different devices: the first is Moondrop read-only, the second is a WalkPlay write-enabled name rule.

Writing to a device that is not in the first level is at your own risk. Read the current EQ first, keep a backup profile of what the device holds now, and expect that a write may not behave as on the supported devices.

## Confirmed on hardware

Four devices have a hardware test behind them, and stable 1.4.1 supports exactly these four. The Micro and the Max use the same code path as in stable; they have not yet been re-tested on this beta build. The FiiO KA15 was tested on this beta line (read, write to USER1-3 with read-back, slot names and rename) before the same code went into stable 1.3.2. The FiiO K13 R2R was tested on this beta line on 10.10.2026 (read, write to USER9 and USER10 with read-back, both slots kept all ten bands and the preamp after a power cycle) before the same fix went into stable 1.4.1. In the beta it is still matched by its USB name on any product ID and asks for a tap on Connect; stable matches the exact `2972:0120`.

| VID:PID | Device | Bands | Filter types | Tested by | Stable since |
|---|---|---|---|---|---|
| `3302:C20F` | CrinEar Protocol Micro | 8 | PK, LS, HS | Project owner | 1.0 |
| `3302:43CC` | CrinEar Protocol Max | 10 | PK, LS, HS (beta also LP, HP: not hardware tested) | Community tester | 1.3.0 |
| `2972:0104` | FiiO KA15 | 10 | PK, LS, HS | Project owner | 1.3.2 |
| `FIIO K13 R2R` (stable: `2972:0120`) | FiiO K13 R2R | 10 | PK, LS, HS | Project owner | 1.4.1 |

The Micro is the only device that also has A/B comparison and hardware volume control.

## Implemented, needs a tester

Writes are enabled in the code, but none of these has been seen on real hardware. Every write needs the advanced build, a HID descriptor check, a complete read first and a hold-to-send gesture.

### WalkPlay: TRN

| VID:PID | Device | Bands | Filter types | Note |
|---|---|---|---|---|
| `3302:43E8` | TRN Black Pearl | 10 | PK, LS, HS, LP, HP | Dedicated TRN route. A/B is blocked: TRN RAM-only writes are not hardware verified. LOW PASS and HIGH PASS since 1.4.1-advbeta1, not hardware tested. |

### WalkPlay: name rules on any product ID (19)

Each rule matches the exact USB product name on any product ID of the 19 vendor IDs above. Gain -10..+10 dB, pregain -30..0 dB in whole dB. Bands, filter types and Q differ per rule, as shown below.

| USB name | Scheme | Bands | Filter types | Q | Note |
|---|---|---|---|---|---|
| `TANCHJIM-SPACE PRO` | 16 | 10 | PK | 0.1..10 | - |
| `TANCHJIM-OLA II DSP` | 10 | 8 | PK | 0.1..5 | - |
| `Protocol Max` | 16 | 10 | PK, LS, HS | 0.1..10 | - |
| `Octave` | 18 | 10 | PK, LS, HS | 0.1..10 | - |
| `CS43131 HiFi Audio DSP` | 11 | 8 | PK, LS | 0.1..10 | - |
| `CS43198 HiFi DSP Audio` | 11 | 8 | PK, LS | 0.1..10 | - |
| `BGVP MX1` | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `DT04` | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `MD-QT-042` | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `MOONDROP HiFi with PD` | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `DAWN PRO 2` | 15 | 8 | PK, LS, HS | 0.1..10 | - |
| `CS431XX` | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `ES9039 ` (trailing space) | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `TANCHJIM-STARGATE II` | 15 | 8 | PK | 0.1..10 | - |
| `didiHiFi DSP Cable - Memory` | 15 | 8 | PK, LS, HS | 0.1..10 | - |
| `ddHiFi DSP Cable - Memory` | 15 | 8 | PK, LS, HS | 0.1..10 | - |
| `Dual CS43198` | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `ES9039 HiFi DSP Audio` | 15 | 8 | PK, LS, HS | 0.1..10 | marked experimental upstream |
| `TRUTHEAR KEYX` | 15 | 8 | PK, LS, HS | 0.1..10 | - |

### WalkPlay: exact catalog pairs (348)

Matched by VID:PID only. The catalog label is the display name, not a USB name. All have gain -10..+10 dB and pregain -30..0 dB; Q is 0.1..10 for every row.

<details>
<summary>Show the 348 pairs</summary>

| VID:PID | Catalog label | Scheme | Bands | Filter types |
|---|---|---|---|---|
| `0104:3302` | `CB5100_Dual_CS43198_TFT_EVK` | 11 | 8 | PK, LS |
| `011D:35D8` | `1` | 15 | 8 | PK, LS, HS |
| `0661:0881` | `TT39518F01-PRO` | 10 | 8 | PK |
| `0663:08F2` | `CB1300D 7.1 TEST` | 21 | 8 | PK |
| `0666:0888` | `Need Update` | 10 | 8 | PK |
| `0762:0880` | `CB1300 384k EVK` | 11 | 8 | PK, LS |
| `0909:39C8` | `ATT-PHA002` | 18 | 10 | PK, LS, HS |
| `2FC6:F806` | `AE1` | 11 | 8 | PK, LS |
| `2FC6:F807` | `AE3` | 11 | 8 | PK, LS |
| `2FC6:F808` | `AE6` | 16 | 10 | PK, LS, HS |
| `3302:00C0` | `KT0210 OTA` | 11 | 8 | PK, LS |
| `3302:0104` | `CB5100_DualCS43131_TFT` | 11 | 8 | PK, LS |
| `3302:1230` | `HUAN` | 11 | 8 | PK, LS |
| `3302:1231` | `TBD` | 11 | 8 | PK, LS |
| `3302:1233` | `G10` | 11 | 8 | PK, LS |
| `3302:1237` | `WHIZZER BEAT DAC` | 11 | 8 | PK, LS |
| `3302:123F` | `XIBERIA  X-Blade` (double space) | 11 | 8 | PK, LS |
| `3302:1240` | `Colorful Sigapes Audio` | 11 | 8 | PK, LS |
| `3302:1241` | `TBD` | 11 | 8 | PK, LS |
| `3302:1243` | `HuoMangXing` | 11 | 8 | PK, LS |
| `3302:1244` | `CANYON QD03` | 11 | 8 | PK, LS |
| `3302:1245` | `CANYON QD03` | 11 | 8 | PK, LS |
| `3302:1248` | `icon` | 11 | 8 | PK, LS |
| `3302:1249` | `TBD` | 11 | 8 | PK, LS |
| `3302:124A` | `SIVGA-SM100` | 11 | 8 | PK, LS |
| `3302:124B` | `VIDVIE-2026` | 11 | 8 | PK, LS |
| `3302:124C` | `Ocean’s Tear` | 11 | 8 | PK, LS |
| `3302:124D` | `TFZ COCO-V1` | 11 | 8 | PK, LS |
| `3302:124E` | `HD10` | 11 | 8 | PK, LS |
| `3302:1251` | `WP02 - U1` | 11 | 8 | PK, LS |
| `3302:1261` | `E07L` | 11 | 8 | PK, LS |
| `3302:1262` | `RX3 PLUS` | 11 | 8 | PK, LS |
| `3302:1264` | `M416` | 11 | 8 | PK, LS |
| `3302:1266` | `C01` | 11 | 8 | PK, LS |
| `3302:1269` | `Muse Dash&Quarks2` | 11 | 8 | PK, LS |
| `3302:126A` | `TFZ-DSP` | 11 | 8 | PK, LS |
| `3302:126B` | `CDE-1` | 11 | 8 | PK, LS |
| `3302:126C` | `BORZOI FANCIER AUDIO` | 11 | 8 | PK, LS |
| `3302:126D` | `DM7122HM-TT` | 11 | 8 | PK, LS |
| `3302:126E` | `DAT4127HM-JH` | 11 | 8 | PK, LS |
| `3302:126F` | `TBD` | 11 | 8 | PK, LS |
| `3302:1272` | `DITA ANTE DAC` | 11 | 8 | PK, LS |
| `3302:1278` | `SIMGOT EG280` | 11 | 8 | PK, LS |
| `3302:127A` | `Microphone` | 11 | 8 | PK, LS |
| `3302:127D` | `Cipher` | 11 | 8 | PK, LS |
| `3302:127E` | `G99P USB Audio` | 11 | 8 | PK, LS |
| `3302:1281` | `T10` | 11 | 8 | PK, LS |
| `3302:1282` | `LLR03-A/E` | 11 | 8 | PK, LS |
| `3302:1283` | `LLR03-C&G` | 11 | 8 | PK, LS |
| `3302:1284` | `LHW24-07` | 11 | 8 | PK, LS |
| `3302:1285` | `LHW23-02` | 11 | 8 | PK, LS |
| `3302:1286` | `PLEXTONE JALLY` | 11 | 8 | PK, LS |
| `3302:1287` | `DOVE Hi-Fi DSP` | 11 | 8 | PK, LS |
| `3302:1288` | `betavo` | 11 | 8 | PK, LS |
| `3302:1289` | `EVAN` | 11 | 8 | PK, LS |
| `3302:128A` | `Titan s2` | 11 | 8 | PK, LS |
| `3302:128B` | `Titan X` | 11 | 8 | PK, LS |
| `3302:128C` | `KSHF-D01/D02(EQ)` | 11 | 8 | PK, LS |
| `3302:128D` | `YunWired1` | 11 | 8 | PK, LS |
| `3302:128E` | `YTA-UC35` | 11 | 8 | PK, LS |
| `3302:128F` | `G30` | 11 | 8 | PK, LS |
| `3302:1292` | `CB1200AU` | 11 | 8 | PK, LS |
| `3302:1293` | `利维坦2A` | 11 | 8 | PK, LS |
| `3302:1294` | `G28 Pro` | 11 | 8 | PK, LS |
| `3302:1295` | `TEARS DSP-W` | 11 | 8 | PK, LS |
| `3302:1296` | `TEARS DSP-B` | 11 | 8 | PK, LS |
| `3302:1297` | `TINHIFI C1` | 11 | 8 | PK, LS |
| `3302:1298` | `ET-01` | 11 | 8 | PK, LS |
| `3302:1299` | `G38` | 11 | 8 | PK, LS |
| `3302:129A` | `Colorful Sigapes Audio` | 11 | 8 | PK, LS |
| `3302:129B` | `Colorful Sigapes Audio` | 11 | 8 | PK, LS |
| `3302:129C` | `TBD` | 11 | 8 | PK, LS |
| `3302:129F` | `Rightear` | 11 | 8 | PK, LS |
| `3302:12B3` | `K419` | 11 | 8 | PK, LS |
| `3302:12C0` | `WP01-Q` | 11 | 8 | PK, LS |
| `3302:12C1` | `WP01 - Q` | 11 | 8 | PK, LS |
| `3302:12C3` | `DAT412BHM-TT` | 11 | 8 | PK, LS |
| `3302:12C4` | `LA2108DA-011` | 11 | 8 | PK, LS |
| `3302:12C5` | `ECHO-A` | 11 | 8 | PK, LS |
| `3302:12C6` | `KYERE HiFi Audio` | 11 | 8 | PK, LS |
| `3302:12C8` | `BORZOI FANCIER AUDIO` | 11 | 8 | PK, LS |
| `3302:12C9` | `DAT412BHM-TT` | 11 | 8 | PK, LS |
| `3302:12CA` | `Hi-MAX` | 11 | 8 | PK, LS |
| `3302:12CB` | `C01` | 11 | 8 | PK, LS |
| `3302:12CC` | `DAT4125HM+P-TT` | 11 | 8 | PK, LS |
| `3302:12CD` | `DUNU Headphone` | 11 | 8 | PK, LS |
| `3302:12CE` | `EPZ G20` | 11 | 8 | PK, LS |
| `3302:12DB` | `Quark2` | 11 | 8 | PK, LS |
| `3302:12E9` | `DAT4122UA-TT` | 11 | 8 | PK, LS |
| `3302:1320` | `T6` | 13 | 10 | PK, LS, HS |
| `3302:1321` | `U-02` | 13 | 10 | PK, LS, HS |
| `3302:1323` | `USB DSP Audio - 10EQ` | 20 | 10 | PK |
| `3302:1326` | `ToneSphere_E30T` | 13 | 10 | PK, LS, HS |
| `3302:1327` | `Leviathan2` | 13 | 10 | PK, LS, HS |
| `3302:1328` | `TBD` | 13 | 10 | PK, LS, HS |
| `3302:1329` | `CB1300` | 13 | 10 | PK, LS, HS |
| `3302:132A` | `XuanJing Pro` | 13 | 10 | PK, LS, HS |
| `3302:132B` | `OTA用` | 11 | 8 | PK, LS |
| `3302:1330` | `X1 stmic-I` | 13 | 10 | PK, LS, HS |
| `3302:1332` | `BS29 ENC` | 13 | 10 | PK, LS, HS |
| `3302:1333` | `AUSDOM` | 13 | 10 | PK, LS, HS |
| `3302:1334` | `TBD` | 13 | 10 | PK, LS, HS |
| `3302:13A3` | `DAE4131HM-TT` | 11 | 8 | PK, LS |
| `3302:13A4` | `AINR Audio` | 11 | 8 | PK, LS |
| `3302:13A5` | `DAE4131HM-TT` | 11 | 8 | PK, LS |
| `3302:13A9` | `X600-ENC` | 13 | 10 | PK, LS, HS |
| `3302:13AB` | `C-08 AI ENC` | 11 | 8 | PK, LS |
| `3302:13AC` | `USB AI ENC Audio` | 13 | 10 | PK, LS, HS |
| `3302:13AE` | `Eagle` | 13 | 10 | PK, LS, HS |
| `3302:13AF` | `LX-HE08` | 13 | 10 | PK, LS, HS |
| `3302:13B0` | `SOMIC` | 13 | 10 | PK, LS, HS |
| `3302:13B1` | `LX-HE05` | 13 | 10 | PK, LS, HS |
| `3302:13B2` | `X600U-ENC` | 13 | 10 | PK, LS, HS |
| `3302:13B4` | `1` | 13 | 10 | PK, LS, HS |
| `3302:13B6` | `THUNDEROBOT HG50` | 13 | 10 | PK, LS, HS |
| `3302:13B7` | `CB1300_EQ_BAND10` | 20 | 10 | PK |
| `3302:13B9` | `OA-25009` | 13 | 10 | PK, LS, HS |
| `3302:13BA` | `X1stmic-A` | 13 | 10 | PK, LS, HS |
| `3302:13BB` | `XuanJingF1` | 13 | 10 | PK, LS, HS |
| `3302:13BE` | `M762 Ultra` | 13 | 10 | PK, LS, HS |
| `3302:13BF` | `XIBERIA SHADOW` | 13 | 10 | PK, LS, HS |
| `3302:13C0` | `DAE4131HM-TT` | 11 | 8 | PK, LS |
| `3302:13C1` | `WP03-AQ` | 11 | 8 | PK, LS |
| `3302:13D3` | `DAE4131HM-TT` | 11 | 8 | PK, LS |
| `3302:13D4` | `EPZ TP13` | 11 | 8 | PK, LS |
| `3302:13D7` | `001` | 11 | 8 | PK, LS |
| `3302:13D9` | `SOMIC` | 11 | 8 | PK, LS |
| `3302:13DC` | `MD-QT-033` | 11 | 8 | PK, LS |
| `3302:13DF` | `DSP Gezi(EQ FREE)` | 13 | 10 | PK, LS, HS |
| `3302:13F2` | `CB1300D 7.1CH` | 21 | 8 | PK |
| `3302:2010` | `AZLA-SMART DAC` | 17 | 5 | PK, LS, HS |
| `3302:201D` | `Note` | 17 | 5 | PK, LS, HS |
| `3302:201E` | `USB Audio Pro` | 17 | 5 | PK, LS, HS |
| `3302:2030` | `CD-3` | 17 | 5 | PK, LS, HS |
| `3302:2036` | `Stealthbite DAC` | 17 | 5 | PK, LS, HS |
| `3302:2038` | `EI-01` | 17 | 5 | PK, LS, HS |
| `3302:203A` | `白羽` | 17 | 5 | PK, LS, HS |
| `3302:203E` | `DAT4205HM+P-TT` | 17 | 5 | PK, LS, HS |
| `3302:2040` | `RY-01` | 17 | 5 | PK, LS, HS |
| `3302:2043` | `MUSE HiFi U3` | 17 | 5 | PK, LS, HS |
| `3302:2044` | `VY-C-MIC01` | 17 | 5 | PK, LS, HS |
| `3302:2048` | `Turbo 5 UAC2.0` | 17 | 5 | PK, LS, HS |
| `3302:2049` | `Turbo 5 UAC1.0` | 17 | 5 | PK, LS, HS |
| `3302:20E1` | `SIMGOT DEW0S` | 17 | 5 | PK, LS, HS |
| `3302:20E2` | `OG10` | 17 | 5 | PK, LS, HS |
| `3302:20E3` | `AD1` | 17 | 5 | PK, LS, HS |
| `3302:20E5` | `KT02H20P+OPA` | 17 | 5 | PK, LS, HS |
| `3302:20E7` | `USB Audio` | 17 | 5 | PK, LS, HS |
| `3302:20E8` | `HDK224-1` | 17 | 5 | PK, LS, HS |
| `3302:20EA` | `Audiocular C18` | 17 | 5 | PK, LS, HS |
| `3302:20EC` | `BQEYZ C30` | 17 | 5 | PK, LS, HS |
| `3302:20EE` | `KT02H20P with EQ` | 17 | 5 | PK, LS, HS |
| `3302:20EF` | `Audiocular` | 17 | 5 | PK, LS, HS |
| `3302:20FF` | `DAT420PHM-TT-KEY` | 17 | 5 | PK, LS, HS |
| `3302:231E` | `PLEXTONE-AKM` | 19 | 6 | PK, LS, HS |
| `3302:231F` | `PLEXTONE Audio` | 19 | 6 | PK, LS, HS |
| `3302:2320` | `iKF USB-C Audio` | 19 | 6 | PK, LS, HS |
| `3302:2321` | `KT0231HP` | 19 | 6 | PK, LS, HS |
| `3302:2323` | `CANYON QD03` | 19 | 6 | PK, LS, HS |
| `3302:2326` | `EWEADN-VX07` | 19 | 6 | PK, LS, HS |
| `3302:232B` | `611` | 19 | 6 | PK, LS, HS |
| `3302:232C` | `612` | 19 | 6 | PK, LS, HS |
| `3302:2334` | `KT0231HP+MAX97220` | 19 | 6 | PK, LS, HS |
| `3302:23C0` | `DAE4131HM-TT` | 13 | 10 | PK, LS, HS |
| `3302:23C1` | `USB AI ENC Audio` | 13 | 10 | PK, LS, HS |
| `3302:23E2` | `TBD` | 19 | 6 | PK, LS, HS |
| `3302:23EE` | `KT0231HP DSP` | 19 | 6 | PK, LS, HS |
| `3302:2DC1` | `KT02H20P USB Audio` | 17 | 5 | PK, LS, HS |
| `3302:39A0` | `ES9039Q2M+SGM8262*2` | 18 | 10 | PK, LS, HS |
| `3302:39A2` | `S1` | 18 | 10 | PK, LS, HS |
| `3302:39A3` | `ET1` | 18 | 10 | PK, LS, HS |
| `3302:39C2` | `ES9039Q2M` | 18 | 10 | PK, LS, HS |
| `3302:39C4` | `CD-30` | 18 | 10 | PK, LS, HS |
| `3302:39C6` | `XUANWU` | 18 | 10 | PK, LS, HS |
| `3302:39C7` | `Seerish.17` | 18 | 10 | PK, LS, HS |
| `3302:39C9` | `TBD` | 18 | 10 | PK, LS, HS |
| `3302:39CD` | `AX8` | 18 | 10 | PK, LS, HS |
| `3302:39CE` | `ARS-A-01` | 18 | 10 | PK, LS, HS |
| `3302:39CF` | `K605` | 18 | 10 | PK, LS, HS |
| `3302:39E0` | `K605` | 16 | 10 | PK, LS, HS |
| `3302:3DB0` | `SK02` | 21 | 8 | PK |
| `3302:3DC1` | `CB1300D CH7.1` | 21 | 8 | PK |
| `3302:3DC4` | `CB1300D CH7.1 + PD60W` | 21 | 8 | PK |
| `3302:3DC5` | `USB 7.1CH AUDIO` | 21 | 8 | PK |
| `3302:3DC6` | `CB1300D DSP 7.1CH` | 21 | 8 | PK |
| `3302:3DC7` | `TBD` | 21 | 8 | PK |
| `3302:3DC8` | `X4-1&2` | 21 | 8 | PK |
| `3302:3DC9` | `骨传导录音EQ用` | 21 | 8 | PK |
| `3302:3DCA` | `GS35` | 21 | 8 | PK |
| `3302:3DCC` | `USB 7.1CH AUDIO` | 21 | 8 | PK |
| `3302:3DCD` | `BGVP M01` | 21 | 8 | PK |
| `3302:4301` | `CS43198` | 16 | 10 | PK, LS, HS |
| `3302:4304` | `evoX HiFi DSP Audio` | 16 | 10 | PK, LS, HS |
| `3302:4305` | `N3 HiFi DSP Audio` | 16 | 10 | PK, LS, HS |
| `3302:4306` | `USB Audio-CUBE` | 16 | 10 | PK, LS, HS |
| `3302:430D` | `YOURAN-D43198 PRO ` (trailing space) | 16 | 10 | PK, LS, HS |
| `3302:430E` | `YOURAN-D43198 PRO MAX` | 16 | 10 | PK, LS, HS |
| `3302:430F` | `BLUE ROSE` | 16 | 10 | PK, LS, HS |
| `3302:4311` | `Azure Cloud Sword` | 16 | 10 | PK, LS, HS |
| `3302:4312` | `Oshun DECO` | 16 | 10 | PK, LS, HS |
| `3302:4313` | `Cube01` | 16 | 10 | PK, LS, HS |
| `3302:4316` | `TP16` | 16 | 10 | PK, LS, HS |
| `3302:4318` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:4319` | `CS43198 + AD8397` | 16 | 10 | PK, LS, HS |
| `3302:431D` | `Hercules DAC` | 16 | 10 | PK, LS, HS |
| `3302:4321` | `F4` | 16 | 10 | PK, LS, HS |
| `3302:4322` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:4323` | `HF001` | 16 | 10 | PK, LS, HS |
| `3302:4324` | `BGVP MX1 Pro` | 16 | 10 | PK, LS, HS |
| `3302:4325` | `TP55 PRO` | 16 | 10 | PK, LS, HS |
| `3302:4326` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:4327` | `U-CUBE` | 16 | 10 | PK, LS, HS |
| `3302:4329` | `DUYOU CS43198 PRO MAX` | 16 | 10 | PK, LS, HS |
| `3302:432A` | `DUYOU CS43198 PRO MAX` | 16 | 10 | PK, LS, HS |
| `3302:432C` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:4351` | `DA5` | 16 | 10 | PK, LS, HS |
| `3302:4352` | `KM_HA03` | 16 | 10 | PK, LS, HS |
| `3302:4355` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:4357` | `Memory USB` | 15 | 8 | PK, LS, HS |
| `3302:4358` | `G6` | 16 | 10 | PK, LS, HS |
| `3302:4359` | `G303` | 16 | 10 | PK, LS, HS |
| `3302:435A` | `KM_HA04_Pro` | 16 | 10 | PK, LS, HS |
| `3302:435D` | `AFUL SnowyNight Pro` | 16 | 10 | PK, LS, HS |
| `3302:435E` | `BQL001` | 16 | 10 | PK, LS, HS |
| `3302:4360` | `EF18pro` | 16 | 10 | PK, LS, HS |
| `3302:4361` | `Dual CS43131 HiFi DSP Audio` | 16 | 10 | PK, LS, HS |
| `3302:4362` | `1` | 15 | 8 | PK, LS, HS |
| `3302:4363` | `CS43198*2+OPA*2` | 16 | 10 | PK, LS, HS |
| `3302:4364` | `DA7` | 16 | 10 | PK, LS, HS |
| `3302:4366` | `TINHIFI Pulse Pro ` (trailing space) | 16 | 10 | PK, LS, HS |
| `3302:4370` | `单CS43XXX 二代HID验证` | 15 | 8 | PK, LS, HS |
| `3302:4380` | `Rouyin-shuimo` | 16 | 10 | PK, LS, HS |
| `3302:4381` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:4382` | `DTC 500 X` | 16 | 10 | PK, LS, HS |
| `3302:4383` | `TP55` | 16 | 10 | PK, LS, HS |
| `3302:4386` | `CS43131` | 16 | 10 | PK, LS, HS |
| `3302:43B1` | `KSHF-02 Pro(EQ)` | 16 | 10 | PK, LS, HS |
| `3302:43B6` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:43B7` | `HC02` | 16 | 10 | PK, LS, HS |
| `3302:43B8` | `HC03` | 16 | 10 | PK, LS, HS |
| `3302:43BC` | `Verum Motus` | 16 | 10 | PK, LS, HS |
| `3302:43BE` | `H800` | 16 | 10 | PK, LS, HS |
| `3302:43BF` | `CS43918+SGM8262` | 16 | 10 | PK, LS, HS |
| `3302:43C1` | `JM20PRO` | 11 | 8 | PK, LS |
| `3302:43C2` | `HiFi DSP Audio Pro` | 16 | 10 | PK, LS, HS |
| `3302:43C3` | `LLR03-X&Q` | 11 | 8 | PK, LS |
| `3302:43C5` | `Dual CS43198+Dual OPA` | 16 | 10 | PK, LS, HS |
| `3302:43C7` | `TBD` | 16 | 10 | PK, LS, HS |
| `3302:43C8` | `CD-10 PRO` | 16 | 10 | PK, LS, HS |
| `3302:43C9` | `Audiocular-Aura` | 16 | 10 | PK, LS, HS |
| `3302:43CA` | `USB HiFi DSP Audio` | 16 | 10 | PK, LS, HS |
| `3302:43CB` | `1` | 15 | 8 | PK, LS, HS |
| `3302:43CD` | `AURORA` | 16 | 10 | PK, LS, HS |
| `3302:43CF` | `Hakugei-DACpro` | 16 | 10 | PK, LS, HS |
| `3302:43D1` | `1Pro` | 11 | 8 | PK, LS |
| `3302:43D5` | `TT39510B01` | 11 | 8 | PK, LS |
| `3302:43D6` | `Audiocular D11` | 15 | 8 | PK, LS, HS |
| `3302:43D7` | `KM-K420_2` | 16 | 10 | PK, LS, HS |
| `3302:43D8` | `KM-K420_1` | 16 | 10 | PK, LS, HS |
| `3302:43D9` | `K423` | 15 | 8 | PK, LS, HS |
| `3302:43DB` | `TC44Grip` | 16 | 10 | PK, LS, HS |
| `3302:43DC` | `SyLark-AMP01` | 11 | 8 | PK, LS |
| `3302:43DE` | `Campfire Audio` | 16 | 10 | PK, LS, HS |
| `3302:43E1` | `TT39518F01-PRO` | 16 | 10 | PK, LS, HS |
| `3302:43E2` | `LBZ-04` | 15 | 8 | PK, LS, HS |
| `3302:43E3` | `LC7100` | 15 | 8 | PK, LS, HS |
| `3302:43E4` | `KSHF-02(EQ)` | 16 | 10 | PK, LS, HS |
| `3302:43E6` | `TP35 Pro` | 16 | 10 | PK, LS, HS |
| `3302:43E7` | `CELEST CD-2` | 11 | 8 | PK, LS |
| `3302:43EA` | `TBD` | 15 | 8 | PK, LS, HS |
| `3302:43EC` | `Sky-reaching Sword` | 16 | 10 | PK, LS, HS |
| `3302:43EF` | `LC01` | 16 | 10 | PK, LS, HS |
| `3302:44D1` | `Truthear 4493S-2` | 18 | 10 | PK, LS, HS |
| `3302:44D2` | `AK4493SEQ` | 18 | 10 | PK, LS, HS |
| `3302:44D3` | `Truthear 4493S-2` | 18 | 10 | PK, LS, HS |
| `3302:44D6` | `AK4493SEQ HiFi DSP Audio` | 18 | 10 | PK, LS, HS |
| `3302:44D7` | `WHIZZER DA6+` | 18 | 10 | PK, LS, HS |
| `3302:44D9` | `AFUL EMBER-01` | 18 | 10 | PK, LS, HS |
| `3302:51C0` | `TT39510B01` | 11 | 8 | PK, LS |
| `3302:60C0` | `DAE4131HM-TT` | 13 | 10 | PK, LS, HS |
| `3302:60C1` | `384kHz AI ENC Audio` | 13 | 10 | PK, LS, HS |
| `3302:60C3` | `384kHz AI ENC Audio` | 13 | 10 | PK, LS, HS |
| `3302:60C6` | `K67` | 13 | 10 | PK, LS, HS |
| `3302:60C7` | `TBD` | 13 | 10 | PK, LS, HS |
| `3302:60C9` | `C7` | 13 | 10 | PK, LS, HS |
| `3302:60CA` | `X1 Audio-1` | 13 | 10 | PK, LS, HS |
| `3302:60CB` | `FUMO MIX` | 13 | 10 | PK, LS, HS |
| `3302:60D1` | `USB AI ENC Audio` | 13 | 10 | PK, LS, HS |
| `3302:60D2` | `USB AI ENC Audio` | 11 | 8 | PK, LS |
| `3302:60E1` | `TINHIFI AI MIC PRO` | 13 | 10 | PK, LS, HS |
| `3302:9121` | `DAT9121-TT` | 11 | 8 | PK, LS |
| `3302:9123` | `USB C Coaxial` | 11 | 8 | PK, LS |
| `3302:9124` | `USB-C SPDIF` | 11 | 8 | PK, LS |
| `3302:9125` | `TAC550` | 11 | 8 | PK, LS |
| `3302:9201` | `TT39219J01` | 16 | 10 | PK, LS, HS |
| `3302:93C0` | `TT39493F01-PRO` | 11 | 8 | PK, LS |
| `3302:93C1` | `TT39493F01-PRO` | 11 | 8 | PK, LS |
| `3302:93D1` | `TT39493D01-JK` | 11 | 8 | PK, LS |
| `3302:98C0` | `TT39518B01-PRO` | 11 | 8 | PK, LS |
| `3302:98C1` | `TT39510C01-PRO` | 11 | 8 | PK, LS |
| `3302:98C2` | `TT39510C01/TT39518B01` | 11 | 8 | PK, LS |
| `3302:98D1` | `TT39518B01` | 11 | 8 | PK, LS |
| `3302:98D2` | `TT39518B01-PRO` | 11 | 8 | PK, LS |
| `3302:98D4` | `KSHF-01(EQ)` | 16 | 10 | PK, LS, HS |
| `3302:98D5` | `CS43918 HiFi DSP Audio` | 11 | 8 | PK, LS |
| `3302:B301` | `CB1300 + BLE` | 13 | 10 | PK, LS, HS |
| `3302:B302` | `CB1300 方案13 测试有线HID` | 13 | 10 | PK, LS, HS |
| `3302:C204` | `YeYing2` | 11 | 8 | PK, LS |
| `3302:C207` | `J10` | 11 | 8 | PK, LS |
| `3302:C208` | `384kHz 32bit` | 11 | 8 | PK, LS |
| `3302:C209` | `TC50 DSP` | 11 | 8 | PK, LS |
| `3302:C20A` | `DSP.H.268` | 11 | 8 | PK, LS |
| `3302:C210` | `XuanJingF1` | 11 | 8 | PK, LS |
| `3302:C211` | `USB Audio` | 11 | 8 | PK, LS |
| `3302:C212` | `SIGMOT` | 11 | 8 | PK, LS |
| `3302:C213` | `ZS-01` | 11 | 8 | PK, LS |
| `3302:C214` | `NineMeet-JiuPai` | 11 | 8 | PK, LS |
| `3302:C215` | `TBD` | 11 | 8 | PK, LS |
| `3302:C216` | `HiFi-LC` | 11 | 8 | PK, LS |
| `3302:C217` | `G20 Pro` | 11 | 8 | PK, LS |
| `3302:C219` | `K300PRO` | 11 | 8 | PK, LS |
| `3302:C21C` | `6060` | 11 | 8 | PK, LS |
| `3302:C21F` | `Orbit` | 11 | 8 | PK, LS |
| `3302:C223` | `XJ01` | 11 | 8 | PK, LS |
| `3302:C224` | `G-Turbo X1` | 11 | 8 | PK, LS |
| `3302:C227` | `ET-01` | 11 | 8 | PK, LS |
| `3302:C228` | `XIBERIA MX06` | 11 | 8 | PK, LS |
| `3302:C229` | `1` | 11 | 8 | PK, LS |
| `3302:C22B` | `Orion` | 11 | 8 | PK, LS |
| `3302:C22C` | `H-Turbo S1` | 11 | 8 | PK, LS |
| `3302:C22D` | `WHIZZER BEAT DAC` | 11 | 8 | PK, LS |
| `3302:C22F` | `CABLE DAC` | 11 | 8 | PK, LS |
| `3302:C230` | `DITA` | 11 | 8 | PK, LS |
| `3302:C232` | `1` | 11 | 8 | PK, LS |
| `3302:C233` | `H-Turbo S2` | 11 | 8 | PK, LS |
| `3302:C234` | `Song 夏鸣` | 11 | 8 | PK, LS |
| `3302:EE10` | `10段 HIFI EQ` | 16 | 10 | PK, LS, HS |
| `3302:EE20` | `WALKPLAY-10` | 16 | 10 | PK, LS, HS |
| `3302:FF01` | `DAT412BHM-TT Test` | 11 | 8 | PK, LS |
| `34BE:0004` | `TBD` | 11 | 8 | PK, LS |
| `35D8:011B` | `aaaaaa` | 13 | 10 | PK, LS, HS |
| `35D8:011D` | `1` | 16 | 10 | PK, LS, HS |
| `35D8:0123` | `仅验证用` | 13 | 10 | PK, LS, HS |
| `35D8:012A` | `TBD` | 15 | 8 | PK, LS, HS |
| `35D8:98D5` | `MD-QT-033` | 11 | 8 | PK, LS |
| `36A7:A862` | `WL HUAN IEM` | 11 | 8 | PK, LS |
| `373B:120C` | `ATK Horizon` | 13 | 10 | PK, LS, HS |
| `B44D:4302` | `FHG SoundFlex Fusion Series Px` | 11 | 8 | PK, LS |

</details>

### WalkPlay: pairs that need an exact name (3)

These three pairs are write-enabled only when the USB product name equals one of the 13 scheme-15 WalkPlay names. Any other name is blocked.

| VID:PID | Catalog label | Scheme | Bands |
|---|---|---|---|
| `3302:39C1` | `ES9039 ` (trailing space) | 15 | 8 |
| `3302:4353` | `Dual CS43198` | 15 | 8 |
| `3302:43EB` | `BGVP MX1` | 15 | 8 |

Accepted names: `BGVP MX1`, `DT04`, `MD-QT-042`, `MOONDROP HiFi with PD`, `DAWN PRO 2`, `CS431XX`, `ES9039 ` (trailing space), `TANCHJIM-STARGATE II`, `didiHiFi DSP Cable - Memory`, `ddHiFi DSP Cable - Memory`, `Dual CS43198`, `ES9039 HiFi DSP Audio`, `TRUTHEAR KEYX`.

### FiiO (23)

Native HID routes. The report type is the FiiO protocol variant. Rows matched by name on any product ID ask for a tap on Connect before they are used.

| USB name | Matched by | Report | Bands | Gain dB | Consent |
|---|---|---|---|---|---|
| `FIIO QX13` | `2972:0128` + exact name; the same name on any other PID of `2972` / `0A12` also opens the route after a tap on Connect | 7 | 10 | -24..+12 | - |
| `SNOWSKY Melody` | `2972:0126` + exact name; the same name on any other PID of `2972` / `0A12` also opens the route after a tap on Connect | 7 | 10 | -12..+12 | - |
| `JadeAudio JIEZI` | name on any PID of `2972` / `0A12` | 2 | 5 | -12..+12 | tap Connect |
| `JadeAudio JA11` | `2972:0102` + exact name; the same name on any other PID of `2972` / `0A12` also opens the route after a tap on Connect | 2 | 5 | -12..+12 | - |
| `FIIO KA17` | `2972:0093` + exact name; the same name on any other PID of `2972` / `0A12` also opens the route after a tap on Connect | 1 | 10 | -12..+12 | - |
| `FIIO Q7` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `FIIO KA17 (MQA HID)` | name on any PID of `2972` / `0A12` | 1 | 10 | -12..+12 | tap Connect |
| `FIIO BT11` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `FIIO BT11 (UAC1.0)` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `FIIO Air Link` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `FIIO BTR13` | name on any PID of `2972` / `0A12` | 7 | 10 | -12..+12 | tap Connect |
| `FIIO BTR17` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `BTR17` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `FIIO K19` | name on any PID of `2972` / `0A12` | 7 | 31 | -24..+12 | tap Connect |
| `FIIO K17` | name on any PID of `2972` / `0A12` | 7 | 31 | -24..+12 | tap Connect |
| `FIIO K15` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `FIIO BR15 R2R` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `FIIO FP3` | name on any PID of `2972` / `0A12` | 7 | 10 | -24..+12 | tap Connect |
| `SNOWSKY TINY A` | name on any PID of `2972` / `0A12` | 7 | 5 | -12..+12 | tap Connect |
| `SNOWSKY TINY B` | name on any PID of `2972` / `0A12` | 7 | 5 | -12..+12 | tap Connect |
| `FIIO FG3` | name on any PID of `2972` / `0A12` | 7 | 10 | -12..+12 | tap Connect |
| `FIIO LS-TC2` | name on any PID of `2972` / `0A12` | 7 | 5 | -12..+12 | tap Connect |
| `FIIO FX17` | name on any PID of the 19 WalkPlay vendor IDs | 7 | 10 | -24..+12 | tap Connect |

### KT Micro (7)

KT_0211L register map. Five bands, gain -10..+10 dB, no manual pregain, writes go to Custom 3 only.

| USB name | Matched by | Bands | Filter types | Consent |
|---|---|---|---|---|
| `Kiwi Ears-Allegro PRO` | `31B2:0111` + exact name | 5 | PK, LS, HS | - |
| `Kiwi Ears Allegro Mini` | `31B2:0111` + exact name | 5 | PK, LS, HS | - |
| `Kiwi Ears-Allegro Mini` | `31B2:0111` + exact name | 5 | PK, LS, HS | - |
| `KT02H20 HIFI Audio` | `31B2:0111` + exact name | 5 | PK | - |
| `TANCHJIM-ONE DSP` | `31B2:0111` + exact name | 5 | PK, LS, HS | - |
| `CDSP` | name on any PID of `31B2` except `0001`, `1132`, `3006`, `3016` | 5 | PK, LS, HS | tap Connect |
| `Chu2 DSP` | `31B2:0113` + exact name | 5 | PK, LS, HS | - |

## Read-only diagnostics

These read the current EQ and never write. Moondrop names need an explicit tap on Connect. Moondrop and Fosi have no profile import; the FiiO best-guess identities below can also import a profile, but still never write.

### Moondrop (12)

Matched by exact USB name on any product ID of the 19 vendor IDs, except `35D8:1496`, `35D8:149B` and `35D8:011C`, which are refused earlier. Writes are unavailable: the source has a bank conflict, the pregain read and write values differ, and automatic headroom varies by model.

| USB name | Bands | Filter types | Consent |
|---|---|---|---|
| `Rays` | 8 | PK | tap Connect |
| `Marigold` | 8 | PK | tap Connect |
| `FreeDSP Pro` | 8 | PK, LS, HS | tap Connect |
| `MOONRIVER 3` | 8 | PK, LS, HS | tap Connect |
| `FreeDSP Mini` | 8 | PK, LS, HS | tap Connect |
| `DAWN PRO2` | 8 | PK, LS, HS | tap Connect |
| `Echo A` | 8 | PK | tap Connect |
| `AG Rays` | 8 | PK | tap Connect |
| `DHA15` | 8 | PK, LS, HS | tap Connect |
| `Deco Audio System` | 8 | PK, LS, HS | tap Connect |
| `INN Deco75-DH Audio` | 8 | PK, LS, HS | tap Connect |
| `ddHiFi DSP IEM - Memory` | 8 | PK | tap Connect |

### FiiO: best-guess identities (5)

Read and whole-profile import work; writes are blocked because the USB identity is only a best guess ("Best-guess USB identity is read-only"). Rows matched by name on any product ID ask for a tap on Connect.

| USB name | Matched by | Bands | Consent |
|---|---|---|---|
| `FIIO OAK NANO` | `2972:4016` + exact name; the same name on any other PID of `2972` / `0A12` also opens the route after a tap on Connect | 10 | - |
| `Oak Nano` | name on any PID of `2972` / `0A12` | 10 | tap Connect |
| `RETRO NANO` | name on any PID of `2972` / `0A12` | 10 | tap Connect |
| `FIIO QX11` | name on any PID of `2972` / `0A12` | 10 | tap Connect |
| `FIIO AIR AMP` | name on any PID of `2972` / `0A12` | 10 | tap Connect |

### Fosi DS3 (1)

| VID:PID | USB name | Reads | Why not more |
|---|---|---|---|
| `152A:88DB` | `Fosi Audio DS3` | First 8 bands of the active bank | The user-bank layout is unknown (8 or 32 bands; firmware 1.4.15 and later use 32). Writes and whole-profile import need firmware evidence. |

## Recognised but blocked

Contour detects these and then refuses, with the reason shown in the table. Nothing here writes.

### WalkPlay: blocked pairs (13)

| VID:PID | Catalog label | Scheme | Reason |
|---|---|---|---|
| `0663:0880` | `1` | 21 | protocol_conflict_catalog_vs_first_PID_match |
| `0666:0883` | `SPV6040 10-EQ` | 11 | protocol_conflict_catalog_vs_first_PID_match |
| `3302:4302` | `HDK-224` | 16 | protocol_conflict_catalog_vs_first_PID_match |
| `3302:435C` | `DT04` | 16 | potential_protocol_conflict_model_name_override |
| `3302:43C0` | `CS431XX` | 16 | potential_protocol_conflict_model_name_override |
| `3302:43C6` | `CS43198` | 16 | potential_protocol_conflict_model_name_override |
| `3302:43DF` | `ddHiFi DSP IEM - Memory` | 15 | potential_protocol_override_outside_family |
| `3302:43H1` | `TT39510F01` | 15 | invalid identifier (invalid_identifier_block); never matched |
| `3302:8901` | `USB DSP Audio` | 24 | unknown_scheme_block |
| `3302:8902` | `USB Audio` | 24 | unknown_scheme_block |
| `3302:EEEE` | `EQ` | 1 | unknown_scheme_block |
| `35D8:43DA` | `MOONRIVER 3` | 16 | potential_protocol_override_outside_family |
| `373B:129F` | `ATK Horizon` | 19 | protocol_conflict_catalog_vs_first_PID_match |

### WalkPlay: recognised, but no write path (3)

Captured pairs with no catalog row. They are detected, ask for a name, and work only if the USB product name matches one of the WalkPlay name rules (in which case the name rule would apply to any product ID anyway).

| VID:PID | Reason |
|---|---|
| `3302:39C3` | captured pair, no scheme row |
| `3302:4367` | captured pair, no scheme row |
| `3302:43D4` | captured pair, no scheme row |

### WalkPlay: guard names (18)

WalkPlay refuses these exact names so that a device cannot fall through to a WalkPlay write route by accident. The name is handled by another family first, shown in the last column.

| USB name | Scope | Handled by |
|---|---|---|
| `Old Fashioned` | any PID of the 19 vendor IDs | Moondrop (blocked) |
| `FIIO FX17` | any PID of the 19 vendor IDs | FiiO (write enabled) |
| `Rays` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `Marigold` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `MOONDROP Marigold` | any PID of the 19 vendor IDs | Moondrop (blocked) |
| `FreeDSP Pro` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `MOONRIVER 3` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `FreeDSP Mini` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `FreeDSP` | any PID of the 19 vendor IDs | Deny-list (blocked) |
| `DAWN PRO2` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `Echo A` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `ECHO-B` | any PID of the 19 vendor IDs | Deny-list (blocked) |
| `AG Rays` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `DHA15` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `Deco Audio System` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `INN Deco75-DH Audio` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `ddHiFi DSP IEM - Memory` | any PID of the 19 vendor IDs | Moondrop (read-only diagnostics) |
| `Space Gaming IEM` | any PID of `31B2` | Deny-list (blocked) |

### FiiO: blocked (2)

Only recognised, never opened for reading or writing. Both names are caught by the deny-list first.

| USB name | Matched by | Reason |
|---|---|---|
| `FIIO DM15 R2R` | name on any PID of `2972` / `0A12` | FZ=-1: source suspects CDC/serial; standard HID transport unproven |
| `Space Gaming IEM` | name on any PID of `31B2` | Excluded: unknown FiiO-vs-KT firmware/compensate2X policy; no proven capture/identity |

### KT Micro: blocked (4)

The three named rows are blocked on purpose; the fourth rule is the PID row described below the table.

| USB name | Matched by | Reason |
|---|---|---|
| `TANCHJIM BUNNY DSP` | `31B2:1112` | Manual pregain unsupported; upstream write 0x66 contradicts no-pregain constraints; Fission slot 1 is not validated 2/3 |
| `TANCHJIM FISSION` | name on any PID of `31B2` (PID unproven) | Manual pregain unsupported; upstream write 0x66 contradicts no-pregain constraints; Fission slot 1 is not validated 2/3 |
| `TANCHJIM-FISSION  DSP` (double space) | `31B2:1119` | Manual pregain unsupported; upstream write 0x66 contradicts no-pregain constraints; Fission slot 1 is not validated 2/3 |

PIDs `0001`, `1132`, `3006` and `3016` on vendor `31B2` are not claimed by the KT driver at all (it has no usable register map for them). With a name that is not a KT Micro name the device is simply unrecognised. With a KT Micro name it gets the misleading WalkPlay message described under Known limitations. This row is counted among the 4 KT Micro rules above, but it is not a block with a reason of its own.

### Moondrop: blocked (2)

| USB name | Matched by | Reason |
|---|---|---|
| `Old Fashioned` | name on any PID of the 19 vendor IDs | Old Fashioned source accepts ANY inputreport without register/command echo validation; automatic read/import blocked (OLD_BLOCKED_REASON) |
| `MOONDROP Marigold` | `35D8:011C` (any name), or this name on any of the 19 vendor IDs | Pinned MOONDROP Marigold capture 35D8:011C explicitly uses WalkPlay SchemeNo10 ... native read/import blocked (MARIGOLD_BLOCKED_REASON) |

### Deny-list (10)

Checked before everything else. These identities are refused even though some share a vendor ID with a supported family.

| Identity | Matched by | Reason |
|---|---|---|
| - | `0A12:4005` (any name) | Qudelix-5K USB DAC 48KHz blocked: USB receive/readback and firmware-specific framing are unproven. Not FiiO despite shared VID. |
| - | `152A:8750` (any name) | Topping DX1II blocked: channel/output routing, preamp and save/persistence are unresolved. Not Fosi despite shared VID. |
| - | `35D8:1496` (any name) | Conexant PEQ blocked: no native readback; upstream pull fabricates flat bands and writes flash per band. Not WalkPlay. |
| - | `35D8:149B` (any name) | Conexant PEQ blocked: no native readback; upstream pull fabricates flat bands and writes flash per band. Not WalkPlay. |
| - | `1A86:55D3` (any name) | FiiO USB serial blocked: serial transport is not implemented; user-bank and bypass maps conflict. Not a FiiO HID route. |
| - | `152A:88FA` (any name) | JDS Labs USB serial blocked: serial transport is not implemented; 10/12-band layout, input/output routing and preamp policies conflict. Not Fosi. |
| `FreeDSP` | name on any PID of the 19 vendor IDs | Informational exact-name exclusion; PID unqualified. + Conexant reason |
| `ECHO-B` | name on any PID of the 19 vendor IDs | Informational exact-name exclusion; PID unqualified. + Conexant reason |
| `FIIO DM15 R2R` | name on any PID of `2972` / `0A12` | Informational exact-name exclusion; PID unqualified. FiiO DM15 R2R source suspects CDC/serial (FZ=-1); standard HID and native layout unproven. Do not fall through FiiO or WalkPlay. |
| `Space Gaming IEM` | name on any PID of `31B2` | Informational exact-name exclusion; PID unqualified. FiiO-vs-KT firmware, receive traffic and compensate2X/preamp policy unresolved. Do not fall through KT Micro. |

## Not supported

### Transports not implemented

The beta talks to devices over USB HID only. These identities are known but use a serial (CDC) transport that is not implemented, so they are on the deny-list above.

| Identity | Device | Transport |
|---|---|---|
| `1A86:55D3` | FiiO USB serial | USB serial |
| `152A:88FA` | JDS Labs USB serial (reported as Element IV) | USB serial |
| `FIIO DM15 R2R` (name on `2972` / `0A12`) | FiiO DM15 R2R | Suspected CDC / serial |

### Families with no driver in this beta

Contour has no driver for these, so it does not recognise them at all:

- JDS
- EarFun
- Edifier
- FiiO EH11 and EH13
- Moondrop Edge
- Audeze Maxwell
- Luxsin

## Known limitations and issues

- Hardware status: only the four devices in the first level have been tested; the Micro and the Max not yet on this beta build. Everything else with writes enabled is untested, and so are LOW PASS and HIGH PASS on the Protocol Max and TRN Black Pearl.
- `3302:43CC` (CrinEar Protocol Max) resolves in every build to the MAX target, the same code path as stable 1.3.2, which has a community hardware test. It no longer uses the generic WalkPlay row or name rule. The `Protocol Max` name rule on any other product ID still goes to the generic scheme-16 catalog path.
- Exception to the previous point, advanced build only: the Moondrop, FiiO, KT Micro and Fosi checks run before the WalkPlay shortcuts. The FiiO `FIIO FX17` rule includes vendor `3302`, so a `3302:C20F` or `3302:43CC` device that reports the name `FIIO FX17` or a Moondrop name leaves the Micro or Max path. This only happens with a deliberately odd USB name.
- Known issue: a blocked KT Micro name currently shows a misleading message ("KT Micro requires its separate driver"). The device is blocked on purpose; the text is wrong. The same message appears for a KT Micro name on PIDs `0001`, `1132`, `3006` and `3016`.
- Name rules on any product ID cannot be in the Android USB attach filter, so they are detected only while the app is open.
- Catalog labels are display names. A device is matched by VID:PID, and the USB name it reports can be anything non-empty.
- Wide recognition is deliberate: the beta would rather offer a read to an unknown device than ignore it. It is also the reason to read first and keep a backup before any write.
