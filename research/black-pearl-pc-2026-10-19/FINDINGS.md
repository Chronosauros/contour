# TRN Black Pearl on the PC - 19.10.2026

Owner's unit, Windows 11, Python hidapi (`py`, 3.14). Tools here: `bp_read.py` (read only), `bp_write.py`
(matrix / factory), `bp_fit_rate.py` (design rate from stored biquads), `wait-replug.ps1`. Raw TX/RX in `logs/`,
states in `*.json`.

## Enumeration
- USB `3302:43E8`, product string "TE-C". Interface 0 = HID (one collection, usage page
  0x0C usage 0x01, report 0x4B), interface 1 = audio.
- **It does not enumerate at all without headphones plugged in** (nothing on the bus, not even a failed device).
  Plug headphones in, then it appears. Contour must say so when no DAC is found.

## Factory state (read 1, before any write)
- VERSION "0.6", bulk slot byte 0, preamp 0 dB.
- 10 bands, all PK, 0 dB, register Q 182/256 (0.711), 31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz.
- Stored biquads fit RBJ at **96 kHz** with Q **0.75** (not the 0.711 in the register). 44.1 / 48 / 192 kHz do not
  fit. This settles the design-rate conflict noted in PROTOCOL.md (the GPL BlackPearlControl comparator) in favour
  of Contour's 96 kHz.

## Write test (write sequence of FreeDSP-Studio dsp2.rs write_bank, MIT)
Band frames `01 09 21 | 00 i 00 00 biquad[20] freq q gain type 00 slot` 25 ms apart, commit `01 0A 04 00 00 FF FF`,
50 ms, preamp `01 03 02 00 g`, 50 ms, `01 01 00` (EQ flash), 200 ms, `01 04 00` (offset/preamp flash), 200 ms.
Slot byte = bulk-read slot (0). Biquads = Contour `WalkPlay.computeIir` (LP/HP = plain RBJ).

Matrix written and read back (`after-matrix-104944.json`): **every register and every biquad byte-identical** to
what was sent:
- LSQ 105 Hz +6 Q 0.707 (code 1), HSQ 8 kHz +4 Q 0.707 (code 3), PK +10 Q 1, PK -10 Q 10, PK 3 kHz +2.3 Q 2,
  PK 6 kHz -3.7 Q 4, PK 12 kHz 0 Q 0.75.
- **LP 18 kHz Q 0.707 (code 4) and HP 30 Hz Q 0.707 (code 5) stored as sent** - type byte kept, not coerced to PK.
  (devicePEQ / FreeDSP list scheme 16 as PK/LS/HS only; the firmware takes pass types.)
- PK 500 Hz **-10.4 dB Q 0.1 stored as sent** - no firmware clamp at +-10 dB (the TRN app also stores -10.4).
- Preamp -12 dB.
- The firmware does not recompute coefficients: what is stored is what runs.

Persistence: unplug/replug (headphones stayed in) -> `read-3-persist.json` identical to the written state,
**including preamp -12** (`01 04 00` flash). Not tested: whether the preamp also persists WITHOUT `01 04 00`
(Contour advBeta's devicePEQ sequence `05`, `17`, `0A`, `01 01` never sends it).

Restore: factory flat written back (register Q 182/256, preamp 0), verified; registers equal read 1. Biquads now
use Q 0.711 instead of the factory 0.75 - at 0 dB a peaking band is unity either way.

Pitfall: a read once missed band 1 (stale reply in the queue); re-asking the missing band alone after a drain fixed it.

## Not yet proven
- Audible response (needs the UMC202HD measurement) - register/biquad identity only.
- Contour on the phone (Pixel not on adb on 19.10 morning).
