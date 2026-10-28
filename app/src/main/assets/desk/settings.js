// Settings and 自定义 (Claude Code's memory, skills, agents, commands, MCP servers), as full pages over the main area.
"use strict";

const SETTINGS_TABS = [
  ["account", "账号", "user"], ["defaults", "默认设置", "sliders"], ["network", "网络", "wifi"], ["egress", "平板出口", "link"],
  ["pc", "电脑", "monitor"], ["browser", "浏览器", "globe"], ["appearance", "外观", "sun"], ["bubble", "小猪", "pig"], ["shortcuts", "快捷键", "keyboard"], ["about", "关于", "info"],
];

function pageShell(main, tabs, cur, onTab) {
  const nav = h("div.page-nav",
    h("button.btn.ghost.back", { on: { click: () => { S.view = "chat"; renderMain(); } } }, icon("chevLeft", 16), "返回"),
    tabs.map(([k, label, ic]) => h("button.ni" + (k === cur ? ".on" : ""), { on: { click: () => onTab(k) } }, icon(ic, 17), label)));
  const body = h("div.page-body");
  main.append(h("div.page", nav, body));
  return body;
}

const setRow = (title, desc, right) => h("div.set-row", h("div.l", h("div.t", { text: title }), desc ? h("div.d", { text: desc }) : null), h("div.r", right));
const toggleBtn = (on, click) => h("button.toggle" + (on ? ".on" : ""), { on: { click } });
function selectBox(value, options, onChange) {
  const s = h("select.field", { style: { width: "auto" }, on: { change: () => onChange(s.value) } },
    options.map(([v, l]) => h("option", { value: v, text: l, selected: v === value })));
  return s;
}
const pref = (key, value) => { S.prefs[key] = value; bridge.post({ type: "setPref", key, value }); applyPrefs(); };

function renderSettings(main) {
  const body = pageShell(main, SETTINGS_TABS, S.settingsTab, (k) => { S.settingsTab = k; renderMain(); });
  const t = S.settingsTab;
  const a = S.account || {}, st = S.setup || {}, n = S.net || {}, eg = S.egress || {}, pc = S.pc || {}, p = S.prefs || {};

  if (t === "account") {
    add(body, [h("h2", { text: "账号" }), h("p.lead", { text: "Claude 只在这台平板上登录；电脑上不登录、不连 Anthropic。" }),
      h("div.set-group",
        setRow(a.signedIn ? (a.email || "已登录") : "未登录", a.signedIn ? (a.plan ? "套餐：" + a.plan : "") : "登录后才能用 Claude",
          a.signedIn ? h("button.btn.danger", { text: "退出登录", on: { click: async () => { if (await confirm2("退出 Claude 登录？", "退出后要重新登录才能继续用。", "退出", true)) bridge.post({ type: "signOut" }); } } })
            : h("button.btn.accent", { text: a.signingIn ? "等待浏览器…" : "登录", disabled: !!a.signingIn, on: { click: () => bridge.post({ type: "signIn" }) } })),
        setRow("Claude Code", st.claude === "ready" ? "版本 " + (st.version || "?") : st.claude === "installing" ? (st.detail || "正在安装…") : st.claude === "error" ? (st.detail || "出错了") : "还没安装",
          st.claude === "ready" ? h("button.btn", { text: "检查更新", on: { click: () => bridge.post({ type: "updateClaude" }) } })
            : st.claude === "installing" ? icon("spinner") : h("div.row",
              h("button.btn.ghost", { text: "从文件", on: { click: () => bridge.post({ type: "installClaudeFile" }) } }),
              h("button.btn.accent", { text: st.claude === "error" ? "接着下" : "安装", on: { click: () => bridge.post({ type: "installClaude" }) } }))),
        setRow("额度", "5 小时和每周的用量", h("button.btn", { text: "查看", on: { click: usageDialog } })))]);
  }

  if (t === "defaults") {
    const models = S.models.map((m) => [m.value, m.label]);
    const efforts = (S.efforts && S.efforts.length ? S.efforts : Object.keys(EFFORT_LABEL)).map((e) => [e, EFFORT_LABEL[e] || e]);
    const modes = (S.modes && S.modes.length ? S.modes : Object.keys(MODE_LABEL)).map((m) => [m, MODE_LABEL[m] + " — " + MODE_DESC[m]]);
    add(body, [h("h2", { text: "默认设置" }), h("p.lead", { text: "新会话用这些；每个会话里还能单独改。" }),
      h("div.set-group",
        setRow("模型", null, selectBox(p.model, models, (v) => pref("model", v))),
        setRow("思考强度", "越高越慢、越费额度", selectBox(p.effort, efforts, (v) => pref("effort", v))),
        setRow("权限模式", null, selectBox(p.mode, modes, (v) => pref("mode", v))),
        setRow("输出风格", "Claude Code 的 output style：讲解会顺带解释为什么这么做，学习会留一些让你自己写", selectBox(p.outputStyle || "", [["", "默认"], ["Explanatory", "讲解"], ["Learning", "学习"]], (v) => pref("outputStyle", v)))),
      h("div.set-group",
        setRow("提示下一步", "每轮结束后猜你下一句想说什么，输入框为空时按 Tab 填入", toggleBtn(p.suggestions !== false, () => { pref("suggestions", p.suggestions === false); renderMain(); })),
        setRow("完成时通知", "Piggy Desk 不在前台时，Claude 做完或等你确认会发通知", toggleBtn(p.notify !== false, () => { pref("notify", p.notify === false); renderMain(); })),
        setRow("链接在右侧打开", "对话里的链接在右侧栏的浏览器里打开；关掉就用系统浏览器", toggleBtn(p.linksInPanel !== false, () => { pref("linksInPanel", p.linksInPanel === false); renderMain(); })))]);
  }

  if (t === "browser") {
    add(body, [h("h2", { text: "浏览器" }), h("p.lead", { text: "右侧栏的浏览器：你可以自己用，Claude 也能用它打开网页、读内容、点按和输入。它和你手机、平板上的其他浏览器分开，登录状态只留在这里。" }),
      h("div.set-group",
        setRow("让 Claude 使用浏览器", "Claude 的每一步照常按权限模式问你；密码、支付这类要你自己在页面里填", toggleBtn(p.browserTools !== false, () => { pref("browserTools", p.browserTools === false); renderMain(); })),
        setRow("搜索", "地址栏里输入的不是网址时用哪个搜索", selectBox(p.searchEngine || "bing", [["bing", "必应"], ["google", "Google"], ["baidu", "百度"], ["duckduckgo", "DuckDuckGo"]], (v) => pref("searchEngine", v))),
        setRow("清除浏览数据", "Cookie、登录状态、缓存", h("button.btn", { text: "清除", on: { click: async () => { if (await confirm2("清除浏览数据？", "右侧浏览器里登录过的网站都要重新登录。", "清除", true)) { bridge.post({ type: "browser", op: "clear" }); toast("已清除"); } } } })))]);
  }

  if (t === "network") {
    const allow = h("input.field", { value: (n.allow || []).join(", ") });
    add(body, [h("h2", { text: "网络" }), h("p.lead", { text: "Claude 的连接只在这些 Wi-Fi 上放行（手机热点），换到别的网络会立刻断开，换回来自动恢复。" }),
      h("div.set-group",
        setRow("当前 Wi-Fi", n.ssid ? null : "读不到名字：需要定位权限，并打开定位", n.ssid ? h("span.pill" + (n.allowed ? ".ok" : ".bad"), { text: n.ssid + (n.allowed ? " · 放行" : " · 不放行") })
          : h("button.btn", { text: "允许定位", on: { click: () => bridge.post({ type: "openNative", screen: "location" }) } })),
        setRow("允许的 Wi-Fi", "用逗号分隔，名字要完全一样", h("div.row", allow, h("button.btn", { text: "保存", on: { click: () => {
          const v = allow.value.split(/[,，]/).map((x) => x.trim()).filter(Boolean);
          if (!v.length) { toast("至少留一个"); return; }
          pref("allowSsid", v); toast("已保存");
        } } }))))]);
  }

  if (t === "egress") {
    const port = h("input.field", { value: eg.port || 8899, style: { width: "100px" } });
    const code = h("input.field.mono", { value: eg.code || "", style: { width: "220px" } });
    add(body, [h("h2", { text: "平板出口" }), h("p.lead", { text: "给电脑上小猪书记的「平板」线路用：电脑上的 Claude 流量经 Tailscale 到这台平板，再从手机热点出去。同样只在允许的 Wi-Fi 上放行。" }),
      h("div.set-group",
        setRow("打开出口", eg.on ? "在 " + (eg.meshIp || "Tailscale 地址") + ":" + (eg.port || 8899) + " 等电脑来连" : "关着", toggleBtn(eg.on, () => bridge.post({ type: "egressSet", on: !eg.on, port: +port.value || 8899, code: code.value.trim() }))),
        setRow("端口", null, port),
        setRow("配对码", "小猪书记里填的那个；电脑连过来要带上它", code),
        setRow("电脑最近一次连接", eg.lastPeer ? fmtAgo(eg.lastPeer) : "还没有", h("button.btn", { text: "保存", on: { click: () => { bridge.post({ type: "egressSet", on: !!eg.on, port: +port.value || 8899, code: code.value.trim() }); toast("已保存"); } } })))]);
  }

  if (t === "pc") {
    const host = h("input.field.mono", { value: pc.host || "", placeholder: "100.84.31.4" });
    const user = h("input.field.mono", { value: pc.user || "", placeholder: "bzy15" });
    const result = h("span.muted", { style: { fontSize: "13px" } });
    const status = pc.status === "ok" ? h("span.pill.ok", { text: "已连接" }) : pc.status === "connecting" ? h("span.pill.wait", { text: "连接中" })
      : pc.status === "error" ? h("span.pill.bad", { text: "连不上" }) : h("span.pill", { text: "没开" });
    add(body, [h("h2", { text: "电脑" }), h("p.lead", { text: "连上以后，项目可以放在电脑上：文件、命令、GPU 都用电脑的，Claude 本身还在平板上运行，电脑不连 Anthropic。走 Tailscale + SSH。" }),
      h("div.set-group",
        setRow("连接电脑", pc.detail || null, h("div.row", status, toggleBtn(pc.on, () => bridge.post({ type: "pcSet", on: !pc.on, host: host.value.trim(), user: user.value.trim() })))),
        setRow("地址", "电脑的 Tailscale 地址", host),
        setRow("用户名", "电脑上的 Windows 用户名", user),
        setRow("测试", null, h("div.row", result, h("button.btn", { text: "保存并测试", on: { click: async () => {
          bridge.post({ type: "pcSet", on: true, host: host.value.trim(), user: user.value.trim() });
          result.textContent = "测试中…";
          try { const r = await bridge.call("pcTest", {}, 60000); result.textContent = r.ok ? "通了 · " + (r.detail || "") : r.detail || "连不上"; } catch (e) { result.textContent = e.message; }
        } } })))),
      h("div.set-group", h("div.set-row", h("div.l",
        h("div.t", { text: "这台平板的公钥" }),
        h("div.d", { text: "在电脑上用管理员 PowerShell 运行一次（PiggyNet 文件夹里）：" }),
        h("div.keyline", { style: { margin: "8px 0" }, text: "powershell -ExecutionPolicy Bypass -File tools\\setup-remote-pc.ps1 -Msi tools\\openssh\\OpenSSH-Win64-v10.4.0.0.msi -PublicKey \"" + (pc.publicKey || "…") + "\"" }),
        h("div.keyline", { text: pc.publicKey || "（连接电脑后生成）" })),
        h("div.r", h("button.btn", { on: { click: () => { bridge.post({ type: "copy", text: pc.publicKey || "" }); toast("公钥已复制"); } } }, icon("copy", 15), "复制公钥"))))]);
  }

  if (t === "appearance") {
    const seg = (cur, opts, on) => h("div.seg", opts.map(([v, l]) => h("button" + (String(v) === String(cur) ? ".on" : ""), { text: l, on: { click: () => { on(v); renderMain(); } } })));
    add(body, [h("h2", { text: "外观" }),
      h("div.set-group",
        setRow("主题", null, seg(p.theme || "dark", [["system", "跟随系统"], ["light", "浅色"], ["dark", "深色"]], (v) => pref("theme", v))),
        setRow("字号", null, seg(p.fontSize || 15, [[14, "小"], [15, "中"], [16, "大"], [17, "特大"]], (v) => pref("fontSize", v))))]);
  }

  if (t === "bubble") {
    const open = (screen) => () => bridge.post({ type: "openNative", screen });
    add(body, [h("h2", { text: "小猪" }), h("p.lead", { text: "Piggy 的无障碍悬浮球：能看屏幕、点按、打字，帮你操作平板上的其他应用。" }),
      h("div.set-group",
        setRow("悬浮球和无障碍", "打开无障碍服务后，小猪会出现在屏幕边上", h("button.btn", { text: "打开", on: { click: open("bubble") } })),
        setRow("应用权限", "小猪能动哪些应用", h("button.btn", { text: "管理", on: { click: open("apps") } })),
        setRow("通知规则", "收到某些通知时自动处理", h("button.btn", { text: "管理", on: { click: open("rules") } })),
        setRow("定时任务", null, h("button.btn", { text: "管理", on: { click: open("schedules") } })))]);
  }

  if (t === "shortcuts") {
    const rows = [["Ctrl N", "新建会话"], ["Ctrl K", "搜索"], ["Ctrl B", "侧栏"], ["Ctrl Shift B", "右侧栏"], ["Ctrl ,", "设置"],
      ["Enter", "发送"], ["Shift Enter", "换行"], ["Shift Tab", "切换权限模式"], ["Esc", "中断 Claude / 关闭弹窗"], ["/", "斜杠命令"]];
    add(body, [h("h2", { text: "快捷键" }), h("div.set-group", rows.map(([k, v]) => setRow(v, null, h("span.kbd", { text: k }))))]);
  }

  if (t === "about") {
    add(body, [h("h2", { text: "关于" }),
      h("div.set-group",
        setRow("Piggy Desk", "版本 " + ((S.app && S.app.version) || "?"), h("button.btn", { text: "检查更新", on: { click: () => bridge.post({ type: "openNative", screen: "update" }) } })),
        setRow("源代码", "github.com/xjtubaizeyu-commits/piggy-desk", h("button.btn", { on: { click: () => bridge.post({ type: "openUrl", url: "https://github.com/xjtubaizeyu-commits/piggy-desk" }) } }, icon("external", 15), "打开")),
        setRow("基于", "Piggy（Buddy 的分支，Apache-2.0）", null))]);
  }
}

/* ------------------------------------------------------------------ 自定义 */
const CUSTOM_TABS = [["memory", "记忆", "brain"], ["skills", "技能", "sliders"], ["agents", "子代理", "bot"], ["commands", "命令", "terminal"], ["mcp", "MCP 服务器", "link"]];
let customTab = "memory";
let customData = null;

function renderCustomize(main) {
  const body = pageShell(main, CUSTOM_TABS, customTab, (k) => { customTab = k; renderMain(); });
  if (customTab === "memory") return renderMemory(body);
  if (!customData) {
    body.append(h("div.working", icon("spinner", 16), h("span", { text: "读取中…" })));
    bridge.call("customGet", {}, 30000).then((d) => { customData = d || {}; if (S.view === "customize") renderMain(); })
      .catch((e) => { clear(body).append(h("div.sys.error", { text: e.message })); });
    return;
  }
  const d = customData;
  if (customTab === "memory") return renderMemory(body);
  const lists = {
    skills: ["技能", "~/.claude/skills/ 里的技能，Claude 需要时会自己用。", d.skills],
    agents: ["子代理", "~/.claude/agents/ 里的子代理。", d.agents],
    commands: ["命令", "~/.claude/commands/ 里的斜杠命令。", d.commands],
    mcp: ["MCP 服务器", "Claude Code 配置里的 MCP 服务器（电脑和小猪的工具由 Piggy Desk 自己接上，不在这里）。", d.mcpServers],
  }[customTab];
  add(body, [h("h2", { text: lists[0] }), h("p.lead", { text: lists[1] }),
    h("div.set-group", (lists[2] || []).length ? lists[2].map((x) => setRow(x.name, x.description || x.path || x.type || "", x.status ? h("span.pill" + (x.status === "connected" ? ".ok" : ""), { text: x.status }) : null))
      : h("div.set-row", h("div.l.muted", { text: "还没有。可以让 Claude 帮你建一个。" })))]);
}

/* ------------------------------------------------------------------ 记忆 */
// Claude Code reads its instructions in levels, broad to specific (organisation policy, you on this tablet, the
// project, the project's local file), and keeps an auto-memory of its own: small typed notes with an index, MEMORY.md.
let memoryProject = null;
let memoryData = null;
const MEM_TYPE = { user: "用户", feedback: "反馈", project: "项目", reference: "参考" };

function renderMemory(body) {
  const pid = memoryProject && projectById(memoryProject) ? memoryProject : (S.projects[0] || {}).id;
  memoryProject = pid;
  const p = projectById(pid) || {};
  add(body, [h("h2", { text: "记忆" }),
    h("p.lead", { text: "Claude Code 每次开始都会读这些说明。从上到下越来越具体，说法冲突时以更具体的为准。自动记忆是 Claude 自己记下的笔记。" }),
    h("div.row", { style: { maxWidth: "900px", marginBottom: "4px" } }, h("span.muted", { style: { fontSize: "13.5px" }, text: "项目" }),
      selectBox(pid, S.projects.map((x) => [x.id, x.name + (x.env === "pc" ? "（电脑）" : "")]), (v) => { memoryProject = v; memoryData = null; renderMain(); }))]);
  if (!memoryData || memoryData.projectId !== pid) {
    body.append(h("div.working", icon("spinner", 16), h("span", { text: "读取中…" })));
    bridge.call("memoryGet", { projectId: pid }, 30000).then((d) => { memoryData = Object.assign({ projectId: pid }, d); if (S.view === "customize" && customTab === "memory") renderMain(); })
      .catch((e) => { clear(body).append(h("div.sys.error", { text: e.message })); });
    return;
  }
  const d = memoryData;
  body.append(h("div.sec-title", h("span.grow", { text: "说明文件（CLAUDE.md）" })));
  body.append(h("div.set-group", (d.levels || []).map((l) => h("div.set-row.mem-level",
    h("div.l", h("div.t", h("span", { text: l.label }), l.where === "pc" ? h("span.tag-pill", { text: "电脑" }) : null,
      !l.exists ? h("span.tag-pill", { text: "还没有" }) : null, l.readOnly ? h("span.tag-pill", { text: "只读" }) : null),
      h("div.d", { text: l.desc || "" }), h("div.path", { text: l.path })),
    h("div.r", l.readOnly && !l.exists ? null : h("button.btn", { text: l.readOnly ? "查看" : l.exists ? "编辑" : "新建", on: { click: () => editMemoryFile(l) } }))))));

  const a = d.auto || {};
  body.append(h("div.sec-title", h("span.grow", { text: "自动记忆" + (a.memories ? " · " + a.memories.length + " 条" : "") })));
  body.append(h("div.set-group",
    setRow("自动记忆", "Claude 自己记下有用的事（你的偏好、纠正过的做法、项目背景），之后的会话会读到。按项目分开存。",
      toggleBtn(a.enabled !== false, () => setMemory({ autoMemoryEnabled: a.enabled === false }))),
    setRow("自动整理", "空闲时在后台合并重复、删掉过时的记忆（auto-dream）", toggleBtn(!!a.dream, () => setMemory({ autoDreamEnabled: !a.dream }))),
    setRow("存放位置", null, h("span.mono.muted", { style: { fontSize: "12px", wordBreak: "break-all" }, text: a.dir || "" }))));
  const cards = [];
  if (a.index) cards.push(h("div.mem-card", { on: { click: () => editMemoryFile({ label: "MEMORY.md（索引）", where: "tablet", path: a.index, exists: true }) } },
    h("div.t", icon("list", 16), h("span.n", { text: "MEMORY.md" }), h("span.tag-pill", { text: "索引" })),
    h("div.d", { text: "每次开始时读的目录：一条记忆一行。" })));
  for (const m of a.memories || []) {
    cards.push(h("div.mem-card", { title: m.path, on: { click: () => editMemoryFile({ label: m.name, where: "tablet", path: m.path, exists: true, deletable: true }) } },
      h("div.t", h("span.n", { text: (m.title || m.name).replace(/\.md$/, "") }), m.type ? h("span.tag-pill." + m.type, { text: MEM_TYPE[m.type] || m.type }) : null),
      m.description ? h("div.d", { text: m.description }) : null,
      h("div.f", icon("clock", 13), h("span", { text: fmtAgo(m.modified) }), h("span.grow"), h("span", { text: fmtSize(m.size) }))));
  }
  if (cards.length) body.append(h("div.mem-grid", cards));
  else body.append(h("div.empty-note", { style: { marginBottom: "18px" }, text: a.enabled === false ? "自动记忆关着。" : "还没有记忆。Claude 在对话里学到值得记的事会自己写下来，也可以直接对它说「记住……」。" }));
}

async function setMemory(change) {
  try { await bridge.call("memorySet", Object.assign({ projectId: memoryProject }, change)); } catch (e) { toast(e.message); }
  memoryData = null;
  renderMain();
}

async function editMemoryFile(f) {
  let text = "";
  if (f.exists !== false) {
    try { text = (await bridge.call("memoryRead", { projectId: memoryProject, where: f.where, path: f.path }, 60000)).text || ""; } catch (e) { toast(e.message); return; }
  }
  dialog((box, close) => {
    const ta = h("textarea.field.editor", { spellcheck: false, readOnly: !!f.readOnly });
    ta.value = text || (f.exists === false ? f.template || "" : "");
    const save = async () => {
      try {
        await bridge.call("memorySave", { projectId: memoryProject, where: f.where, path: f.path, text: ta.value }, 60000);
        close(); toast("已保存"); memoryData = null; refreshMemoryViews();
      } catch (e) { toast(e.message); }
    };
    ta.addEventListener("keydown", (e) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") { e.preventDefault(); if (!f.readOnly) save(); } });
    add(box, [h("h3", { text: f.label }), h("div.mem-level", { style: { marginBottom: "10px" } }, h("div.path", { text: f.path })), ta,
      h("div.actions",
        f.deletable ? h("button.btn.danger", { text: "删除这条", on: { click: async () => {
          if (!(await confirm2("删除这条记忆？", "Claude 以后就不记得这件事了。MEMORY.md 里指向它的那一行也会去掉。", "删除", true))) return;
          try { await bridge.call("memoryDelete", { projectId: memoryProject, path: f.path }); toast("已删除"); } catch (e) { toast(e.message); }
          memoryData = null; refreshMemoryViews();
        } } }) : null,
        h("span.grow"),
        h("button.btn.ghost", { text: f.readOnly ? "关闭" : "取消", on: { click: close } }),
        f.readOnly ? null : h("button.btn.accent", { on: { click: save } }, "保存", h("span.kbd", { text: "Ctrl S" })))]);
    setTimeout(() => ta.focus(), 30);
  }, ".dialog.wide");
}

function refreshMemoryViews() {
  for (const id in S.ses) { S.ses[id].memory = null; S.ses[id].memoryAt = 0; }
  if (S.view === "customize") renderMain(); else renderPanel();
}
