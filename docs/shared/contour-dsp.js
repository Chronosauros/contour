/*
  contour-dsp.js - the Contour app's response maths and Protocol Micro limits, in plain JS.
  Classic <script> (window.ContourDSP) and node (module.exports). No build step.

  Ported line by line from the app (keep them in step):
    android/core/.../core/Dsp.kt      Dsp (RBJ cookbook biquads at 96 kHz, shelf slope S = band Q), Preamp.auto
    android/core/.../core/Model.kt    Band, FilterType
    android/core/.../core/Device.kt   ProtocolMicro limits, deviceBands, highShelfGainSum, devicePreamp
    android/app/.../model/AppModel.kt shownPreamp, widestGapMiddle, flatBand, addBand, setPreamp, seeds
    android/app/.../ui/kit/Scales.kt  Scale (FREQ / GAIN / Q), Fmt
    android/app/.../model/Importer.kt round1

  Self-check: `node contour-dsp.js` prints response values and identity checks.
*/
(function (root) {
  "use strict";

  var FS = 96000, FMIN = 20, FMAX = 20000, DISPLAY_POINTS = 512;
  var PI = Math.PI;

  var TYPES = ["PEAK", "LOW_SHELF", "HIGH_SHELF", "LOW_PASS", "HIGH_PASS"];

  /** Log-spaced frequencies (Dsp.logFreqs). */
  function logFreqs(n, fMin, fMax) {
    fMin = fMin == null ? FMIN : fMin; fMax = fMax == null ? FMAX : fMax;
    var span = Math.log(fMax / fMin), out = new Float64Array(n);
    for (var i = 0; i < n; i++) out[i] = fMin * Math.exp(span * i / (n - 1));
    return out;
  }

  /** Frequencies with cos(w), cos(2w) at FS (Dsp.FreqGrid). */
  function FreqGrid(freqs) {
    this.freqs = freqs;
    this.size = freqs.length;
    this.cosW = new Float64Array(freqs.length);
    this.cos2W = new Float64Array(freqs.length);
    for (var i = 0; i < freqs.length; i++) {
      this.cosW[i] = Math.cos(2 * PI * freqs[i] / FS);
      this.cos2W[i] = Math.cos(4 * PI * freqs[i] / FS);
    }
  }

  var DISPLAY_GRID = new FreqGrid(logFreqs(DISPLAY_POINTS));
  var SCAN_GRID = new FreqGrid(logFreqs(1024));

  function InvalidEq(msg) { this.name = "InvalidEq"; this.message = msg; }
  InvalidEq.prototype = Object.create(Error.prototype);

  /** Normalised biquad {b0,b1,b2,a1,a2} (a0 = 1), Dsp.biquad. Throws InvalidEq when there is no real response. */
  function biquad(band, fs) {
    fs = fs || FS;
    var w0 = 2 * PI * band.freqHz / fs;
    var c = Math.cos(w0), s = Math.sin(w0);
    var alpha = s / (2 * band.q); // peak and pass filters only; shelves use slope S = Q
    var a = Math.pow(10, band.gainDb / 40);
    var shelfAlpha = 0;
    if (band.type === "LOW_SHELF" || band.type === "HIGH_SHELF") {
      var radicand = (a + 1 / a) * (1 / band.q - 1) + 2;
      if (!(isFinite(radicand) && radicand >= 0)) throw new InvalidEq("Shelf gain/Q has no real response");
      shelfAlpha = (s / 2) * Math.sqrt(radicand);
    }
    var b0, b1, b2, a0, a1, a2, k;
    switch (band.type) {
      case "PEAK":
        b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a;
        a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a;
        break;
      case "LOW_SHELF":
        k = 2 * Math.sqrt(a) * shelfAlpha;
        b0 = a * ((a + 1) - (a - 1) * c + k); b1 = 2 * a * ((a - 1) - (a + 1) * c);
        b2 = a * ((a + 1) - (a - 1) * c - k); a0 = (a + 1) + (a - 1) * c + k;
        a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - k;
        break;
      case "HIGH_SHELF":
        k = 2 * Math.sqrt(a) * shelfAlpha;
        b0 = a * ((a + 1) + (a - 1) * c + k); b1 = -2 * a * ((a - 1) + (a + 1) * c);
        b2 = a * ((a + 1) + (a - 1) * c - k); a0 = (a + 1) - (a - 1) * c + k;
        a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - k;
        break;
      case "LOW_PASS":
        b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2;
        a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha;
        break;
      case "HIGH_PASS":
        b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2;
        a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha;
        break;
      default: throw new InvalidEq("Unknown filter type " + band.type);
    }
    var r = { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 };
    if (![r.b0, r.b1, r.b2, r.a1, r.a2].every(isFinite)) throw new InvalidEq("Band has no finite response");
    return r;
  }

  /** Adds one band's response in dB to out (Dsp.addBandDb). */
  function addBandDb(band, grid, out) {
    var q = biquad(band);
    var nk = q.b0 * q.b0 + q.b1 * q.b1 + q.b2 * q.b2;
    var n1 = 2 * (q.b0 * q.b1 + q.b1 * q.b2);
    var n2 = 2 * q.b0 * q.b2;
    var dk = 1 + q.a1 * q.a1 + q.a2 * q.a2;
    var d1 = 2 * (q.a1 + q.a1 * q.a2);
    var d2 = 2 * q.a2;
    var cw = grid.cosW, c2w = grid.cos2W;
    for (var i = 0; i < out.length; i++) {
      var num = nk + n1 * cw[i] + n2 * c2w[i];
      var den = dk + d1 * cw[i] + d2 * c2w[i];
      out[i] += 10 * Math.log10(num / den);
    }
  }

  /** Total response in dB of the enabled bands (Dsp.responseDb). Throws InvalidEq. */
  function responseDb(bands, grid, out) {
    grid = grid || DISPLAY_GRID;
    out = out || new Float64Array(grid.size);
    out.fill(0);
    for (var i = 0; i < bands.length; i++) if (bands[i].enabled !== false) addBandDb(bands[i], grid, out);
    for (var j = 0; j < out.length; j++) if (!isFinite(out[j])) throw new InvalidEq("Profile has no finite response");
    return out;
  }

  /** One band's own curve (Dsp.bandDb), disabled bands included. */
  function bandDb(band, grid) {
    grid = grid || DISPLAY_GRID;
    var out = new Float64Array(grid.size);
    addBandDb(band, grid, out);
    return out;
  }

  function maxOf(arr) { var m = -Infinity; for (var i = 0; i < arr.length; i++) if (arr[i] > m) m = arr[i]; return m; }

  /** Preamp.auto: whole dB, never positive. Max over 1 024 log points plus every enabled band's centre. */
  function autoPreamp(bands) {
    var active = bands.filter(function (b) { return b.enabled !== false; });
    if (!active.length) return 0;
    var centres = new FreqGrid(Float64Array.from(active.map(function (b) { return b.freqHz; })));
    var max = Math.max(maxOf(responseDb(active, SCAN_GRID)), maxOf(responseDb(active, centres)));
    if (!isFinite(max)) throw new InvalidEq("AUTO preamp needs a finite response");
    return max > 0 ? -Math.ceil(max - 1e-6) : 0;
  }

  /* ---- CrinEar Protocol Micro (Device.kt) ---- */
  var CAPS = {
    name: "CrinEar Protocol Micro", bands: 8,
    gainMinDb: -10, gainMaxDb: 10, qMin: 0.1, qMax: 10, freqMinHz: 20, freqMaxHz: 20000,
    types: ["PEAK", "LOW_SHELF", "HIGH_SHELF"], preampWholeDb: true,
    preampMinDb: -30, preampMaxDb: 0,
  };

  function copyBand(b, over) { var o = {}; for (var k in b) o[k] = b[k]; for (var k2 in over) o[k2] = over[k2]; return o; }

  /** HIGH SHELF as the device gets it: LOW SHELF, same freq and Q, negated gain (ProtocolMicro.deviceBands). */
  function deviceBands(bands) {
    return bands.map(function (b) { return b.type === "HIGH_SHELF" ? copyBand(b, { type: "LOW_SHELF", gainDb: -b.gainDb }) : b; });
  }

  /** Sum of the gains of the enabled HIGH SHELF bands (ProtocolMicro.highShelfGainSum). */
  function highShelfGainSum(bands) {
    var s = 0;
    bands.forEach(function (b) { if (b.enabled !== false && b.type === "HIGH_SHELF") s += b.gainDb; });
    return s;
  }

  function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }

  /** Device preamp register, whole dB (ProtocolMicro.devicePreamp). preampDb null = AUTO. Throws InvalidEq. */
  function devicePreamp(bands, preampDb) {
    var raw = preampDb == null ? autoPreamp(deviceBands(bands)) : Math.floor(preampDb + highShelfGainSum(bands) + 1e-6);
    return clamp(raw, CAPS.preampMinDb, CAPS.preampMaxDb);
  }

  /** What the PREAMP row shows: device register minus the HS gains (AppModel.shownPreamp). Throws InvalidEq. */
  function shownPreamp(profile) {
    return devicePreamp(profile.bands, profile.preampDb) - highShelfGainSum(profile.bands);
  }

  /** shownPreamp or null when the EQ has no finite response. */
  function tryShownPreamp(profile) { try { return shownPreamp(profile); } catch (e) { if (e instanceof InvalidEq) return null; throw e; } }

  /** Manual preamp clamp range in the curve domain (AppModel.setPreamp). */
  function preampRange(bands) {
    var hs = highShelfGainSum(bands);
    return [CAPS.preampMinDb - hs, CAPS.preampMaxDb - hs];
  }

  /** Importer.round1 (Kotlin roundToLong = floor(x + 0.5)). */
  function round1(x) { return Math.floor(x * 10 + 0.5) / 10; }

  /* ---- Scales (ui/kit/Scales.kt) ---- */
  function Scale(o) { for (var k in o) this[k] = o[k]; }
  Scale.prototype.perPos = function (v) { return this.log ? clamp(v, this.min, this.max) * Math.log(this.max / this.min) : this.max - this.min; };
  Scale.prototype.toPos = function (v) {
    var c = clamp(v, this.min, this.max);
    return this.log ? Math.log(c / this.min) / Math.log(this.max / this.min) : (c - this.min) / (this.max - this.min);
  };
  Scale.prototype.fromPos = function (p) {
    var c = clamp(p, 0, 1);
    return this.log ? this.min * Math.exp(c * Math.log(this.max / this.min)) : this.min + c * (this.max - this.min);
  };
  Scale.prototype.quantize = function (v) {
    var q = this.quantum;
    var r = clamp(Math.floor(v / q + 0.5) * q, this.min, this.max);
    var digits = q >= 1 ? 0 : q >= 0.1 ? 1 : 2, p = Math.pow(10, digits);
    return Math.round(r * p) / p;
  };

  var ISO_THIRDS = [20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250,
    1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000];
  var SCALES = {
    FREQ: new Scale({ min: 20, max: 20000, log: true, quantum: 1, reset: null, fineMax: 5,
      labels: [[20, "20"], [100, "100"], [1000, "1K"], [10000, "10K"], [20000, "20K"]], ticks: ISO_THIRDS }),
    GAIN: new Scale({ min: -10, max: 10, log: false, quantum: 0.1, reset: 0, fineMax: null,
      labels: [[-10, "-10"], [-5, "-5"], [0, "0"], [5, "+5"], [10, "+10"]] }),
    Q: new Scale({ min: 0.1, max: 10, log: true, quantum: 0.01, reset: 0.71, fineMax: null,
      labels: [[0.1, "0.1"], [0.3, "0.3"], [1, "1"], [3, "3"], [10, "10"]] }),
  };

  /* ---- Fmt (Scales.kt): 85 Hz, 12 450 Hz (thin space U+2009), -4.5 dB, +3.0 dB, 0.0 dB, 6.05 ---- */
  var THIN = " ";
  var Fmt = {
    THIN: THIN,
    freq: function (hz) {
      var n = Math.round(hz);
      return n >= 1000 ? Math.floor(n / 1000) + THIN + String(n % 1000).padStart(3, "0") : String(n);
    },
    gain: function (db) {
      var r = Math.round(db * 10) / 10;
      if (Math.abs(r) < 0.05) return "0.0";
      return r > 0 ? "+" + r.toFixed(1) : "-" + (-r).toFixed(1);
    },
    q: function (q) { return q.toFixed(2); },
    preamp: function (db) { return db === 0 ? "0" : db > 0 ? "+" + db : "-" + (-db); },
  };

  /* ---- Params (ui/tune/Params.kt) ---- */
  var PARAMS = {
    FREQ: { label: "FREQ", unit: "Hz", key: "freqHz", scale: SCALES.FREQ, home: 1000, number: Fmt.freq,
      edit: function (v) { return String(Math.round(v)); } },
    GAIN: { label: "GAIN", unit: "dB", key: "gainDb", scale: SCALES.GAIN, home: 0, number: Fmt.gain,
      edit: function (v) { return Fmt.gain(v).replace(/^\+/, ""); } },
    Q: { label: "Q", unit: "", key: "q", scale: SCALES.Q, home: 1, number: Fmt.q, edit: Fmt.q },
    PREAMP: { label: "PREAMP", unit: "dB", key: null, scale: null, home: 0, number: Fmt.gain,
      edit: function (v) { return Fmt.gain(v).replace(/^\+/, ""); } },
  };
  /** Typed text -> stored value, clamped and quantised (Param.parse); null when not a number. */
  function parseParam(name, text) {
    var v = parseFloat(String(text).trim().replace(",", "."));
    if (!isFinite(v)) return null;
    if (name === "PREAMP") return Math.round(v * 10) / 10;
    var s = PARAMS[name].scale;
    return s.quantize(clamp(v, s.min, s.max));
  }

  /* ---- AppModel helpers ---- */
  var MAX_BANDS = 8;
  var bandSeq = 0;
  function bandId() { bandSeq++; return "b" + Date.now().toString(36).slice(-4) + bandSeq; }
  function band(type, f, g, q) { return { id: bandId(), type: type, freqHz: f, gainDb: g, q: q, enabled: true }; }
  /** PEAK 1 kHz, 0 dB, Q 0.71 (AppModel.flatBand). */
  function flatBand() { return band("PEAK", 1000, 0, 0.71); }

  /** Widest log gap between band frequencies in 20 Hz - 20 kHz, its geometric middle (AppModel.widestGapMiddle). */
  function widestGapMiddle(freqs) {
    if (!freqs.length) return 1000;
    var pts = [20, 20000].concat(freqs.map(function (f) { return clamp(f, 20, 20000); })).sort(function (a, b) { return a - b; });
    var best = 0, mid = 1000;
    for (var i = 0; i < pts.length - 1; i++) {
      var g = Math.log(pts[i + 1] / pts[i]);
      if (g > best) { best = g; mid = Math.sqrt(pts[i] * pts[i + 1]); }
    }
    return mid;
  }

  /** A new PEAK band as AppModel.addBand makes it (Q 0.71, whole Hz, 0.1 dB). */
  function newBand(freq, gain, existing) {
    var f = freq == null ? widestGapMiddle((existing || []).map(function (b) { return b.freqHz; })) : freq;
    return band("PEAK", Math.round(clamp(f, 20, 20000)), round1(clamp(gain || 0, -10, 10)), 0.71);
  }

  /* Seed profiles, exactly as AppModel.load() creates them on first launch (nightfallBands, duskBands). */
  var PROFILES = {
    DUSK: function () {
      return { name: "DUSK", subtitle: "MOONDROP DUSK DEFAULT DSP", icon: "sun", preampDb: null,
        bands: [band("PEAK", 1400, -3, 0.8), band("PEAK", 5400, -3, 2), band("PEAK", 14000, -5, 2)] };
    },
    NIGHTFALL: function () {
      return { name: "NIGHTFALL", subtitle: "CRINEAR NIGHTFALL", icon: "moon", preampDb: null,
        bands: [band("PEAK", 6207, -3, 3.9), band("PEAK", 12450, -4.5, 6.05), band("PEAK", 15911, 3, 6.3)] };
    },
    FLAT: function () {
      return { name: "PROFILE 1", subtitle: "", icon: "headphones", preampDb: null, bands: [flatBand()] };
    },
  };

  /** CLEAR EQ state (AppModel.isClear). */
  function isClear(p) {
    return p.preampDb == null && p.bands.length === 1 && p.bands[0].enabled !== false && p.bands[0].gainDb === 0 && p.bands[0].type === "PEAK";
  }

  var api = {
    FS: FS, FMIN: FMIN, FMAX: FMAX, DISPLAY_POINTS: DISPLAY_POINTS, TYPES: TYPES,
    logFreqs: logFreqs, FreqGrid: FreqGrid, DISPLAY_GRID: DISPLAY_GRID, InvalidEq: InvalidEq,
    biquad: biquad, addBandDb: addBandDb, responseDb: responseDb, bandDb: bandDb, autoPreamp: autoPreamp,
    CAPS: CAPS, deviceBands: deviceBands, highShelfGainSum: highShelfGainSum, devicePreamp: devicePreamp,
    shownPreamp: shownPreamp, tryShownPreamp: tryShownPreamp, preampRange: preampRange, round1: round1,
    SCALES: SCALES, Fmt: Fmt, PARAMS: PARAMS, parseParam: parseParam,
    MAX_BANDS: MAX_BANDS, bandId: bandId, flatBand: flatBand, newBand: newBand, widestGapMiddle: widestGapMiddle,
    PROFILES: PROFILES, isClear: isClear, clamp: clamp,
  };

  root.ContourDSP = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;

  /* ---- self-check: node contour-dsp.js ---- */
  if (typeof require !== "undefined" && typeof module !== "undefined" && require.main === module) {
    var ok = true;
    function check(name, cond, extra) { console.log((cond ? "ok   " : "FAIL ") + name + (extra ? "  " + extra : "")); if (!cond) ok = false; }
    function at(bands, f) { return responseDb(bands, new FreqGrid(Float64Array.of(f)))[0]; }
    var P = function (t, f, g, q) { return { id: "x", type: t, freqHz: f, gainDb: g, q: q, enabled: true }; };

    var dusk = PROFILES.DUSK().bands, night = PROFILES.NIGHTFALL().bands;
    console.log("DUSK response (dB):");
    [20, 100, 1000, 1400, 3000, 5400, 10000, 14000, 20000].forEach(function (f) { console.log("  " + String(f).padStart(5) + " Hz  " + at(dusk, f).toFixed(6)); });
    console.log("NIGHTFALL response (dB):");
    [1000, 6207, 12450, 15911].forEach(function (f) { console.log("  " + String(f).padStart(5) + " Hz  " + at(night, f).toFixed(6)); });

    // PEAK at its own centre = gainDb (RBJ peak: |H(w0)| = A^2 exactly; tolerance 1e-6 dB covers the
    // double rounding of the cos-expansion near DC, which the Kotlin shares - same formula, same doubles)
    [[1000, 6, 1], [100, -10, 0.1], [15000, 10, 10], [5400, -3, 2]].forEach(function (c) {
      var v = at([P("PEAK", c[0], c[1], c[2])], c[0]);
      check("PEAK " + c[0] + " Hz " + c[1] + " dB Q" + c[2] + " at centre = gain", Math.abs(v - c[1]) < 1e-6, v.toFixed(12));
    });
    // LOW SHELF / HIGH SHELF plateaus
    var ls = [P("LOW_SHELF", 200, 6, 0.71)], hs = [P("HIGH_SHELF", 2000, 6, 0.71)];
    check("LOW SHELF 200 Hz +6: 20 Hz ~ +6", Math.abs(at(ls, 20) - 6) < 0.05, at(ls, 20).toFixed(4));
    check("LOW SHELF 200 Hz +6: 20 kHz ~ 0", Math.abs(at(ls, 20000)) < 0.05, at(ls, 20000).toFixed(4));
    check("HIGH SHELF 2 kHz +6: 20 Hz ~ 0", Math.abs(at(hs, 20)) < 0.05, at(hs, 20).toFixed(4));
    check("HIGH SHELF 2 kHz +6: 20 kHz ~ +6", Math.abs(at(hs, 20000) - 6) < 0.3, at(hs, 20000).toFixed(4));
    check("shelf at its corner = gain/2", Math.abs(at(ls, 200) - 3) < 1e-6, at(ls, 200).toFixed(12));
    // Device.kt identity: HS(g) = LS(-g) + g at every point
    [[2000, 6, 0.71], [8000, -7.5, 2], [300, 10, 0.3]].forEach(function (c) {
      var a = responseDb([P("HIGH_SHELF", c[0], c[1], c[2])]), b = responseDb([P("LOW_SHELF", c[0], -c[1], c[2])]);
      var worst = 0; for (var i = 0; i < a.length; i++) worst = Math.max(worst, Math.abs(a[i] - b[i] - c[1]));
      check("HS(" + c[1] + ") - LS(" + (-c[1]) + ") = " + c[1] + " dB everywhere (" + c[0] + " Hz Q" + c[2] + ")", worst < 1e-6, "max err " + worst.toExponential(2));
    });
    // invalid shelf
    var threw = false; try { responseDb([P("HIGH_SHELF", 5000, 10, 10)]); } catch (e) { threw = e instanceof InvalidEq; }
    check("HIGH SHELF +10 dB Q10 is INVALID (negative radicand)", threw);
    // AUTO preamp
    check("DUSK AUTO preamp = 0 (cuts only)", autoPreamp(dusk) === 0 && shownPreamp(PROFILES.DUSK()) === 0);
    var nfAuto = shownPreamp(PROFILES.NIGHTFALL());
    check("NIGHTFALL AUTO preamp = -3 dB (peak +3 at 15 911 Hz)", nfAuto === -3, String(nfAuto));
    var boost = [P("PEAK", 100, 4.2, 1)];
    check("PEAK +4.2 dB -> AUTO -5 (ceil)", autoPreamp(boost) === -5);
    var hsProf = { bands: [P("HIGH_SHELF", 4000, 5, 0.71)], preampDb: null };
    console.log("  HS +5 @4k AUTO: register " + devicePreamp(hsProf.bands, null) + ", shown " + shownPreamp(hsProf));
    check("HS +5 AUTO shown = -5 (curve max +5)", shownPreamp(hsProf) === -5);
    check("manual -2.4 with HS +5: register floor(2.6) = 2 -> clamp 0", devicePreamp(hsProf.bands, -2.4) === 0);
    check("manual -7.6 with HS +5: register floor(-2.6) = -3", devicePreamp(hsProf.bands, -7.6) === -3);
    check("Fmt.freq 12450 = 12\\u2009450", Fmt.freq(12450) === "12 450");
    check("Fmt.gain -4.5 / 3 / 0.02", Fmt.gain(-4.5) === "-4.5" && Fmt.gain(3) === "+3.0" && Fmt.gain(0.02) === "0.0");
    check("GAIN quantize 3.14159 = 3.1, Q 0.7071 = 0.71, FREQ 999.6 = 1000",
      SCALES.GAIN.quantize(3.14159) === 3.1 && SCALES.Q.quantize(0.7071) === 0.71 && SCALES.FREQ.quantize(999.6) === 1000);
    check("widestGapMiddle(DUSK) = sqrt(20*1400)", Math.abs(widestGapMiddle([1400, 5400, 14000]) - Math.sqrt(28000)) < 1e-9);
    // Values asserted by android/core/src/test/.../ProtocolParityTest.kt (the Kotlin's own test)
    var kLs = bandDb(P("LOW_SHELF", 1000, 6, 0.7), new FreqGrid(Float64Array.of(500)))[0];
    check("Kotlin test: LS 1 kHz +6 Q0.7 at 500 Hz = 5.163 +-0.02", Math.abs(kLs - 5.163) < 0.02, kLs.toFixed(4));
    var kHs = bandDb(P("HIGH_SHELF", 8000, 4, 0.7), new FreqGrid(Float64Array.of(20000)))[0];
    check("Kotlin test: HS 8 kHz +4 Q0.7 at 20 kHz = 3.753 +-0.02", Math.abs(kHs - 3.753) < 0.02, kHs.toFixed(4));
    check("Kotlin test: Preamp.auto(LS 1 kHz +6 Q2) = -7", autoPreamp([P("LOW_SHELF", 1000, 6, 2)]) === -7, String(autoPreamp([P("LOW_SHELF", 1000, 6, 2)])));
    ["LOW_SHELF", "HIGH_SHELF"].forEach(function (t) {
      var th = false; try { autoPreamp([P(t, 1000, 10, 10)]); } catch (e) { th = e instanceof InvalidEq; }
      check("Kotlin test: " + t + " 1 kHz +10 Q10 AUTO throws; Q0.7 AUTO < 0", th && autoPreamp([P(t, 1000, 10, 0.7)]) < 0);
    });
    var hs25 = [P("HIGH_SHELF", 8000, 4, 0.7)];
    check("Kotlin test: manual -6 + HS +4 -> register -2", devicePreamp(hs25, -6) === -2);
    console.log(ok ? "ALL OK" : "SOME CHECKS FAILED");
    if (!ok) process.exitCode = 1;
  }
})(typeof window !== "undefined" ? window : typeof globalThis !== "undefined" ? globalThis : this);
