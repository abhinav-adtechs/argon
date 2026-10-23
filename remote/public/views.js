"use strict";
/* Pages besides the remote: Home screen, Settings, Files, Watching, Device, Admin.
   Uses $, $$, app, S, send, tap, buzz, store, el, api, toast, done, svg, tileEl, Mirror from app.js. */

const ICONS = {
  folder:
    '<path d="M3.5 7.5a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>',
  file: '<path d="M6 3.5h8l4 4v13H6z"/><path d="M14 3.5v4h4"/>',
  image:
    '<rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="m4 18 5-5 4 4 2.5-2.5L20 19"/>',
  video:
    '<rect x="3.5" y="5.5" width="17" height="13" rx="2.5"/><path d="m10 9.5 5 2.5-5 2.5z"/>',
  audio:
    '<path d="M9 17.5V6l10-2v11.5"/><circle cx="6.5" cy="17.5" r="2.5"/><circle cx="16.5" cy="15.5" r="2.5"/>',
  text: '<path d="M6 3.5h8l4 4v13H6z"/><path d="M9 12h6M9 15.5h6M9 8.5h3"/>',
  apk: '<rect x="5" y="8" width="14" height="11" rx="2.5"/><path d="M8.5 8a3.5 3.5 0 0 1 7 0M9 5l-1-1.5M15 5l1-1.5"/>',
  download: '<path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/>',
  upload: '<path d="M12 20V9M7 13.5l5-5 5 5M5 4h14"/>',
  trash: '<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13"/>',
  refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4"/>',
  tv: '<rect x="3" y="4" width="18" height="12" rx="2.5"/><path d="M8 20h8M12 16v4"/>',
  back: '<path d="m14.5 6-6 6 6 6"/>',
  chev: '<path d="m9.5 6 6 6-6 6"/>',
  left: '<path d="m14.5 7-5 5 5 5"/>',
  right: '<path d="m9.5 7 5 5-5 5"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4 4"/>',
  picture:
    '<rect x="3" y="4.5" width="18" height="12" rx="2.5"/><path d="M8 20h8"/><path d="M7 13l3-3 2.5 2.5L15 10l2 2"/>',
  wifi: '<path d="M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0"/><circle cx="12" cy="19" r="1"/>',
  user: '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/>',
  grid: '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/>',
  remote:
    '<rect x="7" y="2.5" width="10" height="19" rx="5"/><circle cx="12" cy="9" r="2"/>',
  moon: '<path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z"/>',
  input:
    '<rect x="3" y="4.5" width="18" height="12" rx="2.5"/><path d="M8 20h8M12 7.5v5.5M9.5 10.5 12 13l2.5-2.5"/>',
  chart: '<path d="M5.5 19.5v-6M10 19.5V5.5M14.5 19.5v-9M19 19.5v-12"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
  access:
    '<circle cx="12" cy="5" r="1.8"/><path d="M5 8.5l7 1.5 7-1.5M12 10v4.5M9 21l3-6.5 3 6.5"/>',
  lock: '<rect x="5" y="10.5" width="14" height="10" rx="2.5"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>',
  info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.5v.5"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5" stroke-width="3.2"/>',
  minus: '<path d="M5 12h14" stroke-width="3.4"/>',
  playf: '<path d="M7 4.5v15l12-7.5z" fill="currentColor" stroke="none"/>',
  sparkle:
    '<path d="M12 2.5l2.1 6.4 6.4 2.1-6.4 2.1L12 19.5l-2.1-6.4L3.5 11l6.4-2.1z" fill="currentColor" stroke="none"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.2 2"/>',
  ball: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5l4 2.9-1.5 4.7h-5L8 10.4z"/>',
  cloud: '<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/>',
  hdmi: '<path d="M3.5 8h17v5l-3 3.5h-11l-3-3.5z"/><path d="M8 11.5h8"/>',
  shows:
    '<rect x="2.5" y="6.5" width="19" height="13" rx="2"/><path d="M16.5 2.5 12 6.5 7.5 2.5"/>',
  film: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4"/>',
  pin: '<path d="M12 21s-7-5.7-7-11a7 7 0 0 1 14 0c0 5.3-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/>',
  house:
    '<path d="M3 11 12 4l9 7"/><path d="M5.5 9.5V20h13V9.5"/><path d="M10 20v-5h4v5"/>',
  chevd: '<path d="m6 9 6 6 6-6" stroke-width="2.4"/>',
  dots: '<g fill="currentColor" stroke="none"><circle cx="5" cy="5" r="2"/><circle cx="12" cy="5" r="2"/><circle cx="19" cy="5" r="2"/><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/><circle cx="5" cy="19" r="2"/><circle cx="12" cy="19" r="2"/><circle cx="19" cy="19" r="2"/></g>',
};
function icon(name, size = 18) {
  const s = document.createElement("span");
  s.className = "ico";
  s.innerHTML = `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
  return s;
}
// Native range inputs paint their filled part from --v.
function fillRange(r) {
  const min = +r.min || 0,
    max = +r.max || 100;
  r.style.setProperty("--v", `${((+r.value - min) / (max - min)) * 100}%`);
  return r;
}
document.addEventListener("input", (e) => {
  if (e.target.classList?.contains("range")) fillRange(e.target);
});

function confirmBox(title, text, yes = "Delete") {
  const d = $("#confirm");
  $("#confirmTitle").textContent = title;
  $("#confirmText").textContent = text;
  $("#confirmYes").textContent = yes;
  d.showModal();
  return new Promise((resolve) => {
    const finish = (v) => {
      $("#confirmYes").onclick = $("#confirmNo").onclick = d.onclose = null;
      if (d.open) d.close();
      resolve(v);
    };
    $("#confirmYes").onclick = () => finish(true);
    $("#confirmNo").onclick = () => finish(false);
    d.onclose = () => finish(false);
  });
}

const GB = 1024 ** 3;
function fmtBytes(n) {
  if (!Number.isFinite(n)) return "—";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) ((n /= 1024), i++);
  return `${n >= 100 || i === 0 ? Math.round(n) : n.toFixed(1)} ${u[i]}`;
}
function fmtDuration(sec) {
  const d = Math.floor(sec / 86400),
    h = Math.floor((sec % 86400) / 3600),
    m = Math.floor((sec % 3600) / 60);
  return d ? `${d} d ${h} h` : h ? `${h} h ${m} min` : `${m} min`;
}
const fmtDate = (ms) =>
  new Date(ms).toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
function prettyPkg(pkg) {
  const parts = pkg
    .split(".")
    .filter((p) => !/^(android|tv|app|com|in|google|apps|org|net)$/.test(p));
  const last = parts.pop() || pkg;
  return last.charAt(0).toUpperCase() + last.slice(1);
}
const switchEl = (on, onclick, label) =>
  el("button", {
    class: "switch",
    role: "switch",
    "aria-checked": String(!!on),
    "aria-label": label,
    onclick,
  });

// App labels from the launcher, shared by Device and Settings.
let labelCache = null;
async function labels() {
  if (labelCache) return labelCache;
  const h = await api("/api/home");
  if (h.ok)
    labelCache = Object.fromEntries(h.state.apps.map((a) => [a.pkg, a.label]));
  return labelCache || {};
}

/* ================= Router ================= */

const VIEWS = {};
const ORDER = [
  "remote",
  "home",
  "settings",
  "files",
  "watch",
  "device",
  "admin",
];
let current = "remote";
let arrived = false;
function setView(v, push = true) {
  if (!ORDER.includes(v)) v = "remote";
  if (VIEWS[current]?.hide) VIEWS[current].hide();
  const from = current;
  current = v;
  app.dataset.view = v;
  $$(".tabs button").forEach((b) => {
    b.classList.toggle("on", b.dataset.view === v);
    if (b.dataset.view === v) b.setAttribute("aria-current", "page");
    else b.removeAttribute("aria-current");
  });
  $$(".view").forEach((x) => x.classList.toggle("on", x.id === `view-${v}`));
  if (v !== from || !arrived) arrive(v, from);
  arrived = true;
  if (push && location.hash.slice(1) !== v)
    history.replaceState(
      null,
      "",
      v === "remote" ? location.pathname + location.search : `#${v}`,
    );
  if (VIEWS[v]) VIEWS[v].show();
  $("#page").scrollTop = 0;
  showTab();
  miniTick();
  watchTick();
}
// A page arriving: its cards rise in one after another (the phone's slide in from the side you moved toward). Only for
// a moment, so what a page redraws later (Home on every change, Device every 2 s) doesn't play it again.
function arrive(v, from) {
  const node = v === "remote" ? app : $(`#view-${v}`);
  const cls = v === "remote" ? "arriving" : "entering";
  node.style.setProperty(
    "--dir",
    ORDER.indexOf(v) >= ORDER.indexOf(from) ? 1 : -1,
  );
  // The remote's cards in order down each column, the columns left to right (hidden ones don't count).
  if (v === "remote")
    [".col-l", ".col-c", ".col-r"].forEach((c, col) => {
      let i = 0;
      for (const e of $(c).children)
        if (e.offsetParent) e.style.setProperty("--d", col * 1.5 + i++);
    });
  node.classList.remove(cls);
  void node.offsetWidth;
  node.classList.add(cls);
  clearTimeout(node.arriveTimer);
  node.arriveTimer = setTimeout(() => node.classList.remove(cls), 1600);
}
// The phone's tab row scrolls; keep the open tab in view.
function showTab() {
  const tabs = $("#tabs");
  const b = $(".tabs button.on");
  if (!b || tabs.scrollWidth <= tabs.clientWidth) return;
  const l = b.offsetLeft - tabs.offsetLeft;
  if (
    l < tabs.scrollLeft ||
    l + b.offsetWidth > tabs.scrollLeft + tabs.clientWidth * 0.8
  )
    tabs.scrollTo({ left: Math.max(0, l - 24), behavior: "smooth" });
}
$$(".tabs button").forEach((b) =>
  b.addEventListener("click", () => setView(b.dataset.view)),
);
addEventListener("hashchange", () => setView(location.hash.slice(1), false));

/* ================= Header TV stats (tablet and laptop, every tab) ================= */

let miniTimer = 0;
async function miniTick() {
  clearTimeout(miniTimer);
  if (app.dataset.mode === "phone" || document.hidden) return;
  const s = await api("/api/stats");
  if (s && s.mem) {
    const ram = Math.round((1 - s.mem.available / s.mem.total) * 100);
    const cell = (label, value, hot) =>
      el(
        "span",
        { class: hot ? "hot" : null },
        label ? el("small", { text: label }) : null,
        value,
      );
    $("#mini").replaceChildren(
      cell("CPU", `${Math.round(s.cpu.usage)}%`, s.cpu.usage > 85),
      cell("RAM", `${ram}%`, ram > 90),
      cell(
        null,
        Number.isFinite(s.temp) ? `${Math.round(s.temp)}°C` : "—",
        s.temp >= 85,
      ),
    );
  }
  miniTimer = setTimeout(miniTick, 4000);
}
document.addEventListener("visibilitychange", miniTick);
addEventListener("resize", () => {
  if (app.dataset.mode !== "phone" && !$("#mini").childElementCount) miniTick();
});

/* ================= Watching: shared bits ================= */

// Stats come from the launcher on the TV (GET /api/watch); times are in milliseconds.
function fmtWatch(ms) {
  const m = Math.floor((ms || 0) / 60000);
  return m < 60
    ? `${m}m`
    : `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`;
}
const hourName = (h) => `${h % 12 || 12} ${h < 12 ? "AM" : "PM"}`;
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const DAYS_LONG = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
];
const clock = (ms) =>
  new Date(ms).toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });

function watchSplit(apps, key) {
  const parts = apps.filter((a) => a[key] > 0);
  if (!parts.length) return [el("i", { class: "empty" })];
  return parts.map((a) =>
    el("i", {
      style: `flex:${a[key]} 1 0;background:${a.color}`,
      title: `${a.name} ${fmtWatch(a[key])}`,
    }),
  );
}
const dot = (color) =>
  el("span", { class: "wdot", style: `background:${color}` });

/* Card on the remote page (laptop only; tablet and phone have no room). */

let watchTimer = 0;
async function watchTick() {
  clearTimeout(watchTimer);
  if (current !== "remote" || app.dataset.mode !== "laptop" || document.hidden)
    return;
  const s = await api("/api/watch");
  const card = $("#watchCard");
  card.hidden = !(s && s.tracking);
  if (s && s.tracking) {
    const watched = s.apps.filter((a) => a.today >= 60000);
    const whole = Math.max(1, s.today.screen, s.today.watched);
    $("#wcTotal").textContent = fmtWatch(s.today.watched);
    $("#wcScreen").textContent = `screen on ${fmtWatch(s.today.screen)}`;
    $("#wcSplit").replaceChildren(
      ...watched.map((a) =>
        el("i", {
          style: `width:${(a.today / whole) * 100}%;background:${a.color}`,
          title: `${a.name} ${fmtWatch(a.today)}`,
        }),
      ),
    );
    $("#wcTop").replaceChildren(
      ...(watched.length
        ? watched
            .slice(0, 3)
            .map((a) =>
              el(
                "div",
                { class: "wc-row" },
                tileEl(a.pkg, a.name),
                el("span", { text: a.name }),
                el("b", { text: fmtWatch(a.today) }),
              ),
            )
        : [el("p", { class: "hint", text: "Nothing watched yet today" })]),
    );
  }
  watchTimer = setTimeout(watchTick, 60000);
}
document.addEventListener("visibilitychange", watchTick);
addEventListener("resize", () => {
  if (current === "remote") watchTick();
});

/* ================= Watching ================= */

VIEWS.watch = {
  built: false,
  timer: 0,
  range: "today",
  data: null,
  build() {
    const v = $("#view-watch");
    v.innerHTML = `
      <div class="page-head"><div><h1>Watching</h1><p id="wSub">Reading the TV…</p></div>
        <div class="seg wrange" role="group" aria-label="Range">
          <button data-r="today" class="on" aria-pressed="true">Today</button>
          <button data-r="week" aria-pressed="false">This week</button>
        </div></div>
      <div class="grid-cards watch-grid">
        <div class="cardx"><h3 id="wTotalLabel">Watched today</h3><div class="stat-big" style="margin-top:14px" id="wTotal">—</div>
          <p class="wsubline" id="wTotalSub"></p><div class="wsplit big" id="wSplit"></div></div>
        <div class="cardx span-2"><div class="whead"><h3 id="wChartLabel">Today by hour</h3><div class="wlegend" id="wLegend"></div></div>
          <div class="wbars" id="wBars"></div><div class="waxis" id="wAxis"></div></div>
        <div class="cardx"><h3>Apps</h3><div id="wApps" style="margin-top:6px"></div></div>
        <div class="cardx span-2"><h3>Shows and videos</h3><div id="wShows" style="margin-top:8px"></div></div>
        <div class="cardx"><h3 class="wnowhead"><span class="wlive" id="wLive"></span>Now playing</h3><div id="wNow" style="margin-top:12px"></div>
          <div class="wrule"></div><div id="wFacts"></div></div>
      </div>`;
    $$(".wrange button", v).forEach((b) =>
      b.addEventListener("click", () => {
        this.range = b.dataset.r;
        $$(".wrange button", v).forEach((x) => {
          x.classList.toggle("on", x === b);
          x.setAttribute("aria-pressed", String(x === b));
        });
        if (this.data) this.render(this.data);
      }),
    );
    this.built = true;
  },
  show() {
    if (!this.built) this.build();
    this.tick();
  },
  hide() {
    clearTimeout(this.timer);
  },
  async tick() {
    clearTimeout(this.timer);
    if (current !== "watch") return;
    const s = await api("/api/watch");
    if (s && s.apps) {
      this.data = s;
      this.render(s);
    } else {
      $("#wSub").textContent = s?.error || "Can't read the TV";
    }
    this.timer = setTimeout(() => this.tick(), 30000);
  },
  render(s) {
    const week = this.range === "week";
    const since =
      s.since > 0
        ? new Date(s.since).toLocaleDateString(undefined, {
            weekday: "short",
            day: "numeric",
            month: "short",
          })
        : null;
    $("#wSub").textContent = s.tracking
      ? `Tracking on this TV${since ? ` since ${since}` : ""}`
      : "Usage access is off on the TV: run tools/build_install.sh";
    const key = week ? "week" : "today";
    const apps = s.apps.filter((a) => a[key] >= 60000);
    const total = week ? s.week.watched : s.today.watched;

    $("#wTotalLabel").textContent = week
      ? "Watched this week"
      : "Watched today";
    $("#wTotal").textContent = fmtWatch(total);
    $("#wTotalSub").textContent = week
      ? `${fmtWatch(s.week.screen)} with the screen on · ${s.weekRange}`
      : `of ${fmtWatch(s.today.screen)} with the screen on`;
    $("#wSplit").replaceChildren(...watchSplit(s.apps, key));

    $("#wChartLabel").textContent = week ? "This week by day" : "Today by hour";
    $("#wLegend").replaceChildren(
      ...apps.slice(0, 4).map((a) => el("span", {}, dot(a.color), a.name)),
      el("span", {}, dot("rgba(255,255,255,0.22)"), "Screen on"),
    );
    const buckets = week ? s.days : s.hours;
    const max = Math.max(3600000, ...(week ? buckets.map((b) => b.on) : [0]));
    const order = s.apps.map((a) => a.pkg);
    const colorOf = Object.fromEntries(s.apps.map((a) => [a.pkg, a.color]));
    $("#wBars").classList.toggle("days", week);
    $("#wBars").replaceChildren(
      ...buckets.map((b, i) =>
        el(
          "div",
          { class: "wcol", title: week ? DAYS_LONG[i] : hourName(i) },
          el("i", {
            class: "on",
            style: `height:${Math.min(100, (b.on / max) * 100)}%`,
          }),
          ...order
            .filter((p) => (b.apps[p] || 0) >= 30000)
            .map((p) =>
              el("i", {
                style: `height:${Math.min(100, (b.apps[p] / max) * 100)}%;background:${colorOf[p]}`,
              }),
            ),
        ),
      ),
    );
    $("#wAxis").classList.toggle("days", week);
    $("#wAxis").replaceChildren(
      ...(week ? DAYS : ["12 AM", "6 AM", "12 PM", "6 PM"]).map((t) =>
        el("span", { text: t }),
      ),
    );

    const top = Math.max(1, ...apps.map((a) => a[key]));
    $("#wApps").replaceChildren(
      ...(apps.length
        ? apps.slice(0, 6).map((a) =>
            el(
              "div",
              { class: "wapp" },
              el(
                "div",
                { class: "stat-line" },
                el("span", {}, dot(a.color), a.name),
                el("span", {
                  class: "wextra",
                  text: week
                    ? `${a.opens} ${a.opens === 1 ? "open" : "opens"}`
                    : `${Math.round((a.today / Math.max(1, total)) * 100)}%`,
                }),
                el("b", { text: fmtWatch(a[key]) }),
              ),
              el(
                "div",
                { class: "barx" },
                el("i", {
                  style: `width:${(a[key] / top) * 100}%;background:${a.color}`,
                }),
              ),
            ),
          )
        : [
            el("p", {
              class: "wsubline",
              text: week
                ? "Nothing watched yet this week"
                : "Nothing watched yet today",
            }),
          ]),
    );

    $("#wShows").replaceChildren(
      ...(s.shows.length
        ? s.shows.slice(0, 8).map((sh) => {
            const meta = [sh.subtitle ? `${sh.app} · ${sh.subtitle}` : sh.app];
            if (sh.ms >= 60000) meta.push(fmtWatch(sh.ms));
            if (sh.ms > 0 && sh.last > 0)
              meta.push(
                sh.last >= s.todayStart
                  ? `today, ${clock(sh.last)}`
                  : new Date(sh.last).toLocaleDateString(undefined, {
                      weekday: "short",
                    }),
              );
            return el(
              "div",
              { class: "wshow" },
              el("span", { class: "wstripe", style: `background:${sh.color}` }),
              el(
                "div",
                { class: "wtext" },
                el("span", { class: "t", text: sh.title }),
                el("span", { class: "m", text: meta.join(" · ") }),
              ),
              sh.progress >= 0
                ? el(
                    "div",
                    {
                      class: "wprog",
                      title: `${Math.round(sh.progress * 100)}% watched`,
                    },
                    el("i", { style: `width:${sh.progress * 100}%` }),
                  )
                : null,
            );
          })
        : [
            el("p", {
              class: "wsubline",
              text: "Titles show up here as you watch",
            }),
          ]),
    );

    $("#wLive").classList.toggle("off", !s.playing);
    $("#wNow").replaceChildren(
      ...(s.playing
        ? [
            el("div", { class: "wnow-t", text: s.playing.title }),
            el("div", {
              class: "wsubline",
              text: `${s.playing.app}${s.playing.since > 0 ? ` · since ${clock(s.playing.since)}` : ""}`,
            }),
          ]
        : [
            el("div", { class: "wsubline", text: "Nothing playing right now" }),
          ]),
    );
    const facts = week
      ? [
          [
            "Busiest day",
            s.week.busiestDay >= 0 ? DAYS_LONG[s.week.busiestDay] : "—",
          ],
          [
            "Daily average",
            fmtWatch(s.week.watched / Math.max(1, s.week.daysOn)),
          ],
          ["Screen on", fmtWatch(s.week.screen)],
        ]
      : [
          [
            "Busiest hour",
            s.today.busiestHour >= 0 ? hourName(s.today.busiestHour) : "—",
          ],
          ["TV first on", s.today.firstOn > 0 ? clock(s.today.firstOn) : "—"],
          ["Screensaver", fmtWatch(s.today.screensaver)],
        ];
    $("#wFacts").replaceChildren(
      ...facts.map(([k, v]) =>
        el(
          "div",
          { class: "stat-line" },
          el("span", { text: k }),
          el("b", { text: v }),
        ),
      ),
    );
  },
};

/* ================= Device ================= */

VIEWS.device = {
  built: false,
  timer: 0,
  hist: [],
  build() {
    const v = $("#view-device");
    v.innerHTML = `
      <div class="page-head"><div><h1>Device</h1><p id="devSub">Reading the TV…</p></div></div>
      <div class="grid-cards">
        <div class="cardx"><h3>CPU</h3><div class="stat-big" style="margin-top:14px"><span id="cpuPct">—</span><small>%</small></div>
          <canvas class="spark" id="cpuSpark"></canvas><div class="cores" id="cpuCores"></div><div class="stat-line"><span>Load average</span><b id="cpuLoad">—</b></div></div>
        <div class="cardx"><h3>Memory</h3><div class="ring-wrap" style="margin-top:14px">
          <svg class="ring" viewBox="0 0 120 120"><circle class="bg" cx="60" cy="60" r="50"/><circle class="fg" id="memRing" cx="60" cy="60" r="50" stroke-dasharray="314.16" stroke-dashoffset="314.16"/><text id="memPct" x="60" y="69" text-anchor="middle">—</text></svg>
          <div style="flex:1;min-width:0"><div class="stat-line" style="margin-top:0"><span>In use</span><b id="memUsed">—</b></div><div class="stat-line"><span>Available</span><b id="memAvail">—</b></div><div class="stat-line"><span>Swap in use</span><b id="memSwap">—</b></div></div></div></div>
        <div class="cardx"><h3>Temperature</h3><div class="stat-big" style="margin-top:14px"><span id="tempC">—</span><small>°C</small></div>
          <div class="temp-scale"><i id="tempDot" style="left:0"></i></div><div class="stat-line"><span>Chip</span><b id="tempLabel">—</b></div></div>
        <div class="cardx"><h3>Storage</h3><div id="storage" style="margin-top:6px"></div></div>
        <div class="cardx"><h3>Network</h3><div style="margin-top:14px;display:flex;align-items:center;gap:12px"><span class="signal" id="sig"><span style="height:6px"></span><span style="height:10px"></span><span style="height:14px"></span><span style="height:18px"></span></span><span style="font-size:22px;font-weight:300" id="ssid">—</span></div>
          <div class="stat-line"><span>Signal</span><b id="rssi">—</b></div><div class="stat-line"><span>Link speed</span><b id="link">—</b></div><div class="stat-line"><span>Band</span><b id="band">—</b></div><div class="stat-line"><span>IP address</span><b id="ip" class="mono">—</b></div></div>
        <div class="cardx"><h3>Uptime</h3><div class="stat-big" style="margin-top:14px;font-size:40px" id="uptime">—</div><div class="stat-line"><span>Since</span><b id="since">—</b></div></div>
        <div class="cardx span-2"><h3>Memory by app</h3><div id="top" style="margin-top:10px"></div></div>
        <div class="cardx"><h3>About this TV</h3><dl class="kv" id="about" style="margin-top:14px"></dl></div>
      </div>`;
    this.built = true;
  },
  async show() {
    if (!this.built) this.build();
    this.tick();
    const d = await api("/api/device");
    if (d && d.model) {
      $("#devSub").textContent =
        `${d.manufacturer} ${d.model} · Android ${d.android}`;
      $("#about").replaceChildren(
        ...[
          ["Model", d.model],
          ["Android", d.android],
          ["Security patch", d.patch],
          ["Build", d.build],
          ["Chip", d.platform],
          ["Kernel", d.kernel.split("-")[0]],
          ["Resolution", `${d.resolution} @ ${d.density} dpi`],
          ["Time zone", d.timezone],
        ].flatMap(([k, v]) => [el("dt", { text: k }), el("dd", { text: v })]),
      );
    }
  },
  hide() {
    clearTimeout(this.timer);
  },
  async tick() {
    clearTimeout(this.timer);
    if (current !== "device") return;
    const s = await api("/api/stats?full=1");
    if (s && s.mem) this.render(s);
    this.timer = setTimeout(() => this.tick(), 2000);
  },
  render(s) {
    $("#cpuPct").textContent = Math.round(s.cpu.usage);
    this.hist.push(s.cpu.usage);
    if (this.hist.length > 60) this.hist.shift();
    this.spark();
    $("#cpuCores").replaceChildren(
      ...s.cpu.cores.map((f, i) =>
        el("span", { text: `core ${i + 1} · ${(f / 1000).toFixed(2)} GHz` }),
      ),
    );
    $("#cpuLoad").textContent = s.cpu.load.map((x) => x.toFixed(1)).join(" · ");

    const used = s.mem.total - s.mem.available;
    const pct = used / s.mem.total;
    $("#memRing").setAttribute("stroke-dashoffset", String(314.16 * (1 - pct)));
    $("#memPct").textContent = `${Math.round(pct * 100)}%`;
    $("#memUsed").textContent = `${fmtBytes(used)} of ${fmtBytes(s.mem.total)}`;
    $("#memAvail").textContent = fmtBytes(s.mem.available);
    $("#memSwap").textContent = s.mem.swapTotal
      ? `${fmtBytes(s.mem.swapTotal - s.mem.swapFree)} of ${fmtBytes(s.mem.swapTotal)}`
      : "none";

    if (Number.isFinite(s.temp)) {
      $("#tempC").textContent = Math.round(s.temp);
      $("#tempDot").style.left =
        `${Math.max(0, Math.min(100, ((s.temp - 30) / 60) * 100))}%`;
      $("#tempLabel").textContent =
        s.temp < 60
          ? "Cool"
          : s.temp < 75
            ? "Normal"
            : s.temp < 85
              ? "Warm"
              : "Hot";
    }

    $("#storage").replaceChildren(
      ...s.storage.map((d) =>
        el(
          "div",
          { style: "margin-top:12px" },
          el(
            "div",
            { class: "stat-line", style: "margin-top:0" },
            el("span", { text: d.name }),
            el("b", { text: `${fmtBytes(d.used)} of ${fmtBytes(d.total)}` }),
          ),
          el(
            "div",
            { class: `barx${d.name === "System" ? " cool" : ""}` },
            el("i", {
              style: `width:${Math.round((d.used / d.total) * 100)}%`,
            }),
          ),
        ),
      ),
    );

    const w = s.wifi;
    const bars =
      w.rssi >= -55
        ? 4
        : w.rssi >= -67
          ? 3
          : w.rssi >= -75
            ? 2
            : w.rssi
              ? 1
              : 0;
    $$("#sig span").forEach((b, i) => b.classList.toggle("on", i < bars));
    $("#ssid").textContent = w.ssid || "Not on Wi-Fi";
    $("#rssi").textContent = w.rssi ? `${w.rssi} dBm` : "—";
    $("#link").textContent = w.link ? `${w.link} Mbps` : "—";
    $("#band").textContent = w.freq
      ? w.freq > 5900
        ? "6 GHz"
        : w.freq > 4000
          ? "5 GHz"
          : "2.4 GHz"
      : "—";
    $("#ip").textContent = w.ip || "—";

    $("#uptime").textContent = fmtDuration(s.uptime);
    $("#since").textContent = fmtDate(s.at - s.uptime * 1000);

    if (s.top) {
      const max = s.top[0]?.bytes || 1;
      labels().then((L) =>
        $("#top").replaceChildren(
          ...s.top.map((p) => {
            const base = p.name.split(":")[0];
            const name = L[base]
              ? L[base] +
                (p.name.includes(":") ? ` (${p.name.split(":")[1]})` : "")
              : p.name === "system_server"
                ? "Android system"
                : p.name;
            return el(
              "div",
              { class: "top-row" },
              el("span", { class: "n", text: name, title: p.name }),
              el("span", { class: "m", text: fmtBytes(p.bytes) }),
              el(
                "div",
                { class: "barx" },
                el("i", {
                  style: `width:${Math.round((p.bytes / max) * 100)}%`,
                }),
              ),
            );
          }),
        ),
      );
    }
  },
  spark() {
    const c = $("#cpuSpark");
    const d = devicePixelRatio || 1;
    const w = c.clientWidth,
      h = c.clientHeight;
    if (!w) return;
    c.width = w * d;
    c.height = h * d;
    const x = c.getContext("2d");
    x.scale(d, d);
    const pts = this.hist.map((v, i) => [
      (i / 59) * w,
      h - 4 - (Math.min(100, v) / 100) * (h - 8),
    ]);
    if (pts.length < 2) return;
    const off = w - pts[pts.length - 1][0];
    const g = x.createLinearGradient(0, 0, 0, h);
    g.addColorStop(0, "rgba(90,162,255,0.35)");
    g.addColorStop(1, "rgba(90,162,255,0)");
    x.beginPath();
    pts.forEach(([px, py], i) =>
      i ? x.lineTo(px + off, py) : x.moveTo(px + off, py),
    );
    x.lineTo(w, h);
    x.lineTo(pts[0][0] + off, h);
    x.fillStyle = g;
    x.fill();
    x.beginPath();
    pts.forEach(([px, py], i) =>
      i ? x.lineTo(px + off, py) : x.moveTo(px + off, py),
    );
    x.strokeStyle = "#fff";
    x.lineWidth = 2;
    x.lineJoin = "round";
    x.stroke();
  },
};

/* ================= Files ================= */

const PLACES = [
  ["Internal storage", "/sdcard"],
  ["Downloads", "/sdcard/Download"],
  ["Movies", "/sdcard/Movies"],
  ["Pictures", "/sdcard/Pictures"],
  ["App data", "/sdcard/Android/data"],
  ["Temp", "/data/local/tmp"],
  ["System", "/system"],
  ["Root", "/"],
];
const EXT = {
  image: ["jpg", "jpeg", "png", "gif", "webp", "bmp"],
  video: ["mp4", "webm", "mkv", "mov", "m4v"],
  audio: ["mp3", "ogg", "m4a", "wav", "aac", "flac"],
  text: [
    "txt",
    "log",
    "json",
    "xml",
    "prop",
    "sh",
    "rc",
    "conf",
    "kl",
    "csv",
    "md",
    "ini",
    "cfg",
    "html",
    "js",
    "css",
  ],
  apk: ["apk"],
};
const kindOf = (name) => {
  const ext = name.includes(".")
    ? name.slice(name.lastIndexOf(".") + 1).toLowerCase()
    : "";
  return Object.keys(EXT).find((k) => EXT[k].includes(ext)) || "file";
};
const raw = (path, inline) =>
  `/api/files/raw?path=${encodeURIComponent(path)}${inline ? "&inline=1" : ""}`;
const join = (dir, name) => (dir === "/" ? "" : dir) + "/" + name;

VIEWS.files = {
  built: false,
  path: store.get("argon.files", "/sdcard"),
  build() {
    const v = $("#view-files");
    const input = el("input", {
      type: "file",
      multiple: true,
      style: "display:none",
      onchange: (e) => this.upload([...e.target.files]),
    });
    v.append(
      el(
        "div",
        { class: "page-head" },
        el(
          "div",
          {},
          el("h1", { text: "Files" }),
          el("p", {
            text: "Everything adb can see on the TV. Tap a file to preview or download it.",
          }),
        ),
        el(
          "div",
          { class: "inline" },
          input,
          el(
            "button",
            { class: "btn", onclick: () => input.click() },
            icon("upload"),
            "Upload here",
          ),
          el(
            "button",
            {
              class: "btn icon",
              "aria-label": "Refresh",
              onclick: () => this.load(),
            },
            icon("refresh"),
          ),
        ),
      ),
      el(
        "div",
        { class: "places", id: "places" },
        ...PLACES.map(([n, p]) =>
          el(
            "button",
            { class: "btn small", "data-path": p, onclick: () => this.open(p) },
            n,
          ),
        ),
      ),
      el(
        "div",
        { class: "cardx" },
        el("div", { class: "crumbs", id: "crumbs" }),
        el("div", { class: "flist", id: "flist", style: "margin-top:10px" }),
      ),
    );
    $("#previewClose").onclick = () => $("#preview").close();
    $("#preview").addEventListener("close", () =>
      $("#previewBody").replaceChildren(),
    );
    this.built = true;
  },
  show() {
    if (!this.built) this.build();
    this.load();
  },
  open(p) {
    this.path = p;
    store.set("argon.files", p);
    this.load();
  },
  async load() {
    const list = $("#flist");
    list.replaceChildren(el("div", { class: "spinner" }));
    this.crumbs();
    const r = await api(`/api/files?path=${encodeURIComponent(this.path)}`);
    if (r.ok === false)
      return list.replaceChildren(el("div", { class: "empty", text: r.error }));
    this.path = r.path;
    this.crumbs();
    if (r.error)
      return list.replaceChildren(el("div", { class: "empty", text: r.error }));
    if (!r.entries.length)
      return list.replaceChildren(
        el("div", { class: "empty", text: "This folder is empty." }),
      );
    list.replaceChildren(...r.entries.map((e) => this.row(e)));
  },
  crumbs() {
    const parts = this.path.split("/").filter(Boolean);
    const nodes = [el("button", { onclick: () => this.open("/") }, "TV")];
    parts.forEach((p, i) => {
      nodes.push(el("i", { text: "/" }));
      nodes.push(
        el(
          "button",
          { onclick: () => this.open("/" + parts.slice(0, i + 1).join("/")) },
          p,
        ),
      );
    });
    $("#crumbs").replaceChildren(...nodes);
    $$("#places .btn").forEach((b) =>
      b.classList.toggle("on", b.dataset.path === this.path),
    );
  },
  row(e) {
    const path = join(this.path, e.name);
    const kind = e.dir ? "folder" : kindOf(e.name);
    const meta = e.dir ? "Folder" : fmtBytes(e.size);
    return el(
      "div",
      {
        class: `frow${e.dir ? " dir" : ""}`,
        tabindex: "0",
        role: "button",
        onclick: (ev) =>
          !ev.target.closest(".ac") &&
          (e.dir ? this.open(path) : this.preview(path, e)),
        onkeydown: (ev) =>
          ev.key === "Enter" &&
          (e.dir ? this.open(path) : this.preview(path, e)),
      },
      el("span", { class: "ic" }, icon(kind, 20)),
      el(
        "span",
        { class: "nm" },
        e.name,
        el("small", {
          text: `${meta} · ${new Date(e.mtime).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" })}`,
        }),
      ),
      el("span", { class: "sz", text: e.dir ? "" : fmtBytes(e.size) }),
      el("span", { class: "dt", text: fmtDate(e.mtime) }),
      el(
        "span",
        { class: "ac" },
        e.dir
          ? null
          : el(
              "a",
              {
                class: "btn small icon",
                href: raw(path),
                download: e.name,
                "aria-label": `Download ${e.name}`,
              },
              icon("download", 16),
            ),
        el(
          "button",
          {
            class: "btn small icon",
            "aria-label": `Delete ${e.name}`,
            onclick: () => this.remove(path, e),
          },
          icon("trash", 16),
        ),
      ),
    );
  },
  async preview(path, e) {
    const kind = kindOf(e.name);
    $("#previewName").textContent = e.name;
    $("#previewDownload").href = raw(path);
    $("#previewDownload").setAttribute("download", e.name);
    const body = $("#previewBody");
    if (kind === "image")
      body.replaceChildren(el("img", { src: raw(path, true), alt: e.name }));
    else if (kind === "video")
      body.replaceChildren(
        el("video", { src: raw(path, true), controls: true, autoplay: true }),
      );
    else if (kind === "audio")
      body.replaceChildren(
        el("audio", {
          src: raw(path, true),
          controls: true,
          autoplay: true,
          style: "margin:30px",
        }),
      );
    else if ((kind === "text" || e.size < 64 * 1024) && e.size <= 512 * 1024) {
      body.replaceChildren(el("div", { class: "spinner" }));
      const t = await fetch(raw(path, true))
        .then((r) => r.text())
        .catch(() => "");
      body.replaceChildren(
        /[\x00-\x08\x0e-\x1f]/.test(t.slice(0, 2000))
          ? el("div", {
              class: "empty",
              text: "This is a binary file. Download it to open it.",
            })
          : el("pre", { text: t || "(empty)" }),
      );
    } else
      body.replaceChildren(
        el("div", {
          class: "empty",
          text: `${fmtBytes(e.size)} · no preview for this kind of file`,
        }),
      );
    $("#preview").showModal();
  },
  async remove(path, e) {
    const ok = await confirmBox(
      `Delete ${e.name}?`,
      `${e.dir ? "This folder and everything in it" : "This file"} will be deleted from the TV for good. ${path}`,
    );
    if (!ok) return;
    const r = await api("/api/files/delete", { path });
    done(r, "Deleted");
    this.load();
  },
  async upload(files) {
    for (const f of files) {
      toast(`Sending ${f.name}…`);
      const r = await fetch(
        `/api/files/upload?dir=${encodeURIComponent(this.path)}&name=${encodeURIComponent(f.name)}`,
        { method: "POST", body: f },
      )
        .then((x) => x.json())
        .catch(() => ({ ok: false }));
      done(r, `${f.name} is on the TV`);
    }
    this.load();
  },
};

/* ================= Settings ================= */

const CATS = [
  { id: "display", name: "Display & Sound", icon: "picture", page: "picture" },
  { id: "network", name: "Network & Internet", icon: "wifi", page: "network" },
  {
    id: "accounts",
    name: "Accounts & sign-in",
    icon: "user",
    page: "accounts",
  },
  { id: "apps", name: "Apps", icon: "grid", page: "apps" },
  {
    id: "remotes",
    name: "Remotes & accessories",
    icon: "remote",
    page: "remotes",
  },
  { id: "power", name: "Screen saver & power", icon: "moon", page: "energy" },
  { id: "system", name: "System", icon: "gear", page: "all" },
  {
    id: "accessibility",
    name: "Accessibility",
    icon: "access",
    page: "accessibility",
  },
  { id: "privacy", name: "Privacy & security", icon: "lock", page: "security" },
  { id: "about", name: "About", icon: "info", page: "about" },
];

VIEWS.settings = {
  built: false,
  cat: store.get("argon.settings", "display"),
  data: null,
  stats: null,
  device: null,
  build() {
    const v = $("#view-settings");
    v.append(
      el(
        "div",
        { class: "page-head" },
        el(
          "div",
          {},
          el("h1", { text: "Settings" }),
          el("p", {
            text: "The TV's settings, laid out like the TV's own menu. Changes apply right away.",
          }),
        ),
        el(
          "button",
          { class: "btn", onclick: () => this.openOnTv("all") },
          icon("tv"),
          "Open Settings on TV",
        ),
      ),
      el(
        "div",
        { class: "settings", id: "settingsWrap" },
        el(
          "nav",
          { class: "cats", "aria-label": "Settings categories" },
          ...CATS.map((c) =>
            el(
              "button",
              { "data-cat": c.id, onclick: () => this.select(c.id, true) },
              el("span", { class: "ci" }, icon(c.icon)),
              c.name,
              el("span", { class: "chev" }, icon("chev", 16)),
            ),
          ),
        ),
        el("div", { class: "detail-pane", id: "detail" }),
      ),
    );
    this.built = true;
  },
  async show() {
    if (!this.built) this.build();
    this.select(this.cat, false);
    const [d, s, dev] = await Promise.all([
      api("/api/settings"),
      api("/api/stats"),
      api("/api/device"),
    ]);
    if (d && d.values) this.data = d;
    if (s && s.mem) this.stats = s;
    if (dev && dev.model) this.device = dev;
    this.render();
  },
  select(id, user) {
    this.cat = id;
    store.set("argon.settings", id);
    $$(".cats button").forEach((b) =>
      b.classList.toggle("on", b.dataset.cat === id),
    );
    if (user) $("#settingsWrap").classList.add("detail");
    this.render();
    if (user && app.dataset.mode === "phone") $("#page").scrollTop = 0;
  },
  async openOnTv(page) {
    done(await api("/api/settings/open", { page }), "Opened on the TV");
  },
  async set(id, value) {
    const r = await api("/api/settings/set", { id, value });
    if (r.ok) this.data.values[id] = value;
    done(r);
    this.render();
    return r.ok;
  },
  render() {
    const c = CATS.find((x) => x.id === this.cat) || CATS[0];
    const pane = $("#detail");
    const head = el(
      "div",
      { class: "detail-head" },
      el(
        "button",
        {
          class: "btn icon back",
          "aria-label": "Back to settings",
          onclick: () => $("#settingsWrap").classList.remove("detail"),
        },
        icon("back"),
      ),
      el("h2", { text: c.name }),
      c.page
        ? el(
            "button",
            { class: "btn small", onclick: () => this.openOnTv(c.page) },
            icon("tv", 16),
            "Open on TV",
          )
        : null,
    );
    if (!this.data && !["accounts", "privacy"].includes(c.id))
      return pane.replaceChildren(
        head,
        el("div", { class: "cardx" }, el("div", { class: "spinner" })),
      );
    pane.replaceChildren(head, ...(this[c.id]?.() ?? []));
  },
  // Building blocks
  card(...rows) {
    return el("div", { class: "cardx" }, el("div", { class: "rows" }, ...rows));
  },
  row(title, sub, control) {
    return el(
      "div",
      { class: "rowx" },
      el(
        "div",
        { class: "t" },
        el("b", { text: title }),
        sub ? el("small", { text: sub }) : null,
      ),
      control,
    );
  },
  toggle(title, sub, id) {
    const v = this.data.values[id];
    return this.row(
      title,
      sub,
      switchEl(v, () => this.set(id, !v), title),
    );
  },
  seg(title, sub, current, options, onpick) {
    return this.row(
      title,
      sub,
      el(
        "div",
        { class: "segx" },
        ...options.map(([label, value]) =>
          el(
            "button",
            {
              class: current === value ? "on" : "",
              onclick: () => onpick(value),
            },
            label,
          ),
        ),
      ),
    );
  },
  select2(title, sub, current, options, onpick) {
    const known = options.some(([, v]) => v === current);
    const s = el(
      "select",
      {
        class: "field",
        "aria-label": title,
        onchange: (e) => onpick(JSON.parse(e.target.value)),
      },
      ...(known || current == null
        ? []
        : [
            el(
              "option",
              { value: JSON.stringify(current), selected: true },
              "Custom",
            ),
          ]),
      ...options.map(([label, value]) =>
        el(
          "option",
          { value: JSON.stringify(value), selected: value === current },
          label,
        ),
      ),
    );
    return this.row(title, sub, s);
  },
  link(title, sub, page) {
    return this.row(
      title,
      sub,
      el(
        "button",
        { class: "btn small", onclick: () => this.openOnTv(page) },
        icon("tv", 16),
        "Open on TV",
      ),
    );
  },
  info(title, value) {
    return this.row(
      title,
      null,
      el("span", { class: "v", text: value ?? "—" }),
    );
  },
  // Pages
  display() {
    const vol = el("input", {
      type: "range",
      class: "range",
      min: "0",
      max: "100",
      step: "1",
      value: String(Math.max(0, S.volume)),
      "aria-label": "Volume",
      oninput: (e) => {
        clearTimeout(this.volT);
        this.volT = setTimeout(
          () => send({ t: "volume", v: +e.target.value }),
          80,
        );
      },
    });
    return [
      this.card(
        this.link(
          "Picture",
          "Picture mode, backlight, colour and motion live in Xiaomi's picture menu.",
          "picture",
        ),
        this.link("Sound", "Sound mode, Dolby and audio output.", "sound"),
      ),
      this.card(
        this.row(
          "Volume",
          `${Math.max(0, S.volume)} of 100`,
          el("div", { style: "width:min(260px,45vw)" }, fillRange(vol)),
        ),
        this.toggle(
          "System sounds",
          "Clicks when you move around the TV's menus.",
          "soundEffects",
        ),
        this.seg(
          "Text size",
          null,
          this.data.values.fontScale ?? 1,
          [
            ["Small", 0.85],
            ["Default", 1],
            ["Large", 1.15],
            ["Largest", 1.3],
          ],
          (v) => this.set("fontScale", v),
        ),
        this.toggle(
          "HDMI-CEC one-touch play",
          "A connected device can turn the TV on and switch to its input.",
          "cecOneTouch",
        ),
      ),
    ];
  },
  network() {
    const w = this.stats?.wifi || {};
    return [
      this.card(
        this.info("Network", w.ssid || "—"),
        this.info("Signal", w.rssi ? `${w.rssi} dBm` : "—"),
        this.info("Link speed", w.link ? `${w.link} Mbps` : "—"),
        this.info("Band", w.freq ? (w.freq > 4000 ? "5 GHz" : "2.4 GHz") : "—"),
        this.info("IP address", w.ip || "—"),
        this.info("adb address", S.address || "—"),
      ),
      this.card(
        this.link(
          "Wi-Fi and Ethernet",
          "Join another network or change IP settings.",
          "network",
        ),
      ),
    ];
  },
  accounts() {
    return [
      this.card(
        this.link(
          "Google account",
          "Sign in, add an account or manage the ones on the TV. Sign-in happens on the TV.",
          "accounts",
        ),
      ),
    ];
  },
  apps() {
    const wrap = el("div", { class: "cardx" });
    const list = el("div");
    const q = el("input", {
      class: "field",
      placeholder: "Search apps",
      "aria-label": "Search apps",
      oninput: () => draw(),
    });
    let showSystem = false;
    const sysBtn = el(
      "button",
      {
        class: "btn small",
        onclick: () => {
          showSystem = !showSystem;
          sysBtn.textContent = showSystem
            ? "Hide system apps"
            : "Show system apps";
          draw();
        },
      },
      "Show system apps",
    );
    wrap.append(
      el(
        "div",
        { class: "inline", style: "margin-bottom:12px" },
        el("div", { style: "flex:1;min-width:180px" }, q),
        sysBtn,
      ),
      list,
    );
    const draw = () => {
      if (!this.appList)
        return list.replaceChildren(el("div", { class: "spinner" }));
      const s = q.value.trim().toLowerCase();
      const items = this.appList.filter(
        (a) =>
          (showSystem || !a.system || a.launchable || !a.enabled) &&
          (!s || `${a.label} ${a.pkg}`.toLowerCase().includes(s)),
      );
      if (!items.length)
        return list.replaceChildren(
          el("div", { class: "empty", text: "No apps match." }),
        );
      list.replaceChildren(...items.map((a) => this.appRow(a, draw)));
    };
    draw();
    if (!this.appList)
      api("/api/apps").then((r) => {
        if (Array.isArray(r)) ((this.appList = r), draw());
      });
    return [wrap];
  },
  appRow(a, redraw) {
    const act = async (action, msg) => {
      if (
        action === "disable" &&
        !(await confirmBox(
          `Turn off ${a.label || a.pkg}?`,
          "It disappears from the TV and stops running until you turn it back on here.",
          "Turn off",
        ))
      )
        return;
      if (
        action === "cache" &&
        !(await confirmBox(
          `Clear cache for ${a.label || a.pkg}?`,
          "Frees space. Sign-ins and settings stay.",
          "Clear cache",
        ))
      )
        return;
      const r = await api("/api/apps/action", { pkg: a.pkg, action });
      done(r, msg);
      if (r.ok && (action === "disable" || action === "enable"))
        ((a.enabled = action === "enable"), redraw());
    };
    return el(
      "div",
      { class: "app-item" },
      a.launchable
        ? el("img", {
            src: `/api/home/banner?pkg=${encodeURIComponent(a.pkg)}`,
            alt: "",
            loading: "lazy",
          })
        : el("span", { class: "ph" }),
      el(
        "div",
        { class: "t" },
        el(
          "b",
          {},
          a.label || prettyPkg(a.pkg),
          a.system ? el("span", { class: "badge", text: "system" }) : null,
          !a.enabled ? el("span", { class: "badge off", text: "off" }) : null,
        ),
        el("small", { text: a.pkg }),
      ),
      el(
        "div",
        { class: "acts" },
        a.launchable && a.enabled
          ? el(
              "button",
              {
                class: "btn small",
                onclick: () => act("open", "Opening on the TV"),
              },
              "Open",
            )
          : null,
        a.enabled
          ? el(
              "button",
              { class: "btn small", onclick: () => act("stop", "Stopped") },
              "Force stop",
            )
          : null,
        el(
          "button",
          { class: "btn small", onclick: () => act("cache", "Cache cleared") },
          "Clear cache",
        ),
        a.enabled
          ? el(
              "button",
              {
                class: "btn small",
                onclick: () => act("disable", "Turned off"),
              },
              "Turn off",
            )
          : el(
              "button",
              {
                class: "btn small primary",
                onclick: () => act("enable", "Turned on"),
              },
              "Turn on",
            ),
        el(
          "button",
          {
            class: "btn small icon",
            "aria-label": "Details on TV",
            title: "Details on TV",
            onclick: () => act("info", "Opened on the TV"),
          },
          icon("tv", 16),
        ),
      ),
    );
  },
  remotes() {
    const bt = this.data.bluetooth || [];
    return [
      this.card(
        this.info("Bluetooth", this.data.values.bluetooth ? "On" : "Off"),
        ...(bt.length
          ? bt.map((d) =>
              this.row(
                d.name,
                `${d.type === "LE" ? "Bluetooth LE" : "Bluetooth"} · ${d.address}`,
                el("span", { class: "v", text: "Paired" }),
              ),
            )
          : [this.info("Paired devices", "None")]),
      ),
      this.card(
        this.link(
          "Pair a remote or accessory",
          "The TV searches for nearby Bluetooth devices.",
          "remotes",
        ),
      ),
    ];
  },
  power() {
    const v = this.data.values;
    const MIN = 60000;
    const dreamName = (c) =>
      (c.split("/").pop() || c)
        .replace(/^\./, "")
        .replace(/([a-z])([A-Z])/g, "$1 $2");
    return [
      this.card(
        this.toggle(
          "Screen saver",
          "Shows when the TV has been idle.",
          "screensaver",
        ),
        this.select2(
          "Screen saver",
          null,
          v.screensaverComponent,
          (this.data.dreams || []).map((d) => [dreamName(d), d]),
          (x) => this.set("screensaverComponent", x),
        ),
        this.select2(
          "Start screen saver after",
          null,
          v.screensaverAfter,
          [
            ["5 minutes", 5 * MIN],
            ["10 minutes", 10 * MIN],
            ["15 minutes", 15 * MIN],
            ["30 minutes", 30 * MIN],
            ["1 hour", 60 * MIN],
            ["2 hours", 120 * MIN],
          ],
          (x) => this.set("screensaverAfter", x),
        ),
        this.select2(
          "Turn the TV off after",
          "Counted from when the screen saver starts.",
          v.sleepAfter,
          [
            ["30 minutes", 30 * MIN],
            ["1 hour", 60 * MIN],
            ["2 hours", 120 * MIN],
            ["4 hours", 240 * MIN],
            ["8 hours", 480 * MIN],
            ["Never", -1],
          ],
          (x) => this.set("sleepAfter", x),
        ),
        this.row(
          "Start the screen saver now",
          null,
          el(
            "button",
            {
              class: "btn small",
              onclick: async () =>
                done(
                  await api("/api/settings/power", { action: "screensaver" }),
                  "Screen saver started",
                ),
            },
            "Start",
          ),
        ),
      ),
      this.card(
        this.link(
          "Energy modes",
          "Google TV's power and standby options.",
          "energy",
        ),
        this.row(
          "Sleep now",
          "Same as pressing power on the remote.",
          el(
            "button",
            { class: "btn small", onclick: () => tap("POWER") },
            "Sleep",
          ),
        ),
        this.row(
          "Restart the TV",
          "Takes about a minute; the remote reconnects by itself.",
          el(
            "button",
            {
              class: "btn small danger",
              onclick: async () => {
                if (
                  await confirmBox(
                    "Restart the TV?",
                    "Whatever is playing stops. The TV is back in about a minute.",
                    "Restart",
                  )
                )
                  done(
                    await api("/api/settings/power", { action: "reboot" }),
                    "Restarting…",
                  );
              },
            },
            "Restart",
          ),
        ),
      ),
    ];
  },
  system() {
    const v = this.data.values;
    const dev = this.device || {};
    const anim = v.windowAnim ?? 1;
    const setAnim = async (x) => {
      for (const id of ["windowAnim", "transitionAnim", "animatorAnim"])
        await api("/api/settings/set", { id, value: x });
      v.windowAnim = v.transitionAnim = v.animatorAnim = x;
      toast("Animation speed changed");
      this.render();
    };
    const storage = this.stats?.storage?.find((d) => d.name !== "System");
    return [
      this.card(
        this.toggle(
          "Automatic date and time",
          "Uses the network's time.",
          "autoTime",
        ),
        this.row(
          "24-hour clock",
          null,
          switchEl(
            v.time24 === "24",
            () => this.set("time24", v.time24 === "24" ? "12" : "24"),
            "24-hour clock",
          ),
        ),
        this.info("Time zone", dev.timezone),
        this.link(
          "Date and time",
          "Pick a time zone or set the time by hand.",
          "datetime",
        ),
      ),
      this.card(
        this.info("Language", dev.locale),
        this.link("Language", "Change the TV's language.", "language"),
        this.link(
          "Keyboard",
          "On-screen keyboard and voice input.",
          "keyboard",
        ),
      ),
      this.card(
        storage
          ? this.row(
              "Storage",
              `${fmtBytes(storage.used)} of ${fmtBytes(storage.total)} used`,
              el(
                "div",
                { style: "width:min(220px,40vw)" },
                el(
                  "div",
                  { class: "barx" },
                  el("i", {
                    style: `width:${Math.round((storage.used / storage.total) * 100)}%`,
                  }),
                ),
              ),
            )
          : null,
        this.link(
          "Storage",
          "See what's using space and clear app data.",
          "storage",
        ),
      ),
      this.card(
        this.seg(
          "Animation speed",
          "Off makes menus feel instant.",
          anim,
          [
            ["Off", 0],
            ["Fast", 0.5],
            ["Normal", 1],
            ["Slow", 1.5],
          ],
          setAnim,
        ),
        this.link("Developer options", null, "developer"),
      ),
    ];
  },
  accessibility() {
    return [
      this.card(
        this.toggle(
          "High-contrast text",
          "Bolder outlines around text.",
          "highContrast",
        ),
        this.toggle(
          "Captions",
          "Show subtitles in apps that support them.",
          "captions",
        ),
        this.link("Caption style", "Size, font and background.", "captions"),
      ),
      this.card(
        this.link(
          "All accessibility settings",
          "Screen reader, magnification and more.",
          "accessibility",
        ),
      ),
    ];
  },
  privacy() {
    return [
      this.card(
        this.link(
          "Location",
          "Which apps can use the TV's location.",
          "location",
        ),
        this.link(
          "Security and restrictions",
          "Unknown sources, restricted profile.",
          "security",
        ),
      ),
    ];
  },
  about() {
    const d = this.device || {};
    const s = this.stats;
    return [
      this.card(
        this.info("Model", d.model ? `${d.manufacturer} ${d.model}` : "—"),
        this.info("Android TV OS", d.android),
        this.info("Security patch", d.patch),
        this.info("Build", d.build),
        this.info("Kernel", d.kernel?.split("-")[0]),
        this.info(
          "Resolution",
          d.resolution ? `${d.resolution} @ ${d.density} dpi` : "—",
        ),
        this.info("Memory", s ? fmtBytes(s.mem.total) : "—"),
        this.info("Up for", s ? fmtDuration(s.uptime) : "—"),
      ),
    ];
  },
};

/* ================= Home screen ================= */

const WIDGETS = [
  ["continue", "Continue Watching", "playf"],
  ["foryou", "For You", "sparkle"],
  ["watch", "Watched today", "clock"],
  ["match", "Match", "ball"],
  ["weather", "Weather", "cloud"],
  ["inputs", "Inputs", "hdmi"],
  ["living", "Living room", "house"],
  ["shows", "Shows", "shows"],
  ["movies", "Movies", "film"],
];
const WIDGETS_DEFAULT =
  "continue:L,weather:S,match:S,inputs:S,living:S,foryou:L,watch:L,shows:L,movies:S";
// "id:size,…" in display order; a widget missing from the list is off, an empty list is the default.
function parseWidgets(v) {
  const list = [];
  for (const part of (v || WIDGETS_DEFAULT).split(",")) {
    const [id, size] = part.trim().split(":");
    if (WIDGETS.some(([w]) => w === id) && !list.some((w) => w.id === id))
      list.push({ id, size: size === "L" ? "L" : "S" });
  }
  return list;
}
const WIDGET_SIZE = Object.fromEntries(
  parseWidgets(WIDGETS_DEFAULT).map((w) => [w.id, w.size]),
);
const WAVES_ART =
  '<svg viewBox="0 0 184 104" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><defs><radialGradient id="wvg" cx="150" cy="18" r="175" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1a4bb0"/><stop offset=".33" stop-color="#0b2766"/><stop offset=".68" stop-color="#05102b"/><stop offset="1" stop-color="#02040c"/></radialGradient></defs><rect width="184" height="104" fill="url(#wvg)"/><g fill="none" stroke="#5cb6ff" stroke-opacity=".75" stroke-width=".9"><path d="M-10 92C40 70 80 84 112 52S170 12 196 4"/><path d="M-10 97C42 75 82 89 115 57S173 17 199 9"/></g></svg>';
const ambText = (v) => (+v ? `${v}% of TV volume` : "Off");

VIEWS.home = {
  H: null,
  team: null,
  pv: null,
  mirror: null,
  sizes: {},
  open: null,
  focus: null,
  photoUrl: null,
  async show() {
    const v = $("#view-home");
    if (!this.H)
      v.replaceChildren(
        el("div", { class: "cardx" }, el("div", { class: "spinner" })),
      );
    const r = await api("/api/home");
    if (!r.ok) {
      const old = r.error === "old-launcher";
      return v.replaceChildren(
        el(
          "div",
          { class: "cardx" },
          el("h2", {
            text: old
              ? "The launcher on the TV needs an update"
              : "The TV isn't connected",
          }),
          el("p", {
            class: "sub",
            text: old
              ? "This page talks to the Argon launcher. Install the new build from a computer, then come back:"
              : "Once it's back, this page fills in by itself.",
          }),
          old
            ? el("pre", { class: "code", text: "tools/build_install.sh" })
            : null,
        ),
      );
    }
    this.H = r.state;
    this.team = r.team;
    labelCache = Object.fromEntries(r.state.apps.map((a) => [a.pkg, a.label]));
    this.render();
  },
  label(pkg) {
    return this.H.apps.find((a) => a.pkg === pkg)?.label || prettyPkg(pkg);
  },
  async set(key, value, msg = "Updated on the TV") {
    const r = await api("/api/home/set", { key, value: String(value) });
    done(r, msg);
    if (r.ok) this.mirror?.soon(1200);
    return r.ok;
  },
  render() {
    $("#view-home").replaceChildren(
      el(
        "div",
        { class: "home-grid" },
        el("div", { class: "hcol" }, this.previewCard(), this.wallCard()),
        el(
          "div",
          { class: "hcol" },
          this.rowCard(),
          this.widgetsCard(),
          this.placesCard(),
          this.smartCard(),
        ),
      ),
    );
    if (this.focus) {
      document.getElementById(this.focus)?.focus();
      this.focus = null;
    }
  },
  // The same live picture as the remote's, kept across re-renders.
  previewCard() {
    if (!this.pv) {
      const img = el("img", {
        alt: "The TV screen right now",
        draggable: "false",
      });
      const note = el("div", {
        class: "mirror-note",
        text: "Loading the TV picture…",
      });
      const box = el("div", { class: "mirror still" }, img, note);
      this.pv = el(
        "section",
        { class: "cardx h-preview" },
        el(
          "div",
          { class: "card-head" },
          el("h3", { text: "Preview" }),
          el("span", { class: "hint", text: "Live from the TV" }),
        ),
        box,
      );
      this.mirror = new Mirror(box, img, note);
    }
    return this.pv;
  },
  wallCard() {
    const H = this.H;
    const w = H.wallpaper || "waves";
    const photo = w.startsWith("photo:");
    const file = el("input", {
      type: "file",
      accept: "image/jpeg,image/png",
      hidden: true,
      onchange: (e) => this.upload(e.target.files[0]),
    });
    const pick = async (value) => {
      if (w === value) return;
      const prev = H.wallpaper;
      H.wallpaper = value;
      this.render();
      if (!(await this.set("wallpaper", value, "Wallpaper changed")))
        ((H.wallpaper = prev), this.render());
    };
    const opt = (on, art, title, sub, onclick) =>
      el(
        "button",
        {
          class: `wall${on ? " on" : ""}`,
          "aria-pressed": String(on),
          onclick,
        },
        el(
          "span",
          { class: "wthumb" },
          art,
          on ? el("span", { class: "wcheck" }, icon("check", 14)) : null,
        ),
        el(
          "span",
          { class: "wtext" },
          el("b", { text: title }),
          el("small", { text: sub }),
        ),
      );
    const amb = el("b", { class: "amb-v", text: ambText(H.ambient) });
    const slider = fillRange(
      el("input", {
        type: "range",
        class: "range",
        min: "0",
        max: "100",
        step: "5",
        value: String(H.ambient),
        "aria-label": "Nature sounds volume",
        oninput: (e) => (amb.textContent = ambText(e.target.value)),
        onchange: async (e) => {
          if (await this.set("ambient_volume", e.target.value))
            H.ambient = +e.target.value;
        },
      }),
    );
    return el(
      "section",
      { class: "cardx h-wall" },
      el(
        "div",
        { class: "card-head" },
        el("h3", { text: "Wallpaper" }),
        el("span", {
          class: "hint",
          text: "Behind the Welcome hub · app hubs use the show's art",
        }),
      ),
      el(
        "div",
        { class: "walls" },
        opt(
          w === "waves",
          el("span", { class: "art waves", html: WAVES_ART }),
          "Waves",
          "Default, drawn on the TV",
          () => pick("waves"),
        ),
        opt(
          w === "pick",
          el("span", { class: "art pick" }, icon("sparkle", 24)),
          "Top pick",
          "Changes every hour",
          () => pick("pick"),
        ),
        opt(
          photo,
          this.photoUrl && photo
            ? el("img", { class: "art", src: this.photoUrl, alt: "" })
            : el(
                "span",
                { class: "art up" },
                icon(photo ? "image" : "plus", 26),
                photo ? "Replace" : "Upload",
              ),
          "Your photo",
          "JPG or PNG, 1920×1080",
          () => file.click(),
        ),
        file,
      ),
      el("div", { class: "rule" }),
      el(
        "div",
        { class: "amb" },
        el("span", { class: "label", text: "Nature sounds" }),
        slider,
        amb,
      ),
    );
  },
  async upload(f) {
    if (!f) return;
    if (!/^image\/(jpeg|png)$/.test(f.type))
      return toast("Pick a JPG or PNG photo", true);
    if (f.size > 15 * 1024 * 1024)
      return toast("That photo is over 15 MB", true);
    toast("Sending the photo to the TV…");
    let r;
    try {
      const res = await fetch("/api/home/wallpaper", {
        method: "POST",
        headers: { "content-type": f.type },
        body: f,
      });
      if (res.status === 401) return location.reload();
      r = await res.json();
    } catch {
      r = { ok: false, error: "Can't reach the remote server" };
    }
    if (!r.ok) return done(r);
    if (this.photoUrl) URL.revokeObjectURL(this.photoUrl);
    this.photoUrl = URL.createObjectURL(f);
    toast("Your photo is on the TV");
    const h = await api("/api/home");
    if (h.ok) this.H = h.state;
    else this.H.wallpaper = "photo:";
    this.render();
    this.mirror?.soon(1500);
  },
  rowCard() {
    const H = this.H;
    const save = async (order) => {
      if (order.join(",") === H.dock.join(",")) return this.render();
      const prev = H.dock;
      H.dock = order;
      this.render();
      if (await this.set("dock", order.join(","), "Home row updated"))
        loadHomeApps();
      else ((H.dock = prev), this.render());
    };
    const list = el("div", { class: "rlist" });
    H.dock.forEach((pkg, i) => list.append(this.rowItem(pkg, i, list, save)));
    const locked = (art, name, where) =>
      el(
        "div",
        { class: "ritem locked", title: `${name} always stays ${where}` },
        art,
        el("span", { class: "nm", text: name }),
      );
    const adding = this.open === "add";
    const rest = H.apps
      .filter((a) => !H.dock.includes(a.pkg))
      .sort((a, b) => a.label.localeCompare(b.label));
    return el(
      "section",
      { class: "cardx h-row" },
      el(
        "div",
        { class: "card-head" },
        el(
          "div",
          { class: "ch-l" },
          el("h3", { text: "Home row" }),
          el("span", { class: "hint", text: "Drag to reorder" }),
        ),
        el(
          "button",
          {
            class: "btn small primary",
            "aria-expanded": String(adding),
            onclick: () => ((this.open = adding ? null : "add"), this.render()),
          },
          icon(adding ? "x" : "plus", 16),
          adding ? "Done" : "Add app",
        ),
      ),
      el(
        "div",
        { class: "hrow" },
        locked(
          el("span", { class: "tile glyph", html: MARK }),
          "Welcome",
          "first",
        ),
        el("span", { class: "vsep" }),
        H.dock.length
          ? list
          : el("span", {
              class: "hint rempty",
              text: "No apps on the home row",
            }),
        el("span", { class: "vsep" }),
        locked(
          el("span", { class: "tile dots" }, icon("dots", 28)),
          "All Apps",
          "last",
        ),
      ),
      adding
        ? el(
            "div",
            { class: "all-apps" },
            ...(rest.length
              ? rest.map((a) =>
                  el(
                    "button",
                    { onclick: () => save([...H.dock, a.pkg]) },
                    tileEl(a.pkg, a.label),
                    el("span", { class: "nm", text: a.label }),
                  ),
                )
              : [
                  el("p", {
                    class: "hint",
                    text: "Every app is already on the home row",
                  }),
                ]),
          )
        : null,
    );
  },
  // Drag sideways to reorder (on touch, hold a moment first so the row can still scroll);
  // with the keyboard, Left/Right move the focused app and Delete removes it.
  rowItem(pkg, i, list, save) {
    const H = this.H;
    const name = this.label(pkg);
    const id = `ri-${pkg.replace(/\W/g, "_")}`;
    const remove = () => save(H.dock.filter((p) => p !== pkg));
    const move = (d) => {
      const j = i + d;
      if (j < 0 || j >= H.dock.length) return;
      const o = [...H.dock];
      o.splice(j, 0, o.splice(i, 1)[0]);
      this.focus = id;
      save(o);
    };
    const item = el(
      "div",
      {
        class: "ritem",
        id,
        "data-pkg": pkg,
        tabindex: "0",
        "aria-label": `${name}, place ${i + 1} of ${H.dock.length}. Left and Right move it, Delete removes it.`,
        onkeydown: (e) => {
          if (e.key === "ArrowLeft") (e.preventDefault(), move(-1));
          else if (e.key === "ArrowRight") (e.preventDefault(), move(1));
          else if (e.key === "Delete" || e.key === "Backspace")
            (e.preventDefault(), remove());
        },
      },
      el(
        "span",
        { class: "rart" },
        tileEl(pkg, name),
        el(
          "button",
          {
            class: "rm",
            "aria-label": `Remove ${name} from the home row`,
            onclick: remove,
          },
          icon("minus", 12),
        ),
      ),
      el("span", { class: "nm", text: name }),
    );
    let startX = 0,
      dragging = false,
      wait = 0,
      pid = null;
    const begin = () => {
      dragging = true;
      item.classList.add("dragging");
      buzz(10);
    };
    item.addEventListener("pointerdown", (e) => {
      if (e.target.closest("button") || e.button > 0) return;
      pid = e.pointerId;
      startX = e.clientX;
      item.setPointerCapture(e.pointerId);
      if (e.pointerType === "mouse") begin();
      else wait = setTimeout(begin, 220);
    });
    item.addEventListener("pointermove", (e) => {
      if (e.pointerId !== pid) return;
      if (!dragging) {
        if (Math.abs(e.clientX - startX) > 8)
          (clearTimeout(wait), (pid = null));
        return;
      }
      let dx = e.clientX - startX;
      const w = item.offsetWidth + 12;
      const next = item.nextElementSibling;
      const prev = item.previousElementSibling;
      if (dx > w / 2 && next)
        (list.insertBefore(next, item), (startX += w), (dx -= w));
      else if (dx < -w / 2 && prev)
        (list.insertBefore(item, prev), (startX -= w), (dx += w));
      item.style.transform = `translateX(${dx}px) rotate(-4deg)`;
    });
    const end = () => {
      clearTimeout(wait);
      pid = null;
      if (!dragging) return;
      dragging = false;
      item.style.transform = "";
      item.classList.remove("dragging");
      save([...list.children].map((x) => x.dataset.pkg));
    };
    item.addEventListener("pointerup", end);
    item.addEventListener("pointercancel", end);
    item.addEventListener("touchmove", (e) => dragging && e.preventDefault(), {
      passive: false,
    });
    return item;
  },
  widgetsCard() {
    const list = parseWidgets(this.H.widgets);
    const rows = [
      ...list,
      ...WIDGETS.filter(([id]) => !list.some((w) => w.id === id)).map(
        ([id]) => ({
          id,
          size: this.sizes[id] || WIDGET_SIZE[id] || "S",
          off: true,
        }),
      ),
    ];
    const save = async (next) => {
      const value = next.map((w) => `${w.id}:${w.size}`).join(",");
      const prev = this.H.widgets;
      this.H.widgets = value;
      this.render();
      if (!(await this.set("widgets", value, "Welcome hub updated")))
        ((this.H.widgets = prev), this.render());
    };
    return el(
      "section",
      { class: "cardx h-widgets" },
      el(
        "div",
        { class: "card-head" },
        el("h3", { text: "Welcome hub widgets" }),
        el("span", { class: "hint", text: "On or off, small or large" }),
      ),
      el(
        "div",
        { class: "wgrid" },
        ...rows.map((w) => {
          const [, name, ic] = WIDGETS.find(([id]) => id === w.id);
          const setSize = (size) => {
            this.sizes[w.id] = size;
            if (w.size !== size)
              save(list.map((x) => (x.id === w.id ? { ...x, size } : x)));
          };
          const toggle = () => {
            if (w.off) save([...list, { id: w.id, size: w.size }]);
            else if (list.length === 1)
              toast("Keep at least one widget on", true);
            else save(list.filter((x) => x.id !== w.id));
          };
          return el(
            "div",
            { class: `wrow${w.off ? " off" : ""}` },
            icon(ic, 18),
            el("span", { class: "wname", text: name }),
            el(
              "span",
              { class: "sizes", role: "group", "aria-label": `${name} size` },
              ...["S", "L"].map((sz) =>
                el(
                  "button",
                  {
                    class: w.size === sz ? "on" : null,
                    "aria-pressed": String(w.size === sz),
                    "aria-label": `${name} ${sz === "S" ? "small" : "large"}`,
                    disabled: w.off,
                    onclick: () => setSize(sz),
                  },
                  sz,
                ),
              ),
            ),
            switchEl(!w.off, toggle, name),
          );
        }),
      ),
    );
  },
  // Lights and plugs: pairing the Hue Bridge, the plugs' keys from Tuya, each one's name and icon. Kept current from
  // the remote's live state (H in app.js), so pairing counts down here.
  smartCard() {
    const s = H;
    const pairing = s.pairing?.left > 0;
    const hue = pairing
      ? `Press the round button on top of the bridge (${s.pairing.left} s)`
      : s.hue?.paired
        ? `${s.hue.online ? "Connected" : "Not answering"} · ${s.hue.host}`
        : "Not paired yet";
    const row = (title, sub, control, err) =>
      el(
        "div",
        { class: "rowx" },
        el(
          "div",
          { class: "t" },
          el("b", { text: title }),
          el("small", { text: sub }),
          err ? el("small", { class: "err", text: err }) : null,
        ),
        control,
      );
    const rows = [
      row(
        "Philips Hue Bridge",
        hue,
        el("button", {
          class: "btn small",
          disabled: pairing,
          text: s.hue?.paired ? "Pair again" : "Pair",
          onclick: async () =>
            done(
              await api("/api/smarthome/pair", {}),
              "Press the button on the bridge",
            ),
        }),
        !pairing && s.pairing?.error,
      ),
      row(
        "Wi-Fi plugs",
        s.plugsSet
          ? `${s.plugsSet} ${s.plugsSet === 1 ? "plug" : "plugs"} (Tuya, Smart Life)`
          : "Tuya and Smart Life plugs: Wipro, Syska, Havells…",
        el("button", {
          class: "btn small",
          text: this.tuyaOpen
            ? "Cancel"
            : s.plugsSet
              ? "Fetch keys again"
              : "Add plugs",
          onclick: () => {
            this.tuyaOpen = !this.tuyaOpen;
            this.render();
          },
        }),
      ),
    ];
    if (this.tuyaOpen) rows.push(this.tuyaForm());
    for (const d of s.devices) rows.push(this.deviceRow(d));
    this.smartEl = el(
      "section",
      { class: "cardx h-smart" },
      el(
        "div",
        { class: "card-head" },
        el("h3", { text: "Lights and plugs" }),
        el("span", {
          class: "hint",
          text: s.configured ? `${s.on} of ${s.devices.length} on` : "",
        }),
      ),
      el("div", { class: "rows" }, ...rows),
    );
    return this.smartEl;
  },
  // A new state from the TV: the card again, unless someone is typing in it.
  smartRefresh() {
    const old = this.smartEl;
    if (!old?.isConnected || old.contains(document.activeElement)) return;
    old.replaceWith(this.smartCard());
  },
  tuyaForm() {
    const id = el("input", {
      class: "field",
      placeholder: "Access ID",
      autocomplete: "off",
      spellcheck: "false",
    });
    const secret = el("input", {
      class: "field",
      type: "password",
      placeholder: "Access Secret",
      autocomplete: "off",
    });
    const region = el(
      "select",
      { class: "field", "aria-label": "Data centre" },
      ...[
        ["in", "India"],
        ["eu", "Central Europe"],
        ["us", "Western America"],
        ["cn", "China"],
      ].map(([v, n]) => el("option", { value: v, text: `${n} data centre` })),
    );
    const go = el("button", {
      class: "btn small primary",
      text: "Fetch plug keys",
      onclick: async () => {
        go.disabled = true;
        go.textContent = "Asking Tuya…";
        const r = await api("/api/smarthome/plugs", {
          accessId: id.value,
          secret: secret.value,
          region: region.value,
        });
        go.disabled = false;
        go.textContent = "Fetch plug keys";
        if (!r.ok) return done(r);
        toast(`${r.found} ${r.found === 1 ? "plug" : "plugs"} found`);
        this.tuyaOpen = false;
        this.render();
      },
    });
    return el(
      "div",
      { class: "smart-form" },
      el("p", {
        class: "sub",
        text: "From your cloud project at platform.tuya.com (Smart Home, your app's data centre) with the Smart Life app linked under Devices › Link App Account. The TV keeps only the plugs' keys, not these.",
      }),
      id,
      secret,
      region,
      go,
    );
  },
  deviceRow(d) {
    const save = async (body, msg) =>
      done(await api("/api/smarthome/device", { id: d.id, ...body }), msg);
    const name = el("input", {
      class: "field sm",
      value: d.name,
      "aria-label": "Name",
      onchange: () =>
        name.value.trim() && save({ name: name.value }, "Renamed"),
    });
    const kind = el(
      "select",
      {
        class: "field sm",
        "aria-label": "Icon",
        onchange: () => save({ icon: kind.value }, "Icon changed"),
      },
      ...[
        ["lightbar", "Light bar"],
        ["bulb", "Bulb"],
        ["lamp", "Lamp"],
        ["leaf", "Plant"],
        ["plug", "Plug"],
      ].map(([v, n]) =>
        el("option", { value: v, text: n, selected: v === d.icon }),
      ),
    );
    return el(
      "div",
      { class: "rowx dev-row" },
      el("span", { class: "dev-ico", html: svg(d.icon, 20) }),
      name,
      kind,
    );
  },
  placesCard() {
    return el(
      "section",
      { class: "cardx h-places" },
      el("div", { class: "pgrid" }, this.clubField(), this.placeField()),
    );
  },
  fieldButton(which, searchId, ...kids) {
    const opened = this.open === which;
    return el(
      "button",
      {
        class: `field fbtn${opened ? " open" : ""}`,
        "aria-expanded": String(opened),
        onclick: () => {
          this.open = opened ? null : which;
          this.focus = opened ? null : searchId;
          this.render();
        },
      },
      ...kids,
    );
  },
  clubField() {
    const H = this.H;
    const t = this.team;
    const pick = async (x) => {
      if (await this.set("football_team", x.id, `${x.name} on the TV`)) {
        H.team = x.id;
        this.team = x;
        this.open = null;
        this.render();
      }
    };
    return el(
      "div",
      { class: "pfield" },
      el("h3", { text: "Football club" }),
      this.fieldButton(
        "club",
        "clubSearch",
        t?.logo ? el("img", { src: t.logo, alt: "" }) : icon("ball", 18),
        el("span", {
          class: H.team ? "v" : "v ph",
          text:
            t?.name ||
            (H.team ? `ESPN team ${H.team}` : "Pick a club for the match card"),
        }),
        icon("chevd", 14),
      ),
      this.open === "club"
        ? el(
            "div",
            { class: "pop" },
            this.searchBox(
              "Search for a club",
              "/api/home/teams",
              (x) => [
                x.logo ? el("img", { src: x.logo, alt: "" }) : null,
                x.name,
                el("small", { text: x.league }),
              ],
              pick,
              "clubSearch",
            ),
            H.team
              ? el(
                  "button",
                  {
                    class: "btn small",
                    onclick: async () => {
                      if (await this.set("football_team", "", "Club removed")) {
                        H.team = "";
                        this.team = null;
                        this.open = null;
                        this.render();
                      }
                    },
                  },
                  "Remove club",
                )
              : null,
          )
        : null,
    );
  },
  placeField() {
    const H = this.H;
    const [place] = H.weather ? H.weather.split("|") : [];
    const pick = async (p) => {
      const value = `${p.name}|${p.lat}|${p.lon}`;
      if (await this.set("weather", value, `Location set to ${p.name}`)) {
        H.weather = value;
        this.open = null;
        this.render();
      }
    };
    return el(
      "div",
      { class: "pfield" },
      el("h3", { text: "Weather location" }),
      this.fieldButton(
        "place",
        "placeSearch",
        icon("pin", 18),
        el("span", { class: "v", text: place || "Bengaluru" }),
        place ? null : el("small", { text: "default" }),
      ),
      this.open === "place"
        ? el(
            "div",
            { class: "pop" },
            this.searchBox(
              "Search for a city",
              "/api/home/places",
              (p) => [p.name, el("small", { text: p.region })],
              pick,
              "placeSearch",
            ),
            el("p", {
              class: "hint",
              text: "Sets the weather card and when the TV turns light or dark. Empty means Bengaluru.",
            }),
            place
              ? el(
                  "button",
                  {
                    class: "btn small",
                    onclick: async () => {
                      if (await this.set("weather", "", "Back to Bengaluru")) {
                        H.weather = "";
                        this.open = null;
                        this.render();
                      }
                    },
                  },
                  "Use Bengaluru",
                )
              : null,
          )
        : null,
    );
  },
  searchBox(placeholder, url, renderItem, onPick, id) {
    const results = el("div", { class: "results" });
    let t = 0;
    const input = el("input", {
      class: "field",
      id,
      placeholder,
      "aria-label": placeholder,
      autocomplete: "off",
      oninput: () => {
        clearTimeout(t);
        t = setTimeout(async () => {
          const q = input.value.trim();
          if (!q) return results.replaceChildren();
          const list = await api(`${url}?q=${encodeURIComponent(q)}`);
          results.replaceChildren(
            ...(Array.isArray(list) && list.length
              ? list.map((x) =>
                  el("button", { onclick: () => onPick(x) }, ...renderItem(x)),
                )
              : [
                  el("div", {
                    class: "hintx",
                    style: "padding:8px 10px",
                    text: "No matches",
                  }),
                ]),
          );
        }, 250);
      },
      onkeydown: (e) => {
        if (e.key === "Escape") ((this.open = null), this.render());
      },
    });
    return el("div", { class: "stack" }, input, results);
  },
};

/* ================= Admin ================= */

function ago(ms) {
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
}
function dayName(ms) {
  const day = new Date(ms).setHours(0, 0, 0, 0);
  const diff = Math.round((new Date().setHours(0, 0, 0, 0) - day) / 86400000);
  if (diff === 0) return "Today";
  if (diff === 1) return "Yesterday";
  return new Date(ms).toLocaleDateString(undefined, {
    weekday: "long",
    day: "numeric",
    month: "short",
  });
}

VIEWS.admin = {
  built: false,
  timer: 0,
  filter: "all",
  data: null,
  build() {
    const filters = [
      ["all", "All"],
      ["auth", "Sign-ins"],
      ["action", "Actions"],
    ];
    $("#view-admin").replaceChildren(
      el(
        "div",
        { class: "page-head" },
        el(
          "div",
          {},
          el("h1", { text: "Admin" }),
          el("p", {
            text: "Who can use the remote, and what they did with it.",
          }),
        ),
        el(
          "button",
          { class: "btn", onclick: () => this.signOut() },
          "Sign out",
        ),
      ),
      el(
        "div",
        { class: "grid-cards" },
        el(
          "div",
          { class: "cardx span-2" },
          el("h2", { text: "Signed-in devices" }),
          el("p", {
            class: "sub",
            text: "A device you sign out goes back to the password page.",
          }),
          el("div", { class: "rows", id: "admDevices" }),
        ),
        this.passwordCard(),
      ),
      el(
        "div",
        { class: "cardx adm-history" },
        el(
          "div",
          { class: "adm-hhead" },
          el("h2", { text: "History" }),
          el(
            "div",
            { class: "segx", id: "admFilter" },
            ...filters.map(([k, t]) =>
              el(
                "button",
                {
                  "data-k": k,
                  onclick: () => ((this.filter = k), this.render()),
                },
                t,
              ),
            ),
          ),
        ),
        el("div", { id: "admHistory" }, el("div", { class: "spinner" })),
      ),
    );
    this.built = true;
  },
  passwordCard() {
    const input = (placeholder, autocomplete) =>
      el("input", {
        class: "field",
        type: "password",
        required: true,
        placeholder,
        autocomplete,
        "aria-label": placeholder,
      });
    const cur = input("Current password", "current-password");
    const next = input("New password (6+ characters)", "new-password");
    const again = input("New password again", "new-password");
    const btn = el(
      "button",
      { class: "btn primary", type: "submit" },
      "Change password",
    );
    const form = el(
      "form",
      {
        class: "adm-pw",
        onsubmit: async (e) => {
          e.preventDefault();
          if (next.value !== again.value)
            return toast("The new passwords don't match", true);
          btn.disabled = true;
          const r = await api("/api/auth/password", {
            current: cur.value,
            next: next.value,
          });
          btn.disabled = false;
          if (!r.ok) return toast(r.error || "That didn't work", true);
          form.reset();
          toast(
            r.signedOut
              ? `Password changed. ${r.signedOut} other device${r.signedOut > 1 ? "s" : ""} signed out.`
              : "Password changed",
          );
          this.tick();
        },
      },
      cur,
      next,
      again,
      btn,
    );
    return el(
      "div",
      { class: "cardx" },
      el("h2", { text: "Password" }),
      el("p", {
        class: "sub",
        text: "Changing it signs out every other device.",
      }),
      form,
    );
  },
  show() {
    if (!this.built) this.build();
    this.tick();
  },
  hide() {
    clearTimeout(this.timer);
  },
  async tick() {
    clearTimeout(this.timer);
    if (current !== "admin") return;
    const d = await api("/api/admin");
    if (d.ok) ((this.data = d), this.render());
    this.timer = setTimeout(() => this.tick(), 5000);
  },
  render() {
    const d = this.data;
    $$("#admFilter button").forEach((b) =>
      b.classList.toggle("on", b.dataset.k === this.filter),
    );
    if (!d) return;
    $("#admDevices").replaceChildren(
      ...d.sessions.map((s) => {
        const me = s.id === d.me;
        return el(
          "div",
          { class: "rowx" },
          el("span", {
            class: s.online ? "adm-dot on" : "adm-dot",
            title: s.online ? "Online now" : "Not connected",
          }),
          el(
            "div",
            { class: "t" },
            el(
              "b",
              {},
              s.device,
              me && el("span", { class: "adm-tag", text: "This device" }),
            ),
            el("small", {
              text: `${s.ip} · ${s.online ? "online now" : `last used ${ago(s.seen)}`} · signed in ${fmtDate(s.created)}`,
            }),
          ),
          el(
            "button",
            {
              class: "btn small",
              onclick: () => (me ? this.signOut() : this.revoke(s)),
            },
            "Sign out",
          ),
        );
      }),
    );
    const list = d.history.filter(
      (e) => this.filter === "all" || e.kind === this.filter,
    );
    if (!list.length) {
      $("#admHistory").replaceChildren(
        el("div", { class: "empty", text: "Nothing here yet." }),
      );
      return;
    }
    const rows = [];
    let day = "";
    for (const e of list) {
      if (dayName(e.at) !== day) {
        day = dayName(e.at);
        rows.push(el("h3", { class: "adm-day", text: day }));
      }
      rows.push(
        el(
          "div",
          {
            class: `adm-ev ${e.kind}${e.text === "Wrong password" ? " bad" : ""}`,
          },
          el("span", { class: "adm-time mono", text: clock(e.at) }),
          el(
            "div",
            { class: "t" },
            el("b", { text: e.text }),
            el("small", { text: `${e.device} · ${e.ip}` }),
          ),
        ),
      );
    }
    $("#admHistory").replaceChildren(...rows);
  },
  async revoke(s) {
    if (
      !(await confirmBox(
        `Sign out ${s.device}?`,
        "It will need the password again to use the remote.",
        "Sign out",
      ))
    )
      return;
    done(await api("/api/admin/revoke", { id: s.id }), "Signed out");
    this.tick();
  },
  async signOut() {
    if (
      !(await confirmBox(
        "Sign out of this device?",
        "You'll need the password to use the remote here again.",
        "Sign out",
      ))
    )
      return;
    await api("/api/auth/logout", {});
    location.reload();
  },
};

setView(location.hash.slice(1) || "remote", false);
