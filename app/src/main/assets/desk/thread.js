// The conversation: messages, thinking, tool calls, and the cards that wait for an answer.
"use strict";

/* ------------------------------------------------------------------ tool looks */
// mcp__pc__* are the PC's versions of the built-in tools; they look the same, with a "电脑" tag.
const PC_MAP = { bash: "Bash", powershell: "PowerShell", read: "Read", write: "Write", edit: "Edit", glob: "Glob",
  grep: "Grep", ls: "LS", bg_start: "BgStart", bg_output: "BgOutput", bg_kill: "BgKill", bg_list: "BgList" };

function toolLook(item) {
  let name = item.name || "";
  let where = "";
  const pc = /^mcp__pc__(.+)$/.exec(name);
  if (pc) { name = PC_MAP[pc[1]] || pc[1]; where = "电脑"; }
  const inp = item.input || {};
  const path = inp.file_path || inp.path || inp.notebook_path || "";
  switch (name) {
    case "Bash": return { icon: "terminal", label: "运行", arg: firstLine(inp.command), where, desc: inp.description };
    case "PowerShell": return { icon: "terminal", label: "PowerShell", arg: firstLine(inp.command), where };
    case "Read": return { icon: "fileText", label: "读取", arg: shortPath(path), where };
    case "Write": return { icon: "file", label: "写入", arg: shortPath(path), where, stat: lineStat("", inp.content) };
    case "Edit": return { icon: "pencil", label: "编辑", arg: shortPath(path), where, stat: lineStat(inp.old_string, inp.new_string) };
    case "MultiEdit": return { icon: "pencil", label: "编辑", arg: shortPath(path), where,
      stat: (inp.edits || []).reduce((a, e) => { const s = lineStat(e.old_string, e.new_string); return { a: a.a + s.a, d: a.d + s.d }; }, { a: 0, d: 0 }) };
    case "NotebookEdit": return { icon: "pencil", label: "编辑笔记本", arg: shortPath(path), where };
    case "Glob": return { icon: "search", label: "查找文件", arg: inp.pattern, where };
    case "Grep": return { icon: "search", label: "搜索", arg: inp.pattern + (inp.path ? "  " + shortPath(inp.path) : ""), where };
    case "LS": return { icon: "folder", label: "列目录", arg: shortPath(path), where };
    case "WebFetch": return { icon: "globe", label: "打开网页", arg: inp.url };
    case "WebSearch": return { icon: "globe", label: "搜索网页", arg: inp.query };
    case "TodoWrite": return { icon: "list", label: "更新待办", arg: "" };
    case "Task": case "Agent": return { icon: "bot", label: "子代理", arg: inp.description || inp.subagent_type || "" };
    case "ExitPlanMode": return { icon: "plan", label: "计划", arg: "" };
    case "AskUserQuestion": return { icon: "help", label: "提问", arg: ((inp.questions || [])[0] || {}).question || "" };
    case "BgStart": return { icon: "clock", label: "后台任务", arg: firstLine(inp.command), where };
    case "BgOutput": return { icon: "clock", label: "查看后台任务", arg: inp.id, where };
    case "BgKill": return { icon: "clock", label: "结束后台任务", arg: inp.id, where };
    case "BgList": return { icon: "clock", label: "后台任务列表", arg: "", where };
    case "Skill": return { icon: "sliders", label: "技能", arg: inp.skill || inp.name || "" };
  }
  const mcp = /^mcp__([^_]+(?:_[^_]+)*)__(.+)$/.exec(name);
  if (mcp) return { icon: mcp[1] === "phone" ? "pig" : "link", label: mcp[1] === "phone" ? "小猪" : mcp[1], arg: mcp[2].replace(/_/g, " ") };
  return { icon: "wrench", label: name, arg: "" };
}
const firstLine = (s) => String(s || "").split("\n")[0];
function shortPath(p) {
  p = String(p || "");
  const parts = p.split(/[\\/]/).filter(Boolean);
  return parts.length > 3 ? "…/" + parts.slice(-3).join("/") : p;
}
function lineStat(a, b) {
  const n = (s) => (s ? String(s).split("\n").length : 0);
  return { a: n(b), d: n(a) };
}

/* ------------------------------------------------------------------ diff */
function lineDiff(a, b) {
  const A = String(a || "").split("\n"), B = String(b || "").split("\n");
  if (!a) return B.map((t) => ["add", t]);
  if (A.length * B.length > 250000) return A.map((t) => ["del", t]).concat(B.map((t) => ["add", t]));
  const dp = Array.from({ length: A.length + 1 }, () => new Int32Array(B.length + 1));
  for (let i = A.length - 1; i >= 0; i--) for (let j = B.length - 1; j >= 0; j--)
    dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  const out = [];
  let i = 0, j = 0;
  while (i < A.length && j < B.length) {
    if (A[i] === B[j]) { out.push(["ctx", A[i]]); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) out.push(["del", A[i++]]);
    else out.push(["add", B[j++]]);
  }
  while (i < A.length) out.push(["del", A[i++]]);
  while (j < B.length) out.push(["add", B[j++]]);
  return out;
}
function diffView(path, pairs) {
  const box = h("div.diff");
  if (path) box.append(h("div.file", { text: path }));
  // long unchanged stretches fold to three lines either side
  let ctx = [];
  const flushCtx = (end) => {
    if (ctx.length > 7) {
      ctx.slice(0, box.childElementCount > 1 ? 3 : 0).forEach((t) => box.append(h("div.ln.ctx", { text: t })));
      box.append(h("div.ln.ctx.muted", { text: "⋯" }));
      ctx.slice(end ? ctx.length : -3).forEach((t) => box.append(h("div.ln.ctx", { text: t })));
    } else ctx.forEach((t) => box.append(h("div.ln.ctx", { text: t })));
    ctx = [];
  };
  for (const [k, t] of pairs) {
    if (k === "ctx") { ctx.push(t); continue; }
    flushCtx(false);
    box.append(h("div.ln." + k, { text: t }));
  }
  flushCtx(true);
  return box;
}

/* ------------------------------------------------------------------ items */
function renderItem(sid, item) {
  switch (item.kind) {
    case "user": return userItem(item);
    case "assistant": return h("div.msg.assistant", { "data-id": item.id }, h("div.md", { html: renderMarkdown(item.text) }));
    case "thinking": return thinkingItem(item);
    case "tool": return toolItem(sid, item);
    case "system": return h("div.sys." + (item.level || "info"), { "data-id": item.id }, icon(item.level === "error" ? "alert" : "info", 16), h("span", { text: item.text }));
    case "compact": return h("div.divider", { "data-id": item.id, text: item.text || "之前的对话已压缩" });
  }
  return h("div", { "data-id": item.id });
}

function userItem(item) {
  const b = h("div.bubble");
  if (item.attachments && item.attachments.length) {
    b.append(h("div.atts", item.attachments.map((a) => h("div.att", a.thumb ? h("img", { src: a.thumb, alt: "" }) : icon("file", 16), h("span", { text: a.name })))));
  }
  b.append(item.text || "");
  return h("div.msg.user", { "data-id": item.id }, b);
}

function thinkingItem(item) {
  const step = h("div.step", { "data-id": item.id });
  const secs = item.ms ? " " + fmtSecs(item.ms) : "";
  step.append(h("div.step-head", { on: { click: () => step.classList.toggle("open") } },
    icon("chevRight"), icon("brain", 16), h("span.name", { text: item.done === false ? "思考中…" : "思考了" + secs })));
  step.append(h("div.step-body", h("div.thinking-body", { text: item.text || "" })));
  return step;
}

function toolItem(sid, item) {
  const L = toolLook(item);
  const st = item.status || "running";
  const step = h("div.step" + (st === "error" ? ".err" : st === "denied" ? ".denied" : ""), { "data-id": item.id });
  const meta = h("span.meta");
  if (L.where) meta.append(h("span.pill", { text: L.where }));
  if (L.stat && (L.stat.a || L.stat.d)) meta.append(h("span.stat-add", { text: "+" + L.stat.a }), h("span.stat-del", { text: "−" + L.stat.d }));
  if (st === "running") meta.append(icon("spinner", 14));
  else if (st === "error") meta.append(h("span", { text: "出错" }));
  else if (st === "denied") meta.append(h("span", { text: "已拒绝" }));
  const name = item.name || "";
  const head = h("div.step-head", { on: { click: () => step.classList.toggle("open") } },
    icon("chevRight"), icon(L.icon, 16), h("span.name", { text: L.label }), h("span.arg", { text: L.arg || "" }), meta);
  step.append(head);
  const body = h("div.step-body");
  step.append(body);
  const inp = item.input || {};
  const base = (PC_MAP[(/^mcp__pc__(.+)$/.exec(name) || [])[1]] || name);
  if (base === "TodoWrite") {
    body.append(todoList(inp.todos || []));
  } else if (base === "Edit") {
    body.append(diffView(inp.file_path, lineDiff(inp.old_string, inp.new_string)));
  } else if (base === "MultiEdit") {
    (inp.edits || []).forEach((e, k) => body.append(diffView(k ? "" : inp.file_path, lineDiff(e.old_string, e.new_string))));
  } else if (base === "Write") {
    const lines = String(inp.content || "").split("\n");
    body.append(diffView(inp.file_path, lines.slice(0, 80).map((t) => ["add", t]).concat(lines.length > 80 ? [["ctx", "⋯ 还有 " + (lines.length - 80) + " 行"]] : [])));
  } else if (base === "Bash" || base === "PowerShell" || base === "BgStart") {
    body.append(h("div.out.cmd", { text: inp.command || "" }));
  } else if (base === "Task" || base === "Agent") {
    if (inp.prompt) body.append(h("div.thinking-body", { text: inp.prompt }));
    body.append(h("div.sub", { "data-children": item.id }));
  } else if (base !== "Read" && base !== "ExitPlanMode" && Object.keys(inp).length) {
    body.append(h("div.out", { text: JSON.stringify(inp, null, 2) }));
  }
  if (base === "ExitPlanMode" && inp.plan) body.append(h("div.md", { html: renderMarkdown(inp.plan) }));
  if (item.output && base !== "TodoWrite" && base !== "Edit" && base !== "Write" && base !== "MultiEdit") {
    const out = String(item.output);
    body.append(h("div.out", { text: out.length > 20000 ? out.slice(0, 20000) + "\n⋯" : out, style: { marginTop: "6px" } }));
  } else if (item.output && st === "error") {
    body.append(h("div.out", { text: String(item.output), style: { marginTop: "6px", color: "var(--red)" } }));
  }
  (item.images || []).forEach((src) => body.append(h("img", { src, style: { maxWidth: "100%", borderRadius: "8px", marginTop: "6px" } })));
  return step;
}

function todoList(todos) {
  return h("ul.todo-list", todos.map((t) => h("li." + (t.status || "pending"), h("span.box", t.status === "completed" ? icon("check", 12) : null),
    h("span", { text: t.status === "in_progress" && t.activeForm ? t.activeForm : t.content }))));
}

/* ------------------------------------------------------------------ cards that wait for an answer */
function permissionCard(sid, p) {
  const card = h("div.card", { "data-perm": p.requestId });
  const inp = p.input || {};
  const answer = (decision, extra) => {
    bridge.post(Object.assign({ type: "answer", sessionId: sid, requestId: p.requestId, decision }, extra || {}));
    sesState(sid).permissions.delete(p.requestId);
    card.remove();
    refreshWorking(sid);
  };

  if (p.kind === "plan") {
    const fb = h("textarea.field", { rows: 2, placeholder: "要改哪里？（选「继续完善」时发给 Claude）" });
    add(card, [
      h("h4", icon("plan", 18), "Claude 拟好了计划，要按这个做吗？"),
      h("div.plan", h("div.md", { html: renderMarkdown(inp.plan || "") })),
      h("div", { style: { marginTop: "10px" } }, fb),
      h("div.actions",
        h("button.btn.accent", { text: "按计划做，自动接受编辑", on: { click: () => { answer("allow"); setMode(sid, "acceptEdits"); } } }),
        h("button.btn", { text: "按计划做，每步确认", on: { click: () => { answer("allow"); setMode(sid, "default"); } } }),
        h("button.btn.ghost", { text: "继续完善", on: { click: () => answer("deny", { message: fb.value.trim() || "先别动手，计划还要再改。" }) } })),
    ]);
    return card;
  }

  if (p.kind === "question") {
    const qs = inp.questions || [];
    const picks = qs.map(() => new Set());
    const others = qs.map(() => h("input.field", { placeholder: "其他（自己写）" }));
    add(card, [h("h4", icon("help", 18), "Claude 想问你")]);
    qs.forEach((q, k) => {
      const box = h("div.q", h("div.qt", { text: q.question }));
      (q.options || []).forEach((o) => {
        const opt = h("div.opt", { on: { click: () => {
          if (!q.multiSelect) { picks[k].clear(); $$(".opt", box).forEach((e) => e.classList.remove("on")); }
          if (picks[k].has(o.label)) { picks[k].delete(o.label); opt.classList.remove("on"); } else { picks[k].add(o.label); opt.classList.add("on"); }
        } } }, h("div", h("div", { text: o.label }), o.description ? h("div.d", { text: o.description }) : null));
        box.append(opt);
      });
      box.append(others[k]);
      card.append(box);
    });
    card.append(h("div.actions",
      h("button.btn.accent", { text: "回答", on: { click: () => {
        const answers = {};
        qs.forEach((q, k) => {
          const v = Array.from(picks[k]);
          if (others[k].value.trim()) v.push(others[k].value.trim());
          answers[q.question] = v.join(", ");
        });
        answer("allow", { updatedInput: Object.assign({}, inp, { answers }) });
      } } }),
      h("button.btn.ghost", { text: "不回答", on: { click: () => answer("deny", { message: "用户没有回答这些问题。" }) } })));
    return card;
  }

  // a tool that needs permission
  const L = toolLook({ name: p.tool, input: inp });
  const base = PC_MAP[(/^mcp__pc__(.+)$/.exec(p.tool) || [])[1]] || p.tool;
  let preview = null;
  if (base === "Bash" || base === "PowerShell" || base === "BgStart") preview = h("div.out.cmd", { text: inp.command || "" });
  else if (base === "Edit") preview = diffView(inp.file_path, lineDiff(inp.old_string, inp.new_string));
  else if (base === "Write") preview = diffView(inp.file_path, String(inp.content || "").split("\n").slice(0, 60).map((t) => ["add", t]));
  else if (base === "WebFetch") preview = h("div.out", { text: inp.url });
  else preview = h("div.out", { text: JSON.stringify(inp, null, 2) });
  const why = h("input.field", { placeholder: "拒绝时可以告诉 Claude 该怎么做" });
  add(card, [
    h("h4", icon("shield", 18), "允许 Claude " + L.label + (L.where ? "（" + L.where + "）" : "") + "？"),
    inp.description ? h("div.why", { text: inp.description }) : null,
    preview,
    h("div", { style: { marginTop: "10px" } }, why),
    h("div.actions",
      h("button.btn.accent", { on: { click: () => answer("allow") } }, "允许", h("span.kbd", { text: "Enter" })),
      (p.suggestions && p.suggestions.length) ? h("button.btn", { text: "总是允许", title: "本会话里同类操作不再询问", on: { click: () => answer("allowAlways") } }) : null,
      h("button.btn.ghost", { on: { click: () => answer("deny", { message: why.value.trim() }) } }, "拒绝", h("span.kbd", { text: "Esc" }))),
  ]);
  return card;
}

/* ------------------------------------------------------------------ thread view */
let streamTimer = null;
const pendingDeltas = new Set();

function renderThread(sid) {
  const thread = $("#thread");
  if (!thread) return;
  clear(thread);
  const st = sesState(sid);
  for (const id of st.order) placeItem(sid, st.items.get(id), true);
  for (const p of st.permissions.values()) thread.append(permissionCard(sid, p));
  refreshWorking(sid);
  scrollToEnd(true);
}

/** Puts an item's element in the thread (new at the end, or in place of its old element). */
function placeItem(sid, item, building) {
  const thread = $("#thread");
  if (!thread || S.current !== sid || !item) return;
  const el = renderItem(sid, item);
  const old = thread.querySelector('[data-id="' + cssId(item.id) + '"]');
  if (old) {
    if (old.classList.contains("open")) el.classList.add("open");
    const kids = old.querySelector("[data-children]");
    const newKids = el.querySelector("[data-children]");
    if (kids && newKids) newKids.replaceWith(kids);
    old.replaceWith(el);
    return;
  }
  const parent = item.parentId ? thread.querySelector('[data-children="' + cssId(item.parentId) + '"]') : null;
  if (parent) parent.append(el);
  else {
    const anchor = thread.querySelector(".card, .working");
    anchor ? thread.insertBefore(el, anchor) : thread.append(el);
  }
  if (!building) scrollToEnd();
}
const cssId = (s) => String(s).replace(/["\\]/g, "\\$&");

function onDelta(sid, itemId, text) {
  const st = sesState(sid);
  const item = st.items.get(itemId);
  if (!item) return;
  item.text = (item.text || "") + text;
  if (S.current !== sid) return;
  pendingDeltas.add(sid + "\u0000" + itemId);
  if (!streamTimer) streamTimer = requestAnimationFrame(() => {
    streamTimer = null;
    for (const k of pendingDeltas) {
      const [s, id] = k.split("\u0000");
      const it = sesState(s).items.get(id);
      const el = $('#thread [data-id="' + cssId(id) + '"]');
      if (!el || !it) continue;
      if (it.kind === "assistant") el.firstChild.innerHTML = renderMarkdown(it.text);
      else if (it.kind === "thinking") { const b = el.querySelector(".thinking-body"); if (b) b.textContent = it.text; }
    }
    pendingDeltas.clear();
    scrollToEnd();
  });
}

let workingTick = null;
function refreshWorking(sid) {
  const thread = $("#thread");
  if (!thread || S.current !== sid) return;
  const st = sesState(sid);
  let w = $(".working", thread);
  const show = st.status.running && st.permissions.size === 0;
  if (!show) { if (w) w.remove(); clearInterval(workingTick); workingTick = null; return; }
  if (!w) {
    w = h("div.working", h("span.star", icon("star", 16)), h("span.wt"), h("span.muted.wtime"), h("span.kbd", { text: "Esc 中断" }));
    thread.append(w);
  }
  const phase = { thinking: "思考中…", tool: "运行工具…", waiting: "等待中…" }[st.status.phase] || "处理中…";
  $(".wt", w).textContent = phase;
  const tick = () => { const t = $(".wtime", w); if (t && st.status.since) t.textContent = fmtSecs(Date.now() - st.status.since); };
  tick();
  if (!workingTick) workingTick = setInterval(tick, 1000);
  scrollToEnd();
}

let stickToEnd = true;
function scrollToEnd(force) {
  const sc = $("#scroll");
  if (!sc) return;
  if (force || stickToEnd) sc.scrollTop = sc.scrollHeight;
}

// copy buttons in code blocks, and links (opened in the browser by the app)
document.addEventListener("click", (e) => {
  const c = e.target.closest("[data-copy]");
  if (c) {
    const code = c.closest(".codeblock").querySelector("code").textContent;
    bridge.post({ type: "copy", text: code });
    if (!window.PiggyDesk && navigator.clipboard) navigator.clipboard.writeText(code).catch(() => {});
    c.lastChild.textContent = "已复制";
    setTimeout(() => { c.lastChild.textContent = "复制"; }, 1500);
    return;
  }
  const a = e.target.closest("a[data-ext]");
  if (a) { e.preventDefault(); bridge.post({ type: "openUrl", url: a.getAttribute("href") }); }
});
