"use strict";

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const app = $("#app");
const typeInput = $("#type");

const LIVE_TV = "com.mitv.livetv";
const LAUNCHER = "com.argon.launcher";
// Shown when the TV can't send an app's banner.
const APP_STYLE = {
  "com.google.android.youtube.tv": {
    bg: "#181818",
    fg: "#fff",
    text: "YouTube",
  },
  "com.netflix.ninja": { bg: "#050505", fg: "#E50914", text: "NETFLIX" },
  "com.amazon.amazonvideo.livingroom": {
    bg: "#1A98FF",
    fg: "#fff",
    text: "prime",
  },
  "in.startv.hotstar": { bg: "#4B1FC2", fg: "#fff", text: "Hotstar" },
  "com.sonyliv": { bg: "#0E0E12", fg: "#fff", text: "LIV" },
};

/* ---------- Icons ---------- */

const GL = {
  cross: '<path d="M5.5 5.5l13 13M18.5 5.5l-13 13" stroke-width="2.6"/>',
  circle: '<circle cx="12" cy="12" r="7.5" stroke-width="2.2"/>',
  square:
    '<rect x="5" y="5" width="14" height="14" rx="1.5" stroke-width="2.2"/>',
  triangle: '<path d="M12 4.5 20.5 19h-17z" stroke-width="2.2"/>',
  home: '<path d="M3.5 10.5 12 3.5l8.5 7V20a.5.5 0 0 1-.5.5h-5v-6h-6v6H4a.5.5 0 0 1-.5-.5z"/>',
  kbd: '<rect x="2.5" y="6" width="19" height="12" rx="2" stroke-width="1.8"/><path d="M6.5 10h.01M10 10h.01M14 10h.01M17.5 10h.01M7.5 14h9" stroke-width="1.8"/>',
  power: '<path d="M12 3v8"/><path d="M6.3 6.8a8 8 0 1 0 11.4 0"/>',
  mute: '<path d="M11 5 6 9H2.5v6H6l5 4z" stroke-width="1.8"/><path d="m22 9-6 6M16 9l6 6" stroke-width="1.8"/>',
  speaker:
    '<path d="M11 5 6 9H2.5v6H6l5 4z" stroke-width="1.8"/><path d="M15.5 9.5a3.5 3.5 0 0 1 0 5" stroke-width="1.8"/>',
  speaker2:
    '<path d="M11 5 6 9H2.5v6H6l5 4z" stroke-width="1.8"/><path d="M15.5 9.5a3.5 3.5 0 0 1 0 5M18.5 6.5a7.5 7.5 0 0 1 0 11" stroke-width="1.8"/>',
  minus: '<path d="M5 12h14" stroke-width="2.2"/>',
  plus: '<path d="M5 12h14M12 5v14" stroke-width="2.2"/>',
  chev: '<path d="m9 6 6 6-6 6" stroke-width="2.4"/>',
  playf: '<path d="M6 3.5v17l14-8.5z" fill="currentColor" stroke="none"/>',
  download: '<path d="M12 4v11M7.5 10.5 12 15l4.5-4.5"/><path d="M5 19h14"/>',
  full: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
  bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
  gamepad:
    '<path d="M7.5 6h9a5 5 0 0 1 4.9 4l1 5a2.6 2.6 0 0 1-4.6 2.1L16 15H8l-1.8 2.1A2.6 2.6 0 0 1 1.6 15l1-5A5 5 0 0 1 7.5 6z" stroke-width="1.7"/><path d="M6.5 10.5h3M8 9v3" stroke-width="1.7"/><circle cx="15.5" cy="10" r=".6"/><circle cx="17.5" cy="12" r=".6"/>',
  up: '<path d="M12 19V5M6 11l6-6 6 6" stroke-width="3"/>',
  down: '<path d="M12 5v14M6 13l6 6 6-6" stroke-width="3"/>',
  left: '<path d="M19 12H5M11 6l-6 6 6 6" stroke-width="3"/>',
  right: '<path d="M5 12h14M13 6l6 6-6 6" stroke-width="3"/>',
  rew: '<path d="M11 6 4 12l7 6zM20 6l-7 6 7 6z" stroke-width="2"/>',
  ff: '<path d="m13 6 7 6-7 6zM4 6l7 6-7 6z" stroke-width="2"/>',
  x: '<path d="M6 6l12 12M18 6 6 18" stroke-width="1.8"/>',
  live: '<rect x="2.5" y="6.5" width="19" height="13" rx="2"/><path d="M16.5 2.5 12 6.5 7.5 2.5"/>',
  hdmi: '<path d="M3.5 8h17v5l-3 3.5h-11l-3-3.5z"/><path d="M8 11.5h8"/>',
  av: '<rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M8 20h8M12 16.5V20"/>',
  eyeoff:
    '<path d="M3 3l18 18M10.6 5.1A9.6 9.6 0 0 1 12 5c5 0 8.5 4.5 9.5 7a13 13 0 0 1-2.6 3.8M6.6 6.6C4.4 8 3 10.2 2.5 12c1 2.5 4.5 7 9.5 7 1.6 0 3-.4 4.3-1.1"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
};
function svg(name, size = 20) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${GL[name] || ""}</svg>`;
}
// The ○ △ ✕ □ mark, for the launcher itself and the Welcome hub.
const MARK =
  '<svg viewBox="0 0 84 84" fill="none" stroke="currentColor" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="20" cy="20" r="14"/><path d="M64 6 79 33H49z"/><path d="M8 52l24 24M32 52 8 76"/><rect x="50" y="50" width="28" height="28" rx="3"/></svg>';
function paintIcons(root = document) {
  $$("i[data-i]", root).forEach((i) => {
    i.outerHTML = svg(i.dataset.i, +i.dataset.s || 20);
  });
}
paintIcons();

/* ---------- Small helpers shared with views.js ---------- */

function el(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k === "style") e.style.cssText = v;
    else if (k === "text") e.textContent = v;
    else if (k === "html") e.innerHTML = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat())
    if (k != null && k !== false)
      e.append(k instanceof Node ? k : document.createTextNode(String(k)));
  return e;
}

async function api(path, body) {
  try {
    const r = await fetch(
      path,
      body === undefined
        ? {}
        : {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify(body),
          },
    );
    // Signed out (from the Admin page or by a password change): the server now serves sign-in.
    if (r.status === 401) location.reload();
    return await r.json();
  } catch {
    return { ok: false, error: "Can't reach the remote server" };
  }
}

let toastTimer = 0;
function toast(msg, err = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.toggle("err", err);
  t.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("show"), err ? 4000 : 1800);
}
const done = (r, okMsg) =>
  r && r.ok !== false
    ? okMsg && toast(okMsg)
    : toast(r?.error || "That didn't work", true);

const store = {
  get(k, d) {
    try {
      return localStorage.getItem(k) ?? d;
    } catch {
      return d;
    }
  },
  set(k, v) {
    try {
      localStorage.setItem(k, v);
    } catch {}
  },
};
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

/* ---------- App tiles ---------- */

const bannerUrl = (pkg) => `/api/home/banner?pkg=${encodeURIComponent(pkg)}`;
const tileBg = new Map();
// The banner is 16:9; the tile is square, so it sits in the middle on its own edge colour.
function edgeColor(img) {
  try {
    const c = document.createElement("canvas");
    c.width = 32;
    c.height = 18;
    const x = c.getContext("2d", { willReadFrequently: true });
    x.drawImage(img, 0, 0, 32, 18);
    const d = x.getImageData(0, 0, 32, 18).data;
    let r = 0,
      g = 0,
      b = 0,
      n = 0;
    for (let y = 0; y < 18; y++)
      for (const px of [0, 31]) {
        const i = (y * 32 + px) * 4;
        r += d[i];
        g += d[i + 1];
        b += d[i + 2];
        n++;
      }
    return `rgb(${Math.round(r / n)},${Math.round(g / n)},${Math.round(b / n)})`;
  } catch {
    return null;
  }
}
function fillTile(t, pkg, name = "") {
  if (t.dataset.pkg === pkg) return t;
  t.dataset.pkg = pkg;
  t.classList.remove("has-img", "glyph");
  t.style.background = "";
  t.style.color = "";
  t.replaceChildren();
  if (!pkg || pkg === LAUNCHER) {
    t.classList.add("glyph");
    t.innerHTML = MARK;
    return t;
  }
  const st = APP_STYLE[pkg];
  const tx = el("span", {
    class: "tx",
    text: st?.text || name || pkg.split(".").pop(),
  });
  if (st) t.style.cssText = `background:${st.bg};color:${st.fg}`;
  const img = el("img", { alt: "", src: bannerUrl(pkg), draggable: "false" });
  if (tileBg.has(pkg)) t.style.background = tileBg.get(pkg);
  img.addEventListener("load", () => {
    if (t.dataset.pkg !== pkg) return;
    if (!tileBg.has(pkg)) tileBg.set(pkg, edgeColor(img));
    if (tileBg.get(pkg)) t.style.background = tileBg.get(pkg);
    t.classList.add("has-img");
  });
  img.addEventListener("error", () => img.remove());
  t.append(tx, img);
  return t;
}
const tileEl = (pkg, name, cls = "") =>
  fillTile(el("span", { class: `tile ${cls}`.trim() }), pkg, name);

/* ---------- Keys ---------- */

const KEYMAP = {
  ArrowUp: "UP",
  ArrowDown: "DOWN",
  ArrowLeft: "LEFT",
  ArrowRight: "RIGHT",
  Enter: "OK",
  Escape: "BACK",
  Backspace: "BACK",
  h: "HOME",
  H: "HOME",
  " ": "PLAY_PAUSE",
  "[": "VOL_DOWN",
  "]": "VOL_UP",
  m: "MUTE",
  M: "MUTE",
  ".": "MENU",
};
const KEY_LABEL = {
  UP: "UP",
  DOWN: "DOWN",
  LEFT: "LEFT",
  RIGHT: "RIGHT",
  OK: "OK",
  BACK: "BACK",
  HOME: "HOME",
  PLAY_PAUSE: "PLAY",
  MENU: "MENU",
  VOL_UP: "VOL +",
  VOL_DOWN: "VOL −",
  MUTE: "MUTE",
  POWER: "POWER",
  REWIND: "REWIND",
  FAST_FORWARD: "FORWARD",
};
const KEY_GLYPH = {
  OK: "cross",
  BACK: "circle",
  PLAY_PAUSE: "square",
  MENU: "triangle",
  UP: "up",
  DOWN: "down",
  LEFT: "left",
  RIGHT: "right",
  HOME: "home",
  VOL_UP: "plus",
  VOL_DOWN: "minus",
  MUTE: "mute",
  POWER: "power",
  REWIND: "rew",
  FAST_FORWARD: "ff",
};
const ARROWS = new Set(["UP", "DOWN", "LEFT", "RIGHT"]);
const SHAPES = new Set(["OK", "BACK", "PLAY_PAUSE", "MENU"]);

let S = {
  connected: false,
  now: { pkg: "", name: "" },
  volume: -1,
  kbd: false,
  apps: [],
  inputs: [],
};
let latency = null;
let lastInput = null;

/* ---------- Layout ---------- */

// Phone under 700 px wide, or any touch screen whose short side is phone-sized; landscape on a
// phone is the two-thumb couch layout.
function pickMode() {
  const q = new URLSearchParams(location.search).get("mode");
  if (q === "phone" || q === "tablet" || q === "laptop") return q;
  const coarse = matchMedia("(pointer: coarse)").matches;
  if (innerWidth < 700 || (coarse && Math.min(innerWidth, innerHeight) < 500))
    return "phone";
  return coarse ? "tablet" : "laptop";
}
function applyMode() {
  const m = pickMode();
  if (app.dataset.mode !== m) app.dataset.mode = m;
  app.toggleAttribute(
    "data-land",
    m === "phone" && innerWidth > innerHeight && innerHeight < 560,
  );
}
applyMode();
addEventListener("resize", () => {
  applyMode();
  paintPlaceholder();
  // The lights card has a laptop and a tablet form.
  if (H.configured) paintHome();
});

/* ---------- Connection ---------- */

let ws = null;
let backoff = 250;
let retryTimer = 0;
let seq = 0;
const pending = new Map();
const on = {};

function connect() {
  clearTimeout(retryTimer);
  ws = new WebSocket(
    `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`,
  );
  ws.onopen = () => {
    backoff = 250;
    ping();
    renderStatus();
  };
  ws.onclose = (e) => {
    // 4001: this device was signed out. A refused connection may mean the same, so ask.
    if (e.code === 4001) return location.reload();
    fetch("/api/auth/status")
      .then((r) => r.json())
      .then((s) => s.signedIn || location.reload())
      .catch(() => {});
    renderStatus();
    retryTimer = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 2000);
  };
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    on[m.t]?.(m);
  };
}
const open = () => ws?.readyState === 1;
function send(o) {
  if (open()) ws.send(JSON.stringify(o));
}
function ping() {
  send({ t: "ping", c: performance.now() });
}
setInterval(ping, 2000);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && (!ws || ws.readyState > 1))
    connect();
});

on.pong = (m) => {
  latency = performance.now() - m.c + (m.adb || 0);
  renderStatus();
};
on.ack = (m) => {
  const p = pending.get(m.id);
  if (!p) return;
  pending.delete(m.id);
  const ms = performance.now() - p.t0 + (m.adb || 0);
  addRecent(p.k, p.label, ms);
  if (m.id === chip.id) ((chip.ms = ms), paintChips());
  flashLight();
  mirror.afterKey();
};
on.state = (m) => {
  const appsChanged = JSON.stringify(m.apps) !== JSON.stringify(S.apps);
  const inputsChanged = JSON.stringify(m.inputs) !== JSON.stringify(S.inputs);
  const volChanged =
    S.volume >= 0 &&
    m.volume >= 0 &&
    (m.volume !== S.volume || m.muted !== S.muted);
  const kbdOpened = m.kbd && !S.kbd;
  const kbdClosed = !m.kbd && S.kbd;
  S = m;
  if (volChanged) showVolumeHud();
  if (appsChanged) buildApps();
  if (inputsChanged) buildInputs();
  render();
  if (kbdOpened) autoType();
  if (kbdClosed && app.classList.contains("typing") && tmode === "type")
    closeType();
};

function track(k, label) {
  const id = ++seq;
  const now = performance.now();
  for (const [x, p] of pending) if (now - p.t0 > 5000) pending.delete(x);
  pending.set(id, { t0: now, k, label });
  return id;
}
// A short tick on phones that can; not for controller presses, and not before the first tap
// (Chrome refuses it then).
let quiet = false;
function buzz(ms = 8) {
  if (quiet || navigator.userActivation?.hasBeenActive === false) return;
  try {
    navigator.vibrate?.(ms);
  } catch {}
}

function tap(k) {
  buzz();
  const id = track(k, KEY_LABEL[k] ?? k);
  noteKey(k, id);
  send({ t: "tap", k, id });
}
function down(k) {
  buzz();
  if (k === "VOL_UP" || k === "VOL_DOWN" || k === "MUTE") showVolumeHud();
  const id = track(k, KEY_LABEL[k] ?? k);
  noteKey(k, id);
  send({ t: "down", k, id });
}
function up(k) {
  send({ t: "up", k });
}
function long(k) {
  buzz(14);
  const id = track(k, `HOLD ${KEY_LABEL[k] ?? k}`);
  noteKey(k, id);
  send({ t: "long", k, id });
}
function visual(k, onState) {
  $$(`[data-hold="${k}"]`).forEach((x) =>
    x.classList.toggle("pressed", onState),
  );
}

// Buttons send key down on touch and key up on release, so holding repeats on the TV
// exactly like the physical remote.
const heldButtons = new Map();
$$("[data-hold]").forEach((b) => {
  const k = b.dataset.hold;
  b.addEventListener("pointerdown", (e) => {
    if (e.button > 0) return;
    e.preventDefault();
    e.stopPropagation();
    b.setPointerCapture?.(e.pointerId);
    if (heldButtons.has(b)) return;
    heldButtons.set(b, k);
    down(k);
    visual(k, true);
  });
  const end = () => {
    if (!heldButtons.has(b)) return;
    heldButtons.delete(b);
    up(k);
    visual(k, false);
  };
  b.addEventListener("pointerup", end);
  b.addEventListener("pointercancel", end);
  b.addEventListener("lostpointercapture", end);
});

const heldKeys = new Map();
addEventListener("keydown", (e) => {
  if (e.target === typeInput || e.metaKey || e.ctrlKey || e.altKey) return;
  if (
    app.dataset.view !== "remote" ||
    e.target.closest?.("input, textarea, select, dialog")
  )
    return;
  if (e.key === "/") {
    e.preventDefault();
    openType();
    return;
  }
  if (/^[1-9]$/.test(e.key)) {
    const i = +e.key - 1;
    if (S.apps[i]) {
      e.preventDefault();
      if (!e.repeat) openApp(i);
    }
    return;
  }
  const k = KEYMAP[e.key];
  if (!k) return;
  e.preventDefault();
  if (e.repeat || heldKeys.has(e.code)) return;
  heldKeys.set(e.code, k);
  down(k);
  visual(k, true);
});
addEventListener("keyup", (e) => {
  const k = heldKeys.get(e.code);
  if (!k) return;
  heldKeys.delete(e.code);
  up(k);
  visual(k, false);
});
function releaseAll() {
  for (const [code, k] of heldKeys)
    (up(k), visual(k, false), heldKeys.delete(code));
  for (const [b, k] of heldButtons)
    (up(k), visual(k, false), heldButtons.delete(b));
  gpRelease();
}
addEventListener("blur", releaseAll);
document.addEventListener(
  "visibilitychange",
  () => document.visibilityState === "hidden" && releaseAll(),
);
document.addEventListener(
  "contextmenu",
  (e) => e.target !== typeInput && e.preventDefault(),
);
document.addEventListener("gesturestart", (e) => e.preventDefault());
$$("[data-focus=type]").forEach((b) => b.addEventListener("click", openType));

/* ---------- Light bar, last-key chip, last presses ---------- */

// Blue when all is well, white for a moment on every key the TV confirms, orange while the TV
// is muted, red while the TV or the remote server can't be reached.
function paintLight() {
  app.dataset.light =
    !open() || !S.connected ? "off" : S.muted ? "muted" : "idle";
}
let flashTimer = 0;
function flashLight() {
  app.classList.add("flash");
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => app.classList.remove("flash"), 150);
}

const chip = { k: null, n: 0, t: 0, id: 0, ms: null };
let chipTimer = 0;
function noteKey(k, id) {
  const now = performance.now();
  if (k === chip.k && now - chip.t < 1500) chip.n++;
  else ((chip.k = k), (chip.n = 1));
  chip.t = now;
  chip.id = id;
  chip.ms = null;
  paintChips();
}
function paintChips() {
  const k = chip.k;
  const word = ARROWS.has(k)
    ? `×${chip.n}`
    : `${KEY_LABEL[k] ?? k}${chip.n > 1 ? ` ×${chip.n}` : ""}`;
  const html = `${svg(KEY_GLYPH[k] || "cross", 14)}<span>${word}</span><em>${chip.ms == null ? "" : `${Math.round(chip.ms)} ms`}</em>`;
  $$(".keychip").forEach((c) => {
    c.innerHTML = html;
    c.classList.add("show");
  });
  clearTimeout(chipTimer);
  chipTimer = setTimeout(
    () => $$(".keychip").forEach((c) => c.classList.remove("show")),
    2500,
  );
}

const recents = [];
function addRecent(k, label, ms) {
  const now = Date.now();
  const last = recents[0];
  if (last && last.label === label && now - last.t < 4000)
    ((last.n += 1), (last.t = now), (last.ms = ms));
  else recents.unshift({ k, label, n: 1, t: now, ms });
  recents.length = Math.min(recents.length, 9);
  $("#recent").replaceChildren(
    ...recents.map((r, i) =>
      el(
        "span",
        {
          class: `kc${i === 0 ? " on" : ""}`,
          title: `${Math.round(r.ms)} ms`,
          html:
            SHAPES.has(r.k) && !r.label.startsWith("HOLD")
              ? svg(KEY_GLYPH[r.k], 13)
              : "",
        },
        `${r.label}${r.n > 1 ? ` ×${r.n}` : ""}`,
      ),
    ),
  );
}

/* ---------- Swipe pad (and the TV picture, which swipes the same way) ---------- */

class Pad {
  constructor(surface) {
    this.el = surface;
    this.cv = $("canvas", surface);
    this.ctx = this.cv.getContext("2d");
    this.id = null;
    this.pts = [];
    this.rings = [];
    this.flings = [];
    this.raf = 0;
    surface.addEventListener("pointerdown", (e) => this.down(e));
    surface.addEventListener("pointermove", (e) => this.move(e));
    surface.addEventListener("pointerup", (e) => this.end(e, true));
    surface.addEventListener("pointercancel", (e) => this.end(e, false));
    new ResizeObserver(() => this.resize()).observe(surface);
  }

  resize() {
    const r = this.cv.getBoundingClientRect();
    const d = devicePixelRatio || 1;
    this.w = r.width;
    this.h = r.height;
    this.cv.width = Math.round(r.width * d);
    this.cv.height = Math.round(r.height * d);
    this.ctx.setTransform(d, 0, 0, d, 0, 0);
    this.step = Math.max(34, Math.min(60, Math.min(r.width, r.height) / 7));
  }

  pos(e) {
    const r = this.cv.getBoundingClientRect();
    return {
      x: e.clientX - r.left,
      y: e.clientY - r.top,
      t: performance.now(),
    };
  }

  down(e) {
    if (this.id !== null || e.button > 0) return;
    e.preventDefault();
    this.id = e.pointerId;
    this.el.setPointerCapture(e.pointerId);
    this.flings.forEach(clearTimeout);
    this.flings = [];
    const p = this.pos(e);
    this.start = p;
    this.anchor = { x: p.x, y: p.y };
    this.moved = false;
    this.held = false;
    this.dir = null;
    this.hist = [p];
    this.pts = [p];
    this.el.classList.add("active");
    this.holdTimer = setTimeout(() => {
      if (this.moved || this.id === null) return;
      this.held = true;
      long("OK");
      this.rings.push({ ...p, t: performance.now(), big: true });
      this.loop();
    }, 450);
    this.loop();
  }

  move(e) {
    if (e.pointerId !== this.id) return;
    const p = this.pos(e);
    if (
      !this.moved &&
      Math.hypot(p.x - this.start.x, p.y - this.start.y) > 10
    ) {
      this.moved = true;
      clearTimeout(this.holdTimer);
    }
    if (this.held) return;
    this.pts.push(p);
    this.hist.push(p);
    while (this.hist.length > 2 && p.t - this.hist[0].t > 90) this.hist.shift();
    if (this.moved) {
      const st = this.step;
      let dx = p.x - this.anchor.x;
      let dy = p.y - this.anchor.y;
      while (Math.abs(dx) >= st || Math.abs(dy) >= st) {
        if (Math.abs(dx) >= Math.abs(dy)) {
          this.anchor.x += Math.sign(dx) * st;
          this.anchor.y = p.y;
          this.emit(dx > 0 ? "RIGHT" : "LEFT");
        } else {
          this.anchor.y += Math.sign(dy) * st;
          this.anchor.x = p.x;
          this.emit(dy > 0 ? "DOWN" : "UP");
        }
        dx = p.x - this.anchor.x;
        dy = p.y - this.anchor.y;
      }
    }
    this.loop();
  }

  emit(dir) {
    tap(dir);
    this.dir = dir;
  }

  end(e, fling) {
    if (e.pointerId !== this.id) return;
    this.id = null;
    clearTimeout(this.holdTimer);
    this.el.classList.remove("active");
    const p = this.pos(e);
    if (!this.moved && !this.held) {
      tap("OK");
      this.rings.push({ ...p, t: performance.now(), big: false });
    } else if (fling && this.moved && this.dir) {
      // A quick flick keeps going, like a scroll with momentum.
      const f = this.hist[0];
      const l = this.hist[this.hist.length - 1];
      const horiz = this.dir === "LEFT" || this.dir === "RIGHT";
      const v =
        Math.abs(horiz ? l.x - f.x : l.y - f.y) / Math.max(16, l.t - f.t);
      if (v > 1.1) {
        const extra = Math.max(1, Math.min(5, Math.round((v - 1.1) * 2.5)));
        for (let i = 1; i <= extra; i++)
          this.flings.push(setTimeout(() => this.emit(this.dir), i * 55));
      }
    }
    this.loop();
  }

  loop() {
    if (this.raf) return;
    const frame = () => {
      this.raf = 0;
      if (this.draw()) this.raf = requestAnimationFrame(frame);
    };
    this.raf = requestAnimationFrame(frame);
  }

  draw() {
    const c = this.ctx;
    const now = performance.now();
    const LIFE = 420;
    const live = this.id !== null && !this.held;
    c.clearRect(0, 0, this.w, this.h);
    const last = this.pts[this.pts.length - 1];
    this.pts = this.pts.filter((p) => now - p.t < LIFE || (live && p === last));
    c.lineCap = "round";
    c.lineJoin = "round";
    for (let i = 1; i < this.pts.length; i++) {
      const a = this.pts[i - 1];
      const b = this.pts[i];
      const k = Math.max(0, 1 - (now - b.t) / LIFE);
      // Opaque fades over the dark surface, so the joints don't double up.
      c.strokeStyle = `rgb(${30 + 190 * k},${33 + 188 * k},${42 + 182 * k})`;
      c.lineWidth = 3 + 7 * k;
      c.beginPath();
      c.moveTo(a.x, a.y);
      c.lineTo(b.x, b.y);
      c.stroke();
    }
    if (live && last) {
      c.fillStyle = "rgba(255,255,255,0.16)";
      c.strokeStyle = "rgba(255,255,255,0.7)";
      c.lineWidth = 2;
      c.beginPath();
      c.arc(last.x, last.y, 22, 0, Math.PI * 2);
      c.fill();
      c.stroke();
    }
    this.rings = this.rings.filter((r) => now - r.t < (r.big ? 650 : 450));
    for (const r of this.rings) {
      const k = (now - r.t) / (r.big ? 650 : 450);
      const ease = 1 - (1 - k) ** 3;
      if (r.big) {
        c.fillStyle = `rgba(90,162,255,${0.25 * (1 - k)})`;
        c.beginPath();
        c.arc(r.x, r.y, 20 + 90 * ease, 0, Math.PI * 2);
        c.fill();
      }
      c.strokeStyle = `rgba(255,255,255,${0.7 * (1 - k)})`;
      c.lineWidth = 2;
      c.beginPath();
      c.arc(r.x, r.y, 12 + (r.big ? 90 : 46) * ease, 0, Math.PI * 2);
      c.stroke();
    }
    return live || this.pts.length > 1 || this.rings.length > 0;
  }
}
new Pad($("#pad"));

/* ---------- Live picture of the TV ---------- */

// GET /api/screen is a ~960×540 JPEG of the screen. One request at a time; refreshed when it
// comes into view, 0.5 s and 1.6 s after the last key the TV confirms, and every 5 s while it's
// on screen and the page is visible.
class Mirror {
  constructor(box, img, note, badge) {
    this.box = box;
    this.img = img;
    this.note = note;
    this.badge = badge;
    this.busy = false;
    this.again = false;
    this.visible = false;
    this.url = null;
    this.timer = 0;
    this.keyTimer = 0;
    this.settleTimer = 0;
    new IntersectionObserver(([e]) => {
      this.visible = e.isIntersecting;
      if (this.visible) this.refresh();
      else clearTimeout(this.timer);
    }).observe(box);
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && this.visible) this.refresh();
    });
  }
  // Again once the home screen's picture has cross-faded (it waits for the focus to settle).
  afterKey() {
    if (!this.visible) return;
    clearTimeout(this.keyTimer);
    clearTimeout(this.settleTimer);
    this.keyTimer = setTimeout(() => this.refresh(), 500);
    this.settleTimer = setTimeout(() => this.refresh(), 1600);
  }
  soon(ms = 1200) {
    clearTimeout(this.keyTimer);
    this.keyTimer = setTimeout(() => this.refresh(), ms);
  }
  async refresh() {
    if (!this.visible || document.hidden) return;
    if (this.busy) {
      this.again = true;
      return;
    }
    this.busy = true;
    clearTimeout(this.timer);
    try {
      const r = await fetch(`/api/screen?t=${Date.now()}`, {
        cache: "no-store",
      });
      if (r.status === 401) return location.reload();
      if (r.status === 409) (this.img.removeAttribute("src"), this.hidden());
      else if (
        !r.ok ||
        !(r.headers.get("content-type") || "").startsWith("image/")
      )
        this.fail(
          r.status === 503
            ? "The TV picture isn't available right now"
            : "Can't load the TV picture",
        );
      else await this.show(await r.blob());
    } catch {
      this.fail("Can't reach the TV");
    } finally {
      this.busy = false;
      if (this.again) {
        this.again = false;
        this.refresh();
      } else if (this.visible && !document.hidden)
        this.timer = setTimeout(() => this.refresh(), 5000);
    }
  }
  async show(blob) {
    const url = URL.createObjectURL(blob);
    const probe = new Image();
    probe.src = url;
    try {
      await probe.decode();
    } catch {
      URL.revokeObjectURL(url);
      return this.fail("Can't load the TV picture");
    }
    // The last frame fades out over the new one (its picture is let go once it's gone).
    const last = this.url;
    if (last && this.img.getAttribute("src")) {
      const old = this.img.cloneNode();
      old.removeAttribute("id");
      old.className = "mirror-old";
      this.img.after(old);
      setTimeout(() => (old.remove(), URL.revokeObjectURL(last)), 300);
    } else if (last) URL.revokeObjectURL(last);
    this.img.src = url;
    this.url = url;
    this.box.classList.remove("stale");
    this.badge?.classList.remove("off");
    if (isBlack(probe)) this.hidden();
    else this.say(null);
  }
  // Protected video: the TV refuses the capture (409) or sends black.
  hidden() {
    const pkg = S.now.pkg;
    const name =
      pkg && pkg !== LAUNCHER && S.now.name ? S.now.name : "this app";
    this.say(`Picture hidden while ${name} plays`, true);
  }
  fail(msg) {
    this.box.classList.add("stale");
    this.badge?.classList.add("off");
    this.say(msg, false);
  }
  say(msg, small) {
    if (!msg) return (this.note.hidden = true);
    this.note.hidden = false;
    this.note.classList.toggle("small", !!small);
    this.note.innerHTML = small ? svg("eyeoff", 16) : "";
    this.note.append(msg);
  }
}
// Protected video (Netflix and the like) comes back black.
function isBlack(img) {
  try {
    const c = document.createElement("canvas");
    c.width = 32;
    c.height = 18;
    const x = c.getContext("2d", { willReadFrequently: true });
    x.drawImage(img, 0, 0, 32, 18);
    const d = x.getImageData(0, 0, 32, 18).data;
    let sum = 0,
      max = 0;
    for (let i = 0; i < d.length; i += 4) {
      const l = 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
      sum += l;
      max = Math.max(max, l);
    }
    return sum / (d.length / 4) < 8 && max < 32;
  } catch {
    return false;
  }
}
const mirror = new Mirror(
  $("#mirror"),
  $("#mirrorImg"),
  $("#mirrorNote"),
  $("#liveBadge"),
);
new Pad($("#mirror"));

$("#fullBtn").addEventListener("click", () => {
  const m = $("#mirror");
  (m.requestFullscreen || m.webkitRequestFullscreen)?.call(m);
});
$("#shotBtn").addEventListener("click", async () => {
  const b = $("#shotBtn");
  b.disabled = true;
  try {
    const r = await fetch("/api/screen?full=1", { cache: "no-store" });
    if (r.status === 401) return location.reload();
    if (!r.ok || !(r.headers.get("content-type") || "").startsWith("image/"))
      return toast("The TV didn't send a screenshot", true);
    const url = URL.createObjectURL(await r.blob());
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-");
    el("a", { href: url, download: `argon-tv-${stamp}.png` }).click();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch {
    toast("Can't reach the TV", true);
  } finally {
    b.disabled = false;
  }
});

/* ---------- Apps, inputs ---------- */

function openApp(i) {
  const a = S.apps[i];
  if (!a) return;
  buzz(10);
  send({ t: "app", i, id: track("APP", a.name.toUpperCase()) });
  S.now = { pkg: a.pkg, name: a.name };
  render();
}
function openPkg(pkg, name) {
  const i = S.apps.findIndex((a) => a.pkg === pkg);
  if (i >= 0) return openApp(i);
  buzz(10);
  send({ t: "app", pkg, name, id: track("APP", name.toUpperCase()) });
  S.now = { pkg, name };
  render();
}

// The TV's home row (dock) and app names, for the laptop's app grid.
let homeApps = { dock: [], labels: {} };
async function loadHomeApps() {
  const r = await api("/api/home");
  if (!r.ok) return;
  homeApps = {
    dock: r.state.dock || [],
    labels: Object.fromEntries(r.state.apps.map((a) => [a.pkg, a.label])),
  };
  buildApps();
}

function buildApps() {
  const list = S.apps.map((a, i) => ({ pkg: a.pkg, name: a.name, i }));
  for (const pkg of homeApps.dock) {
    if (list.length >= 10) break;
    if (!list.some((a) => a.pkg === pkg))
      list.push({ pkg, name: homeApps.labels[pkg] || pkg.split(".").pop() });
  }
  $("#tiles").replaceChildren(
    ...list.map((a) =>
      el(
        "button",
        {
          class: "appbtn",
          "data-pkg": a.pkg,
          "aria-label": `Open ${a.name}${a.i != null ? `, shortcut ${a.i + 1}` : ""}`,
          onclick: () => openPkg(a.pkg, a.name),
        },
        tileEl(a.pkg, a.name),
        el("span", { class: "nm", text: a.name.replace(/ Video$/, "") }),
      ),
    ),
  );
  $("#dock").replaceChildren(
    ...S.apps.map((a, i) =>
      el(
        "button",
        {
          class: "appbtn",
          "data-pkg": a.pkg,
          "aria-label": `Open ${a.name}`,
          onclick: () => openApp(i),
        },
        tileEl(a.pkg, a.name),
      ),
    ),
  );
  $("#appsHint").textContent = S.apps.length
    ? `1–${S.apps.length} on the keyboard`
    : "";
  paintApps();
}
function paintApps() {
  $$(".appbtn").forEach((b) =>
    b.classList.toggle("on", b.dataset.pkg === S.now.pkg),
  );
}

const INPUT_ORDER = (i) =>
  i.id === "live" ? 0 : i.label.startsWith("HDMI") ? 1 : 2;
function buildInputs() {
  const items = [...S.inputs].sort(
    (a, b) => INPUT_ORDER(a) - INPUT_ORDER(b) || a.label.localeCompare(b.label),
  );
  $("#inputs").replaceChildren(
    ...items.map((inp) =>
      el(
        "button",
        {
          class: `ringbtn${inp.connected === false ? " unplugged" : ""}`,
          "data-key": inp.id,
          onclick: () => {
            buzz(10);
            lastInput = inp.id;
            send({ t: "input", id: inp.id });
            render();
          },
        },
        el("span", {
          class: "rg",
          html: svg(
            inp.id === "live"
              ? "live"
              : inp.label.startsWith("HDMI")
                ? "hdmi"
                : "av",
            22,
          ),
        }),
        el("span", { text: inp.label }),
      ),
    ),
  );
}
function paintInputs() {
  const pkg = S.now.pkg;
  const current =
    S.input ??
    (pkg === LIVE_TV
      ? "live"
      : pkg.startsWith("com.mediatek")
        ? lastInput
        : null);
  $$("#inputs button").forEach((b) =>
    b.classList.toggle("on", b.dataset.key === current),
  );
}

/* ---------- Lights and plugs (the room's, through the TV) ---------- */

Object.assign(GL, {
  house:
    '<path d="M3 11 12 4l9 7"/><path d="M5.5 9.5V20h13V9.5"/><path d="M10 20v-5h4v5"/>',
  lightbar:
    '<rect x="8.5" y="2.5" width="7" height="14.5" rx="1.6"/><path d="M12 17v3.5M8 21h8"/>',
  bulb: '<path d="M7.8 13.2A6 6 0 1 1 16.2 13.2L14.5 15v2.5h-5V15z"/><path d="M10.5 20.5h3"/>',
  lamp: '<path d="M8.5 3h7l3.5 8h-14z"/><path d="M12 11v8.5M8 21h8"/>',
  leaf: '<path d="M12 21V10"/><path d="M12 10c0-3.5 2.6-6 6.5-6.5C18 7.4 15.6 10 12 10z"/><path d="M12 14.5c0-3-2.3-5.2-5.8-5.6.4 3.4 2.6 5.6 5.8 5.6z"/>',
  plug: '<path d="M9 3v4M15 3v4M6 7h12v4a6 6 0 0 1-12 0z"/><path d="M12 17v4"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/>',
  timer:
    '<circle cx="12" cy="13.5" r="7.5"/><path d="M12 10v3.5l2.4 1.6M9.5 2.5h5"/>',
});
// The colours SmartHome knows (smarthome/Palette.java): id, name, how it looks, white or not.
const PRESETS = [
  ["warm", "Warm", "#ffc778", true],
  ["soft", "Soft white", "#ffe6c4", true],
  ["daylight", "Daylight", "#eef4ff", true],
  ["red", "Red", "#ff4a3d"],
  ["orange", "Orange", "#ff8a2a"],
  ["violet", "Violet", "#9d6bff"],
  ["blue", "Blue", "#3d7bff"],
  ["green", "Green", "#3ddc84"],
];
const TIMERS = [
  [1800, "30 min"],
  [3600, "1 h"],
  [7200, "2 h"],
  [0, "Off"],
];

let H = { devices: [], scenes: [], configured: false, on: 0, watts: 0 };
let homeSel = store.get("homeSel", "");
let sheetId = null;
let homeHeld = 0;
let homePending = false;

on.home = (m) => {
  H = m;
  if (homeHeld) homePending = true;
  else paintHome();
  if (app.dataset.view === "home") VIEWS.home.smartRefresh?.();
};
on.homeError = (m) => toast(m.error, true);

const homeDo = (o) => send({ t: "home", ...o });
const dev = (id) => H.devices.find((d) => d.id === id);
const lit = (d) => d.on && d.online;
const isLight = (d) => d.kind === "light";
// Bright colours (whites, warm white, amber) take dark ink.
function pale(hex) {
  const n = parseInt(hex.slice(1), 16);
  return (
    (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255 >
    0.66
  );
}
function deep(hex, f) {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${Math.round((n >> 16) * f)},${Math.round(((n >> 8) & 255) * f)},${Math.round((n & 255) * f)})`;
}
const fmtW = (w) => (Number.isInteger(w) ? String(w) : w.toFixed(1));
const kwh = (v) => v.toFixed(v < 0.1 ? 3 : 2);
function devState(d) {
  if (!d.online) return "Not reachable";
  if (!d.on) return "Off";
  return isLight(d) ? `${d.bri}% · ${d.colorName}` : `${fmtW(d.watts)} W`;
}
// "Hue play 1" → "Play 1", "Main Lamp" → "Lamp": what fits on a phone chip. Short names stay whole.
function shortName(name) {
  const w = name.trim().split(/\s+/);
  if (w.length < 2 || name.trim().length <= 8) return name.trim();
  const tail = /^\d+$/.test(w.at(-1)) ? w.slice(-2) : w.slice(-1);
  const s = tail.join(" ");
  return s.charAt(0).toUpperCase() + s.slice(1);
}
function timerText(d) {
  if (!d.timer) return "Timer";
  const m = Math.max(1, Math.round(d.timer / 60));
  return `Off in ${m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ""}` : `${m} min`}`;
}
function recount() {
  H.on = H.devices.filter((d) => d.on).length;
}

// Shown at once; the TV's next state confirms it.
function toggleDev(id) {
  const d = dev(id);
  if (!d) return;
  buzz(10);
  d.on = !d.on;
  recount();
  paintHome();
  homeDo({ id, on: d.on });
}
let briTimer = 0;
function setBri(id, v) {
  const d = dev(id);
  if (!d) return;
  d.bri = clamp(Math.round(v), 1, 100);
  d.on = true;
  recount();
  clearTimeout(briTimer);
  briTimer = setTimeout(() => homeDo({ id, bri: d.bri }), 70);
}
function setColor(id, p) {
  const d = dev(id);
  if (!d) return;
  buzz(8);
  Object.assign(d, { color: p[0], colorName: p[1], rgb: p[2], on: true });
  recount();
  paintHome();
  homeDo({ id, color: p[0] });
}
const presetsFor = (d) =>
  PRESETS.filter(([, , , white]) => (white ? d.whites || d.colors : d.colors));

// Tap: switch it. On a card, tapping the name (on a laptop's tile, anywhere but the ring) only picks it for the
// controls. Hold: its sheet.
function holdable(b, id) {
  let t = 0;
  let held = false;
  b.addEventListener("pointerdown", (e) => {
    if (e.button > 0) return;
    held = false;
    t = setTimeout(() => {
      held = true;
      buzz(14);
      openSheet(id);
    }, 480);
  });
  for (const ev of ["pointerup", "pointerleave", "pointercancel"])
    b.addEventListener(ev, () => clearTimeout(t));
  b.addEventListener("click", (e) => {
    if (held) return (held = false);
    homeSel = id;
    store.set("homeSel", id);
    const pick =
      app.dataset.mode === "laptop" && b.classList.contains("lv-dev")
        ? !e.target.closest(".rg")
        : !!e.target.closest(".lv-t");
    if (pick) return paintHome();
    toggleDev(id);
  });
}
// Sliders hold off re-painting until they're let go, so a drag isn't rebuilt under the finger.
function holdPaint(on) {
  homeHeld += on ? 1 : -1;
  if (!homeHeld && homePending) ((homePending = false), paintHome());
}

function devButton(d, cls) {
  const b = el(
    "button",
    {
      class: `${cls}${lit(d) ? " on" : ""}${d.online ? "" : " gone"}${homeSel === d.id ? " sel" : ""}`,
      style: lit(d)
        ? `--c:${d.rgb};--c2:${deep(d.rgb, 0.5)};--ink:${pale(d.rgb) ? "#1a1206" : "#fff"}`
        : "",
      "aria-label": `${d.name}, ${devState(d)}`,
      "aria-pressed": String(d.on),
    },
    el("span", { class: "rg", html: svg(d.icon, cls === "lchip" ? 18 : 22) }),
  );
  holdable(b, d.id);
  return b;
}

function paintHome() {
  app.toggleAttribute("data-home", !!H.configured);
  paintStrip();
  $$(".living").forEach(paintLiving);
  if (sheetId) paintSheet();
}

function paintStrip() {
  $("#lstrip").replaceChildren(
    ...H.devices.map((d) => {
      const b = devButton(d, "lchip");
      b.append(el("span", { text: shortName(d.name) }));
      return b;
    }),
  );
}

// The card: compact beside the tablet's touch surface; on a laptop, the middle of the page, with the picked one's
// controls (as in the sheet) and the scenes as cards.
function paintLiving(card) {
  if (!H.configured) return card.replaceChildren();
  if (!dev(homeSel)) homeSel = (H.devices.find(isLight) || H.devices[0]).id;
  const sel = dev(homeSel);
  const sum = `${H.on} on${H.watts > 0 ? ` · ${fmtW(H.watts)} W` : ""}`;
  const head = el(
    "div",
    { class: "card-head" },
    el(
      "div",
      { class: "ch-l" },
      el("span", { class: "label", text: H.room }),
      el("span", { class: "lv-sum", text: sum }),
    ),
    el("button", {
      class: "btn small soft",
      text: H.on ? "All off" : "All on",
      onclick: () => {
        buzz(10);
        const on = !H.on;
        H.devices.forEach((d) => (d.on = on));
        recount();
        paintHome();
        homeDo({ all: on });
      },
    }),
  );
  const devs = el(
    "div",
    { class: "lv-devs" },
    ...H.devices.map((d) => {
      const b = devButton(d, "lv-dev");
      b.append(
        el(
          "span",
          { class: "lv-t" },
          el("b", { text: d.name }),
          el("small", { text: devState(d) }),
        ),
      );
      return b;
    }),
  );
  if (card.dataset.at === "center" && app.dataset.mode === "laptop")
    return card.replaceChildren(
      head,
      devs,
      el("div", { class: "lv-detail" }, ...deviceDetail(sel)),
      scenesSection(),
    );
  card.replaceChildren(
    head,
    devs,
    el(
      "div",
      { class: "lv-row" },
      isLight(sel) ? lightControls(sel) : plugControls(sel),
      el("div", { class: "lv-scenes" }, ...H.scenes.map((sc) => sceneChip(sc))),
    ),
  );
}

function sceneChip(sc) {
  return el(
    "button",
    {
      class: "scene",
      title: sc.sub,
      onclick: () => {
        buzz(10);
        homeDo({ scene: sc.id });
        toast(`${sc.name}`);
      },
    },
    el(
      "span",
      { class: "sc-dots" },
      ...sc.colors.map((c) => el("i", { style: c ? `background:${c}` : "" })),
    ),
    sc.name,
  );
}

// Brightness, then the colours (on the same line when there's room). A light that's off shows the level it comes
// back at, faded; moving the slider turns it on.
function lightControls(d) {
  const pct = el("b", { class: "lv-pct", text: d.on ? `${d.bri}%` : "Off" });
  const input = el("input", {
    type: "range",
    min: "1",
    max: "100",
    step: "1",
    value: String(d.bri),
    "aria-label": `${d.name} brightness`,
  });
  const box = el(
    "label",
    { class: "slider", style: `--v:${d.bri}%` },
    input,
    el(
      "span",
      { class: "track" },
      el("span", { class: "fill" }),
      el("span", { class: "thumb" }),
    ),
  );
  input.addEventListener("pointerdown", () => holdPaint(true));
  input.addEventListener("change", () => holdPaint(false));
  const bri = el(
    "span",
    { class: `lv-bri${d.on ? "" : " off"}` },
    el("span", { class: "ico", html: svg("sun", 16) }),
    box,
    pct,
  );
  input.addEventListener("input", () => {
    setBri(d.id, +input.value);
    box.style.setProperty("--v", `${input.value}%`);
    pct.textContent = `${input.value}%`;
    bri.classList.remove("off");
  });
  return el(
    "div",
    { class: "lv-ctl" },
    bri,
    el(
      "span",
      { class: "lv-sw" },
      ...presetsFor(d).map((p) =>
        el("button", {
          class: `sw${d.on && d.color === p[0] ? " cur" : ""}`,
          style: `background:${p[2]}`,
          title: p[1],
          "aria-label": p[1],
          onclick: () => setColor(d.id, p),
        }),
      ),
    ),
  );
}

function plugControls(d) {
  if (!d) return el("div", { class: "lv-ctl" });
  const next = TIMERS.find(([s]) => s > (d.timer || 0)) || TIMERS[3];
  return el(
    "div",
    { class: "lv-ctl plug" },
    el("span", { class: "ico", html: svg("plug", 16) }),
    el("span", {
      class: "lv-pw",
      text: d.online
        ? `${d.name} · ${fmtW(d.watts)} W now · ${kwh(d.today)} kWh today`
        : `${d.name} isn't reachable`,
    }),
    d.on
      ? el(
          "button",
          {
            class: "btn small soft",
            onclick: () => {
              buzz(8);
              d.timer = next[0];
              paintHome();
              homeDo({ id: d.id, timer: next[0] });
            },
          },
          el("span", { class: "ico", html: svg("timer", 15) }),
          timerText(d),
        )
      : null,
  );
}

/* The sheet: one light or plug, from a hold on its button. */

function openSheet(id) {
  sheetId = id;
  const sh = $("#lsheet");
  sh.hidden = false;
  requestAnimationFrame(() => sh.classList.add("open"));
  paintSheet();
}
function closeSheet() {
  const sh = $("#lsheet");
  sh.classList.remove("open");
  sheetId = null;
  setTimeout(() => sheetId || (sh.hidden = true), 220);
}
addEventListener("keydown", (e) => {
  if (e.key === "Escape" && sheetId) closeSheet();
});

function paintSheet() {
  const d = dev(sheetId);
  if (!d) return closeSheet();
  const sh = $("#lsheet");
  const icoStyle = lit(d)
    ? `background:linear-gradient(160deg,${d.rgb},${deep(d.rgb, 0.55)});color:${pale(d.rgb) ? "#1a1206" : "#fff"};box-shadow:0 0 22px ${d.rgb}88`
    : "";
  const parts = [
    el("span", { class: "ls-grab", onclick: closeSheet }),
    el(
      "div",
      { class: "ls-head" },
      el("span", { class: "ls-ico", style: icoStyle, html: svg(d.icon, 22) }),
      el(
        "div",
        { class: "ls-t" },
        el("b", { text: d.name }),
        el("small", { text: devState(d) }),
      ),
      el("button", {
        class: "switch",
        role: "switch",
        "aria-checked": String(d.on),
        "aria-label": `${d.name} on`,
        style: lit(d) ? `background:${d.rgb}` : "",
        onclick: () => toggleDev(d.id),
      }),
    ),
  ];
  parts.push(...deviceDetail(d), scenesSection());
  parts.push(
    el(
      "button",
      {
        class: "btn ls-show",
        onclick: () => {
          homeDo({ show: d.id });
          toast("Showing it on the TV");
          closeSheet();
        },
      },
      el("span", { class: "ico", html: svg("av", 18) }),
      "Show on TV",
    ),
  );
  sh.replaceChildren(
    el("div", { class: "ls-back", onclick: closeSheet }),
    el(
      "section",
      { class: "ls-panel", "aria-label": d.name, role: "dialog" },
      ...parts,
    ),
  );
}

// A light's brightness and colours, or a plug's power and timer: in the sheet, and in the laptop's big card.
function deviceDetail(d) {
  const out = [];
  if (isLight(d)) {
    const bar = el(
      "div",
      {
        class: `ls-bri${d.on ? "" : " off"}`,
        style: `--v:${d.bri}%;--c:${d.rgb};--c2:${deep(d.rgb, 0.55)}`,
        role: "slider",
        "aria-label": "Brightness",
        "aria-valuenow": String(d.on ? d.bri : 0),
      },
      el("i"),
      el("span", { class: "ico", html: svg("sun", 22) }),
      el("b", { text: d.on ? `${d.bri}%` : "Off" }),
    );
    let drag = null;
    const level = (e) => {
      const r = bar.getBoundingClientRect();
      return clamp(((e.clientX - r.left) / r.width) * 100, 1, 100);
    };
    bar.addEventListener("pointerdown", (e) => {
      drag = e.pointerId;
      bar.setPointerCapture(e.pointerId);
      holdPaint(true);
      move(e);
    });
    const move = (e) => {
      if (e.pointerId !== drag) return;
      const v = Math.round(level(e));
      setBri(d.id, v);
      bar.classList.remove("off");
      bar.style.setProperty("--v", `${v}%`);
      bar.querySelector("b").textContent = `${v}%`;
    };
    bar.addEventListener("pointermove", move);
    const end = (e) => {
      if (e.pointerId !== drag) return;
      drag = null;
      holdPaint(false);
    };
    bar.addEventListener("pointerup", end);
    bar.addEventListener("pointercancel", end);
    out.push(
      bar,
      el(
        "div",
        { class: "ls-sec" },
        el("span", { class: "label", text: "Colour" }),
        el(
          "div",
          { class: "ls-sw" },
          ...presetsFor(d).map((p) =>
            el("button", {
              class: `sw${d.on && d.color === p[0] ? " cur" : ""}`,
              style: `background:${p[2]}`,
              "aria-label": p[1],
              onclick: () => setColor(d.id, p),
            }),
          ),
        ),
      ),
    );
  } else {
    const stat = (label, value, unit) =>
      el(
        "div",
        { class: "ls-stat" },
        el("span", { class: "label", text: label }),
        el("b", { text: value }, el("small", { text: ` ${unit}` })),
      );
    out.push(
      el(
        "div",
        { class: "ls-stats" },
        stat("Now", fmtW(d.watts), "W"),
        stat("Today", kwh(d.today), "kWh"),
        stat("This week", kwh(d.week), "kWh"),
        stat("This month", kwh(d.month), "kWh"),
      ),
      // A countdown switches the plug over, so it's only offered while the plug is on.
      d.on
        ? el(
            "div",
            { class: "ls-sec timer" },
            el("span", {
              class: "label",
              text: d.timer ? timerText(d) : "Turn off after",
            }),
            el(
              "div",
              { class: "seg" },
              ...TIMERS.map(([sec, label]) =>
                el("button", {
                  class:
                    (sec && Math.abs(d.timer - sec) < 90) || (!sec && !d.timer)
                      ? "on"
                      : "",
                  text: label,
                  onclick: () => {
                    buzz(8);
                    d.timer = sec;
                    paintHome();
                    homeDo({ id: d.id, timer: sec });
                  },
                }),
              ),
            ),
          )
        : null,
    );
  }
  return out;
}

function scenesSection() {
  return el(
    "div",
    { class: "ls-sec" },
    el("span", { class: "label", text: "Scenes" }),
    el("div", { class: "ls-scenes" }, ...H.scenes.map(sceneCard)),
  );
}

function sceneCard(sc) {
  const glow = sc.colors
    .map((c, i, a) =>
      c
        ? `radial-gradient(60px 46px at ${Math.round(((i + 0.5) / a.length) * 100)}% 55%, ${c}cc, transparent)`
        : null,
    )
    .filter(Boolean);
  return el(
    "button",
    {
      class: "ls-scene",
      style: `background:${[...glow, "#1b1d25"].join(",")}`,
      onclick: () => {
        buzz(10);
        homeDo({ scene: sc.id });
        toast(sc.name);
      },
    },
    el("span", { text: sc.name }),
  );
}

/* ---------- Volume ---------- */

const slider = $("#volSlider");
let sliding = false;
let volTimer = 0;
function setVolume(v) {
  sliding = true;
  paintVolume(v);
  clearTimeout(volTimer);
  volTimer = setTimeout(() => send({ t: "volume", v }), 60);
}
slider.addEventListener("input", () => setVolume(+slider.value));
slider.addEventListener("change", () =>
  setTimeout(() => (sliding = false), 400),
);

// Couch layout: drag the bar to set the level; its ends are − and + buttons.
const vslider = $("#vslider");
let vsDrag = null;
const vsLevel = (e) => {
  const r = vslider.getBoundingClientRect();
  return clamp(Math.round(((r.bottom - e.clientY) / r.height) * 100), 0, 100);
};
vslider.addEventListener("pointerdown", (e) => {
  if (e.target.closest("button") || e.button > 0) return;
  e.preventDefault();
  vsDrag = e.pointerId;
  vslider.setPointerCapture(e.pointerId);
  buzz();
  setVolume(vsLevel(e));
});
vslider.addEventListener("pointermove", (e) => {
  if (e.pointerId === vsDrag) setVolume(vsLevel(e));
});
const vsEnd = (e) => {
  if (e.pointerId !== vsDrag) return;
  vsDrag = null;
  setTimeout(() => (sliding = false), 400);
};
vslider.addEventListener("pointerup", vsEnd);
vslider.addEventListener("pointercancel", vsEnd);

// Big, short-lived volume readout, like the TV's own, whenever the level or mute changes.
let hudTimer = 0;
function showVolumeHud() {
  if (S.volume < 0) return;
  const hud = $("#volHud");
  const muted = !!S.muted;
  $("#vhIco").innerHTML = svg(
    muted ? "mute" : S.volume < 40 ? "speaker" : "speaker2",
    26,
  );
  $("#vhFill").style.width = `${S.volume}%`;
  $("#vhNum").textContent = muted ? "Muted" : S.volume;
  hud.classList.toggle("muted", muted);
  hud.classList.add("show");
  clearTimeout(hudTimer);
  hudTimer = setTimeout(() => hud.classList.remove("show"), 1600);
}

function paintVolume(v) {
  const known = v >= 0;
  const muted = !!S.muted;
  const pct = `${known ? v : 0}%`;
  $("#vmNum").textContent = !known ? "—" : muted ? "Muted" : v;
  $("#vmOf").hidden = !known || muted;
  $("#volmod").classList.toggle("muted", muted);
  $(".slider").style.setProperty("--v", pct);
  if (!sliding && known) slider.value = v;
  $("#vpFill").style.width = pct;
  $("#volNum").textContent = known ? v : "—";
  vslider.style.setProperty("--v", pct);
  vslider.setAttribute("aria-valuenow", String(known ? v : 0));
  $("#vsNum").textContent = known ? v : "—";
  $$(".mute").forEach((b) => b.classList.toggle("on", muted));
}

/* ---------- Status, banner ---------- */

// The red banner waits a moment so a page load or a quick reconnect doesn't flash it.
let bannerTimer = 0;
function renderStatus() {
  const st = $("#status");
  const txt = $("#latency");
  const liveNow = open() && S.connected;
  st.classList.toggle("live", liveNow);
  if (!open()) txt.textContent = "reconnecting";
  else if (!S.connected) txt.textContent = "TV offline";
  else txt.textContent = latency == null ? "live" : `${Math.round(latency)} ms`;
  paintLight();

  if (liveNow) {
    clearTimeout(bannerTimer);
    bannerTimer = 0;
    $("#banner").classList.remove("show");
    document.body.classList.remove("offline");
    return;
  }
  $("#bannerText").textContent = open()
    ? "TV not connected"
    : "Can't reach the TV";
  $("#bannerSub").textContent = open()
    ? `retrying ${S.address || ""}`
    : "retrying";
  if (!bannerTimer && !$("#banner").classList.contains("show")) {
    bannerTimer = setTimeout(() => {
      bannerTimer = 0;
      if (open() && S.connected) return;
      $("#banner").classList.add("show");
      document.body.classList.add("offline");
    }, 1800);
  }
}

/* ---------- Now on TV, Continue on TV ---------- */

const cont = { items: [], i: 0 };
function nowSub() {
  const it = cont.items.find((x) => x.pkg && x.pkg === S.now.pkg);
  return it ? [it.title, it.subtitle].filter(Boolean).join(" · ") : "";
}
function nowName() {
  const pkg = S.now.pkg;
  return !pkg || pkg === LAUNCHER ? "Home" : S.now.name || pkg;
}
let nowShown = null;
function paintNow() {
  // Another app on the TV: its name rises in, as on the TV's row.
  if (nowShown !== null && nowShown !== S.now.pkg)
    replay($("#nowPill"), $("#nowCard"));
  nowShown = S.now.pkg;
  fillTile($("#nowArt"), S.now.pkg, S.now.name);
  fillTile($("#nowPillArt"), S.now.pkg, S.now.name);
  $("#nowTitle").textContent = nowName();
  $("#nowPillName").textContent = nowName();
  $("#nowSub").textContent = nowSub();
  $("#nowPillSub").textContent = nowSub();
  if (!cont.items.length) paintContinue();
}

async function loadContinue() {
  if (document.hidden) return;
  const r = await api("/api/continue");
  if (r && r.ok && Array.isArray(r.items)) {
    const was = cont.items[cont.i]?.title;
    cont.items = r.items.slice(0, 10);
    const keep = cont.items.findIndex((x) => x.title === was);
    cont.i = keep >= 0 ? keep : 0;
  }
  paintContinue();
  paintNow();
}
setInterval(loadContinue, 30000);
addEventListener("focus", loadContinue);

const contImg = $("#contImg");
contImg.addEventListener("load", () => {
  contImg.hidden = false;
  contImg.classList.add("in");
});
contImg.addEventListener("error", () => (contImg.hidden = true));
function hash(s) {
  let h = 0;
  for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return (h >>> 0).toString(36);
}
function paintContinue() {
  const it = cont.items[cont.i];
  const n = cont.items.length;
  $("#cont").classList.toggle("empty", !it);
  $("#contResume").hidden = !it;
  $("#contNext").hidden = n < 2;
  const prog = $("#contProg");
  if (!it) {
    fillTile($("#contAppTile"), S.now.pkg, S.now.name);
    fillTile($("#contFallback"), S.now.pkg, S.now.name);
    $("#contApp").textContent = nowName();
    $("#contLabel").textContent = "Now on TV";
    $("#contTitle").textContent = nowName();
    $("#contSub").textContent = "Shows you're partway through show up here";
    contImg.hidden = true;
    contImg.removeAttribute("src");
    prog.hidden = true;
    return;
  }
  fillTile($("#contAppTile"), it.pkg, it.app);
  fillTile($("#contFallback"), it.pkg, it.app);
  $("#contApp").textContent = it.app || "";
  $("#contLabel").textContent = "Continue on TV";
  $("#contTitle").textContent = it.title;
  $("#contSub").textContent = [it.subtitle, `${cont.i + 1} of ${n}`]
    .filter(Boolean)
    .join(" · ");
  const src = `/api/continue/image?i=${cont.i}&v=${hash(`${it.title}|${it.subtitle}`)}`;
  if (contImg.getAttribute("src") !== src) {
    if (contImg.getAttribute("src")) replay($("#cont"));
    swapContImg(src);
  }
  prog.hidden = !(it.progress >= 0);
  prog.firstChild.style.width = `${clamp(it.progress, 0, 1) * 100}%`;
}
// The next picture fades in over the last one (a copy of it fading out on top) and settles from a slight zoom.
function swapContImg(src) {
  if (!contImg.hidden && contImg.complete && contImg.naturalWidth) {
    const old = contImg.cloneNode();
    old.removeAttribute("id");
    old.className = "cont-old";
    contImg.after(old);
    setTimeout(() => old.remove(), 420);
  }
  contImg.hidden = true;
  contImg.classList.remove("in");
  contImg.src = src;
}
// Plays a "swap" (the pieces of a card rising in again) once more.
function replay(...nodes) {
  for (const n of nodes) {
    if (!n) continue;
    n.classList.remove("swap");
    void n.offsetWidth;
    n.classList.add("swap");
    clearTimeout(n.swapTimer);
    n.swapTimer = setTimeout(() => n.classList.remove("swap"), 700);
  }
}
$("#contNext").addEventListener("click", () => {
  if (cont.items.length < 2) return;
  cont.i = (cont.i + 1) % cont.items.length;
  paintContinue();
});
$("#contResume").addEventListener("click", async () => {
  const it = cont.items[cont.i];
  if (!it) return;
  buzz(10);
  const r = await api("/api/continue/resume", { index: cont.i });
  done(r, `Resuming ${it.title} on the TV`);
  if (r.ok) mirror.soon(1500);
});

/* ---------- Render ---------- */

function render() {
  paintNow();
  paintApps();
  paintInputs();
  paintVolume(S.volume);
  renderStatus();
}

/* ---------- Typing on the TV, and notices ---------- */

let synced = "";
let typeTimer = 0;
let tmode = "type";

function flushType(enter = false) {
  clearTimeout(typeTimer);
  if (tmode !== "type") return;
  const v = typeInput.value;
  let p = 0;
  while (p < v.length && p < synced.length && v[p] === synced[p]) p++;
  const del = synced.length - p;
  const s = v.slice(p);
  if (del || s || enter) send({ t: "type", del, s, enter });
  synced = v;
}
function paintPlaceholder() {
  const phone = app.dataset.mode === "phone";
  typeInput.placeholder =
    tmode === "notice"
      ? phone
        ? "Notice for the TV"
        : "Write a notice for the TV"
      : phone
        ? "Type on the TV"
        : "Type into the TV's search box";
}
function setTypeMode(m) {
  if (m === tmode) return;
  flushType();
  tmode = m;
  typeInput.value = "";
  synced = "";
  $("#typebar").dataset.tmode = m;
  $$("[data-tmode]", $(".tmode")).forEach((b) => {
    b.classList.toggle("on", b.dataset.tmode === m);
    b.setAttribute("aria-pressed", String(b.dataset.tmode === m));
  });
  const notice = m === "notice";
  paintPlaceholder();
  typeInput.enterKeyHint = notice ? "send" : "search";
  if (notice) typeInput.maxLength = 500;
  else typeInput.removeAttribute("maxlength");
}
// The TV shows it as a notice card titled with this device's name.
async function sendNotice() {
  const text = typeInput.value.trim().slice(0, 500);
  if (!text) return;
  const r = await api("/api/notice", { text });
  if (r.ok) {
    typeInput.value = "";
    toast("Notice is on the TV");
  } else toast(r.error || "The TV didn't take the notice", true);
}
function openType() {
  app.classList.add("typing");
  $("#typebar").classList.add("focus");
  typeInput.focus();
}
// A text box on the TV just brought up its keyboard. On a laptop typing then goes straight to the
// TV; a phone or tablet only opens its keyboard from a tap, so the bar waits there for one.
function autoType() {
  if (app.dataset.view !== "remote" || app.classList.contains("typing")) return;
  if (document.activeElement?.closest?.("input, textarea, select, dialog"))
    return;
  setTypeMode("type");
  openType();
}
// A notice being written survives clicking away; text typed on the TV is already there.
function closeType(keep = false) {
  flushType();
  if (!keep) typeInput.value = "";
  synced = "";
  app.classList.remove("typing");
  $("#typebar").classList.remove("focus");
  typeInput.blur();
}
typeInput.addEventListener("input", () => {
  if (tmode !== "type") return;
  clearTimeout(typeTimer);
  typeTimer = setTimeout(flushType, 70);
});
typeInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    if (tmode === "notice") sendNotice();
    else flushType(true);
  } else if (e.key === "Escape") {
    e.preventDefault();
    closeType();
  } else if (
    e.key === "Backspace" &&
    typeInput.value === "" &&
    tmode === "type"
  ) {
    send({ t: "type", del: 1, s: "" });
  }
});
typeInput.addEventListener("focus", () => {
  app.classList.add("typing");
  $("#typebar").classList.add("focus");
});
typeInput.addEventListener("blur", () => {
  if (app.dataset.mode !== "phone") closeType(tmode === "notice");
});
$$("[data-tmode]", $(".tmode")).forEach((b) => {
  // Keep the field focused so switching doesn't close the bar.
  b.addEventListener("pointerdown", (e) => e.preventDefault());
  b.addEventListener("click", () => {
    setTypeMode(b.dataset.tmode);
    openType();
  });
});
$("#typeDone").addEventListener("click", () => closeType());
$("#kbdBtn").addEventListener("click", () =>
  app.classList.contains("typing") ? closeType() : openType(),
);

/* ---------- Game controller ---------- */

// Standard mapping: ✕ ○ □ △, L1/R1 seek, L2/R2 volume, Create types, Options is the hold-OK
// menu, the d-pad and left stick move, PS is Home, the touchpad click is OK.
const GP_KEYS = {
  0: "OK",
  1: "BACK",
  2: "PLAY_PAUSE",
  3: "MENU",
  4: "REWIND",
  5: "FAST_FORWARD",
  6: "VOL_DOWN",
  7: "VOL_UP",
  12: "UP",
  13: "DOWN",
  14: "LEFT",
  15: "RIGHT",
  16: "HOME",
  17: "OK",
};
const gp = { held: new Map(), sticks: new Map(), raf: 0 };
const pads = () => [...(navigator.getGamepads?.() || [])].filter(Boolean);
function paintCtrl() {
  $("#ctrlChip").hidden = !pads().length;
}
function gpRelease() {
  for (const [id, k] of gp.held) if (k) (up(k), visual(k, false));
  gp.held.clear();
  gp.sticks.clear();
}
function gpPoll(p) {
  p.buttons.forEach((b, i) => {
    const id = `${p.index}:${i}`;
    const pressed = i === 6 || i === 7 ? b.value > 0.4 : b.pressed;
    if (pressed === gp.held.has(id)) return;
    if (!pressed) {
      const k = gp.held.get(id);
      gp.held.delete(id);
      if (k) (up(k), visual(k, false));
      return;
    }
    const k = GP_KEYS[i] || null;
    gp.held.set(id, k);
    if (i === 8) {
      if (current !== "remote") setView("remote");
      setTypeMode("type");
      openType();
    } else if (i === 9) long("OK");
    else if (k) (down(k), visual(k, true));
  });
  const x = p.axes[0] || 0;
  const y = p.axes[1] || 0;
  const dir =
    Math.max(Math.abs(x), Math.abs(y)) < 0.55
      ? null
      : Math.abs(x) > Math.abs(y)
        ? x > 0
          ? "RIGHT"
          : "LEFT"
        : y > 0
          ? "DOWN"
          : "UP";
  const st = gp.sticks.get(p.index) || {};
  const now = performance.now();
  if (dir !== st.dir) {
    st.dir = dir;
    if (dir) (tap(dir), (st.next = now + 350));
  } else if (dir && now >= st.next) (tap(dir), (st.next = now + 120));
  gp.sticks.set(p.index, st);
}
function gpLoop() {
  if (gp.raf) return;
  const frame = () => {
    gp.raf = 0;
    const list = pads();
    if (!list.length) return (gpRelease(), paintCtrl());
    if (!document.hidden) {
      quiet = true;
      try {
        list.forEach(gpPoll);
      } finally {
        quiet = false;
      }
    }
    gp.raf = requestAnimationFrame(frame);
  };
  gp.raf = requestAnimationFrame(frame);
}
addEventListener("gamepadconnected", () => (paintCtrl(), gpLoop()));
addEventListener(
  "gamepaddisconnected",
  () => (paintCtrl(), pads().length || gpRelease()),
);
if (pads().length) (paintCtrl(), gpLoop());

/* ---------- Start ---------- */

render();
paintPlaceholder();
connect();
document.addEventListener("DOMContentLoaded", () => {
  loadContinue();
  loadHomeApps();
});
