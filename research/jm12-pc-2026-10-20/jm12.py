#!/usr/bin/env python3
"""PEQ tool for a KTMicro KT02H20 dongle (JCally JM12, USB 31B2:0111), stock firmware.

KTMicro register protocol on HID report 0x4B, as in devicePEQ's ktmicroUsbHidHandler.js:
  band i: reg 0x26+2i = gain (x10, s16 LE) + freq (u16 LE); reg 0x27+2i = Q (x1000, u16 LE) + type (0 = PK)
  reg 0x24 = EQ slot (0x02 off, 0x03 Custom); cmd 0x52 read, 0x57 write, 0x53 commit.
Never touches report 0x54 (bootloader unlock). Needs root for /dev/hidraw*:

  sudo python3 -I jm12.py                          read what the DAC holds (read-only)
  sudo python3 -I jm12.py tone-test                write a notch at raw 1000 Hz + test tones at 48/96 kHz
  sudo python3 -I jm12.py apply EQ.txt             write an Equalizer APO / AutoEQ file (PK only)
  sudo python3 -I jm12.py restore jm12-backup-*.json  put a backup back
  --log (before the command)                       print every report in and out, with timestamps

Every write first saves a backup next to this script and is read back afterwards.
"""
import argparse, glob, json, math, os, re, select, struct, sys, time, wave

REPORT_ID = 0x4B
READ, WRITE, COMMIT = 0x52, 0x57, 0x53
SLOT_REG, BASE_REG, BANDS = 0x24, 0x26, 5
# devicePEQ's preamp register on other KTMicro chips. On the owner's JM12 (factory d0..d3 = fa 02 00 00) a write of
# d0 -6 -> -12 dB was ACKed, read back and survived the commit, but changed nothing audible (2026-10-20): no preamp.
# Read and backed up only, so a restore can put the factory value back.
PREAMP_REG = 0x66
SLOT_OFF, SLOT_CUSTOM = 0x02, 0x03
TYPES = {0: "PK", 3: "LSQ", 4: "HSQ"}
# devicePEQ constraints for this model: 5 PK bands, +-10 dB, Q 0.1-5 (no shelves, no pregain)
GAIN_MAX, Q_RANGE, F_RANGE = 10.0, (0.1, 5.0), (20, 20000)
HERE = os.path.dirname(os.path.abspath(__file__))
LOG = False  # --log: every report in and out, with wall-clock ms to line up with `journalctl -k -o short-precise`


def log(tag, msg):
    if LOG:
        t = time.time()
        print(f"{time.strftime('%H:%M:%S', time.localtime(t))}.{int(t * 1000) % 1000:03d} {tag} {msg}")


def find_hidraw(wait=0.0):
    deadline = time.time() + wait
    while True:
        for d in glob.glob("/sys/class/hidraw/hidraw*"):
            with open(f"{d}/device/uevent") as f:
                if "HID_ID=0003:000031B2:00000111" in f.read():
                    return "/dev/" + os.path.basename(d)
        if time.time() >= deadline:
            sys.exit("KT02H20 (31B2:0111) not found")
        time.sleep(0.2)


def xfer(fd, reg, cmd, data=(0, 0, 0, 0), timeout=1.0):
    """Send one report; return the 10-byte reply echoing reg+cmd, or None."""
    out = bytes([REPORT_ID, reg, 0, 0, 0, cmd, 0, *data])
    os.write(fd, out)
    log("TX", out.hex(" "))
    end = time.time() + timeout
    while select.select([fd], [], [], max(0, end - time.time()))[0]:
        r = os.read(fd, 64)
        log("RX", r.hex(" "))
        if len(r) >= 11 and r[0] == REPORT_ID and r[1] == reg and r[5] == cmd:
            return r[1:11]
    log("--", f"no reply to reg 0x{reg:02x} cmd 0x{cmd:02x} within {timeout} s")
    return None


def s16(lo, hi):
    v = lo | hi << 8
    return v - 0x10000 if v > 0x7FFF else v


def decode_band(gf, qt):
    return s16(gf[6], gf[7]) / 10, gf[8] | gf[9] << 8, (qt[6] | qt[7] << 8) / 1000, TYPES.get(qt[8], f"?{qt[8]}")


def encode_band(gain, raw_freq, q):
    """-> (gain/freq data, q/type data), 4 bytes each, PK type."""
    g = round(gain * 10) & 0xFFFF
    qq = round(q * 1000)
    return (g & 0xFF, g >> 8, raw_freq & 0xFF, raw_freq >> 8), (qq & 0xFF, qq >> 8, 0, 0)


def read_state(fd):
    regs = {SLOT_REG: xfer(fd, SLOT_REG, READ)}
    if regs[SLOT_REG] is None:
        sys.exit("No reply on reg 0x24: this firmware does not answer the KTMicro PEQ protocol "
                 "(try again with music playing; if still nothing, PEQ needs another firmware)")
    for r in range(BASE_REG, BASE_REG + 2 * BANDS):
        regs[r] = xfer(fd, r, READ)
        if regs[r] is None:
            sys.exit(f"No reply on reg 0x{r:02x}")
    regs[PREAMP_REG] = xfer(fd, PREAMP_REG, READ)  # may be None; never fatal
    return regs


def show(regs):
    slot = regs[SLOT_REG][6]
    print(f"EQ slot {slot} ({ {SLOT_OFF: 'OFF', SLOT_CUSTOM: 'Custom'}.get(slot, '?') })")
    for i in range(BANDS):
        g, f, q, t = decode_band(regs[BASE_REG + 2 * i], regs[BASE_REG + 2 * i + 1])
        print(f"  band {i + 1}: {t:3} raw freq {f:5} Hz (x2 = {2 * f:5})  gain {g:+5.1f} dB  Q {q:.3f}")
    p = regs.get(PREAMP_REG)
    print(f"reg 0x66: {p[6:10].hex(' ')}  (factory fa 02 00 00; not a preamp on the JM12)" if p
          else "reg 0x66: no reply")


def own(path):
    """Files written under sudo go back to the real user."""
    if "SUDO_UID" in os.environ:
        os.chown(path, int(os.environ["SUDO_UID"]), int(os.environ["SUDO_GID"]))


def backup(regs):
    path = os.path.join(HERE, time.strftime("jm12-backup-%Y%m%d-%H%M%S.json"))
    with open(path, "w") as f:
        json.dump({f"0x{r:02x}": list(v) for r, v in regs.items() if v is not None}, f, indent=1)
    own(path)
    print(f"backup: {path}")


def write_and_verify(fd, slot, band_data):
    """band_data: {reg: 4 data bytes}. Writes, reads back, commits, re-reads after a possible re-enumeration."""
    if xfer(fd, SLOT_REG, READ)[6] != slot:
        xfer(fd, SLOT_REG, WRITE, (slot, 0, 0, 0))
    for reg, data in band_data.items():
        xfer(fd, reg, WRITE, data, timeout=0.3)  # the JM12 ACKs (d0 = 03); read-back is what decides
    bad = [r for r, d in band_data.items() if tuple(xfer(fd, r, READ)[6:10]) != tuple(d)]
    if bad:
        sys.exit(f"READ-BACK MISMATCH on regs {[hex(r) for r in bad]} - not committed; unplug to drop RAM state")
    print("read-back OK, committing")
    xfer(fd, 0, COMMIT, timeout=2.0)
    os.close(fd)
    log("--", "closed after commit")
    time.sleep(1.5)  # devicePEQ: this family may disconnect after save
    path = find_hidraw(wait=8)
    fd = os.open(path, os.O_RDWR)
    log("--", f"reopened {path}")
    try:
        regs = read_state(fd)
    finally:
        os.close(fd)
    bad = [r for r, d in band_data.items() if regs.get(r) is None or tuple(regs[r][6:10]) != tuple(d)]
    show(regs)
    print("saved OK" if not bad else f"AFTER COMMIT regs {[hex(r) for r in bad]} differ - check the output above")


APO_LINE = re.compile(r"Filter\s*\d*\s*:\s*ON\s+(\w+)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)", re.I)


def parse_apo(text):
    """-> (bands [(freq, gain, q)], preamp dB). Raises ValueError for what the JM12 cannot hold."""
    bands, preamp = [], 0.0
    for line in text.splitlines():
        if m := re.match(r"\s*Preamp\s*:\s*(-?[\d.]+)", line, re.I):
            preamp = float(m[1])
        elif m := APO_LINE.search(line):
            t, f, g, q = m[1].upper(), float(m[2]), float(m[3]), float(m[4])
            if t not in ("PK", "PEQ"):
                raise ValueError(f"{t} filter: JM12 stock firmware takes only PK")
            if not (F_RANGE[0] <= f <= F_RANGE[1] and abs(g) <= GAIN_MAX and Q_RANGE[0] <= q <= Q_RANGE[1]):
                raise ValueError(f"band {f} Hz {g} dB Q {q} outside {F_RANGE} Hz, +-{GAIN_MAX} dB, Q {Q_RANGE}")
            bands.append((f, g, q))
    if not bands or len(bands) > BANDS:
        raise ValueError(f"{len(bands)} bands; JM12 holds 1..{BANDS}")
    return bands, preamp


def bands_to_regs(bands, freq_div, current):
    """Unused tail bands keep their freq/Q and go to 0 dB."""
    out = {}
    for i in range(BANDS):
        rg, rq = BASE_REG + 2 * i, BASE_REG + 2 * i + 1
        if i < len(bands):
            f, g, q = bands[i]
            out[rg], out[rq] = encode_band(g, round(f / freq_div), q)
        else:
            out[rg] = (0, 0, *current[rg][8:10])
            out[rq] = tuple(current[rq][6:10])
    return out


TONE_RATES, TONE_HZ = (48000, 96000), (1000, 2000, 4000)


def tone_wavs():
    """1, 2 and 4 kHz sines at -20 dBFS, 3 s each, at 48 and 96 kHz, in this folder."""
    for rate in TONE_RATES:
        for hz in TONE_HZ:
            p = os.path.join(HERE, f"tone-{rate // 1000}k-{hz}.wav")
            with wave.open(p, "wb") as w:
                w.setnchannels(2), w.setsampwidth(2), w.setframerate(rate)
                a = 0.1 * 32767
                w.writeframes(b"".join(struct.pack("<hh", s, s) for s in
                                       (round(a * math.sin(2 * math.pi * hz * n / rate)) for n in range(rate * 3))))
            own(p)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd")
    a = sub.add_parser("apply")
    a.add_argument("file")
    # x2 is fixed on the owner's JM12: same sound at 96 kHz and at a forced 44.1 kHz stream (ear test 2026-10-20)
    a.add_argument("--freq-div", type=int, choices=(1, 2), default=2,
                   help="2 (default) = DAC plays twice the stored Hz, as measured on the JM12; 1 = stored Hz as-is")
    sub.add_parser("tone-test")
    r = sub.add_parser("restore")
    r.add_argument("file")
    ap.add_argument("--log", action="store_true", help="print every report sent and received, with timestamps")
    args = ap.parse_args()
    global LOG
    LOG = args.log

    fd = os.open(find_hidraw(), os.O_RDWR)
    regs = read_state(fd)
    show(regs)
    if args.cmd is None:
        return os.close(fd)

    if args.cmd == "restore":
        saved = {int(k, 16): v for k, v in json.load(open(args.file)).items()}
        band_data = {reg: tuple(saved[reg][6:10]) for reg in range(BASE_REG, BASE_REG + 2 * BANDS)}
        if PREAMP_REG in saved and regs[PREAMP_REG] is not None:
            band_data[PREAMP_REG] = tuple(saved[PREAMP_REG][6:10])
        slot = saved[SLOT_REG][6]
    elif args.cmd == "tone-test":
        # One deep narrow notch at RAW 1000: whichever tone gets quieter tells the scale.
        band_data = bands_to_regs([(1000, -10.0, 4.0)], 1, regs)
        slot = SLOT_CUSTOM
    else:
        bands, preamp = parse_apo(open(args.file).read())
        if preamp:
            print(f"note: preamp {preamp} dB ignored - the JM12 stock firmware has no preamp; lower the volume instead")
        band_data = bands_to_regs(bands, args.freq_div, regs)
        slot = SLOT_CUSTOM

    backup(regs)
    write_and_verify(fd, slot, band_data)
    if args.cmd == "tone-test":
        tone_wavs()
        # aplay to hw: bypasses PipeWire, which would resample everything to 48 kHz and spoil the 96 kHz test
        print("\nNow WITHOUT sudo, nothing else playing on the JM12. For each rate, which tone is much quieter?")
        for rate in TONE_RATES:
            files = " ".join(os.path.join(HERE, f"tone-{rate // 1000}k-{hz}.wav") for hz in TONE_HZ)
            print(f"  {rate // 1000} kHz:  for f in {files}; aplay -D hw:CARD=Audio,DEV=0 $f; end")
        print("Notch on 2 kHz at both rates -> fixed x2.  Moves to 4 kHz at 96 kHz -> depends on the stream rate.\n"
              "Afterwards re-apply your preset (tone-test replaced it).")


def _selftest():
    # devicePEQ capture of a JCally KT02H20: band 1 = -4.0 dB, raw freq 150, Q 3.0, PK
    gf, qt = bytes([38, 0, 0, 0, 82, 0, 216, 255, 150, 0]), bytes([39, 0, 0, 0, 82, 0, 184, 11, 0, 0])
    assert decode_band(gf, qt) == (-4.0, 150, 3.0, "PK")
    assert encode_band(-4.0, 150, 3.0) == ((216, 255, 150, 0), (184, 11, 0, 0))
    bands, pre = parse_apo("Preamp: -2.0 dB\nFilter 1: ON PK Fc 60 Hz Gain 2.0 dB Q 0.700\n"
                           "Filter 2: ON PK Fc 7100 Hz Gain -8.0 dB Q 1.400\n")
    assert bands == [(60, 2.0, 0.7), (7100, -8.0, 1.4)] and pre == -2.0
    cur = {r: bytes([r, 0, 0, 0, 82, 0, 9, 9, 50, 0]) for r in range(BASE_REG, BASE_REG + 10)}
    regs = bands_to_regs(bands, 2, cur)
    assert regs[0x26] == encode_band(2.0, 30, 0.7)[0] and regs[0x29] == encode_band(-8.0, 3550, 1.4)[1]
    assert regs[0x28 + 2] == (0, 0, 50, 0) and regs[0x2B] == (9, 9, 50, 0)  # tail: 0 dB, freq/Q kept
    for bad in ("Filter 1: ON LSC Fc 60 Hz Gain 2 dB Q 0.7", "Filter 1: ON PK Fc 60 Hz Gain 11 dB Q 0.7"):
        try:
            parse_apo(bad)
            raise AssertionError(bad)
        except ValueError:
            pass


if __name__ == "__main__":
    _selftest()
    main()
