/*
  contour-tune.js - the Contour EQ page (Tune) as a mountable web component, no build step.
    <link rel="stylesheet" href="../shared/tokens.css">
    <link rel="stylesheet" href="../shared/contour-tune.css">
    <script src="../shared/contour-dsp.js"></script>
    <script src="../shared/contour-tune.js"></script>
    var panel = ContourTune.mount(element, { profile: "DUSK" });   // or a profile object {name, icon, bands, preampDb}
    panel.getProfile(); panel.setProfile(p); panel.destroy();
  Options: profile, onChange(profile), note (HTML under HOLD TO SEND), noteSent (after a hold).

  Ported from android/app/.../ui/tune/: ResponseGraph.kt (PlotMap, grid, labels, nodes, gestures),
  TuneScreen.kt (header, chips, type row, value rows, PREAMP bar), Controls.kt (RelSlider drag gain,
  HOLD TO SEND 700 ms), ui/kit/LiftGuard.kt (touch lift-off correction), model/AppModel.kt (edits, undo 100).
  Every instance keeps its own state; nothing uses ids or document-level state, so panels do not interfere.
*/
(function (root) {
  "use strict";
  var D = root.ContourDSP;
  if (!D) throw new Error("contour-tune.js needs contour-dsp.js first");
  var SVGNS = "http://www.w3.org/2000/svg";

  /* ---- icons (Material outlined shapes, 24 grid) ---- */
  var ICON = {
    sun: '<circle cx="12" cy="12" r="4.2" fill="none" stroke="currentColor" stroke-width="2"/><g stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.55 1.55M17.15 17.15l1.55 1.55M5.3 18.7l1.55-1.55M17.15 6.85l1.55-1.55"/></g>',
    moon: '<path fill="currentColor" d="M9.37 5.51A7.35 7.35 0 0 0 9.1 7.5c0 4.08 3.32 7.4 7.4 7.4.68 0 1.35-.09 1.99-.27A7.014 7.014 0 0 1 12 19c-3.86 0-7-3.14-7-7 0-2.93 1.81-5.45 4.37-6.49zM12 3a9 9 0 1 0 9 9c0-.46-.04-.92-.1-1.36a5.389 5.389 0 0 1-4.4 2.26 5.403 5.403 0 0 1-3.14-9.8c-.44-.06-.9-.1-1.36-.1z"/>',
    headphones: '<path fill="currentColor" d="M12 3a9 9 0 0 0-9 9v7c0 1.1.9 2 2 2h4v-8H5v-1c0-3.87 3.13-7 7-7s7 3.13 7 7v1h-4v8h4c1.1 0 2-.9 2-2v-7a9 9 0 0 0-9-9zM7 15v4H5v-4h2zm12 4h-2v-4h2v4z"/>',
    undo: '<path fill="currentColor" d="M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z"/>',
    redo: '<path fill="currentColor" d="M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z"/>',
    add: '<path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>',
    history: '<path fill="currentColor" d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/>',
  };
  function icon(name) { return '<svg viewBox="0 0 24 24" aria-hidden="true">' + (ICON[name] || ICON.headphones) + "</svg>"; }

  function h(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }

  /* ---- Controls.kt drag gain ---- */
  var SLOW = 0.1, FAST = 2.2, SPEED_FINE = 0.25, SPEED_FAST = 1.8;
  function speedRamp(s) { var t = clamp((s - SPEED_FINE) / (SPEED_FAST - SPEED_FINE), 0, 1); return t * t * (3 - 2 * t); }
  function ratioLerp(a, b, s) { return a * Math.pow(b / a, s); }
  function slowGain(scale, v, travel) { return scale.fineMax == null ? SLOW : Math.min(SLOW, scale.fineMax * travel / scale.perPos(v)); }
  function dragGain(speed, slow) { return ratioLerp(slow, FAST, speedRamp(speed)); }
  var PREAMP_DB_PER_DP = 0.08, PREAMP_SLOW = 0.25;
  var HOLD_MS = 700, LONG_MS = 500, DOUBLE_MS = 300;

  /* ---- ui/kit/LiftGuard.kt ---- */
  var REST_SLOP = 1.5, REST_MS = 70, LIFT_MS = 100, LIFT_SLOP = 10, LEAD_MS = 20;
  function LiftGuard() { this.trace = []; }
  LiftGuard.prototype.start = function (t, x, y, v) { this.trace = [{ t: t, x: x, y: y, v: v }]; };
  LiftGuard.prototype.move = function (t, x, y, v) {
    var tr = this.trace; tr.push({ t: t, x: x, y: y, v: v });
    var horizon = t - LIFT_MS - REST_MS - LEAD_MS, drop = 0;
    while (drop + 1 < tr.length && tr[drop + 1].t <= horizon) drop++;
    if (drop > 0) tr.splice(0, drop);
  };
  LiftGuard.prototype.rest = function (now) {
    var tr = this.trace, n = tr.length;
    for (var j = n - 1; j >= 0; j--) {
      var end = j === n - 1 ? now : tr[j + 1].t;
      if (end < now - LIFT_MS) return null;
      var start = end - REST_MS, i = j, still = true;
      while (true) {
        if (Math.hypot(tr[i].x - tr[j].x, tr[i].y - tr[j].y) > REST_SLOP) { still = false; break; }
        if (tr[i].t <= start) break;
        if (i === 0) { still = false; break; }
        i--;
      }
      if (still) return { index: j, end: end };
    }
    return null;
  };
  LiftGuard.prototype.release = function (now, same) {
    var tr = this.trace, last = tr[tr.length - 1];
    if (!last) return null;
    var r = this.rest(now), back = null;
    if (r && r.index < tr.length - 1) {
      var drift = Math.hypot(last.x - tr[r.index].x, last.y - tr[r.index].y), t = r.end - LEAD_MS, held = tr[0].v;
      for (var i = tr.length - 1; i >= 0; i--) if (tr[i].t <= t) { held = tr[i].v; break; }
      if (drift <= LIFT_SLOP && !same(held, last.v)) back = held;
    }
    this.trace = [];
    return back;
  };

  /* ---- cube-motion (optional, window.cubeMotion): morph state text, rise/leave small overlays ---- */
  function motion() { return root.cubeMotion && typeof Element.prototype.animate === "function" ? root.cubeMotion : null; }
  /** Two faces in one parent: the active one in flow, the other absolute, aria-hidden and inert. */
  function makeFaces(cls) {
    var w = document.createElement("span"); w.className = "ct-faces" + (cls ? " " + cls : "");
    var a = document.createElement("span"), b = document.createElement("span");
    b.setAttribute("aria-hidden", "true"); b.inert = true;
    w.append(a, b); w._html = null;
    return w;
  }
  /** Put html in the faces; morph when animate and it differs, else swap in place. */
  function faceSet(w, html, animate) {
    if (w._html === html) return;
    var first = w._html === null; w._html = html;
    var f = w.children, act = f[0].hasAttribute("aria-hidden") ? f[1] : f[0], ina = act === f[0] ? f[1] : f[0];
    var cm = motion();
    if (first || !animate || !cm) { act.innerHTML = html; return; }
    ina.innerHTML = html;
    cm.morph(act, ina);
  }
  function esc(t) { return String(t).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function capture(el, id) { try { el.setPointerCapture(id); } catch (e) { /* synthetic or ended pointer */ } }
  function cloneProfile(p) { return JSON.parse(JSON.stringify(p)); }
  function resolveProfile(p) {
    if (typeof p === "string") return D.PROFILES[p] ? D.PROFILES[p]() : D.PROFILES.DUSK();
    if (!p) return D.PROFILES.DUSK();
    var c = cloneProfile(p);
    c.bands = (c.bands || []).map(function (b) { return { id: b.id || D.bandId(), type: b.type || "PEAK", freqHz: b.freqHz, gainDb: b.gainDb, q: b.q, enabled: b.enabled !== false }; });
    if (c.preampDb === undefined) c.preampDb = null;
    return c;
  }

  var NOTE = "Hold for 0.7 s. On the web nothing is sent - in the app this writes the dongle and reads it back.";
  var NOTE_SENT = "<strong>Nothing was sent</strong> - this is the web. In the app, HOLD TO SEND writes every band and the preamp to the dongle, reads it all back, compares, and only then stores it in the dongle's memory.";

  function Tune(el, opts) {
    var self = this;
    var p = resolveProfile(opts.profile);
    var sel = 0, undo = [], redo = [], sent = null, sending = false, activeWheel = false;
    var gesture = { open: false, before: null, ids: new Set() };
    var disposers = [];
    function on(t, type, fn, o) { t.addEventListener(type, fn, o); disposers.push(function () { t.removeEventListener(type, fn, o); }); }

    /* ---- model (AppModel) ---- */
    function snap() { return JSON.stringify({ bands: p.bands, preampDb: p.preampDb }); }
    function applySnap(s) { var o = JSON.parse(s); p.bands = o.bands; p.preampDb = o.preampDb; }
    function push(s) { undo.push(s); if (undo.length > 100) undo.shift(); redo = []; }
    function clampSel() { sel = p.bands.length ? clamp(sel, 0, p.bands.length - 1) : 0; }
    function change(mut) {
      var before = gesture.open ? null : snap();
      mut();
      if (before !== null && snap() !== before) push(before);
      clampSel(); update();
    }
    function begin() { if (gesture.open) return; gesture.open = true; gesture.before = snap(); }
    function end() {
      if (!gesture.open) return;
      gesture.open = false;
      if (snap() !== gesture.before) push(gesture.before);
      update();
    }
    function history(from, to) {
      if (!from.length) return;
      to.push(snap()); applySnap(from.pop());
      if (gesture.open) gesture.before = snap();
      clampSel(); update();
    }
    function band() { return p.bands[sel]; }
    function setBand(i, over) { var b = p.bands[i]; if (!b) return; for (var k in over) b[k] = over[k]; }
    function valid() { try { D.responseDb(D.deviceBands(p.bands)); D.devicePreamp(p.bands, p.preampDb); return true; } catch (e) { return false; } }
    function addBand(f, g) {
      if (p.bands.length >= D.MAX_BANDS) return false;
      change(function () { p.bands.push(D.newBand(f, g, p.bands)); sel = p.bands.length - 1; });
      return true;
    }
    function setPreampAuto(auto) {
      change(function () {
        if (auto) { if (D.tryShownPreamp({ bands: p.bands, preampDb: null }) != null) p.preampDb = null; }
        else { var v = D.tryShownPreamp(p); if (v != null) p.preampDb = D.round1(v); }
      });
    }
    function setPreamp(db) { var r = D.preampRange(p.bands); p.preampDb = D.round1(clamp(db, r[0], r[1])); }

    /* ---- DOM ---- */
    var rootEl = h("div", "ct");
    rootEl.setAttribute("role", "group");
    rootEl.setAttribute("aria-label", "Contour EQ page, live");
    el.innerHTML = ""; el.appendChild(rootEl);

    var head = h("div", "ct-head");
    var nameEl = h("div", "ct-name"), nameIcon = h("span", "ct-icon"), nameFaces = makeFaces("ct-title"), iconNow = null;
    nameEl.append(nameIcon, nameFaces);
    var undoBtn = h("button", "ct-hist", icon("undo")); undoBtn.type = "button"; undoBtn.setAttribute("aria-label", "Undo");
    var redoBtn = h("button", "ct-hist", icon("redo")); redoBtn.type = "button"; redoBtn.setAttribute("aria-label", "Redo");
    var clearBtn = h("button", "ct-clear", "CLEAR EQ"); clearBtn.type = "button";
    head.append(nameEl, undoBtn, redoBtn, clearBtn);

    var graph = h("div", "ct-graph");
    var canvas = h("canvas"); canvas.setAttribute("role", "img");
    graph.appendChild(canvas);
    var ctx = canvas.getContext("2d");

    var bandsEl = h("div", "ct-bands");
    var typeEl = h("div", "ct-type");
    var typePill = h("div", "pill"); typeEl.appendChild(typePill);
    var TYPES = [["PEAK", "PEAK"], ["LOW_SHELF", "LOW SHELF"], ["HIGH_SHELF", "HIGH SHELF"]];
    var typeBtns = TYPES.map(function (t) {
      var b = h("button", null, t[1]); b.type = "button";
      on(b, "click", function () { var bd = band(); if (bd && bd.type !== t[0]) change(function () { bd.type = t[0]; }); });
      typeEl.appendChild(b); return b;
    });

    function valueBox(param) {
      var box = h("button", "ct-val"); box.type = "button";
      box.innerHTML = '<span class="lbl">' + param.label + '</span><span class="num"></span>';
      return box;
    }
    var rows = {};
    ["FREQ", "GAIN", "Q"].forEach(function (name) {
      var param = D.PARAMS[name];
      var row = h("div", "ct-row"), box = valueBox(param);
      var sl = h("div", "ct-slider"), trk = h("div", "trk"), fil = h("div", "fil");
      trk.appendChild(fil); sl.appendChild(trk);
      sl.tabIndex = 0; sl.setAttribute("role", "slider"); sl.setAttribute("aria-label", param.label);
      row.append(box, sl);
      rows[name] = { row: row, box: box, slider: sl, trk: trk, fil: fil, param: param };
    });

    var preRow = h("div", "ct-row"), preBox = valueBox(D.PARAMS.PREAMP), preFaces = makeFaces("num"), preMode = null;
    preBox.querySelector(".num").replaceWith(preFaces);
    var pre = h("div", "ct-pre"), preTrk = h("div", "trk");
    var preLabels = h("div", "labels", "<span>MANUAL</span><span>AUTO</span>");
    var prePill = h("div", "pill");
    var preOver = h("div", "labels over", "<span>MANUAL</span><span>AUTO</span>");
    var preHitM = h("button", "hit m"), preHitA = h("button", "hit a");
    preHitM.type = preHitA.type = "button"; preHitM.setAttribute("aria-label", "Preamp manual"); preHitA.setAttribute("aria-label", "Preamp auto");
    var preInvalid = h("div", "invalid", "INVALID EQ");
    preTrk.append(preLabels, prePill, preOver, preHitM, preHitA, preInvalid);
    pre.appendChild(preTrk);
    preRow.append(preBox, pre);

    var sendRow = h("div", "ct-send");
    var lastBtn = h("button", "ct-last", icon("history") + "<span>LAST SENT</span>"); lastBtn.type = "button";
    var hold = h("button", "ct-hold", '<span class="shade"></span><span class="txt">HOLD TO SEND</span>'); hold.type = "button";
    var holdShade = hold.firstChild, holdTxt = makeFaces("txt"); hold.lastChild.replaceWith(holdTxt);
    sendRow.append(lastBtn, hold);
    var note = h("p", "ct-note"); note.innerHTML = opts.note || NOTE;
    var menu = null;

    rootEl.append(head, graph, bandsEl, typeEl, rows.FREQ.row, rows.GAIN.row, rows.Q.row, preRow, sendRow, note);

    /* ---- colours ---- */
    var dark = root.matchMedia ? root.matchMedia("(prefers-color-scheme: dark)") : null;
    function pal() {
      var cs = getComputedStyle(rootEl), g = function (n) { return cs.getPropertyValue(n).trim(); };
      return { surface: g("--surface"), grid: g("--grid"), track: g("--track"), text: g("--text"), textMute: g("--text-mute"),
        accent: g("--accent"), fill: g("--fill"), bg: g("--bg"), onAccent: g("--on-accent"), shadow: g("--shadow"),
        font: g("--font") || "sans-serif", shadowAlpha: dark && dark.matches ? 1 : 0.3 };
    }
    function withAlpha(hex, a) {
      var m = /^#([0-9a-f]{6})$/i.exec(hex); if (!m) return hex;
      var n = parseInt(m[1], 16); return "rgba(" + (n >> 16) + "," + ((n >> 8) & 255) + "," + (n & 255) + "," + a + ")";
    }

    /* ---- graph (ResponseGraph.kt) ---- */
    var PAD = 18, NODE_R = 12, HIT_R = 24;
    var GRID_F = [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000];
    var LABEL_F = [[100, "100"], [1000, "1K"], [10000, "10K"]];
    var GRID_DB = [-12, -6, 0, 6, 12];
    var SPAN = Math.log(1000);
    var curve = new Float64Array(D.DISPLAY_POINTS);
    function map() {
      var w = canvas.clientWidth, hh = canvas.clientHeight;
      return {
        x: function (f) { return PAD + (w - 2 * PAD) * Math.log(f / 20) / SPAN; },
        y: function (g) { return PAD + (hh - 2 * PAD) * (12 - clamp(g, -12, 12)) / 24; },
        f: function (x) { return 20 * Math.exp(clamp((x - PAD) / (w - 2 * PAD), 0, 1) * SPAN); },
        g: function (y) { return 12 - 24 * clamp((y - PAD) / (hh - 2 * PAD), 0, 1); },
      };
    }
    var drawQueued = false;
    function queueDraw() { if (!drawQueued) { drawQueued = true; root.requestAnimationFrame(function () { drawQueued = false; draw(); }); } }
    function draw() {
      var dpr = root.devicePixelRatio || 1, w = canvas.clientWidth, hh = canvas.clientHeight;
      if (!w || !hh) return;
      if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(hh * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(hh * dpr); }
      var c = pal(), m = map();
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, w, hh);
      ctx.fillStyle = c.surface; ctx.fillRect(0, 0, w, hh);
      ctx.lineWidth = 1; ctx.strokeStyle = c.grid;
      GRID_F.forEach(function (f) { var x = Math.round(m.x(f)) + 0.5; ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, hh); ctx.stroke(); });
      GRID_DB.forEach(function (g) {
        var y = m.y(g); ctx.beginPath();
        ctx.strokeStyle = g === 0 ? c.track : c.grid; ctx.lineWidth = g === 0 ? 1.5 : 1;
        var yy = g === 0 ? y : Math.round(y) + 0.5; ctx.moveTo(0, yy); ctx.lineTo(w, yy); ctx.stroke();
      });
      ctx.fillStyle = c.textMute; ctx.font = "500 11px " + c.font; ctx.textBaseline = "top"; ctx.textAlign = "left";
      LABEL_F.forEach(function (l) { ctx.fillText(l[1], m.x(l[0]) + 4, hh - PAD - 12); });
      [6, -6].forEach(function (g) { ctx.fillText(g > 0 ? "+6" : "-6", PAD - 6, m.y(g) - 17); });

      var validCurve = true;
      try { D.responseDb(p.bands, D.DISPLAY_GRID, curve); } catch (e) { validCurve = false; }
      if (validCurve) {
        var fr = D.DISPLAY_GRID.freqs;
        ctx.beginPath();
        for (var i = 0; i < curve.length; i++) { var x = m.x(fr[i]), y = m.y(curve[i]); if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y); }
        ctx.strokeStyle = c.text; ctx.lineWidth = 2.5; ctx.lineCap = "round"; ctx.lineJoin = "round"; ctx.stroke();
      } else {
        ctx.fillStyle = c.text; ctx.fillText("INVALID EQ - adjust shelf gain or Q", PAD, PAD);
      }
      var shadowA = 0.5 * c.shadowAlpha;
      p.bands.forEach(function (b, i) {
        var x = m.x(b.freqHz), y = m.y(b.gainDb), s = i === sel, fill = s ? c.accent : c.fill;
        ctx.save();
        if (b.enabled) {
          ctx.shadowColor = withAlpha(c.shadow, shadowA); ctx.shadowBlur = 5.6; ctx.shadowOffsetY = 1.5;
          ctx.fillStyle = fill; ctx.beginPath(); ctx.arc(x, y, NODE_R, 0, 2 * Math.PI); ctx.fill();
        } else {
          ctx.fillStyle = c.surface; ctx.beginPath(); ctx.arc(x, y, NODE_R, 0, 2 * Math.PI); ctx.fill();
          ctx.strokeStyle = fill; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(x, y, NODE_R - 1, 0, 2 * Math.PI); ctx.stroke();
        }
        ctx.restore();
        ctx.fillStyle = !b.enabled ? fill : s ? c.onAccent : c.bg;
        ctx.font = "700 12px " + c.font; ctx.textAlign = "center"; ctx.textBaseline = "middle";
        ctx.fillText(String(i + 1), x, y + 0.5);
      });
      canvas.setAttribute("aria-label", "EQ response graph, " + p.bands.length + " bands" + (validCurve ? "" : ", invalid EQ"));
    }
    function hitNode(x, y) {
      var m = map(), best = -1, bd = Infinity;
      p.bands.forEach(function (b, i) { var d = Math.hypot(m.x(b.freqHz) - x, m.y(b.gainDb) - y); if (d < bd) { bd = d; best = i; } });
      return bd <= HIT_R ? best : -1;
    }
    function localXY(e) { var r = canvas.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }

    var gp = new Map(); // active graph pointers: id -> {x, y}
    var gs = null;      // graph gesture state
    var lastTap = { t: 0, hit: -2, x: 0, y: 0 };
    var nodeGuard = new LiftGuard(), pinchGuard = new LiftGuard();
    on(canvas, "pointerdown", function (e) {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      capture(canvas, e.pointerId);
      var pt = localXY(e); gp.set(e.pointerId, pt);
      closeMenu();
      if (gp.size === 1) {
        var hit = hitNode(pt.x, pt.y);
        gs = { mode: 0, hit: hit, down: pt, t0: e.timeStamp, grab: null, touch: e.pointerType !== "mouse", long: null, pinch: null };
        if (hit >= 0) nodeGuard.start(e.timeStamp, pt.x, pt.y, [p.bands[hit].freqHz, p.bands[hit].gainDb]);
        else if (gs.touch) gs.long = setTimeout(function () { // touch and pen only; a mouse double-clicks
          if (!gs || gs.mode !== 0) return;
          gs.mode = 3;
          addBand(map().f(gs.down.x), clamp(map().g(gs.down.y), -10, 10));
        }, LONG_MS);
      } else if (gp.size === 2 && gs) startPinch(e.timeStamp);
      e.preventDefault();
    });
    function pinchDist() { var a = Array.from(gp.values()); return Math.max(1, Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y)); }
    function startPinch(t) {
      clearTimeout(gs.long); gs.mode = 2;
      var b = band(); gs.pinch = { d0: pinchDist(), q0: b ? b.q : 1 };
      pinchGuard.start(t, gs.pinch.d0, 0, gs.pinch.q0);
    }
    on(canvas, "pointermove", function (e) {
      var pt = localXY(e);
      if (!gp.has(e.pointerId)) {
        canvas.classList.toggle("on-node", hitNode(pt.x, pt.y) >= 0);
        return;
      }
      gp.set(e.pointerId, pt);
      if (!gs) return;
      if (gs.mode === 2 && gp.size >= 2) {
        var b = band(); if (!b) return;
        var d = pinchDist(), q = D.SCALES.Q.quantize(gs.pinch.q0 * gs.pinch.d0 / d);
        if (q !== b.q) change(function () { b.q = q; });
        pinchGuard.move(e.timeStamp, d, 0, q);
        return;
      }
      if (gs.mode === 0) {
        var slop = gs.touch ? 8 : 3;
        if (Math.hypot(pt.x - gs.down.x, pt.y - gs.down.y) > slop) {
          clearTimeout(gs.long);
          if (gs.hit >= 0) {
            gs.mode = 1; sel = gs.hit;
            var hb = p.bands[gs.hit], m0 = map();
            gs.grab = { x: m0.x(hb.freqHz) - pt.x, y: m0.y(hb.gainDb) - pt.y };
            canvas.classList.add("dragging");
            nodeGuard.move(e.timeStamp, pt.x, pt.y, [hb.freqHz, hb.gainDb]);
            update();
          } else gs.mode = 3;
        }
      }
      if (gs.mode === 1) {
        var nb = p.bands[gs.hit]; if (!nb) return;
        var m = map();
        var f = D.SCALES.FREQ.quantize(m.f(pt.x + gs.grab.x));
        var g = D.SCALES.GAIN.quantize(clamp(m.g(pt.y + gs.grab.y), -10, 10));
        if (f !== nb.freqHz || g !== nb.gainDb) change(function () { nb.freqHz = f; nb.gainDb = g; });
        nodeGuard.move(e.timeStamp, pt.x, pt.y, [f, g]);
      }
    });
    function sameFG(a, b) { return a[0] === b[0] && a[1] === b[1]; }
    function graphUp(e, cancelled) {
      if (!gp.has(e.pointerId)) return;
      gp.delete(e.pointerId);
      if (!gs) return;
      if (gs.mode === 2 && gp.size < 2) {
        var qb = gs.touch ? pinchGuard.release(e.timeStamp, function (a, b) { return a === b; }) : null;
        if (qb != null && band()) { var bb = band(); change(function () { bb.q = qb; }); }
        gs.mode = 3;
      }
      if (gp.size) return;
      clearTimeout(gs.long);
      canvas.classList.remove("dragging");
      if (!cancelled && gs.mode === 1 && gs.touch) {
        var back = nodeGuard.release(e.timeStamp, sameFG), nb = p.bands[gs.hit];
        if (back && nb) change(function () { nb.freqHz = back[0]; nb.gainDb = back[1]; });
      }
      if (!cancelled && gs.mode === 0) {
        var now = e.timeStamp, pt = gs.down;
        var dbl = now - lastTap.t < DOUBLE_MS && lastTap.hit === gs.hit && Math.hypot(pt.x - lastTap.x, pt.y - lastTap.y) < 24;
        if (gs.hit >= 0) {
          if (dbl) { var tb = p.bands[gs.hit]; if (tb) change(function () { tb.gainDb = 0; }); lastTap.hit = -2; }
          else { sel = gs.hit; update(); lastTap = { t: now, hit: gs.hit, x: pt.x, y: pt.y }; }
        } else {
          if (dbl) { addBand(map().f(pt.x), clamp(map().g(pt.y), -10, 10)); lastTap.hit = -2; }
          else lastTap = { t: now, hit: -1, x: pt.x, y: pt.y };
        }
      }
      gs = null;
    }
    on(canvas, "pointerup", function (e) { graphUp(e, false); });
    on(canvas, "pointercancel", function (e) { graphUp(e, true); });
    on(canvas, "pointerleave", function () { if (!gp.size) canvas.classList.remove("on-node"); });
    on(canvas, "contextmenu", function (e) { e.preventDefault(); });

    // wheel / trackpad pinch = Q of the selected band. Plain wheel only once the panel is in use (no scroll trap).
    var wheel = { q: null, timer: null };
    on(canvas, "wheel", function (e) {
      if (!(activeWheel || e.ctrlKey)) return;
      var b = band(); if (!b) return;
      e.preventDefault();
      if (wheel.q == null || D.SCALES.Q.quantize(wheel.q) !== b.q) wheel.q = b.q;
      var dy = e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1);
      wheel.q = clamp(wheel.q * Math.exp(dy * (e.ctrlKey ? 0.01 : 0.002)), 0.1, 10);
      var q = D.SCALES.Q.quantize(wheel.q);
      begin();
      if (q !== b.q) { b.q = q; clampSel(); update(); }
      clearTimeout(wheel.timer); wheel.timer = setTimeout(end, 400);
    }, { passive: false });

    /* ---- band chips (BandStrip) ---- */
    function openMenu(i, chip) {
      closeMenu();
      var b = p.bands[i]; if (!b) return;
      sel = i; update();
      chip = bandsEl.children[i] || chip;
      menu = h("div", "ct-menu"); menu.setAttribute("role", "menu");
      var byp = h("button", null, b.enabled ? "BYPASS" : "ENABLE"), del = h("button", null, "DELETE");
      byp.type = del.type = "button"; byp.setAttribute("role", "menuitem"); del.setAttribute("role", "menuitem");
      byp.addEventListener("click", function () { var bi = p.bands[i]; closeMenu(); if (bi) change(function () { bi.enabled = !bi.enabled; }); });
      del.addEventListener("click", function () {
        closeMenu();
        change(function () { p.bands.splice(i, 1); if (sel >= i && sel > 0) sel--; });
      });
      menu.append(byp, del);
      rootEl.appendChild(menu);
      var cr = chip.getBoundingClientRect(), rr = rootEl.getBoundingClientRect();
      var left = clamp(cr.left - rr.left, 0, Math.max(0, rr.width - menu.offsetWidth));
      menu.style.left = left + "px"; menu.style.top = (cr.bottom - rr.top + 8) + "px";
      byp.focus({ preventScroll: true });
      var cm = motion(); if (cm) cm.rise(menu);
    }
    function closeMenu() {
      if (!menu) return;
      var m = menu, cm = motion(); menu = null; m.inert = true;
      if (!cm) { m.remove(); return; }
      Promise.all(cm.leave(m).map(function (a) { return a.finished; })).then(function () { m.remove(); }, function () { m.remove(); });
    }
    on(document, "pointerdown", function (e) {
      if (menu && !menu.contains(e.target)) closeMenu();
      activeWheel = rootEl.contains(e.target);
    }, true);
    on(rootEl, "keydown", function (e) { if (e.key === "Escape") closeMenu(); });

    function renderChips() {
      bandsEl.innerHTML = "";
      p.bands.forEach(function (b, i) {
        var c = h("button", "ct-chip" + (i === sel ? " sel" : "") + (b.enabled ? "" : " off"), String(i + 1));
        c.type = "button"; c.setAttribute("aria-label", "Band " + (i + 1) + (b.enabled ? "" : ", bypassed") + ". Long-press for bypass or delete");
        c.setAttribute("aria-pressed", i === sel ? "true" : "false");
        var lp = null, fired = false;
        c.addEventListener("pointerdown", function (e) {
          if (e.pointerType === "mouse" && e.button !== 0) return;
          fired = false;
          lp = setTimeout(function () { fired = true; openMenu(i, c); }, LONG_MS);
        });
        var cancel = function () { clearTimeout(lp); };
        c.addEventListener("pointerup", cancel); c.addEventListener("pointerleave", cancel); c.addEventListener("pointercancel", cancel);
        c.addEventListener("click", function (e) { if (fired) { e.preventDefault(); return; } sel = i; update(); });
        c.addEventListener("contextmenu", function (e) { e.preventDefault(); clearTimeout(lp); fired = true; openMenu(i, c); });
        c.addEventListener("keydown", function (e) { if (e.key === "ContextMenu" || (e.shiftKey && e.key === "F10") || e.key === "Delete") { e.preventDefault(); openMenu(i, c); } });
        bandsEl.appendChild(c);
      });
      if (p.bands.length < D.MAX_BANDS) {
        var add = h("button", "ct-chip", icon("add")); add.type = "button"; add.setAttribute("aria-label", "Add band");
        add.addEventListener("click", function () { addBand(null, 0); });
        bandsEl.appendChild(add);
      }
    }

    /* ---- relative sliders (RelSlider) ---- */
    Object.keys(rows).forEach(function (name) {
      var r = rows[name], param = r.param, scale = param.scale, guard = new LiftGuard(), st = null;
      on(r.slider, "pointerdown", function (e) {
        var b = band(); if (!b || (e.pointerType === "mouse" && e.button !== 0)) return;
        st = { id: e.pointerId, x0: e.clientX, lastX: e.clientX, lastT: e.timeStamp, started: false, pos: scale.toPos(b[param.key]),
          speed: 0, bandId: b.id, touch: e.pointerType !== "mouse" };
        capture(r.slider, e.pointerId);
        guard.start(e.timeStamp, e.clientX, e.clientY, b[param.key]);
      });
      on(r.slider, "pointermove", function (e) {
        if (!st || e.pointerId !== st.id) return;
        var b = band(); if (!b || b.id !== st.bandId) return;
        if (!st.started) {
          var slop = st.touch ? 8 : 2, off = e.clientX - st.x0;
          if (Math.abs(off) <= slop) return;
          st.started = true; st.lastX = st.x0 + (off > 0 ? slop : -slop);
        }
        var dx = e.clientX - st.lastX, dt = Math.max(1, e.timeStamp - st.lastT);
        st.lastX = e.clientX; st.lastT = e.timeStamp;
        st.speed = 0.6 * st.speed + 0.4 * (Math.abs(dx) / dt);
        var travel = Math.max(1, r.trk.clientWidth - r.trk.clientHeight);
        var slow = slowGain(scale, scale.fromPos(st.pos), travel);
        st.pos = clamp(st.pos + dx * dragGain(st.speed, slow) / travel, 0, 1);
        var next = scale.quantize(scale.fromPos(st.pos));
        if (next !== b[param.key]) change(function () { b[param.key] = next; });
        guard.move(e.timeStamp, e.clientX, e.clientY, next);
      });
      function up(e, cancelled) {
        if (!st || e.pointerId !== st.id) return;
        if (!cancelled && st.started && st.touch) {
          var back = guard.release(e.timeStamp, function (a, b) { return a === b; }), b = band();
          if (back != null && b && b.id === st.bandId) change(function () { b[param.key] = back; });
        }
        st = null;
      }
      on(r.slider, "pointerup", function (e) { up(e, false); });
      on(r.slider, "pointercancel", function (e) { up(e, true); });
      on(r.slider, "keydown", function (e) {
        var b = band(); if (!b) return;
        var dir = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[e.key]; if (!dir) return;
        e.preventDefault();
        var v = b[param.key], next;
        if (scale.log) next = scale.quantize(scale.fromPos(scale.toPos(v) + dir * (e.shiftKey ? 0.05 : 0.005)));
        else next = scale.quantize(v + dir * scale.quantum * (e.shiftKey ? 10 : 1));
        if (next === v) next = scale.quantize(v + dir * scale.quantum);
        change(function () { b[param.key] = next; });
      });
      on(r.box, "click", function () { if (band()) typeIn(r.box, name); });
    });

    /* ---- tap a number to type it ---- */
    function typeIn(box, name) {
      var param = D.PARAMS[name], b = band();
      var cur = name === "PREAMP" ? p.preampDb : b && b[param.key];
      if (cur == null) return;
      var num = box.querySelector(".num"), input = document.createElement("input");
      input.type = "text"; input.inputMode = "decimal"; input.value = param.edit(cur);
      input.setAttribute("aria-label", param.label + (param.unit ? " in " + param.unit : ""));
      num.replaceWith(input); input.focus(); input.select();
      var done = false;
      function finish(commit) {
        if (done) return; done = true;
        var v = commit ? D.parseParam(name, input.value) : null;
        input.replaceWith(num);
        if (v == null) { update(); return; }
        if (name === "PREAMP") change(function () { setPreamp(v); });
        else { var bb = p.bands.find(function (x) { return b && x.id === b.id; }); if (bb) change(function () { bb[param.key] = v; }); }
      }
      input.addEventListener("keydown", function (e) { e.stopPropagation(); if (e.key === "Enter") finish(true); else if (e.key === "Escape") finish(false); });
      input.addEventListener("blur", function () { finish(true); });
      input.addEventListener("click", function (e) { e.stopPropagation(); });
    }
    on(preBox, "click", function () { if (p.preampDb != null) typeIn(preBox, "PREAMP"); });

    /* ---- PREAMP bar ---- */
    var pst = null, preGuard = new LiftGuard();
    on(preTrk, "pointerdown", function (e) {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      pst = { id: e.pointerId, x0: e.clientX, lastX: e.clientX, lastT: e.timeStamp, started: false, speed: 0, acc: p.preampDb,
        touch: e.pointerType !== "mouse", onAuto: e.clientX - preTrk.getBoundingClientRect().left > preTrk.clientWidth / 2 };
      if (p.preampDb != null) { capture(preTrk, e.pointerId); preGuard.start(e.timeStamp, e.clientX, e.clientY, p.preampDb); }
    });
    on(preTrk, "pointermove", function (e) {
      if (!pst || e.pointerId !== pst.id || p.preampDb == null || pst.acc == null) return;
      if (!pst.started) {
        var slop = pst.touch ? 8 : 2, off = e.clientX - pst.x0;
        if (Math.abs(off) <= slop) return;
        pst.started = true; pst.lastX = pst.x0 + (off > 0 ? slop : -slop); pre.classList.add("drag");
      }
      var dx = e.clientX - pst.lastX, dt = Math.max(1, e.timeStamp - pst.lastT);
      pst.lastX = e.clientX; pst.lastT = e.timeStamp;
      pst.speed = 0.6 * pst.speed + 0.4 * (Math.abs(dx) / dt);
      var r = D.preampRange(p.bands), rate = PREAMP_DB_PER_DP * ratioLerp(PREAMP_SLOW, 1, speedRamp(pst.speed));
      pst.acc = clamp(pst.acc + dx * rate, r[0], r[1]);
      var next = Math.round(pst.acc * 10) / 10;
      if (next !== p.preampDb) change(function () { setPreamp(next); });
      preGuard.move(e.timeStamp, e.clientX, e.clientY, next);
    });
    function preUp(e, cancelled) {
      if (!pst || e.pointerId !== pst.id) return;
      var s = pst; pst = null; pre.classList.remove("drag");
      if (cancelled) return;
      if (s.started) {
        if (s.touch) { var back = preGuard.release(e.timeStamp, function (a, b) { return a === b; }); if (back != null) change(function () { setPreamp(back); }); }
        return;
      }
      var auto = p.preampDb == null;
      if (auto && !s.onAuto) setPreampAuto(false);
      else if (!auto && s.onAuto) setPreampAuto(true);
    }
    on(preTrk, "pointerup", function (e) { preUp(e, false); });
    on(preTrk, "pointercancel", function (e) { preUp(e, true); });
    on(preHitM, "keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setPreampAuto(false); } });
    on(preHitA, "keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setPreampAuto(true); } });

    /* ---- header buttons ---- */
    on(undoBtn, "click", function () { history(undo, redo); });
    on(redoBtn, "click", function () { history(redo, undo); });
    on(clearBtn, "click", function () {
      if (D.isClear(p)) return;
      change(function () { p.bands = [D.flatBand()]; p.preampDb = null; sel = 0; });
    });
    on(lastBtn, "click", function () { if (sent && sent !== snap()) change(function () { applySnap(sent); }); });

    /* ---- HOLD TO SEND (Controls.kt HoldToSend) ---- */
    var hs = null;
    function holdLabel() {
      if (sending) return "SENDING";
      if (!valid()) return "INVALID EQ - EDIT BAND";
      if (sent === snap()) return "ON DAC";
      return "HOLD TO SEND";
    }
    function holdPaint(v) {
      holdShade.style.width = (v * 100) + "%";
      if (hold.classList.contains("invalid")) { hold.style.boxShadow = ""; return; }
      var rad = 14 * (1 - 0.6 * v), y = 5 * (1 - 0.7 * v);
      hold.style.boxShadow = "0 " + y.toFixed(2) + "px " + (1.15 * rad + 1).toFixed(2) + "px color-mix(in srgb, var(--shadow) var(--k-high), transparent)";
    }
    function animateBack(from, ms) {
      var t0 = performance.now();
      (function step(now) {
        var k = Math.min(1, (now - t0) / ms); holdPaint(from * (1 - k));
        if (k < 1 && !hs) root.requestAnimationFrame(step);
      })(t0);
    }
    function holdStart(id) {
      if (hs || sending || !valid()) return;
      hs = { id: id, t0: performance.now(), v: 0, raf: 0, timer: 0 };
      var s = hs;
      // the timer decides (rAF pauses in hidden tabs); frames only paint the fill
      s.timer = setTimeout(function () { if (hs !== s) return; root.cancelAnimationFrame(s.raf); hs = null; holdPaint(1); complete(); }, HOLD_MS);
      (function step() {
        if (hs !== s) return;
        s.v = Math.min(1, (performance.now() - s.t0) / HOLD_MS); holdPaint(s.v);
        s.raf = root.requestAnimationFrame(step);
      })();
    }
    function holdStop() { if (!hs) return; var v = hs.v; root.cancelAnimationFrame(hs.raf); clearTimeout(hs.timer); hs = null; animateBack(v, 150); }
    function complete() {
      sending = true; update();
      setTimeout(function () {
        sending = false; sent = snap(); animateBack(1, 250);
        note.innerHTML = opts.noteSent || NOTE_SENT; update();
      }, 600);
    }
    on(hold, "pointerdown", function (e) {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      e.preventDefault(); holdStart(e.pointerId);
    });
    on(hold, "pointerup", holdStop); on(hold, "pointercancel", holdStop); on(hold, "pointerleave", holdStop);
    on(hold, "contextmenu", function (e) { e.preventDefault(); });
    on(hold, "keydown", function (e) { if ((e.key === " " || e.key === "Enter") && !e.repeat) { e.preventDefault(); holdStart("key"); } });
    on(hold, "keyup", function (e) { if (e.key === " " || e.key === "Enter") holdStop(); });
    on(hold, "blur", holdStop);
    on(hold, "click", function (e) { e.preventDefault(); });

    /* ---- gesture = one UNDO step (TuneScreen gestures) ---- */
    on(rootEl, "pointerdown", function (e) { gesture.ids.add(e.pointerId); begin(); }, true);
    function gestureUp(e) { if (!gesture.ids.delete(e.pointerId)) return; if (!gesture.ids.size) end(); }
    on(root, "pointerup", gestureUp); on(root, "pointercancel", gestureUp);

    var lastShown = false;
    lastBtn.style.display = "none";
    function showLast(show) {
      if (show === lastShown) return;
      lastShown = show;
      var cm = motion();
      if (show) { lastBtn.style.display = ""; lastBtn.inert = false; if (cm) cm.rise(lastBtn); return; }
      if (!cm) { lastBtn.style.display = "none"; return; }
      lastBtn.inert = true;
      Promise.all(cm.leave(lastBtn).map(function (a) { return a.finished; })).then(function () {
        if (!lastShown) lastBtn.style.display = "none";
      }, function () { /* interrupted by a new rise */ });
    }

    /* ---- render ---- */
    function update() {
      if (iconNow !== p.icon) { iconNow = p.icon; nameIcon.innerHTML = icon(p.icon); }
      faceSet(nameFaces, esc(p.name || "PROFILE"), true);
      undoBtn.setAttribute("aria-disabled", undo.length ? "false" : "true");
      redoBtn.setAttribute("aria-disabled", redo.length ? "false" : "true");
      clearBtn.setAttribute("aria-disabled", D.isClear(p) ? "true" : "false");
      if (!menu) renderChips();
      var b = band();
      typeEl.style.display = b ? "" : "none";
      ["FREQ", "GAIN", "Q"].forEach(function (n) { rows[n].row.style.display = b ? "" : "none"; });
      if (b) {
        var ti = Math.max(0, TYPES.findIndex(function (t) { return t[0] === b.type; }));
        typePill.style.width = "calc((100% - 8px) / 3)";
        typePill.style.transform = "translateX(" + (ti * 100) + "%)";
        typeBtns.forEach(function (tb, i) { tb.classList.toggle("sel", i === ti); tb.setAttribute("aria-pressed", i === ti ? "true" : "false"); });
        ["FREQ", "GAIN", "Q"].forEach(function (n) {
          var r = rows[n], v = b[r.param.key], num = r.box.querySelector(".num");
          if (num) num.innerHTML = r.param.number(v) + (r.param.unit ? '<span class="unit">' + r.param.unit + "</span>" : "");
          var w = r.trk.clientWidth, hh = r.trk.clientHeight, pos = r.param.scale.toPos(v);
          r.fil.style.width = w ? clamp(hh + (w - hh) * pos, hh, w) + "px" : "calc(" + (pos * 100) + "% )";
          r.slider.setAttribute("aria-valuenow", String(v));
          r.slider.setAttribute("aria-valuetext", r.param.number(v).replace(D.Fmt.THIN, " ") + (r.param.unit ? " " + r.param.unit : ""));
          r.slider.setAttribute("aria-valuemin", String(r.param.scale.min)); r.slider.setAttribute("aria-valuemax", String(r.param.scale.max));
        });
      }
      // PREAMP
      var auto = p.preampDb == null, autoDb = D.tryShownPreamp({ bands: p.bands, preampDb: null });
      var db = auto ? autoDb : p.preampDb, isValid = autoDb != null;
      preFaces.classList.toggle("auto", auto);
      faceSet(preFaces, db != null ? D.Fmt.gain(db) + '<span class="unit">dB</span>' : "--", preMode !== null && preMode !== auto);
      preMode = auto;
      preBox.setAttribute("aria-disabled", auto ? "true" : "false");
      preBox.title = auto ? "AUTO: switch to MANUAL to type a value" : "Type a preamp value";
      pre.classList.toggle("manual", !auto);
      preInvalid.style.display = isValid ? "none" : "";
      [preLabels, prePill, preOver].forEach(function (x) { x.style.display = isValid ? "" : "none"; });
      var tw = preTrk.clientWidth, half = tw / 2, rg = D.preampRange(p.bands);
      var pos = db == null || rg[1] <= rg[0] ? 0 : clamp((db - rg[0]) / (rg[1] - rg[0]), 0, 1);
      var left = auto ? half : 0, width = auto ? half : half + half * pos;
      prePill.style.left = left + "px"; prePill.style.width = width + "px";
      preOver.style.clipPath = "inset(0 " + Math.max(0, tw - left - width) + "px 0 " + left + "px round 16px)";
      var anim = pst && pst.started ? "none" : "left .25s, width .25s, clip-path .25s";
      prePill.style.transition = anim; preOver.style.transition = anim;
      preHitM.style.display = auto ? "" : "none";
      preHitA.style.display = auto ? "none" : "";
      // send row
      var label = holdLabel();
      faceSet(holdTxt, label, true);
      hold.classList.toggle("grey", label === "ON DAC");
      hold.classList.toggle("invalid", label.indexOf("INVALID") === 0);
      if (!hs) holdPaint(0);
      showLast(!!(sent && sent !== snap() && !sending));
      if (label === "HOLD TO SEND" && sent) note.innerHTML = opts.note || NOTE;
      queueDraw();
      if (opts.onChange) try { opts.onChange(self.getProfile()); } catch (e) { /* host error */ }
    }

    this.getProfile = function () { return cloneProfile(p); };
    this.setProfile = function (np) { p = resolveProfile(np); sel = 0; undo = []; redo = []; sent = null; update(); };
    this.destroy = function () { disposers.forEach(function (f) { f(); }); if (ro) ro.disconnect(); if (dark && dark.removeEventListener) dark.removeEventListener("change", update); rootEl.remove(); };

    var ro = root.ResizeObserver ? new ResizeObserver(function () { update(); }) : null;
    if (ro) { ro.observe(canvas); ro.observe(preTrk); ro.observe(rows.FREQ.trk); }
    if (dark && dark.addEventListener) dark.addEventListener("change", update);
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(queueDraw);
    update();
  }

  root.ContourTune = { mount: function (el, opts) { return new Tune(el, opts || {}); } };
})(window);
