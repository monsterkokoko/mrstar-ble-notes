// effects.js: a model of every MR Star (FINDN) LED garland effect, for drawing it on a screen.
// Usage: frame(FX_BY_ID.get(0x4C), 65, t, out) fills out[i*4..i*4+3] = r, g, b, lit (0..1) for bead i at t seconds
// (speed 50, direction "forward"). Beads are counted from the controller. See EFFECTS.md for what each one does.
// The group names and labels are Ukrainian, as on the site this came from.

// MR Star effects as the garland really plays them, one model each in frame(). Fitted to a real garland: on 2026-11-18 the phone's camera filmed every effect at speed 50 (BLE Bridge /burst,
// 30 frames a second) and we read off, bead by bead, its colours, pattern, step and speed. Positions are in the strip's
// own beads (65, counted from the controller), times in seconds at speed 50.
// Its seven colours, as they look on it: the strip's orange shows as a warm yellow and its yellow as lime, because its
// green is strong (the same reason strip.py corrects the colours we send). Its "7 colours" are the rainbow, r o y g c b p.
const PAL = { r: [255, 45, 45], o: [255, 205, 60], y: [185, 255, 50], g: [40, 255, 90], c: [40, 200, 255],
              b: [50, 70, 255], p: [190, 80, 255], w: [225, 245, 255] };
const PAL_NAME = { r: "червоний", o: "помаранчевий", y: "жовтий", g: "зелений", c: "блакитний", b: "синій", p: "фіолетовий", w: "білий" };
const RAINBOW = "roygcbp", STRIP_N = 65;
const FX_GROUPS = [], FX_BY_ID = new Map();
const cap = s => s[0].toUpperCase() + s.slice(1);
function group(title, solo) { FX_GROUPS.push({ title, solo, items: [] }); }
// pal: the strip's colours in the order it runs them (for "over", the one colour it runs the rainbow over).
function fx(id, kind, pal = RAINBOW, name) {
  const g = FX_GROUPS[FX_GROUPS.length - 1];
  name ||= pal === RAINBOW ? "7 кольорів" : cap([...pal].map(k => PAL_NAME[k]).join(" · "));
  const f = { id, kind, name, pal: [...pal].map(k => PAL[k]), label: g.solo ? name : `${g.title} · ${name[0].toLowerCase()}${name.slice(1)}` };
  f.tint = g.solo || pal === RAINBOW ? [205, 140, 255] : f.pal[0];  // the light it throws round the tile
  g.items.push(f); FX_BY_ID.set(id, f);
}
group("Веселка", true);
fx(0x02, "symphony", undefined, "Симфонія"); fx(0x01, "auto", undefined, "Автоцикл"); fx(0x0A, "gradient", undefined, "Перелив");
fx(0x03, "energy", undefined, "Енергія"); fx(0x04, "jumps", undefined, "Стрибки"); fx(0x07, "strobe", undefined, "Стробоскоп");
group("Потік");
fx(0x37, "water"); fx(0x38, "water", "bgr"); fx(0x39, "water", "pcy"); fx(0x3A, "water", "rg"); fx(0x3B, "water", "gb");
fx(0x3C, "water", "yb"); fx(0x3D, "water", "yc"); fx(0x3E, "water", "cp");
group("Хвіст");
fx(0x4C, "tail"); fx(0x4D, "tail", "r"); fx(0x4E, "tail", "g"); fx(0x4F, "tail", "b"); fx(0x50, "tail", "y");
fx(0x51, "tail", "c"); fx(0x52, "tail", "p"); fx(0x53, "tail", "w");
group("Біг");
fx(0x5B, "run"); fx(0x5C, "run", "bgr"); fx(0x54, "run", "r"); fx(0x55, "run", "g"); fx(0x56, "run", "b");
fx(0x57, "run", "y"); fx(0x58, "run", "c"); fx(0x59, "run", "p"); fx(0x5A, "run", "w");
fx(0x5D, "run", "p", "Фіолетовий, другий");  // listed as purple-cyan-yellow; ours plays it purple only, like 0x59
group("Смуги");
fx(0x2D, "bands"); fx(0x2E, "bands", "brg"); fx(0x2F, "bands", "pcy");
group("Веселка на тлі");
fx(0x30, "over", "r"); fx(0x31, "over", "g"); fx(0x32, "over", "b"); fx(0x33, "over", "c"); fx(0x34, "over", "y");
fx(0x35, "over", "p"); fx(0x36, "over", "w");
group("Розкриття");
fx(0x23, "open"); fx(0x24, "open", "rgb"); fx(0x25, "open", "ycp"); fx(0x26, "open", "r"); fx(0x27, "open", "g");
fx(0x28, "open", "b"); fx(0x29, "open", "y"); fx(0x2A, "open", "c"); fx(0x2B, "open", "p"); fx(0x2C, "open", "w");
group("Пензель");
fx(0x1D, "brush"); fx(0x1E, "brush", "rgb"); fx(0x1F, "brush", "ycp"); fx(0x20, "pull", undefined, "7 кольорів, швидко");
group("Мерехтіння");
fx(0x1A, "flutter"); fx(0x1B, "flutter", "rgb");

const frac = x => x - Math.floor(x), mod = (a, n) => ((a % n) + n) % n;
const hueRgb = h => { h = mod(h, 360) / 60; const f = n => { const k = mod(n + h, 6); return 255 * (1 - Math.max(0, Math.min(k, 4 - k, 1))); }; return [f(5), f(3), f(1)]; };
const mixRgb = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
// How the strip shows a hue it runs through by itself (measured, see HUE_SEEN in light/strip.py): for the smooth rainbows.
const HUE_SENT = [0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 360];
const HUE_SEEN = [0, 52, 82, 116, 123, 175, 200, 215, 237, 253, 275, 300, 360];
const stripHue = h => { h = mod(h, 360); const k = Math.min(11, Math.floor(h / 30));
  return hueRgb(HUE_SEEN[k] + (h - HUE_SENT[k]) / 30 * (HUE_SEEN[k + 1] - HUE_SEEN[k])); };
// Patterns that run away from the controller: [length of one repeat, beads a second].
const MOVE = { tail: [13, 13], run: [6, 10.5], flutter: [4, 10.5], bands: [17, 7.5], over: [13, 7] };
// One frame of effect f on n beads at time t (seconds at speed 50): out[i*4..] = r, g, b, how lit (0..1).
// span: how many of the strip's beads the n drawn ones stand for (the garland drawing: all 65; an effect card: a short
// stretch at true size). Effects laid over the whole strip (opening, brushing) always use the whole strip.
function frame(f, n, t, out, span = STRIP_N) {
  const P = f.pal, L = P.length;
  for (let i = 0; i < n; i++) {
    const x = i * span / n, s = n > 1 ? i / (n - 1) * (STRIP_N - 1) : 0;
    let c = P[0], a = 1;
    switch (f.kind) {
      // a rainbow longer than the strip (a whole turn over ~105 beads), creeping along it
      case "symphony": c = stripHue(3.4 * x - 48 * t); break;
      // the whole strip one colour, gliding round the hues every 7.5 s
      case "auto": c = stripHue(48 * t); break;
      // the whole strip one colour at a time, 0.66 s each, dipping dark between them
      case "gradient": { const q = t / .66, k = Math.floor(q), p = q - k; c = P[k % L]; a = Math.max(0, Math.min(1, p / .15, (1 - p) / .15)); break; }
      // every bead its own colour of the seven, the rainbow running along
      case "energy": c = P[mod(-Math.floor(x - t * 14), L)]; break;
      case "jumps": c = P[Math.floor(t / .37) % L]; break;
      // a colour for 0.4 s, dark for 0.4 s, the next colour
      case "strobe": { const k = Math.floor(t / .4); c = P[Math.floor(k / 2) % L]; a = k % 2 ? 0 : 1; break; }
      // blocks of 8 beads of each colour, flowing on, edges blending over a bead
      case "water": { const q = (x - t * 13) / 8, m = Math.floor(q), p = q - m; c = P[mod(-m, L)];
        if (p > 7 / 8) c = mixRgb(c, P[mod(-m - 1, L)], (p - 7 / 8) * 8); break; }
      case "tail": case "run": case "flutter": case "bands": case "over": {
        const [len, v] = MOVE[f.kind], h = t * v - x, m = Math.floor(h / len), d = h - m * len;
        // comets every 13 beads: a bright head, 6 beads of tail, 4 fading, 2 dark; each comet the next colour
        if (f.kind === "tail") { c = P[mod(m, L)]; a = d < 1 ? 1 : d < 7 ? .85 : d < 11 ? [.65, .45, .28, .12][Math.floor(d) - 7] : 0; }
        // 13 beads of a colour, 4 dark
        else if (f.kind === "bands") { c = P[mod(m, L)]; a = d < 13 ? 1 : 0; }
        // 7 beads of the next rainbow colour, 6 of the background colour
        else if (f.kind === "over") c = d < 7 ? PAL[RAINBOW[mod(m, 7)]] : P[0];
        // one bright bead every 6 (running) or 4 (fluttering) on a dim strip, each the next colour
        // (a drawn bead stands for span / n of the strip's; it lights if the bright one falls in its stretch)
        else { c = P[mod(m, L)]; a = d < Math.max(1, span / n) ? 1 : .16; }
        break; }
      // 6.5 s a colour: it lights from bead 18 outwards and from the far end back, the two meeting; after a pause
      // the stretch from bead 18 back to the controller fills; all lit a moment; dark 1.3 s; the next colour
      case "open": { const q = t / 6.53, k = Math.floor(q), u = (q - k) * 6.53; c = P[k % L];
        a = u < 5.23 && (u > 4.96 || (s >= 18 && (s <= 18 + 11 * u || s >= STRIP_N - 1 - 11 * u)) || (u > 3.23 && s >= 18 - 10.5 * (u - 3.23))) ? 1 : 0; break; }
      // the next colour sweeps from the far end back to the controller (13 beads a second), then holds
      case "brush": case "pull": { const B = f.kind === "pull" ? 6.2 : 12.2, q = t / B, k = Math.floor(q), u = (q - k) * B;
        c = P[mod(s >= STRIP_N - 1 - 13 * u ? k : k - 1, L)]; break; }
    }
    out[i * 4] = c[0]; out[i * 4 + 1] = c[1]; out[i * 4 + 2] = c[2]; out[i * 4 + 3] = a;
  }
}

module.exports = { PAL, RAINBOW, STRIP_N, FX_GROUPS, FX_BY_ID, frame };
