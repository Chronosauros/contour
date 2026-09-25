"""Round-trip write test for CrinEar Protocol Micro. NOT RUN FOR REAL YET.

Sequence: read -> write same state with band 1 gain -0.5 dB -> read back + compare
          -> restore original -> read back + compare.

--mode volatile  (default) per-filter writes [01 09 18 ...] + global gain [01 03 ...] only,
                 NO commit sequence. Unverified whether the firmware applies these live
                 without the commit; the read-back tells us whether the registers changed.
--mode devicepeq exactly devicePEQ pushToDevice(), incl. [01 05],[01 17],[01 0A ..],[01 01 01]
                 which PERSISTS to flash (the restore also persists).
--dry-run        print every report, send nothing (uses --state JSON instead of reading).

Restore writes the ORIGINAL raw register values (freq/Q/gain/type) and recomputes the
biquad from them; compute_iir was verified bit-exact against all 8 stored biquads.
"""
import argparse
import json
import sys
import time

import walkplay as wp


def write_state(dev, filters, preamp, slot, mode):
    for f in filters:
        r = f["raw"]
        dev.send(wp.filter_write_payload(f["index"], r["freq"], r["gain_x256"] / 256,
                                         r["q_x256"] / 256, wp.TYPE_NAMES[r["type"]], slot))
        time.sleep(0.020)
    time.sleep(0.100)
    dev.send(wp.global_gain_payload(preamp))
    time.sleep(0.050)
    if mode == "devicepeq":
        delays = [0.020, 0.020, 0.050, 0]
        for p, d in zip(wp.COMMIT_SEQUENCE, delays):
            dev.send(p)
            time.sleep(d)
    time.sleep(0.2)


def regs(state):
    return [(f["index"], f["raw"]["freq"], f["raw"]["q_x256"], f["raw"]["gain_x256"], f["raw"]["type"])
            for f in state["filters"]] + [("preamp", state["preamp_db"])]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--mode", choices=["volatile", "devicepeq"], default="volatile")
    ap.add_argument("--state", default="device-backup-2026-09-25.json", help="state used in --dry-run")
    ap.add_argument("--log", default=None)
    a = ap.parse_args()
    log = wp.Log(a.log)
    dev = wp.Device(log, dry_run=a.dry_run)
    ok = True
    try:
        if a.dry_run:
            orig = json.load(open(a.state, encoding="utf-8"))
            print(f"# dry-run: state from {a.state}; the real run starts with the read sequence")
        else:
            orig = wp.read_all(dev)
            if orig["missing_filters"]:
                raise SystemExit("incomplete read, aborting before any write")
        slot = orig["current_slot"]
        mod = json.loads(json.dumps(orig))
        g = mod["filters"][0]["raw"]["gain_x256"] - 128           # -0.5 dB
        if g < -10 * 256:
            raise SystemExit("band 1 already at -10 dB, refusing")
        mod["filters"][0]["raw"]["gain_x256"] = g
        print(f"# step 1: write modified state (band 1 gain {orig['filters'][0]['raw']['gain_x256']/256} -> {g/256} dB), mode={a.mode}")
        write_state(dev, mod["filters"], mod["preamp_db"], slot, a.mode)
        if not a.dry_run:
            back = wp.read_all(dev)
            same = regs(back) == regs(mod)
            print("# read-back matches modified:", same)
            ok &= same
        print("# step 2: restore original state")
        write_state(dev, orig["filters"], orig["preamp_db"], slot, a.mode)
        if not a.dry_run:
            back = wp.read_all(dev)
            same = regs(back) == regs(orig)
            print("# read-back matches original:", same)
            ok &= same
    finally:
        dev.close()
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
