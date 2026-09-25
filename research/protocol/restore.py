"""Restore device-backup JSON with devicePEQ's full commit sequence, then read back."""
import json, sys, walkplay as wp
from write_test import write_state
log = wp.Log(sys.argv[2] if len(sys.argv) > 2 else None)
s = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "device-backup-2026-09-25.json", encoding="utf-8"))
dev = wp.Device(log)
try:
    write_state(dev, s["filters"], s["preamp_db"], s["current_slot"], "devicepeq")
    print(wp.to_apo(wp.read_all(dev)))
finally:
    dev.close()
