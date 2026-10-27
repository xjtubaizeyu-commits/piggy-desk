// The workspace: sidebar, conversation and composer, side panel, new-session and setup pages, search, shortcuts,
// and the handler for everything the app sends.
"use strict";

const root = $("#app");

/* ------------------------------------------------------------------ messages from the app */
window.desk = {
  recv(m) {
    try { handle(m); } catch (e) { console.error(e, m); }
  },
  back() {
    if (closeMenu() || closeDialog()) return true;
    if (S.ui.sideOver || S.ui.panelOver) { S.ui.sideOver = S.ui.panelOver = false; layout(); return true; }
    if (S.view !== "chat") { S.view = "chat"; renderMain(); return true; }
    return false;
  },
};

const PARTS = ["setup", "account", "net", "egress", "pc", "prefs", "usage", "projects", "sessions", "commands", "models", "efforts", "modes"];

function handle(m) {
  if (m.type === "reply") return bridge.reply(m);
  if (m.type === "state") {
    for (const k of PARTS.concat(["app"])) if (m[k] !== undefined) S[k] = m[k];
    applyPrefs();
    if (S.current && !sessionById(S.current)) S.current = null;
    renderAll();
    return;
  }
  if (PARTS.includes(m.type)) {
    S[m.type] = m.value;
    if (m.type === "prefs") applyPrefs();
    if (m.type === "sessions" || m.type === "projects") { renderSide(); if (S.current && !sessionById(S.current)) { S.current = null; renderMain(); } else renderTopbar(); }
    else if (m.type === "usage" || m.type === "models" || m.type === "efforts" || m.type === "modes") renderBelow();
    else if (m.type === "setup" || m.type === "account" || m.type === "net") { renderSide(); renderMain(); }
    else if (S.view === "settings") renderMain();
    if (m.type === "pc" || m.type === "egress" || m.type === "net") renderPanel();
    return;
  }
  if (m.type === "toast") return toast(m.text);
  if (m.type === "dictation") return onDictation(m);
  const sid = m.sessionId;
  if (!sid) return;
  const st = sesState(sid);
  switch (m.type) {
    case "transcript":
      st.items = new Map(); st.order = [];
      for (const it of m.items || []) { st.items.set(it.id, it); st.order.push(it.id); }
      st.loaded = true;
      if (S.current === sid) renderThread(sid);
      break;
    case "item": {
      const it = m.item;
      if (!st.items.has(it.id)) st.order.push(it.id);
      st.items.set(it.id, it);
      placeItem(sid, it);
      break;
    }
    case "delta": onDelta(sid, m.itemId, m.text); break;
    case "status":
      st.status = { running: !!m.running, phase: m.phase || "idle", since: m.since || Date.now() };
      refreshWorking(sid);
      if (S.current === sid) renderComposerState();
      break;
    case "context": st.context = m; if (S.current === sid) renderPanel(); break;
    case "outputs": st.outputs = m.files || []; if (S.current === sid) renderPanel(); break;
    case "todos": st.todos = m.todos || []; if (S.current === sid) renderPanel(); break;
    case "env": st.env = m; if (S.current === sid) renderPanel(); break;
    case "permission":
      st.permissions.set(m.requestId, m);
      if (S.current === sid) { const t = $("#thread"); if (t) { t.append(permissionCard(sid, m)); refreshWorking(sid); scrollToEnd(true); } }
      break;
    case "permissionDone": {
      st.permissions.delete(m.requestId);
      const c = $('[data-perm="' + cssId(m.requestId) + '"]');
      if (c) c.remove();
      refreshWorking(sid);
      break;
    }
  }
}

function applyPrefs() {
  const p = S.prefs || {};
  let theme = p.theme || "dark";
  if (theme === "system") theme = matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.setProperty("--fs", (p.fontSize || 15) + "px");
}

/* ------------------------------------------------------------------ layout */
function renderAll() {
  layout();
  renderSide();
  renderMain();
  renderPanel();
}

function layout() {
  root.classList.toggle("no-side", !S.ui.side);
  root.classList.toggle("no-panel", !S.ui.panel || S.view !== "chat" || !S.current);
  root.classList.toggle("side-over", S.ui.sideOver);
  root.classList.toggle("panel-over", S.ui.panelOver);
  let scrim = $(".over-scrim");
  if (S.ui.sideOver || S.ui.panelOver) {
    if (!scrim) document.body.append(h("div.over-scrim", { on: { click: () => { S.ui.sideOver = S.ui.panelOver = false; layout(); } } }));
  } else if (scrim) scrim.remove();
}
const narrow = () => innerWidth <= 900;
const midWidth = () => innerWidth <= 1100;

function toggleSide() {
  if (narrow()) S.ui.sideOver = !S.ui.sideOver;
  else { S.ui.side = !S.ui.side; saveUi(); }
  layout();
  renderTopbar();
}
function togglePanel() {
  if (midWidth()) S.ui.panelOver = !S.ui.panelOver;
  else { S.ui.panel = !S.ui.panel; saveUi(); }
  layout();
  renderTopbar();
}
addEventListener("resize", () => { if (!narrow()) S.ui.sideOver = false; if (!midWidth()) S.ui.panelOver = false; layout(); renderTopbar(); });

/* ------------------------------------------------------------------ sidebar */
function renderSide() {
  const side = clear($("#side"));
  side.append(h("div.side-top",
    h("div.brand", h("img", { src: "pig.png", alt: "" }), h("span", { text: "Piggy Desk" })),
    h("button.icon-btn", { title: "收起侧栏 (Ctrl+B)", on: { click: toggleSide } }, icon("sidebar"))));
  side.append(h("div.nav",
    h("button.nav-item", { on: { click: () => newSession() } }, icon("plus"), "新建会话", h("span.kbd", { text: "Ctrl N" })),
    h("button.nav-item", { on: { click: openSearch } }, icon("search"), "搜索", h("span.kbd", { text: "Ctrl K" })),
    h("button.nav-item", { on: { click: () => openView("customize") } }, icon("sliders"), "自定义"),
    h("button.nav-item", { on: { click: (e) => moreMenu(e.currentTarget) } }, icon("more"), "更多")));

  const list = h("div.side-list");
  const sessions = S.sessions.filter((s) => !s.archived).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  for (const p of S.projects) {
    const mine = sessions.filter((s) => s.projectId === p.id);
    const collapsed = !!S.ui.groups[p.id];
    const head = h("div.group-head" + (collapsed ? ".collapsed" : ""), {
      on: {
        click: () => { S.ui.groups[p.id] = !collapsed; saveUi(); renderSide(); },
        contextmenu: (e) => { e.preventDefault(); projectMenu(p, { x: e.clientX, y: e.clientY }); },
      },
    }, icon("chevDown"), h("span.grow", { text: p.name, style: { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" } }),
      h("span.env-tag", { text: p.env === "pc" ? "电脑" : "" }),
      h("span.add", { title: "在这个项目里新建会话", on: { click: (e) => { e.stopPropagation(); newSession(p.id); } } }, icon("plus", 15)));
    list.append(head);
    if (collapsed) continue;
    if (!mine.length) list.append(h("div.side-empty", { text: "还没有会话" }));
    for (const s of mine) list.append(sessionRow(s));
  }
  const loose = sessions.filter((s) => !projectById(s.projectId));
  for (const s of loose) list.append(sessionRow(s));
  list.append(h("div.group-head", { on: { click: newProjectDialog } }, icon("plus", 14), h("span", { text: "新建项目" })));
  side.append(list);

  const a = S.account || {};
  const name = a.name || (a.email ? a.email.split("@")[0] : "未登录");
  side.append(h("div.side-bottom",
    h("button.account", { on: { click: (e) => accountMenu(e.currentTarget) } },
      h("span.avatar", { text: (name[0] || "?").toUpperCase() }),
      h("span.who", name, a.plan ? h("span.plan", { text: " · " + a.plan }) : null),
      icon("chevDown", 14)),
    h("button.icon-btn", { title: "设置 (Ctrl+,)", on: { click: () => openView("settings") } }, icon("gear"))));
}

function sessionRow(s) {
  const st = S.ses[s.id];
  const running = s.running || (st && st.status.running);
  return h("div.ses" + (s.id === S.current ? ".sel" : ""), {
    on: {
      click: () => openSession(s.id),
      contextmenu: (e) => { e.preventDefault(); sessionMenu(s, { x: e.clientX, y: e.clientY }); },
    },
    title: s.title,
  }, h("span.t", { text: s.title || "新会话" }), running ? icon("spinner", 13) : s.unread ? h("span.dot") : null);
}

function moreMenu(anchor) {
  menu(anchor, [
    { label: "终端", icon: "terminal", sub: "平板里的 Linux", on: () => bridge.post({ type: "openNative", screen: "terminal" }) },
    { label: "小猪", icon: "pig", sub: "无障碍悬浮球", on: () => bridge.post({ type: "openNative", screen: "bubble" }) },
    { label: "通知规则", icon: "bell", on: () => bridge.post({ type: "openNative", screen: "rules" }) },
    { label: "定时任务", icon: "clock", on: () => bridge.post({ type: "openNative", screen: "schedules" }) },
    "-",
    { label: "快捷键", icon: "keyboard", kbd: "Ctrl /", on: shortcutsDialog },
    { label: "设置", icon: "gear", kbd: "Ctrl ,", on: () => openView("settings") },
  ]);
}

function accountMenu(anchor) {
  const a = S.account || {};
  menu(anchor, [
    a.email ? { header: a.email } : null,
    { label: "设置", icon: "gear", on: () => openView("settings") },
    { label: "额度", icon: "clock", on: usageDialog },
    "-",
    a.signedIn ? { label: "退出登录", icon: "logout", danger: true, on: async () => { if (await confirm2("退出 Claude 登录？", "退出后要重新在平板上登录才能继续用。", "退出", true)) bridge.post({ type: "signOut" }); } }
      : { label: "登录 Claude", icon: "user", on: () => bridge.post({ type: "signIn" }) },
  ], { up: true });
}

function sessionMenu(s, at) {
  menu(at, [
    { label: "重命名", icon: "edit", on: () => renameSession(s) },
    { label: "复制会话 ID", icon: "copy", on: () => { bridge.post({ type: "copy", text: s.claudeSessionId || s.id }); toast("已复制"); } },
    { label: s.archived ? "取消归档" : "归档", icon: "archive", on: () => bridge.post({ type: "archiveSession", sessionId: s.id, archived: !s.archived }) },
    "-",
    { label: "删除", icon: "trash", danger: true, on: async () => {
      if (await confirm2("删除这个会话？", "只删 Piggy Desk 里的记录，项目文件夹里的文件不动。", "删除", true)) {
        bridge.post({ type: "deleteSession", sessionId: s.id });
        if (S.current === s.id) { S.current = null; renderMain(); }
      }
    } },
  ]);
}

function projectMenu(p, at) {
  menu(at, [
    { label: "新建会话", icon: "plus", on: () => newSession(p.id) },
    { label: "重命名", icon: "edit", on: async () => { const v = await prompt2("项目名称", p.name); if (v) bridge.post({ type: "editProject", projectId: p.id, name: v }); } },
    { header: (p.env === "pc" ? "电脑 · " : "平板 · ") + p.path },
    "-",
    { label: "从列表移除", icon: "trash", danger: true, on: async () => {
      if (await confirm2("移除项目「" + p.name + "」？", "只从侧栏移除，文件夹和里面的文件都不动。", "移除", true)) bridge.post({ type: "removeProject", projectId: p.id });
    } },
  ]);
}

async function renameSession(s) {
  const v = await prompt2("会话名称", s.title);
  if (v) bridge.post({ type: "renameSession", sessionId: s.id, title: v });
}

function newProjectDialog() {
  dialog((box, close) => {
    let env = "tablet";
    const name = h("input.field", { placeholder: "例如：cell" });
    const path = h("input.field.mono", { placeholder: "" });
    const hint = h("div.muted", { style: { fontSize: "13px", marginTop: "6px" } });
    const seg = h("div.seg");
    const setEnv = (v) => {
      env = v;
      $$("button", seg).forEach((b) => b.classList.toggle("on", b.dataset.v === v));
      path.placeholder = v === "pc" ? "C:\\Users\\bzy15\\Desktop\\cell" : "留空：在平板上新建一个文件夹";
      hint.textContent = v === "pc" ? (S.pc && S.pc.status === "ok" ? "文件、命令、GPU 都在电脑上；Claude 本身还在平板上运行。" : "电脑还没连上，可以先建，连上后再用（设置 → 电脑）。") : "文件放在平板自带的 Linux 里，不连电脑也能用。";
    };
    [["tablet", "本平板"], ["pc", "电脑"]].forEach(([v, l]) => seg.append(h("button", { text: l, "data-v": v, on: { click: () => setEnv(v) } })));
    setEnv("tablet");
    const ok = async () => {
      if (!name.value.trim()) { name.focus(); return; }
      try {
        const r = await bridge.call("newProject", { name: name.value.trim(), env, path: path.value.trim() });
        close();
        newSession(r.projectId);
      } catch (e) { toast(e.message); }
    };
    add(box, [h("h3", { text: "新建项目" }), h("label", { text: "名称" }), name, h("label", { text: "放在哪里" }), seg,
      h("label", { text: "文件夹" }), path, hint,
      h("div.actions", h("button.btn.ghost", { text: "取消", on: { click: close } }), h("button.btn.primary", { text: "创建", on: { click: ok } }))]);
  });
}

/* ------------------------------------------------------------------ main area */
function openView(v) {
  S.view = v;
  if (narrow()) S.ui.sideOver = false;
  layout();
  renderMain();
}

function setupNeeded() {
  const s = S.setup || {};
  return s.claude !== "ready" || !(S.account && S.account.signedIn);
}

function renderMain() {
  const main = clear($("#main"));
  layout();
  if (S.view === "settings") return renderSettings(main);
  if (S.view === "customize") return renderCustomize(main);
  if (setupNeeded()) return renderSetup(main);
  if (!S.current) return renderHero(main);
  main.append(h("div.topbar#topbar"));
  renderTopbar();
  const banner = netBanner();
  if (banner) main.append(banner);
  const scroll = h("div#scroll", h("div.thread#thread"));
  scroll.addEventListener("scroll", () => {
    stickToEnd = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 80;
    const tb = $("#topbar");
    if (tb) tb.classList.toggle("scrolled", scroll.scrollTop > 4);
  });
  main.append(scroll);
  main.append(composer());
  stickToEnd = true;
  const st = sesState(S.current);
  if (st.loaded) renderThread(S.current);
  renderPanel();
  focusComposer();
}

function renderTopbar() {
  const tb = $("#topbar");
  if (!tb) return;
  clear(tb);
  const s = sessionById(S.current) || {};
  if (!S.ui.side || narrow()) tb.append(h("button.icon-btn", { title: "侧栏 (Ctrl+B)", on: { click: toggleSide } }, icon("sidebar")));
  tb.append(h("button.title-btn", { on: { click: (e) => sessionMenu(s, e.currentTarget) } }, h("span", { text: s.title || "新会话" }), icon("chevDown", 15)));
  const p = projectById(s.projectId);
  if (p) tb.append(h("span.muted", { style: { fontSize: "13px", whiteSpace: "nowrap" }, text: p.name + (p.env === "pc" ? " · 电脑" : "") }));
  tb.append(h("div.top-tools",
    h("button.icon-btn", { title: "终端", on: { click: () => bridge.post({ type: "openNative", screen: "terminal", sessionId: S.current }) } }, icon("terminal")),
    h("button.icon-btn", { title: "改动", on: { click: diffDialog } }, icon("diff")),
    h("button.icon-btn", { title: "文件", on: { click: () => filesDialog("") } }, icon("folder")),
    h("button.icon-btn" + (S.ui.panel && !midWidth() || S.ui.panelOver ? ".on" : ""), { title: "右侧栏 (Ctrl+Shift+B)", on: { click: togglePanel } }, icon("panel"))));
}

function netBanner() {
  const n = S.net || {};
  if (n.allowed !== false) return null;
  return h("div.banner.warn", icon("wifiOff", 18),
    h("span.grow", h("b", { text: "Claude 已断开" }), "　" + (n.reason || ("现在连的是「" + (n.ssid || "未知网络") + "」，不在允许的 Wi-Fi 里。连上 " + ((n.allow || [])[0] || "手机热点") + " 就会恢复。"))));
}

/* new session page */
function renderHero(main) {
  if (!S.ui.side || narrow()) main.append(h("div.topbar", h("button.icon-btn", { on: { click: toggleSide } }, icon("sidebar"))));
  const banner = netBanner();
  const hour = new Date().getHours();
  const hello = hour < 6 ? "夜深了，还在忙？" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
  const name = (S.account && S.account.name) || "";
  const pid = S.draftProject && projectById(S.draftProject) ? S.draftProject : (S.projects[0] || {}).id;
  S.draftProject = pid;
  const p = projectById(pid);
  const where = h("div.where",
    h("button.chip", { on: { click: (e) => projectPicker(e.currentTarget) } }, icon(p && p.env === "pc" ? "monitor" : "tablet", 15),
      h("span", { text: p ? p.name : "选择项目" }), icon("chevDown", 13)),
    p ? h("span.chip.muted", { style: { cursor: "default" } }, icon("folder", 15), h("span.mono", { style: { fontSize: "12.5px" }, text: p.path })) : null);
  const hero = h("div.hero",
    h("div.greet", h("img", { src: "pig.png", alt: "" }), h("span", { text: hello + (name ? "，" + name : "") })),
    banner, where, composer(true),
    h("div.tips", ["看看这个项目是做什么的", "找找最近改动里的 bug", "跑一下测试并修好失败的"].map((t) =>
      h("button.tip", { text: t, on: { click: () => { const ta = $("#composer-input"); ta.value = t; autosize(ta); ta.focus(); } } }))));
  main.append(hero);
  focusComposer();
}

function projectPicker(anchor) {
  menu(anchor, S.projects.map((p) => ({
    label: p.name, sub: (p.env === "pc" ? "电脑 · " : "平板 · ") + p.path, icon: p.env === "pc" ? "monitor" : "tablet",
    check: p.id === S.draftProject, on: () => { S.draftProject = p.id; renderMain(); },
  })).concat(["-", { label: "新建项目…", icon: "plus", on: newProjectDialog }]));
}

function newSession(projectId) {
  S.current = null;
  S.view = "chat";
  if (projectId) S.draftProject = projectId;
  if (narrow()) S.ui.sideOver = false;
  renderSide();
  renderMain();
  renderPanel();
}

function openSession(id) {
  S.current = id;
  S.view = "chat";
  if (narrow()) S.ui.sideOver = false;
  const st = sesState(id);
  bridge.post({ type: "openSession", sessionId: id });
  const s = sessionById(id);
  if (s && s.unread) s.unread = false;
  renderSide();
  renderMain();
  if (!st.loaded) { const t = $("#thread"); if (t) t.append(h("div.working", icon("spinner", 16), h("span", { text: "正在打开…" }))); }
}

/* setup page: shown until Claude Code is installed and signed in */
function renderSetup(main) {
  const s = S.setup || {}, a = S.account || {}, n = S.net || {};
  const netOk = n.allowed !== false && !!n.ssid;
  const installed = s.claude === "ready";
  const step = (num, done, now, title, desc, action) => h("div.stp" + (done ? ".done" : now ? ".now" : ""),
    h("span.num", done ? icon("check", 14) : String(num)), h("div.l", h("div", { text: title }), h("div.d", { text: desc })), action);
  main.append(h("div.setup", h("div.setup-in",
    h("div.hello", h("img", { src: "pig.png", alt: "" }), h("h1", { text: "欢迎使用 Piggy Desk" })),
    h("div.muted", { text: "Claude Code 装在这台平板里，也只在这台平板上登录。Claude 的网络只在手机热点上放行。" }),
    h("div.steps",
      step(1, netOk, !netOk, "网络", netOk ? "已连上「" + n.ssid + "」" : n.ssid ? "现在连的是「" + n.ssid + "」，不在允许列表里（" + (n.allow || []).join("、") + "）" : "需要定位权限才能读 Wi-Fi 名字（只用来判断是不是手机热点）",
        netOk ? null : h("button.btn", { text: n.ssid ? "改允许列表" : "允许定位", on: { click: () => n.ssid ? (S.settingsTab = "network", openView("settings")) : bridge.post({ type: "openNative", screen: "location" }) } })),
      step(2, installed, netOk && !installed, "安装 Claude Code",
        s.claude === "installing" ? (s.detail || "正在安装…") : s.claude === "error" ? (s.detail || "安装失败") + "（已下的部分会保留，重试接着下）"
          : installed ? "版本 " + (s.version || "") : "压缩包约 100 MB，走手机流量；断了会接着下，只装一次",
        installed ? null : s.claude === "installing" ? icon("spinner") : h("div.row",
          h("button.btn.ghost", { text: "从文件", title: "用已经下好的 claude.zst（比如电脑下好用 Taildrop 发过来的）", on: { click: () => bridge.post({ type: "installClaudeFile" }) } }),
          h("button.btn.accent", { text: s.claude === "error" ? "接着下" : "安装", disabled: !netOk, on: { click: () => bridge.post({ type: "installClaude" }) } }))),
      step(3, !!a.signedIn, installed && !a.signedIn, "登录 Claude",
        a.signedIn ? (a.email || "已登录") : a.signingIn ? "在浏览器里批准，回来就好了" : "用你的 Claude 账号（Pro / Max）在浏览器里登录",
        a.signedIn ? null : a.signingIn ? icon("spinner") : h("button.btn.accent", { text: "登录", disabled: !installed || !netOk, on: { click: () => bridge.post({ type: "signIn" }) } }))),
    h("div", { style: { marginTop: "18px" } }, h("button.btn.ghost", { text: "设置", on: { click: () => openView("settings") } }, icon("gear", 16))))));
}

/* ------------------------------------------------------------------ composer */
let dictating = false;
function composer(onHero) {
  const wrap = h("div.composer-wrap", { style: { position: "relative" } });
  const box = h("div.composer");
  const atts = h("div.atts#composer-atts");
  const ta = h("textarea#composer-input", { rows: 1, placeholder: onHero ? "描述一下要做的事…" : "回复 Claude…（/ 看命令）" });
  ta.value = S.draft.text;
  ta.addEventListener("input", () => { S.draft.text = ta.value; autosize(ta); slashMenu(ta); renderComposerState(); });
  ta.addEventListener("keydown", composerKeys);
  const plus = h("button.icon-btn", { title: "添加图片或文件", on: { click: (e) => attachMenu(e.currentTarget) } }, icon("plus"));
  const mic = h("button.icon-btn#mic", { title: "语音输入", on: { click: toggleDictation } }, icon("mic"));
  const send = h("button.send#send", { title: "发送 (Enter)", on: { click: sendOrStop } }, icon("arrowUp"));
  add(box, [atts, ta, h("div.bar", plus, h("span.grow"), mic, send)]);
  wrap.append(box, h("div.below#below"));
  setTimeout(() => { renderAttachments(); autosize(ta); renderBelow(); renderComposerState(); }, 0);
  return wrap;
}
function autosize(ta) { ta.style.height = "auto"; ta.style.height = Math.min(ta.scrollHeight, innerHeight * 0.4) + "px"; }
function focusComposer() { setTimeout(() => { const t = $("#composer-input"); if (t && !("ontouchstart" in window && !matchMedia("(pointer:fine)").matches)) t.focus(); }, 30); }

function renderComposerState() {
  const send = $("#send");
  if (!send) return;
  const st = S.current ? sesState(S.current) : null;
  const running = st && st.status.running;
  const has = S.draft.text.trim() || S.draft.attachments.length;
  clear(send).append(icon(running && !has ? "stop" : "arrowUp"));
  send.classList.toggle("stop", !!(running && !has));
  send.disabled = !running && !has;
  send.title = running && !has ? "停止 (Esc)" : "发送 (Enter)";
}

function renderBelow() {
  const b = $("#below");
  if (!b) return;
  clear(b);
  const s = sessionById(S.current) || {};
  const mode = s.mode || S.prefs.mode || "default";
  const model = s.model || S.prefs.model;
  const effort = s.effort || S.prefs.effort;
  b.append(h("button.chip.mode-" + mode, { on: { click: (e) => modeMenu(e.currentTarget) }, title: "权限模式 (Shift+Tab 切换)" }, icon(MODE_ICON[mode] || "shield", 15), h("span", { text: MODE_LABEL[mode] || mode }), icon("chevDown", 13)));
  b.append(h("span.grow"));
  b.append(h("button.chip", { on: { click: (e) => modelMenu(e.currentTarget) } }, h("span", { text: modelLabel(model) + (effort ? " " + (EFFORT_LABEL[effort] || effort) : "") }), icon("chevDown", 13)));
  b.append(usageRing());
}

function usageRing() {
  const u = (S.usage && S.usage.fiveHour) || {};
  const pct = u.pct;
  const r = 7.5, c = 2 * Math.PI * r;
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 20 20");
  svg.setAttribute("class", "ring" + (pct == null ? "" : pct < 50 ? " low" : pct >= 85 ? " high" : ""));
  svg.innerHTML = '<circle class="bgc" cx="10" cy="10" r="' + r + '"/>' + (pct == null ? "" : '<circle class="fgc" cx="10" cy="10" r="' + r + '" stroke-dasharray="' + c + '" stroke-dashoffset="' + c * (1 - Math.min(100, pct) / 100) + '"/>');
  const title = pct == null ? (u.status === "rejected" ? "额度用完了" : "额度") : "5 小时额度已用 " + pct + "%";
  return h("button.chip", { title, on: { click: usageDialog } }, svg);
}

function usageDialog() {
  bridge.post({ type: "refreshUsage" });
  const u = S.usage || {};
  const row = (label, x) => {
    x = x || {};
    const pct = x.pct;
    return h("div", { style: { margin: "14px 0" } },
      h("div.row", h("span.grow", { text: label }), h("span.muted", { text: pct == null ? (x.status === "rejected" ? "已用完" : x.status === "allowed_warning" ? "快用完了" : x.status ? "可用" : "—") : pct + "%" })),
      h("div.ctx-bar", { style: { margin: "6px 0 4px" } }, h("span", { style: { width: (pct || 0) + "%", background: pct >= 85 ? "var(--red)" : "var(--accent)" } })),
      h("div.muted", { style: { fontSize: "13px" }, text: x.resetsAt ? "重置：" + fmtUntil(x.resetsAt) : "" }));
  };
  dialog((box, close) => add(box, [h("h3", { text: "额度" + (S.account && S.account.plan ? " · " + S.account.plan : "") }),
    row("5 小时", u.fiveHour), row("本周", u.weekly), u.weeklyOpus ? row("本周 · Opus", u.weeklyOpus) : null,
    h("div.muted", { style: { fontSize: "12.5px" }, text: "数字来自 Claude Code 每次回复带回的额度信息。" }),
    h("div.actions", h("button.btn.primary", { text: "好", on: { click: close } }))]));
}

function modeMenu(anchor) {
  const s = sessionById(S.current) || {};
  const cur = s.mode || S.prefs.mode || "default";
  const modes = S.modes && S.modes.length ? S.modes : Object.keys(MODE_LABEL);
  menu(anchor, [{ header: "权限模式 · Shift+Tab 切换" }].concat(modes.map((m) => ({
    label: MODE_LABEL[m] || m, sub: MODE_DESC[m], icon: MODE_ICON[m], check: m === cur, on: () => setMode(S.current, m),
  }))), { up: true });
}
function setMode(sid, mode) {
  bridge.post({ type: "setMode", sessionId: sid || undefined, mode });
  const s = sessionById(sid);
  if (s) s.mode = mode; else S.prefs.mode = mode;
  renderBelow();
}
function cycleMode() {
  const s = sessionById(S.current) || {};
  const modes = (S.modes && S.modes.length ? S.modes : Object.keys(MODE_LABEL)).filter((m) => m !== "bypassPermissions");
  const cur = s.mode || S.prefs.mode || "default";
  setMode(S.current, modes[(modes.indexOf(cur) + 1) % modes.length]);
}

function modelMenu(anchor) {
  const s = sessionById(S.current) || {};
  const model = s.model || S.prefs.model, effort = s.effort || S.prefs.effort;
  const set = (m, e) => {
    bridge.post({ type: "setModel", sessionId: S.current || undefined, model: m, effort: e });
    if (S.current && s.id) { s.model = m; s.effort = e; } else { S.prefs.model = m; S.prefs.effort = e; }
    renderBelow();
  };
  const efforts = S.efforts && S.efforts.length ? S.efforts : Object.keys(EFFORT_LABEL);
  menu(anchor, [{ header: "模型" }].concat(
    S.models.map((m) => ({ label: m.label, sub: m.description, check: m.value === model, on: () => set(m.value, effort) })),
    ["-", { header: "思考强度" }],
    efforts.map((e) => ({ label: EFFORT_LABEL[e] || e, check: e === effort, on: () => set(model, e) }))), { up: true, alignRight: true });
}

function attachMenu(anchor) {
  const pick = async (kind) => {
    try {
      const r = await bridge.call("pickAttachment", { kind }, 600000);
      S.draft.attachments.push(...(r.attachments || []));
      renderAttachments();
      renderComposerState();
    } catch (e) { if (e.message !== "cancelled") toast(e.message); }
  };
  menu(anchor, [
    { label: "图片", icon: "image", on: () => pick("image") },
    { label: "文件", icon: "clip", on: () => pick("file") },
    { label: "拍照", icon: "camera", on: () => pick("camera") },
  ], { up: true });
}
function renderAttachments() {
  const box = $("#composer-atts");
  if (!box) return;
  clear(box);
  box.style.display = S.draft.attachments.length ? "" : "none";
  S.draft.attachments.forEach((a, k) => box.append(h("div.att", a.thumb ? h("img", { src: a.thumb, alt: "" }) : icon("file", 16), h("span", { text: a.name }),
    h("button.x", { on: { click: () => { S.draft.attachments.splice(k, 1); renderAttachments(); renderComposerState(); } } }, icon("x", 13)))));
}

function toggleDictation() {
  dictating = !dictating;
  bridge.post({ type: "dictate", on: dictating });
  const m = $("#mic");
  if (m) m.classList.toggle("on", dictating);
}
let dictBase = null;
function onDictation(m) {
  const ta = $("#composer-input");
  if (!ta) return;
  if (dictBase === null) dictBase = ta.value ? ta.value.replace(/\s*$/, " ") : "";
  ta.value = dictBase + m.text;
  if (m.final) { dictBase = null; }
  S.draft.text = ta.value;
  autosize(ta);
  renderComposerState();
  if (m.final && m.done) { dictating = false; const b = $("#mic"); if (b) b.classList.remove("on"); }
}

async function sendOrStop() {
  const text = S.draft.text.trim();
  const atts = S.draft.attachments;
  const st = S.current ? sesState(S.current) : null;
  if (!text && !atts.length) {
    if (st && st.status.running) bridge.post({ type: "interrupt", sessionId: S.current });
    return;
  }
  if (S.net && S.net.allowed === false) { toast("现在不在允许的 Wi-Fi 上，Claude 连不上"); return; }
  let sid = S.current;
  if (!sid) {
    if (!S.draftProject) { toast("先建一个项目"); newProjectDialog(); return; }
    try {
      const r = await bridge.call("newSession", { projectId: S.draftProject });
      sid = r.sessionId;
    } catch (e) { toast(e.message); return; }
    S.current = sid;
    sesState(sid).loaded = true;
    renderSide();
    renderMain();
  }
  bridge.post({ type: "send", sessionId: sid, text, attachments: atts.map((a) => a.id) });
  S.draft = { text: "", attachments: [] };
  const ta = $("#composer-input");
  if (ta) { ta.value = ""; autosize(ta); }
  renderAttachments();
  renderComposerState();
  stickToEnd = true;
}

/* slash commands */
let slashSel = 0;
function slashMenu(ta) {
  let box = $(".slash");
  const v = ta.value;
  const m = /^\/(\S*)$/.exec(v);
  if (!m) { if (box) box.remove(); return; }
  const builtin = [{ name: "compact", description: "压缩对话，腾出上下文" }, { name: "clear", description: "清空，从头开始" }, { name: "context", description: "看上下文用量" }];
  const all = builtin.concat((S.commands || []).filter((c) => !builtin.some((b) => b.name === c.name)));
  const hits = all.filter((c) => c.name.toLowerCase().startsWith(m[1].toLowerCase())).slice(0, 30);
  if (!hits.length) { if (box) box.remove(); return; }
  if (!box) { box = h("div.menu.slash"); ta.closest(".composer-wrap").append(box); }
  box.style.position = "absolute";
  clear(box);
  slashSel = Math.min(slashSel, hits.length - 1);
  hits.forEach((c, k) => box.append(h("button.mi" + (k === slashSel ? ".kb" : ""), { "data-cmd": c.name, on: { click: () => pickSlash(c.name) } },
    h("span.mono", { text: "/" + c.name }), h("span.grow.muted", { style: { fontSize: "13px" }, text: c.description || "" }))));
}
function pickSlash(name) {
  const ta = $("#composer-input");
  ta.value = "/" + name + " ";
  S.draft.text = ta.value;
  const box = $(".slash");
  if (box) box.remove();
  ta.focus();
  renderComposerState();
}

function composerKeys(e) {
  const box = $(".slash");
  if (box) {
    const items = $$(".mi", box);
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      slashSel = (slashSel + (e.key === "ArrowDown" ? 1 : items.length - 1)) % items.length;
      items.forEach((it, k) => it.classList.toggle("kb", k === slashSel));
      return;
    }
    if ((e.key === "Enter" || e.key === "Tab") && items[slashSel]) { e.preventDefault(); pickSlash(items[slashSel].dataset.cmd); return; }
    if (e.key === "Escape") { e.preventDefault(); box.remove(); return; }
  }
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) { e.preventDefault(); sendOrStop(); return; }
  if (e.key === "Tab" && e.shiftKey) { e.preventDefault(); cycleMode(); return; }
}

/* ------------------------------------------------------------------ side panel */
const CTX_COLORS = ["var(--c1)", "var(--c2)", "var(--c3)", "var(--c4)", "var(--c5)", "var(--c6)", "var(--faint)"];
function renderPanel() {
  const panel = clear($("#panel"));
  if (!S.current) return;
  const st = sesState(S.current);
  const s = sessionById(S.current) || {};
  const p = projectById(s.projectId) || {};
  const env = st.env || { env: p.env, cwd: p.path };
  const pcard = (key, title, body) => {
    const collapsed = !!S.ui.groups["panel." + key];
    return h("div.pcard" + (collapsed ? ".collapsed" : ""),
      h("div.pcard-head", { on: { click: () => { S.ui.groups["panel." + key] = !collapsed; saveUi(); renderPanel(); } } }, h("span", { text: title }), icon("chevDown")),
      h("div.pcard-body", body));
  };

  // 环境
  const onPc = env.env === "pc";
  const pcOk = S.pc && S.pc.status === "ok";
  panel.append(pcard("env", "环境", [
    h("div.prow", icon(onPc ? "monitor" : "tablet"), h("span.lbl", { text: onPc ? "电脑 · " + (S.pc.host || "piggy-pc") : "本平板" }),
      onPc ? h("span.v", h("span.pill" + (pcOk ? ".ok" : S.pc.status === "connecting" ? ".wait" : ".bad"), { text: pcOk ? "已连接" : S.pc.status === "connecting" ? "连接中" : "未连接" })) : null),
    h("div.prow.click", { title: env.cwd, on: { click: () => filesDialog("") } }, icon("folder"), h("span.lbl", { text: p.name || basename(env.cwd) }), h("span.v.mono", { text: env.cwd || "" })),
    env.branch ? h("div.prow", icon("branch"), h("span.lbl", { text: env.branch })) : null,
    h("div.prow", icon("cloud"), h("span.lbl", { text: "Remote Control" }),
      h("button.toggle" + (env.remote ? ".on" : ""), { title: "交给 Claude Code 的远程控制，在手机的 Claude App 里接着用", on: { click: () => bridge.post({ type: "remoteControl", sessionId: S.current, on: !env.remote }) } })),
  ]));

  // 上下文
  const c = st.context;
  if (c && c.window) {
    const pct = Math.round((c.used / c.window) * 100);
    const parts = (c.breakdown || []).filter((b) => b.tokens > 0);
    const bar = h("div.ctx-bar", parts.length ? parts.map((b, k) => h("span", { style: { width: (b.tokens / c.window) * 100 + "%", background: CTX_COLORS[k % CTX_COLORS.length] } }))
      : h("span", { style: { width: pct + "%", background: "var(--c1)" } }));
    const left = c.autoCompactAt ? Math.max(0, c.autoCompactAt - c.used) : null;
    panel.append(pcard("ctx", "上下文", [
      h("div.ctx-top", h("span", { text: "已使用 " + pct + "%" }), h("span.muted", { text: fmtTokens(c.used) + " / " + fmtTokens(c.window) })),
      bar,
      parts.map((b, k) => h("div.ctx-row", h("span.sw", { style: { background: CTX_COLORS[k % CTX_COLORS.length] } }), h("span", { text: b.label }),
        h("span.n", { text: fmtTokens(b.tokens) }), h("span.p", { text: ((b.tokens / c.window) * 100).toFixed(1) + "%" }))),
      h("div.ctx-foot", h("span", { text: left != null ? "距自动压缩还剩 " + fmtTokens(left) : "" }),
        h("button.btn", { text: "压缩会话", on: { click: () => bridge.post({ type: "send", sessionId: S.current, text: "/compact", attachments: [] }) } })),
    ]));
  } else {
    panel.append(pcard("ctx", "上下文", h("div.empty-note", { text: "发第一条消息后显示用量" })));
  }

  // 待办
  if (st.todos && st.todos.length) panel.append(pcard("todos", "待办", todoList(st.todos)));

  // 输出内容
  panel.append(pcard("out", "输出内容", st.outputs.length ? st.outputs.slice().reverse().map((f) =>
    h("div.file-row", { title: f.path, on: { click: () => viewFile(f.path) } }, icon("file", 15), h("span.fn", { text: "\u200e" + f.path }), h("span.tag", { text: f.action === "edit" ? "改" : "新" })))
    : h("div.empty-note", { text: "Claude 写过、改过的文件会列在这里" })));
}

/* ------------------------------------------------------------------ files, diff, search */
async function filesDialog(path) {
  let entries = [];
  try { entries = (await bridge.call("listFiles", { sessionId: S.current, path })).entries || []; } catch (e) { toast(e.message); return; }
  dialog((box, close) => {
    const up = path ? path.split("/").slice(0, -1).join("/") : null;
    add(box, [h("h3", { text: path || "项目文件" }),
      h("div", { style: { maxHeight: "60vh", overflowY: "auto" } },
        up !== null ? h("div.file-row", { on: { click: () => filesDialog(up) } }, icon("chevLeft", 15), h("span", { text: ".." })) : null,
        entries.map((e) => h("div.file-row", { on: { click: () => e.dir ? filesDialog((path ? path + "/" : "") + e.name) : viewFile((path ? path + "/" : "") + e.name) } },
          icon(e.dir ? "folder" : "file", 15), h("span.grow", { text: e.name }), h("span.tag", { text: e.dir ? "" : fmtSize(e.size) })))),
      h("div.actions", h("button.btn.primary", { text: "关闭", on: { click: close } }))]);
  });
}
const fmtSize = (n) => (n == null ? "" : n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB");

async function viewFile(path) {
  let r;
  try { r = await bridge.call("readFile", { sessionId: S.current, path }); } catch (e) { toast(e.message); return; }
  dialog((box, close) => add(box, [h("h3.mono", { style: { fontSize: "14px", wordBreak: "break-all" }, text: path }),
    r.image ? h("img", { src: r.image, style: { maxWidth: "100%" } }) : h("div.codeblock", h("pre", h("code", { html: highlight(r.text || "", path.split(".").pop()) }))),
    h("div.actions", h("button.btn.primary", { text: "关闭", on: { click: close } }))]), ".dialog.wide");
  const d = $(".dialog.wide");
  if (d) d.style.width = "min(960px, calc(100% - 32px))";
}

async function diffDialog() {
  let r;
  try { r = await bridge.call("gitDiff", { sessionId: S.current }); } catch (e) { toast(e.message); return; }
  dialog((box, close) => {
    const view = h("div.diff", { style: { maxHeight: "65vh" } });
    const lines = String(r.diff || "").split("\n");
    if (!r.diff) view.append(h("div.ln.ctx.muted", { text: "没有未提交的改动" }));
    for (const l of lines) {
      if (/^diff --git /.test(l)) view.append(h("div.file", { text: l.replace(/^diff --git a\/(.*) b\/.*$/, "$1") }));
      else if (/^(index |--- |\+\+\+ )/.test(l)) continue;
      else if (l.startsWith("@@")) view.append(h("div.ln.ctx.muted", { text: l }));
      else if (l.startsWith("+")) view.append(h("div.ln.add", { text: l.slice(1) }));
      else if (l.startsWith("-")) view.append(h("div.ln.del", { text: l.slice(1) }));
      else view.append(h("div.ln.ctx", { text: l.slice(1) }));
    }
    add(box, [h("h3", { text: "未提交的改动" + (r.branch ? " · " + r.branch : "") }), view, h("div.actions", h("button.btn.primary", { text: "关闭", on: { click: close } }))]);
  });
  const d = $(".dialog");
  if (d) d.style.width = "min(1000px, calc(100% - 32px))";
}

function openSearch() {
  dialog((box, close) => {
    const input = h("input", { placeholder: "搜索会话…" });
    const res = h("div.res");
    let sel = 0, hits = [], timer = null;
    const draw = () => {
      clear(res);
      if (!hits.length) res.append(h("div.empty-note", { style: { padding: "10px 12px" }, text: input.value ? "没找到" : "输入关键词，搜标题和内容" }));
      hits.forEach((x, k) => res.append(h("div.hit" + (k === sel ? ".kb" : ""), { on: { click: () => { close(); openSession(x.sessionId); } } },
        h("div", { text: x.title || "新会话" }), x.snippet ? h("div.s", { text: x.snippet }) : null)));
    };
    const run = () => {
      const q = input.value.trim().toLowerCase();
      hits = S.sessions.filter((s) => !q || (s.title || "").toLowerCase().includes(q)).slice(0, 20).map((s) => ({ sessionId: s.id, title: s.title, snippet: (projectById(s.projectId) || {}).name || "" }));
      sel = 0; draw();
      clearTimeout(timer);
      if (q) timer = setTimeout(async () => {
        try {
          const r = await bridge.call("search", { query: q }, 15000);
          for (const x of r.hits || []) if (!hits.some((y) => y.sessionId === x.sessionId)) hits.push(x);
          draw();
        } catch (e) { /* titles are enough */ }
      }, 250);
    };
    input.addEventListener("input", run);
    input.addEventListener("keydown", (e) => {
      if (e.key === "ArrowDown") { sel = Math.min(sel + 1, hits.length - 1); draw(); e.preventDefault(); }
      if (e.key === "ArrowUp") { sel = Math.max(sel - 1, 0); draw(); e.preventDefault(); }
      if (e.key === "Enter" && hits[sel]) { close(); openSession(hits[sel].sessionId); }
    });
    add(box, [input, res]);
    run();
  }, ".search");
}

function shortcutsDialog() {
  const rows = [["Ctrl N", "新建会话"], ["Ctrl K", "搜索"], ["Ctrl B", "侧栏"], ["Ctrl Shift B", "右侧栏"], ["Ctrl ,", "设置"],
    ["Enter", "发送"], ["Shift Enter", "换行"], ["Shift Tab", "切换权限模式"], ["Esc", "中断 Claude / 关闭弹窗"], ["/", "斜杠命令"], ["Ctrl /", "这张表"]];
  dialog((box, close) => add(box, [h("h3", { text: "快捷键" }),
    rows.map(([k, v]) => h("div.prow", h("span.lbl", { text: v }), h("span.v", h("span.kbd", { text: k })))),
    h("div.actions", h("button.btn.primary", { text: "好", on: { click: close } }))]));
}

/* ------------------------------------------------------------------ keyboard */
document.addEventListener("keydown", (e) => {
  const ctrl = e.ctrlKey || e.metaKey;
  if (ctrl && !e.shiftKey && e.key.toLowerCase() === "n") { e.preventDefault(); closeDialog(); newSession(); return; }
  if (ctrl && e.key.toLowerCase() === "k") { e.preventDefault(); openSearch(); return; }
  if (ctrl && e.key.toLowerCase() === "b") { e.preventDefault(); e.shiftKey ? togglePanel() : toggleSide(); return; }
  if (ctrl && e.key === ",") { e.preventDefault(); openView("settings"); return; }
  if (ctrl && e.key === "/") { e.preventDefault(); shortcutsDialog(); return; }
  if (e.key === "Escape") {
    if (closeMenu() || closeDialog()) { e.preventDefault(); return; }
    if (S.view !== "chat") { S.view = "chat"; renderMain(); return; }
    const sid = S.current;
    if (sid) {
      const st = sesState(sid);
      if (st.permissions.size) {
        const card = $("#thread .card[data-perm]");
        const deny = card && $$("button", card).pop();
        if (deny) deny.click();
        return;
      }
      if (st.status.running) { bridge.post({ type: "interrupt", sessionId: sid }); return; }
    }
  }
  if (e.key === "Enter" && !e.shiftKey && !ctrl && S.current && document.activeElement && document.activeElement.tagName !== "TEXTAREA" && document.activeElement.tagName !== "INPUT") {
    const card = $("#thread .card[data-perm]");
    const allow = card && $(".btn.accent", card);
    if (allow) { e.preventDefault(); allow.click(); }
  }
});

/* ------------------------------------------------------------------ start */
renderAll();
bridge.post({ type: "ready" });
