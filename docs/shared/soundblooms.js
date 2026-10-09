/* The Soundblooms wordmark loop, ported 1:1 from the "header" variant of soundblooms.com
   (its Wordmark component: settle 9-15 s, drift 10-18 s into a 13-22 degree tilt, annoyed eyes,
   eyes push the band straight with a soft bounce, blink, happy arcs for 2 s, blink, repeat).
   Timings and easing curves are the same as there. The loop does not run under prefers-reduced-motion. */
(function () {
  "use strict";
  var EASE_SETTLE = "cubic-bezier(0.22, 1, 0.36, 1)";
  var EASE_DRIFT = "cubic-bezier(0.45, 0.05, 0.55, 0.95)";
  var EASE_BOUNCE = "linear(0, 0.45 16%, 0.83 32%, 1.05 52%, 0.985 66%, 1.018 80%, 0.997 90%, 1)";
  var mq = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : null;
  var marks = Array.prototype.slice.call(document.querySelectorAll(".sb-face"));
  if (!marks.length) return;

  function Face(face) {
    this.band = face.querySelector(".sb-band");
    this.eyes = face.querySelector(".sb-eyes");
    this.timers = [];
  }
  Face.prototype.setBand = function (rot, ms, ease) {
    this.band.style.transition = "transform " + ms + "ms " + ease;
    this.band.style.transform = "translate(-50%, 0) rotate(" + rot + "deg)";
  };
  Face.prototype.setEyes = function (mood) { this.eyes.setAttribute("data-eyes", mood); };
  Face.prototype.stop = function () {
    this.timers.forEach(clearTimeout);
    this.timers = [];
    this.band.style.transition = "none";
    this.band.style.transform = "translate(-50%, 0)";
    this.setEyes("calm");
  };
  Face.prototype.run = function () {
    var self = this;
    var push = function (ms, fn) { self.timers.push(setTimeout(fn, ms)); };
    self.timers.forEach(clearTimeout);
    self.timers = [];
    var settle = 9000 + Math.random() * 6000;
    var driftMs = 10000 + Math.random() * 8000;
    var crooked = (Math.random() < 0.5 ? -1 : 1) * (13 + Math.random() * 9);
    push(settle, function () { self.setBand(crooked, driftMs, EASE_DRIFT); });
    push(settle + driftMs - 2300, function () { self.setEyes("annoyed"); });
    var fixAt = settle + driftMs + 350;
    push(fixAt, function () { self.setEyes("fixing"); });
    push(fixAt + 560, function () { self.setBand(0, 1300, EASE_BOUNCE); });
    var blink1At = fixAt + 1050;
    push(blink1At, function () { self.setEyes("blink"); });
    var happyAt = blink1At + 600;
    push(happyAt, function () { self.setEyes("happy"); });
    var blink2At = happyAt + 2000;
    push(blink2At, function () { self.setEyes("blink"); });
    push(blink2At + 600, function () { self.setEyes("calm"); self.run(); });
  };

  var faces = marks.map(function (m) { return new Face(m); });
  function apply() {
    var reduced = !mq || mq.matches;
    faces.forEach(function (f) { if (reduced) f.stop(); else f.run(); });
  }
  apply();
  if (mq && mq.addEventListener) mq.addEventListener("change", apply);
})();
