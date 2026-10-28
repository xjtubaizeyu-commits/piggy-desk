// Shared pieces: element helper, app state, the bridge to the app, menus, dialogs, toasts, formatting.
"use strict";

/** h("div.cls#id", {on: {click}, text, html, title, …attributes}, ...children) */
function h(spec, attrs, ...kids) {
  const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(spec);
  const el = document.createElement(m[1] || "div");
  (m[2].match(/[.#][\w-]+/g) || []).forEach((p) => (p[0] === "." ? el.classList.add(p.slice(1)) : (el.id = p.slice(1))));
  if (attrs && (typeof attrs !== "object" || attrs instanceof Node || Array.isArray(attrs))) { kids.unshift(attrs); attrs = null; }
  for (const k in attrs || {}) {
    const v = attrs[k];
    if (v == null || v === false) continue;
    if (k === "on") for (const e in v) el.addEventListener(e, v[e]);
    else if (k === "text") el.textContent = v;
    else if (k === "html") el.innerHTML = v;
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k === "class") el.className += " " + v;
    else if (k in el && k !== "list" && typeof v !== "string") el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  add(el, kids);
  return el;
}
function add(el, kids) {
  for (const k of kids.flat(9)) if (k != null && k !== false) el.append(k instanceof Node ? k : String(k));
  return el;
}
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
function clear(el) { while (el.firstChild) el.firstChild.remove(); return el; }

/* ------------------------------------------------------------------ state */
const S = {
  app: {}, setup: {}, account: {}, net: {}, egress: {}, pc: {}, prefs: {},
  models: [], efforts: [], modes: [], commands: [], usage: {}, projects: [], sessions: [],
  current: null,        // open session id; null = the new-session page
  draftProject: null,   // project picked on the new-session page
  view: "chat",         // chat | settings | customize
  settingsTab: "account",
  ses: {},              // per session: what the app sent (items, status, panels)
  ui: { side: true, panel: true, groups: {}, sideOver: false, panelOver: false, sideW: 0, panelW: 0, panelMax: false },
  browser: {},          // the side panel's browser: url, title, canBack, canForward, loading, progress
  draft: { text: "", attachments: [] },
};

function sesState(id) {
  return S.ses[id] || (S.ses[id] = {
    items: new Map(), order: [], status: { running: false, phase: "idle" }, context: null, outputs: [], todos: [],
    env: null, permissions: new Map(), loaded: false,
    // side panel: the open tab, files opened in tabs, what was read for them, the plan, background tasks, memory
    tab: "overview", files: [], fileCache: {}, fileMode: {}, plan: null, planSeen: null, tasks: [], memory: null,
    diff: null, tree: {}, treeOpen: new Set(), findQ: "", index: null, suggestion: "",
  });
}
const sessionById = (id) => S.sessions.find((s) => s.id === id);
const projectById = (id) => S.projects.find((p) => p.id === id);

try { Object.assign(S.ui, JSON.parse(localStorage.getItem("desk.ui") || "{}"), { sideOver: false, panelOver: false }); } catch (e) { /* first run */ }
function saveUi() {
  try { localStorage.setItem("desk.ui", JSON.stringify({ side: S.ui.side, panel: S.ui.panel, groups: S.ui.groups, sideW: S.ui.sideW, panelW: S.ui.panelW, panelMax: S.ui.panelMax })); } catch (e) { /* private */ }
}

/* ------------------------------------------------------------------ bridge */
const bridge = {
  seq: 0,
  waiting: new Map(),
  post(msg) {
    if (window.PiggyDesk) window.PiggyDesk.post(JSON.stringify(msg));
  },
  /** A request with a reply: resolves with `data`, rejects with the app's error text. */
  call(type, fields, timeoutMs) {
    const id = "r" + ++this.seq;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.waiting.delete(id); reject(new Error("应用没有回应")); }, timeoutMs || 120000);
      this.waiting.set(id, { resolve, reject, t });
      this.post(Object.assign({ type, id }, fields || {}));
    });
  },
  reply(m) {
    const w = this.waiting.get(m.id);
    if (!w) return;
    this.waiting.delete(m.id);
    clearTimeout(w.t);
    m.ok ? w.resolve(m.data) : w.reject(new Error(m.error || "失败"));
  },
};

/* ------------------------------------------------------------------ menus */
let openMenu = null;
/**
 * A popup menu next to `anchor` (an element, or {x, y} for a right-click).
 * items: {label, sub, icon, check, danger, kbd, on} | "-" (separator) | {header}
 */
function menu(anchor, items, opts) {
  closeMenu();
  const el = h("div.menu", { role: "menu" });
  for (const it of items) {
    if (!it) continue;
    if (it === "-") { el.append(h("div.sep")); continue; }
    if (it.header) { el.append(h("div.mh", { text: it.header })); continue; }
    const b = h("button.mi" + (it.danger ? ".danger" : ""), { on: { click: () => { closeMenu(); it.on && it.on(); } } },
      it.icon ? icon(it.icon, 16) : null,
      h("span.grow", it.label, it.sub ? h("span.sub-t", { text: it.sub }) : null),
      it.kbd ? h("span.kbd", { text: it.kbd }) : null,
      it.check ? h("span.ck", icon("check", 16)) : null);
    el.append(b);
  }
  document.body.append(el);
  const r = anchor instanceof Element ? anchor.getBoundingClientRect() : { left: anchor.x, right: anchor.x, top: anchor.y, bottom: anchor.y };
  const w = el.offsetWidth, hgt = el.offsetHeight;
  const up = (opts && opts.up) || r.bottom + hgt + 8 > innerHeight;
  let x = opts && opts.alignRight ? r.right - w : r.left;
  x = Math.max(8, Math.min(x, innerWidth - w - 8));
  let y = up ? r.top - hgt - 6 : r.bottom + 6;
  y = Math.max(8, Math.min(y, innerHeight - hgt - 8));
  el.style.left = x + "px";
  el.style.top = y + "px";
  openMenu = el;
  setTimeout(() => document.addEventListener("pointerdown", outside, true), 0);
  overlaysChanged();
  return el;
}
function outside(e) { if (openMenu && !openMenu.contains(e.target)) closeMenu(); }
function closeMenu() {
  if (!openMenu) return false;
  openMenu.remove();
  openMenu = null;
  document.removeEventListener("pointerdown", outside, true);
  overlaysChanged();
  return true;
}

/** Menus and dialogs would sit under the app's native browser view: it steps aside while one is open. */
function overlaysChanged() { if (typeof syncBrowserSoon === "function") syncBrowserSoon(); }

/* ------------------------------------------------------------------ dialogs */
let openDialog = null;
/** A modal box; build(box, close) fills it. Returns close(). */
function dialog(build, cls) {
  closeDialog();
  const box = h("div" + (cls || ".dialog"));
  const scrim = h("div.scrim", { on: { pointerdown: (e) => { if (e.target === scrim) closeDialog(); } } }, box);
  document.body.append(scrim);
  openDialog = scrim;
  overlaysChanged();
  build(box, closeDialog);
  const f = box.querySelector("input, textarea");
  if (f) setTimeout(() => f.focus(), 30);
  return closeDialog;
}
function closeDialog() {
  if (!openDialog) return false;
  openDialog.remove();
  openDialog = null;
  overlaysChanged();
  return true;
}
function prompt2(title, value, okLabel) {
  return new Promise((resolve) => {
    dialog((box, close) => {
      const f = h("input.field", { value: value || "" });
      const ok = () => { const v = f.value.trim(); close(); resolve(v || null); };
      f.addEventListener("keydown", (e) => { if (e.key === "Enter") ok(); });
      add(box, [h("h3", { text: title }), f, h("div.actions",
        h("button.btn.ghost", { text: "取消", on: { click: () => { close(); resolve(null); } } }),
        h("button.btn.primary", { text: okLabel || "确定", on: { click: ok } }))]);
    });
  });
}
function confirm2(title, body, okLabel, danger) {
  return new Promise((resolve) => {
    dialog((box, close) => add(box, [h("h3", { text: title }), h("div.muted", { text: body }), h("div.actions",
      h("button.btn.ghost", { text: "取消", on: { click: () => { close(); resolve(false); } } }),
      h("button.btn" + (danger ? ".danger" : ".primary"), { text: okLabel || "确定", on: { click: () => { close(); resolve(true); } } }))]));
  });
}

/* ------------------------------------------------------------------ toasts */
function toast(text) {
  let box = $(".toast-box");
  if (!box) document.body.append(box = h("div.toast-box"));
  const t = h("div.toast", { text });
  box.append(t);
  setTimeout(() => t.remove(), 2600);
}

/* ------------------------------------------------------------------ formatting */
function fmtTokens(n) {
  if (n == null) return "—";
  if (n >= 1e6) return (n / 1e6).toFixed(n % 1e6 === 0 ? 0 : 1).replace(/\.0$/, "") + "M";
  if (n >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, "") + "k";
  return String(n);
}
function fmtAgo(t) {
  if (!t) return "";
  const s = (Date.now() - t) / 1000;
  if (s < 60) return "刚刚";
  if (s < 3600) return Math.floor(s / 60) + " 分钟前";
  if (s < 86400) return Math.floor(s / 3600) + " 小时前";
  if (s < 86400 * 7) return Math.floor(s / 86400) + " 天前";
  const d = new Date(t);
  return d.getMonth() + 1 + " 月 " + d.getDate() + " 日";
}
function fmtUntil(t) {
  if (!t) return "";
  let s = Math.max(0, (t - Date.now()) / 1000);
  if (s < 3600) return Math.ceil(s / 60) + " 分钟后";
  if (s < 86400) return Math.floor(s / 3600) + " 小时 " + Math.floor((s % 3600) / 60) + " 分后";
  const d = new Date(t);
  return "周" + "日一二三四五六"[d.getDay()] + " " + String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
}
function fmtSecs(ms) {
  const s = Math.round(ms / 1000);
  return s < 60 ? s + " 秒" : Math.floor(s / 60) + " 分 " + (s % 60) + " 秒";
}
const fmtSize = (n) => (n == null ? "" : n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB");
const basename = (p) => String(p || "").split(/[\\/]/).filter(Boolean).pop() || String(p || "");

const MODE_LABEL = { default: "询问", acceptEdits: "接受编辑", plan: "计划", auto: "自动", bypassPermissions: "跳过权限" };
const MODE_DESC = {
  default: "改文件、跑命令前都先问你",
  acceptEdits: "改文件直接做，跑命令前先问",
  plan: "只看不改，先拿出计划给你批",
  auto: "Claude 自己判断，危险操作才问",
  bypassPermissions: "什么都不问，直接执行",
};
const MODE_ICON = { default: "shield", acceptEdits: "pencil", plan: "plan", auto: "zap", bypassPermissions: "alert" };
const EFFORT_LABEL = { low: "低", medium: "中", high: "高", xhigh: "超高", max: "最高" };
function modelLabel(v) {
  const m = S.models.find((x) => x.value === v);
  return m ? m.label : v || "默认模型";
}
