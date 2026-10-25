# Stale proofs

Cells whose proof log no longer replays: the app's frames changed after the hardware run. Each is awaiting re-proof on hardware (plan v2, step 2);
a new PASS log then replaces the old one and the line goes. `ProvenLogReplayTest` fails on a differing cell that is not listed here, and on a
listed cell that matches again. Line format: `- <dac>/<function>: <commit that changed the frames> - <what>`.

- allegro-mini/eq-full: a937516 - frequency register = intended f x 24000/48000 (the old log is the pre-rescale encoding); proven on a phone (Pixel 9 Pro, run 2026-10-25-contour16-r7-phone-nothing), Mac log at the next desk
- dawn-pro-2/eq-ls: scale applied after the measurement; acoustic law from run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k (the shelf register is now intended Q x sqrt2, the log holds the unscaled register)
- dawn-pro-2/eq-hs: scale applied after the measurement; acoustic law from run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k (the shelf register is now intended Q x sqrt2, the log holds the unscaled register)
