// The right-hand panel, after Claude Desktop's side pane: tabs for 概览 (environment, context, memory, background
// tasks, to-dos, outputs, outline), 计划, 改动, 文件, 浏览器, and any files opened from the conversation (Markdown, code,
// images, PDF, HTML, tables, media). The browser is a native view the app lays over the slot this page leaves for it.
"use strict";

const PANEL_TABS = [["overview", "概览", "panel"], ["plan", "计划", "plan"], ["diff", "改动", "diff"], ["files", "文件", "folder"], ["browser", "浏览器", "globe"]];
const CTX_COLORS = ["var(--c1)", "var(--c2)", "var(--c3)", "var(--c4)", "var(--c5)", "var(--c6)", "var(--faint)"];

const panelVisible = () => !!S.current && S.view === "chat" && !setupNeeded() && (midWidth() ? S.ui.panelOver : S.ui.panel);

function showPanel() {
  if (midWidth()) S.ui.panelOver = true;
  else if (!S.ui.panel) { S.ui.panel = true; saveUi(); }
  layout();
  renderTopbar();
}

function setTab(sid, tab) {
  const st = sesState(sid);
  st.tab = tab;
  if (tab === "plan") st.planSeen = st.plan && st.plan.at;
  if (S.current === sid) { showPanel(); renderPanel(); }
}

/** Opens a file in a tab of its own. where: "" = the project's own place (tablet or PC), "tablet" = this tablet's Linux. */
function openFile(path, opts) {
  const sid = S.current;
  if (!sid || !path) return;
  opts = opts || {};
  const st = sesState(sid);
  const where = opts.where || "";
  const key = "f:" + where + ":" + path;
  if (!st.files.some((f) => f.key === key)) st.files.push({ key, path, where, name: opts.name || basename(path) });
  while (st.files.length > 8) { const old = st.files.shift(); delete st.fileCache[old.key]; }
  if (opts.reload) delete st.fileCache[key];
  setTab(sid, key);
}

function closeFileTab(sid, key) {
  const st = sesState(sid);
  const k = st.files.findIndex((f) => f.key === key);
  if (k < 0) return;
  st.files.splice(k, 1);
  delete st.fileCache[key];
  if (st.tab === key) st.tab = st.files.length ? st.files[Math.min(k, st.files.length - 1)].key : "overview";
  renderPanel();
}

/* ------------------------------------------------------------------ the panel */
function renderPanel() {
  const panel = clear($("#panel"));
  if (!S.current) { syncBrowserSoon(); return; }
  const sid = S.current;
  const st = sesState(sid);
  if (!PANEL_TABS.some(([k]) => k === st.tab) && !st.files.some((f) => f.key === st.tab)) st.tab = "overview";
  panel.classList.toggle("compact", panelWidth() < 470);
  panel.append(panelHead(sid, st));
  const body = h("div.pbody" + (st.tab === "browser" ? ".flush" : ""));
  panel.append(body);
  const f = st.files.find((x) => x.key === st.tab);
  if (f) fileView(sid, st, f, body);
  else ({ overview: overviewBody, plan: planBody, diff: diffBody, files: filesBody, browser: browserBody })[st.tab](sid, st, body);
  syncBrowserSoon();
}

function panelWidth() {
  const p = $("#panel");
  return p && p.offsetWidth ? p.offsetWidth : S.ui.panelW;
}

function panelHead(sid, st) {
  const tabs = h("div.ptabs");
  for (const [k, label, ic] of PANEL_TABS) {
    let badge = null;
    if (k === "plan" && st.plan && st.plan.at !== st.planSeen) badge = h("span.bdot");
    if (k === "overview" && st.tasks.some((t) => t.status === "running")) badge = icon("spinner", 12);
    if (k === "diff" && st.outputs.length) badge = h("span.bn", { text: String(st.outputs.length) });
    tabs.append(h("button.ptab" + (st.tab === k ? ".on" : ""), { title: label, on: { click: () => setTab(sid, k) } }, icon(ic, 16), h("span.pl", { text: label }), badge));
  }
  for (const f of st.files) {
    tabs.append(h("div.ptab.ftab" + (st.tab === f.key ? ".on" : ""), { title: f.path, on: { click: () => setTab(sid, f.key), auxclick: (e) => { if (e.button === 1) closeFileTab(sid, f.key); } } },
      icon(fileIcon(f.name), 15), h("span.pl", { text: f.name }),
      h("button.x", { title: "关闭", on: { click: (e) => { e.stopPropagation(); closeFileTab(sid, f.key); } } }, icon("x", 12))));
  }
  const tb = tabs.querySelector(".on");
  if (tb) setTimeout(() => tb.scrollIntoView({ block: "nearest", inline: "nearest" }), 0);
  return h("div.phead", tabs, h("div.pact",
    midWidth() ? null : h("button.icon-btn", { title: S.ui.panelMax ? "还原宽度" : "放大", on: { click: () => { S.ui.panelMax = !S.ui.panelMax; saveUi(); applyWidths(); renderPanel(); } } }, icon(S.ui.panelMax ? "shrink" : "expand", 17)),
    h("button.icon-btn", { title: "关闭右侧栏 (Ctrl+Shift+B)", on: { click: togglePanel } }, icon("x", 17))));
}

function pcard(key, title, body, extra) {
  const collapsed = !!S.ui.groups["panel." + key];
  return h("div.pcard" + (collapsed ? ".collapsed" : ""),
    h("div.pcard-head", { on: { click: () => { S.ui.groups["panel." + key] = !collapsed; saveUi(); renderPanel(); } } }, h("span.grow", { text: title }), extra || null, icon("chevDown")),
    h("div.pcard-body", body));
}

/* ------------------------------------------------------------------ 概览 */
function overviewBody(sid, st, body) {
  const s = sessionById(sid) || {};
  const p = projectById(s.projectId) || {};
  const env = st.env || { env: p.env, cwd: p.path };

  // 环境
  const onPc = env.env === "pc";
  const pcOk = S.pc && S.pc.status === "ok";
  body.append(pcard("env", "环境", [
    h("div.prow", icon(onPc ? "monitor" : "tablet"), h("span.lbl", { text: onPc ? "电脑 · " + (S.pc.host || "piggy-pc") : "本平板" }),
      onPc ? h("span.v", h("span.pill" + (pcOk ? ".ok" : S.pc.status === "connecting" ? ".wait" : ".bad"), { text: pcOk ? "已连接" : S.pc.status === "connecting" ? "连接中" : "未连接" })) : null),
    h("div.prow.click", { title: env.cwd, on: { click: () => setTab(sid, "files") } }, icon("folder"), h("span.lbl", { text: p.name || basename(env.cwd) }), h("span.v.mono", { text: env.cwd || "" })),
    env.branch ? h("div.prow.click", { on: { click: () => setTab(sid, "diff") } }, icon("branch"), h("span.lbl", { text: env.branch })) : null,
    h("div.prow", icon("cloud"), h("span.lbl", { text: "Remote Control" }),
      h("button.toggle" + (env.remote ? ".on" : ""), { title: "交给 Claude Code 的远程控制，在手机的 Claude App 里接着用", on: { click: () => bridge.post({ type: "remoteControl", sessionId: sid, on: !env.remote }) } })),
  ]));

  // 上下文
  const c = st.context;
  if (c && c.window) {
    const pct = Math.round((c.used / c.window) * 100);
    const parts = (c.breakdown || []).filter((b) => b.tokens > 0);
    const bar = h("div.ctx-bar", parts.length ? parts.map((b, k) => h("span", { style: { width: (b.tokens / c.window) * 100 + "%", background: CTX_COLORS[k % CTX_COLORS.length] } }))
      : h("span", { style: { width: pct + "%", background: "var(--c1)" } }));
    const left = c.autoCompactAt ? Math.max(0, c.autoCompactAt - c.used) : null;
    body.append(pcard("ctx", "上下文", [
      h("div.ctx-top", h("span", { text: "已使用 " + pct + "%" }), h("span.muted", { text: fmtTokens(c.used) + " / " + fmtTokens(c.window) })),
      bar,
      parts.map((b, k) => h("div.ctx-row", h("span.sw", { style: { background: CTX_COLORS[k % CTX_COLORS.length] } }), h("span", { text: b.label }),
        h("span.n", { text: fmtTokens(b.tokens) }), h("span.p", { text: ((b.tokens / c.window) * 100).toFixed(1) + "%" }))),
      h("div.ctx-foot", h("span", { text: left != null ? "距自动压缩还剩 " + fmtTokens(left) : "" }),
        h("button.btn", { text: "压缩会话", on: { click: () => bridge.post({ type: "send", sessionId: sid, text: "/compact", attachments: [] }) } })),
    ]));
  } else {
    body.append(pcard("ctx", "上下文", h("div.empty-note", { text: "发第一条消息后显示用量" })));
  }

  // 记忆
  body.append(pcard("mem", "记忆", memoryRows(sid, st, p), h("button.mini", { title: "管理记忆", on: { click: (e) => { e.stopPropagation(); customTab = "memory"; memoryProject = p.id; openView("customize"); } } }, icon("sliders", 14))));

  // 后台任务
  if (st.tasks.length) {
    body.append(pcard("tasks", "后台任务", st.tasks.slice().reverse().slice(0, 12).map((t) => h("div.task-row",
      h("span.pill" + (t.status === "running" ? ".wait" : t.status === "completed" ? ".ok" : ".bad"), { text: TASK_STATUS[t.status] || t.status }),
      h("span.grow.lbl", { text: t.description || t.id, title: t.description }),
      t.kind === "shell" ? h("button.mini", { title: "看输出", on: { click: () => taskOutput(sid, t) } }, icon("terminal", 14)) : null,
      t.status === "running" ? h("button.mini", { title: "停止", on: { click: () => bridge.post({ type: "stopTask", sessionId: sid, taskId: t.id }) } }, icon("stop", 14)) : null))));
  }

  // 待办
  if (st.todos && st.todos.length) body.append(pcard("todos", "待办", todoList(st.todos)));

  // 输出内容
  body.append(pcard("out", "输出内容", st.outputs.length ? st.outputs.slice().reverse().map((f) =>
    h("div.file-row", { title: f.path, on: { click: () => openFile(f.path) } }, icon(fileIcon(f.path), 15), h("span.fn", { text: "‎" + f.path }), h("span.tag", { text: f.action === "edit" ? "改" : "新" })))
    : h("div.empty-note", { text: "Claude 写过、改过的文件会列在这里" })));

  // 目录: the user's messages, to jump around a long conversation
  const asks = st.order.map((id) => st.items.get(id)).filter((it) => it && it.kind === "user" && (it.text || "").trim());
  if (asks.length > 1) {
    body.append(pcard("toc", "目录", asks.map((it, k) => h("div.toc-row", { on: { click: () => jumpTo(it.id) } },
      h("span.n", { text: String(k + 1) }), h("span.grow.lbl", { text: it.text.trim().split("\n")[0] })))));
  }
}
const TASK_STATUS = { running: "运行中", completed: "完成", failed: "失败", stopped: "已停", killed: "已停", pending: "排队" };

function jumpTo(itemId) {
  const el = $('#thread [data-id="' + cssId(itemId) + '"]');
  if (!el) return;
  stickToEnd = false;
  el.scrollIntoView({ block: "start", behavior: "smooth" });
  el.classList.add("flash");
  setTimeout(() => el.classList.remove("flash"), 1400);
  if (midWidth()) { S.ui.panelOver = false; layout(); }
}

async function taskOutput(sid, t) {
  let r;
  try { r = await bridge.call("taskOutput", { sessionId: sid, taskId: t.id }, 20000); } catch (e) { toast(e.message); return; }
  dialog((box, close) => add(box, [h("h3", { text: t.description || "后台任务" }),
    h("div.out", { style: { maxHeight: "60vh" }, text: (r.truncated ? "…\n" : "") + (r.output || "（还没有输出）") }),
    h("div.actions", h("button.btn.primary", { text: "关闭", on: { click: close } }))]), ".dialog.wide");
}

/** The 记忆 card: which instruction files this session reads, and its auto-memory. */
function memoryRows(sid, st, p) {
  const m = st.memory;
  if (!m || Date.now() - (st.memoryAt || 0) > 60000) {
    st.memoryAt = Date.now();
    bridge.call("memoryGet", { projectId: p.id }, 30000).then((d) => { st.memory = d; if (S.current === sid && st.tab === "overview") renderPanel(); }).catch(() => {});
    if (!m) return h("div.empty-note", { text: "读取中…" });
  }
  const rows = (m.levels || []).filter((l) => l.exists).map((l) => h("div.file-row", { title: l.path, on: { click: () => openFile(l.path, { where: l.where === "pc" ? "pc" : "tablet", name: l.label }) } },
    icon("fileText", 15), h("span.grow", { text: l.label }), h("span.tag", { text: l.where === "pc" ? "电脑" : "" })));
  const a = m.auto || {};
  rows.push(h("div.file-row", { on: { click: () => { customTab = "memory"; memoryProject = p.id; openView("customize"); } } },
    icon("brain", 15), h("span.grow", { text: "自动记忆" }), h("span.tag", { text: a.enabled === false ? "已关" : (a.memories || []).length + " 条" })));
  return rows;
}

/* ------------------------------------------------------------------ 计划 */
function planBody(sid, st, body) {
  const p = st.plan;
  st.planSeen = p && p.at;
  const waiting = Array.from(st.permissions.values()).some((x) => x.kind === "plan");
  const s = sessionById(sid) || {};
  body.append(h("div.ptool",
    h("span.grow.muted", { text: p ? (p.at ? "更新于 " + fmtAgo(p.at) : "") : "" }),
    p && p.path ? h("button.mini", { title: "打开计划文件", on: { click: () => openFile(p.path, { where: "tablet" }) } }, icon("fileText", 15)) : null,
    p ? h("button.mini", { title: "复制", on: { click: () => { bridge.post({ type: "copy", text: p.text }); toast("已复制"); } } }, icon("copy", 15)) : null,
    h("button.mini", { title: "刷新", on: { click: () => bridge.post({ type: "planRefresh", sessionId: sid }) } }, icon("refresh", 15))));
  if (waiting) body.append(h("div.banner.warn.slim", icon("plan", 16), h("span.grow", { text: "Claude 在等你批准这个计划" }),
    h("button.btn", { text: "去批准", on: { click: () => { const c = $("#thread .card[data-perm]"); if (c) c.scrollIntoView({ block: "center", behavior: "smooth" }); if (midWidth()) { S.ui.panelOver = false; layout(); } } } })));
  if (!p || !p.text) {
    body.append(h("div.empty-big", icon("plan", 30), h("div", { text: "还没有计划" }),
      h("div.muted", { text: "切到「计划」模式，Claude 只看不改，先把计划写出来给你批。" }),
      (s.mode || S.prefs.mode) !== "plan" ? h("button.btn", { text: "切到计划模式", on: { click: () => setMode(sid, "plan") } }) : null));
    return;
  }
  body.append(h("div.md.doc", { html: renderMarkdown(p.text) }));
}

/* ------------------------------------------------------------------ 改动 */
async function diffBody(sid, st, body) {
  const bar = h("div.ptool");
  const list = h("div.dlist");
  body.append(bar, list);
  const draw = (r) => {
    clear(bar); clear(list);
    const files = parseDiff(r.diff || "");
    for (const u of r.untracked || []) if (!files.some((f) => f.path === u)) files.push({ path: u, status: "untracked", add: 0, del: 0, lines: [] });
    const nAdd = files.reduce((a, f) => a + f.add, 0), nDel = files.reduce((a, f) => a + f.del, 0);
    add(bar, [r.branch ? h("span.chip.muted", { style: { cursor: "default" } }, icon("branch", 14), h("span", { text: r.branch })) : null,
      h("span.grow.muted", { style: { fontSize: "13px" } }, files.length ? [files.length + " 个文件 ", h("span.stat-add", { text: "+" + nAdd }), " ", h("span.stat-del", { text: "−" + nDel })] : ""),
      files.length ? h("button.mini", { title: "全部展开", on: { click: () => { const all = !$$(".dfile", list).every((x) => x.classList.contains("open")); $$(".dfile", list).forEach((x) => x.classList.toggle("open", all)); } } }, icon("chevDown", 15)) : null,
      h("button.mini", { title: "刷新", on: { click: () => { st.diff = null; renderPanel(); } } }, icon("refresh", 15))]);
    if (!files.length) { list.append(h("div.empty-big", icon("check", 28), h("div", { text: r.error ? "看不了改动" : "没有未提交的改动" }), h("div.muted", { text: r.error || (r.branch ? "工作区和 " + r.branch + " 一样" : "这里不是 git 仓库，或者还没有提交") }))); return; }
    for (const f of files) {
      const row = h("div.dfile" + (files.length === 1 ? ".open" : ""));
      const head = h("div.dhead", { on: { click: () => row.classList.toggle("open") } }, icon("chevRight", 14),
        h("span.dst." + f.status, { text: { added: "新", deleted: "删", renamed: "移", untracked: "未跟踪", modified: "改" }[f.status] || "改" }),
        h("span.fn", { text: "‎" + f.path, title: f.path }),
        f.add ? h("span.stat-add", { text: "+" + f.add }) : null, f.del ? h("span.stat-del", { text: "−" + f.del }) : null,
        f.status !== "deleted" ? h("button.mini", { title: "打开文件", on: { click: (e) => { e.stopPropagation(); openFile(f.path); } } }, icon("external", 14)) : null);
      const view = h("div.diff.dbody");
      if (!f.lines.length) view.append(h("div.ln.ctx.muted", { text: f.status === "untracked" ? "新文件，还没有加进 git" : f.binary ? "二进制文件" : "（没有内容改动）" }));
      for (const l of f.lines) {
        if (l.startsWith("@@")) view.append(h("div.ln.hunk", { text: l.replace(/^@@ [^@]*@@ ?/, "") || "⋯" }));
        else if (l[0] === "+") view.append(h("div.ln.add", { text: l.slice(1) }));
        else if (l[0] === "-") view.append(h("div.ln.del", { text: l.slice(1) }));
        else if (l[0] !== "\\") view.append(h("div.ln.ctx", { text: l.slice(1) }));
      }
      row.append(head, view);
      list.append(row);
    }
  };
  if (st.diff && Date.now() - st.diff.at < 15000) return draw(st.diff);
  list.append(h("div.empty-note", { text: "读取改动…" }));
  try {
    const r = await bridge.call("gitDiff", { sessionId: sid }, 60000);
    st.diff = Object.assign({ at: Date.now() }, r);
  } catch (e) { st.diff = { at: Date.now(), diff: "", error: e.message }; }
  if (S.current === sid && st.tab === "diff") draw(st.diff);
}

/** A unified diff, split per file: [{path, status, add, del, lines, binary}]. */
function parseDiff(text) {
  const files = [];
  let f = null;
  for (const l of String(text).split("\n")) {
    const m = /^diff --git a\/(.*?) b\/(.*)$/.exec(l);
    if (m) { f = { path: m[2], status: "modified", add: 0, del: 0, lines: [] }; files.push(f); continue; }
    if (!f) continue;
    if (/^new file mode/.test(l)) f.status = "added";
    else if (/^deleted file mode/.test(l)) f.status = "deleted";
    else if (/^rename (from|to) /.test(l)) f.status = "renamed";
    else if (/^Binary files /.test(l)) f.binary = true;
    else if (/^(index |--- |\+\+\+ |similarity |old mode|new mode)/.test(l)) continue;
    else if (l.startsWith("@@") || l[0] === " " || l[0] === "\\") f.lines.push(l);
    else if (l[0] === "+") { f.add++; f.lines.push(l); }
    else if (l[0] === "-") { f.del++; f.lines.push(l); }
  }
  return files;
}

/* ------------------------------------------------------------------ 文件 */
function filesBody(sid, st, body) {
  const q = h("input.field.find", { placeholder: "按名字找文件…", value: st.findQ || "" });
  const out = h("div.ftree");
  body.append(h("div.ptool", q, h("button.mini", { title: "刷新", on: { click: () => { st.tree = {}; st.index = null; renderPanel(); } } }, icon("refresh", 15))), out);
  let timer = null;
  q.addEventListener("input", () => { st.findQ = q.value; clearTimeout(timer); timer = setTimeout(() => drawFind(sid, st, out, q.value.trim()), 150); });
  if (st.findQ) drawFind(sid, st, out, st.findQ.trim());
  else drawTree(sid, st, out);
}

async function listDir(sid, st, path) {
  if (st.tree[path]) return st.tree[path];
  const r = await bridge.call("listFiles", { sessionId: sid, path }, 60000);
  return (st.tree[path] = r.entries || []);
}

async function drawTree(sid, st, out) {
  const rows = [];
  const walk = async (path, depth) => {
    let entries;
    try { entries = await listDir(sid, st, path); } catch (e) { rows.push(h("div.empty-note", { text: e.message })); return; }
    for (const e of entries) {
      const p = (path ? path + "/" : "") + e.name;
      const open = st.treeOpen.has(p);
      rows.push(h("div.trow", { style: { paddingLeft: 6 + depth * 14 + "px" }, title: p, on: { click: () => {
        if (e.dir) { open ? st.treeOpen.delete(p) : st.treeOpen.add(p); drawTree(sid, st, out); } else openFile(p);
      } } }, e.dir ? icon(open ? "chevDown" : "chevRight", 13) : h("span.sp"), icon(e.dir ? "folder" : fileIcon(e.name), 15),
        h("span.grow.fname", { text: e.name }), e.dir ? null : h("span.tag", { text: fmtSize(e.size) })));
      if (e.dir && open) await walk(p, depth + 1);
    }
    if (!entries.length && !depth) rows.push(h("div.empty-note", { text: "文件夹是空的" }));
  };
  if (!st.tree[""]) { clear(out).append(h("div.empty-note", { text: "读取中…" })); }
  await walk("", 0);
  if (S.current !== sid || st.tab !== "files" || st.findQ) return;
  add(clear(out), rows);
}

async function drawFind(sid, st, out, q) {
  if (!q) return drawTree(sid, st, out);
  if (!st.index) {
    clear(out).append(h("div.empty-note", { text: "建索引…" }));
    try { st.index = (await bridge.call("fileIndex", { sessionId: sid }, 60000)).files || []; } catch (e) { clear(out).append(h("div.empty-note", { text: e.message })); return; }
  }
  const hits = fuzzyFiles(st.index, q, 200);
  if (st.findQ.trim() !== q) return;
  clear(out);
  if (!hits.length) out.append(h("div.empty-note", { text: "没找到" }));
  hits.forEach((p) => out.append(h("div.trow", { title: p, on: { click: () => openFile(p) } }, icon(fileIcon(p), 15),
    h("span.grow.fname", { text: basename(p) }), h("span.tag.dir", { text: p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "" }))));
}

/** Paths whose name (then whole path) contains the query's letters in order, best first. */
function fuzzyFiles(paths, q, max) {
  q = q.toLowerCase();
  const score = (s) => {
    s = s.toLowerCase();
    const at = s.indexOf(q);
    if (at >= 0) return 1000 - at - s.length / 100;
    let i = 0, gaps = 0, last = -1;
    for (let k = 0; k < s.length && i < q.length; k++) if (s[k] === q[i]) { if (last >= 0) gaps += k - last - 1; last = k; i++; }
    return i === q.length ? 500 - gaps - s.length / 100 : -1;
  };
  const out = [];
  for (const p of paths) {
    const byName = score(basename(p));
    const sc = Math.max(byName > 0 ? byName + 200 : -1, score(p));
    if (sc > 0) out.push([sc, p]);
  }
  return out.sort((a, b) => b[0] - a[0]).slice(0, max).map((x) => x[1]);
}

/* ------------------------------------------------------------------ 浏览器 */
function browserBody(sid, st, body) {
  const b = S.browser || {};
  const addr = h("input.addr", { value: b.url || "", placeholder: "输入网址或搜索", spellcheck: false, autocapitalize: "off" });
  addr.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); browserGo(addr.value); addr.blur(); } });
  addr.addEventListener("focus", () => addr.select());
  const btn = (ic, title, on, dis) => h("button.mini", { title, disabled: !!dis, on: { click: on } }, icon(ic, 16));
  body.append(h("div.btool",
    btn("chevLeft", "后退", () => bridge.post({ type: "browser", op: "back" }), !b.canBack),
    btn("chevRight", "前进", () => bridge.post({ type: "browser", op: "forward" }), !b.canForward),
    b.loading ? btn("x", "停止", () => bridge.post({ type: "browser", op: "stop" })) : btn("refresh", "刷新", () => bridge.post({ type: "browser", op: "reload" }), !b.url),
    h("div.addr-wrap", b.loading ? h("span.prog", { style: { width: (b.progress || 10) + "%" } }) : null, addr),
    btn("at", "把这一页告诉 Claude", () => mentionPage(), !b.url),
    btn("external", "用系统浏览器打开", () => b.url && bridge.post({ type: "openUrl", url: b.url }), !b.url)));
  const slot = h("div.bslot#bslot");
  if (!window.PiggyDesk || window.PiggyDesk.mock) slot.append(h("div.empty-big", icon("globe", 30), h("div", { text: b.url ? b.title || b.url : "浏览器" }), h("div.muted", { text: "在平板上，这里是一个真正的浏览器。Claude 也能用它（设置 → 浏览器）。" })));
  else if (!b.url) slot.append(h("div.empty-big", icon("globe", 30), h("div", { text: "输入网址开始" }), h("div.muted", { text: "Claude 也能打开、阅读和操作这个浏览器（设置 → 浏览器）。" })));
  body.append(slot);
  if (!b.url) setTimeout(() => { if (document.activeElement === document.body) addr.focus(); }, 50);
}

function browserGo(text) {
  text = String(text || "").trim();
  if (!text) return;
  let url = text;
  if (!/^[a-z][a-z0-9+.-]*:/i.test(text)) url = /^[^\s]+\.[^\s]{2,}$/.test(text) && !text.includes(" ") ? "https://" + text : "https://www.bing.com/search?q=" + encodeURIComponent(text);
  S.browser = Object.assign({}, S.browser, { url, loading: true, progress: 5 });
  bridge.post({ type: "browser", op: "go", url });
  if (S.current) setTab(S.current, "browser");
}

function mentionPage() {
  const b = S.browser || {};
  insertComposer("（浏览器里打开的页面：" + (b.title ? b.title + " — " : "") + b.url + "）");
}

let slotKey = "", slotQueued = false;
function syncBrowserSoon() {
  if (slotQueued) return;
  slotQueued = true;
  requestAnimationFrame(() => { slotQueued = false; syncBrowser(); });
}
/** Tells the app where the browser goes (CSS pixels from the page's top-left), or that it is hidden. */
function syncBrowser() {
  const slot = $("#bslot");
  const visible = !!slot && panelVisible() && !openMenu && !openDialog && !document.body.classList.contains("resizing-x") && slot.offsetWidth > 40;
  let msg;
  if (!visible) msg = { type: "browserSlot", visible: false };
  else {
    const r = slot.getBoundingClientRect();
    msg = { type: "browserSlot", visible: true, x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) };
  }
  const key = JSON.stringify(msg);
  if (key === slotKey) return;
  slotKey = key;
  bridge.post(msg);
}
addEventListener("resize", syncBrowserSoon);
document.addEventListener("transitionend", (e) => { if (e.target && (e.target.id === "panel" || e.target.id === "side")) syncBrowserSoon(); });
if (window.ResizeObserver) new ResizeObserver(syncBrowserSoon).observe(document.documentElement);

/* ------------------------------------------------------------------ files */
const EXT_LANG = { mjs: "js", cjs: "js", kts: "kt", hpp: "cpp", cc: "cpp", cxx: "cpp", m: "c", gradle: "java", bat: "sh", cmd: "sh", psm1: "ps1", htm: "html", vue: "html", svelte: "html", conf: "ini", cfg: "ini", env: "sh", properties: "ini", lock: "json", ipynb: "json", jsonl: "json" };
const extOf = (p) => { const n = basename(p).toLowerCase(); const k = n.lastIndexOf("."); return k > 0 ? n.slice(k + 1) : n; };
function fileIcon(p) {
  const e = extOf(p);
  if (/^(png|jpe?g|gif|webp|bmp|svg|ico|heic)$/.test(e)) return "image";
  if (e === "pdf") return "pdf";
  if (/^(md|markdown|txt|rst)$/.test(e)) return "fileText";
  if (/^(csv|tsv|xlsx?)$/.test(e)) return "table";
  if (/^(html?|xml|svg)$/.test(e)) return "globe";
  if (/^(mp4|webm|mov|mkv|mp3|wav|ogg|m4a|flac)$/.test(e)) return "play";
  if (/^(js|ts|tsx|jsx|py|java|kt|c|cpp|h|go|rs|sh|ps1|css|json|ya?ml|toml|sql|gradle|kts)$/.test(e)) return "code";
  return "file";
}

async function fileView(sid, st, f, body) {
  const ext = extOf(f.path);
  const box = h("div.fview");
  const tools = h("span.ftools");
  body.append(h("div.ptool.fbar", h("span.fpath.mono", { text: "‎" + f.path, title: f.path }), tools), box);
  let r = st.fileCache[f.key];
  if (!r) {
    box.append(h("div.empty-note", { text: "打开中…" }));
    try { r = await bridge.call("readFile", { sessionId: sid, path: f.path, where: f.where }, 120000); } catch (e) { clear(box).append(h("div.empty-big", icon("alert", 28), h("div", { text: "打不开" }), h("div.muted", { text: e.message }))); return; }
    if (r.image && !r.kind) r = { kind: "image", url: r.image };
    if (r.text != null && !r.kind) r.kind = "text";
    st.fileCache[f.key] = r;
    if (S.current !== sid || st.tab !== f.key) return;
    clear(box);
  }
  const mode = st.fileMode[f.key] || "preview";
  const previewable = r.kind === "text" && /^(md|markdown|csv|tsv|json|ipynb)$/.test(ext) || r.kind === "html";
  const tb = (ic, title, on) => h("button.mini", { title, on: { click: on } }, icon(ic, 15));
  if (previewable) tools.append(h("div.seg.tiny", [["preview", "预览"], ["source", "源码"]].map(([v, l]) => h("button" + (mode === v ? ".on" : ""), { text: l, on: { click: () => { st.fileMode[f.key] = v; renderPanel(); } } }))));
  if (r.kind === "html" && r.browseUrl) tools.append(tb("globe", "在浏览器里打开（会运行页面里的脚本）", () => browserGo(r.browseUrl)));
  tools.append(tb("at", "在消息里提到这个文件", () => insertComposer("@" + f.path + " ")),
    tb("copy", "复制路径", () => { bridge.post({ type: "copy", text: f.path }); toast("路径已复制"); }),
    tb("refresh", "重新读取", () => { delete st.fileCache[f.key]; renderPanel(); }));
  if (r.shareable) tools.append(tb("external", "用其他应用打开", () => bridge.post({ type: "openFile", sessionId: sid, path: f.path, where: f.where })));
  renderFile(box, f, r, mode, ext);
}

function renderFile(box, f, r, mode, ext) {
  const info = (ic, a, b) => box.append(h("div.empty-big", icon(ic, 30), h("div", { text: a }), b ? h("div.muted", { text: b }) : null));
  switch (r.kind) {
    case "image": box.append(h("div.fimg", h("img", { src: r.url, alt: f.name }))); return;
    case "media": box.append(/^(mp3|wav|ogg|m4a|flac|aac)$/.test(ext) ? h("audio", { src: r.url, controls: true, style: { width: "100%" } }) : h("video.fvideo", { src: r.url, controls: true })); return;
    case "pdf": {
      box.classList.add("pdf");
      box.append(h("div.pdf-n.muted", { text: "共 " + r.pages + " 页" }));
      const w = Math.min(2000, Math.round(Math.max(320, panelWidth() - 30) * (devicePixelRatio || 1)));
      for (let i = 0; i < r.pages; i++) {
        const ratio = (r.sizes && r.sizes[i]) || r.ratio || 1.414;
        box.append(h("div.pdf-page", { style: { aspectRatio: "1 / " + ratio } }, h("img", { loading: "lazy", src: r.url + "/" + i + ".png?w=" + w, alt: "第 " + (i + 1) + " 页" })));
      }
      return;
    }
    case "html":
      if (mode === "source") box.append(codeView(r.text || "", "html"));
      else box.append(h("iframe.fhtml", { sandbox: "", srcdoc: r.text || "", title: f.name }));
      return;
    case "binary": info("file", "这个文件不能直接显示", (r.size != null ? fmtSize(r.size) + " · " : "") + "可以用其他应用打开"); return;
    case "big": info("file", "文件太大", fmtSize(r.size) + "，只能用其他应用打开"); return;
  }
  const text = r.text || "";
  if (r.truncated) box.append(h("div.banner.warn.slim", icon("info", 15), h("span", { text: "文件很长，只显示前 " + fmtSize(text.length) })));
  if (mode === "preview" && /^(md|markdown)$/.test(ext)) box.append(h("div.md.doc", { html: renderMarkdown(text) }));
  else if (mode === "preview" && /^(csv|tsv)$/.test(ext)) box.append(csvTable(text, ext === "tsv" ? "\t" : ","));
  else if (mode === "preview" && ext === "ipynb") box.append(notebookView(text));
  else if (mode === "preview" && ext === "json") { let t = text; try { t = JSON.stringify(JSON.parse(text), null, 2); } catch (e) { /* as written */ } box.append(codeView(t, "json")); }
  else box.append(codeView(text, EXT_LANG[ext] || ext));
}

function codeView(text, lang) {
  const n = text.split("\n").length;
  const nums = Array.from({ length: n }, (_, i) => i + 1).join("\n");
  return h("div.codeview", h("pre.gutter", { text: nums }), h("pre.code", h("code", { html: text.length < 300000 ? highlight(text, lang) : esc(text) })));
}

function csvTable(text, sep) {
  const rows = [];
  let row = [], cell = "", q = false;
  for (let i = 0; i < text.length && rows.length < 2001; i++) {
    const c = text[i];
    if (q) { if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') q = false; else cell += c; continue; }
    if (c === '"') q = true;
    else if (c === sep) { row.push(cell); cell = ""; }
    else if (c === "\n") { row.push(cell.replace(/\r$/, "")); rows.push(row); row = []; cell = ""; }
    else cell += c;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  const [head, ...rest] = rows;
  return h("div.csv", h("table", h("thead", h("tr", h("th.rn"), (head || []).map((c) => h("th", { text: c })))),
    h("tbody", rest.slice(0, 2000).map((r, k) => h("tr", h("td.rn", { text: String(k + 1) }), r.map((c) => h("td", { text: c })))))),
    rows.length > 2000 ? h("div.empty-note", { text: "只显示前 2000 行" }) : null);
}

function notebookView(text) {
  let nb;
  try { nb = JSON.parse(text); } catch (e) { return codeView(text, "json"); }
  const src = (x) => (Array.isArray(x) ? x.join("") : String(x || ""));
  return h("div.nb", (nb.cells || []).map((c) => c.cell_type === "markdown" ? h("div.md.doc.nbmd", { html: renderMarkdown(src(c.source)) })
    : h("div.nbcell", h("div.codeblock", h("pre", h("code", { html: highlight(src(c.source), "py") }))),
      (c.outputs || []).map((o) => {
        const d = o.data || {};
        if (d["image/png"]) return h("img.nbimg", { src: "data:image/png;base64," + src(d["image/png"]).trim() });
        const t = o.text || d["text/plain"];
        return t ? h("div.out", { text: src(t) }) : null;
      }))));
}
