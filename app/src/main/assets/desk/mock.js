// Stand-in for the app when the page runs in a desktop browser (no PiggyDesk bridge): made-up data, so the layout can
// be built and checked on a PC. URL switches: ?setup=1 (first run), ?net=0 (wrong Wi-Fi), ?theme=light, ?empty=1.
"use strict";
(function () {
  if (window.PiggyDesk) return;
  const q = new URLSearchParams(location.search);
  const now = Date.now();
  const send = (m) => setTimeout(() => window.desk && window.desk.recv(JSON.parse(JSON.stringify(m))), 0);
  const later = (ms, f) => setTimeout(f, ms);

  const D = {
    app: { version: "0.1.0", id: "app.piggy.desk" },
    setup: q.get("setup") ? { linux: true, claude: "missing", version: "", detail: "" } : { linux: true, claude: "ready", version: "2.3.4", detail: "" },
    account: q.get("setup") ? { signedIn: false } : { signedIn: true, email: "pig@example.com", name: "PIG", plan: "Pro" },
    net: q.get("net") === "0" ? { ssid: "Campus-WiFi", allowed: false, allow: ["MIX 4"], reason: "" } : { ssid: "MIX 4", allowed: true, allow: ["MIX 4"], reason: "" },
    egress: { on: true, port: 8899, code: "abcd-1234-ef56-7890", meshIp: "100.123.6.79", lastPeer: now - 40000 },
    pc: { on: true, host: "100.84.31.4", user: "bzy15", status: "ok", detail: "piggy-pc · RTX 5070", publicKey: "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMockMockMockMockMockMockMockMockMock piggy-desk" },
    prefs: { model: "opus", effort: "xhigh", mode: "auto", theme: q.get("theme") || "dark", fontSize: 15, allowSsid: ["MIX 4"] },
    models: [{ value: "opus", label: "Opus 5.5", description: "最强，适合难活" }, { value: "sonnet", label: "Sonnet 5.5", description: "快，日常够用" },
      { value: "haiku", label: "Haiku 5.5", description: "最快最省" }],
    efforts: ["low", "medium", "high", "xhigh", "max"],
    modes: ["default", "acceptEdits", "plan", "auto", "bypassPermissions"],
    commands: [{ name: "review", description: "审查当前改动" }, { name: "init", description: "生成 CLAUDE.md" }, { name: "pr-comments", description: "看 PR 评论" }],
    usage: { fiveHour: { pct: 42, resetsAt: now + 2.3 * 3600e3, status: "allowed" }, weekly: { pct: 18, resetsAt: now + 4 * 86400e3, status: "allowed" } },
    projects: [
      { id: "p1", name: "piggy-desk", env: "tablet", path: "/root/projects/piggy-desk" },
      { id: "p2", name: "cell", env: "pc", path: "C:\\Users\\bzy15\\Desktop\\cell" },
      { id: "p3", name: "jing", env: "pc", path: "C:\\Users\\bzy15\\Desktop\\jing" },
    ],
    sessions: q.get("empty") ? [] : [
      { id: "s1", projectId: "p1", title: "Android Claude Desktop 远控电脑方案", updatedAt: now - 60e3, mode: "auto", model: "opus", effort: "xhigh" },
      { id: "s2", projectId: "p1", title: "Buddy 手机端 Agent 助手迁移", updatedAt: now - 3 * 3600e3 },
      { id: "s3", projectId: "p2", title: "训练脚本显存优化", updatedAt: now - 26 * 3600e3, unread: true },
      { id: "s4", projectId: "p3", title: "数据清洗和去重", updatedAt: now - 5 * 86400e3 },
    ],
  };

  let n = 100;
  const nid = () => "m" + ++n;
  const T = {
    s1: [
      { id: "u1", kind: "user", text: "帮我看看 desk 界面的布局，按 Claude Desktop 的样子调一下右侧栏，再跑一下测试。" },
      { id: "t1", kind: "thinking", text: "先看看现在的布局文件，右侧栏是 desk.css 里的 #panel。然后找测试脚本。", ms: 6200 },
      { id: "a1", kind: "assistant", text: "好的，我先看一下现在的结构。" },
      { id: "x1", kind: "tool", name: "Bash", input: { command: "git status --short && ls app/src/main/assets/desk", description: "看看改动和文件" }, status: "done",
        output: " M app/src/main/assets/desk/desk.css\n?? app/src/main/assets/desk/mock.js\napp.js\ncore.js\ndesk.css\nindex.html\nmd.js\nmock.js\nsettings.js\nthread.js" },
      { id: "x2", kind: "tool", name: "Read", input: { file_path: "/root/projects/piggy-desk/app/src/main/assets/desk/desk.css" }, status: "done", output: "     1\t/* Piggy Desk */\n     2\t:root {\n     3\t  --bg: #262624;" },
      { id: "x3", kind: "tool", name: "Edit", input: { file_path: "/root/projects/piggy-desk/app/src/main/assets/desk/desk.css",
        old_string: "#panel { background: var(--bg); padding: 10px; }\n.pcard { border-radius: 10px; }",
        new_string: "#panel { background: var(--bg); border-left: 1px solid var(--line); padding: 12px; }\n.pcard { border-radius: 14px; }\n.pcard-head { font-size: 14px; }" }, status: "done", output: "已更新" },
      { id: "x4", kind: "tool", name: "TodoWrite", input: { todos: [
        { content: "调整右侧栏样式", status: "completed" }, { content: "跑测试", status: "in_progress", activeForm: "正在跑测试" }, { content: "截图对照", status: "pending" }] }, status: "done" },
      { id: "x5", kind: "tool", name: "Task", input: { description: "检查样式里的对比度", prompt: "看一遍 desk.css 里深色和浅色两套颜色，找出对比度不够的地方。", subagent_type: "general-purpose" }, status: "done", output: "两处次要文字在浅色主题下偏淡，建议 --muted 改成 #6b6a64。" },
      { id: "x6", kind: "tool", name: "Grep", parentId: "x5", input: { pattern: "--muted", path: "app/src/main/assets/desk" }, status: "done", output: "desk.css:29:  --muted: #9c9a92;\ndesk.css:62:  --muted: #73726c;" },
      { id: "x7", kind: "tool", name: "mcp__pc__bash", input: { command: "nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv", description: "看看电脑的显卡" }, status: "done", output: "name, memory.used [MiB], memory.total [MiB]\nNVIDIA GeForce RTX 5070, 1024 MiB, 12227 MiB" },
      { id: "a2", kind: "assistant", text: "右侧栏改好了，主要变化：\n\n## 改了什么\n\n- 卡片圆角从 `10px` 改成 **14px**，和 Desktop 一致\n- 加了左边框，和对话区分开\n- 标题字号统一成 14px\n\n| 区域 | 之前 | 现在 |\n| --- | --- | --- |\n| 卡片圆角 | 10px | 14px |\n| 内边距 | 10px | 12px |\n\n子代理发现浅色主题下有两处次要文字偏淡，可以这样改：\n\n```css\n:root[data-theme=\"light\"] {\n  --muted: #6b6a64; /* 原来 #73726c */\n}\n```\n\n顺便看了一下电脑：RTX 5070 现在空闲（显存用了 1 GB）。接下来跑测试。" },
    ],
    s3: [
      { id: "u1", kind: "user", text: "train.py 跑到第 3 个 epoch 就 OOM，帮我看看。" },
      { id: "x1", kind: "tool", name: "mcp__pc__read", input: { file_path: "C:\\Users\\bzy15\\Desktop\\cell\\train.py" }, status: "done", output: "     1\timport torch\n     2\tfrom model import Net" },
      { id: "a1", kind: "assistant", text: "问题在第 88 行：每个 step 都把 `loss` 张量 append 到列表里，计算图一直留着。改成 `loss.item()` 就好。" },
    ],
  };
  const PERM = { s1: { requestId: "q1", tool: "Bash", input: { command: "npm test -- --watch=false", description: "跑一遍界面测试" }, kind: "tool", suggestions: [{ type: "addRules" }] } };
  const CTX = { s1: { used: 605600, window: 1000000, autoCompactAt: 965600, breakdown: [
    { label: "消息", tokens: 548600 }, { label: "系统工具", tokens: 24100 }, { label: "MCP 工具", tokens: 14100 }, { label: "系统提示", tokens: 9700 }, { label: "技能", tokens: 6000 }, { label: "记忆文件", tokens: 2200 }] } };

  function state() { send(Object.assign({ type: "state" }, D)); }
  function open(sid) {
    const items = T[sid] || [];
    later(120, () => {
      send({ type: "transcript", sessionId: sid, items });
      const s = D.sessions.find((x) => x.id === sid) || {};
      const p = D.projects.find((x) => x.id === s.projectId) || {};
      send({ type: "env", sessionId: sid, env: p.env, cwd: p.path, branch: p.env === "pc" ? "" : "main", remote: false });
      send({ type: "context", sessionId: sid, ...(CTX[sid] || { used: 18000 + items.length * 2000, window: 1000000, autoCompactAt: 965600, breakdown: [] }) });
      if (sid === "s1") {
        send({ type: "todos", sessionId: sid, todos: T.s1[6].input.todos });
        send({ type: "outputs", sessionId: sid, files: [{ path: "app/src/main/assets/desk/desk.css", action: "edit", at: now }, { path: "app/src/main/assets/desk/mock.js", action: "write", at: now }] });
        if (PERM.s1) send(Object.assign({ type: "permission", sessionId: sid }, PERM.s1));
        send({ type: "status", sessionId: sid, running: !!PERM.s1, phase: "waiting", since: now - 95000 });
      }
    });
  }

  function reply(m, data, error) { send(error ? { type: "reply", id: m.id, ok: false, error } : { type: "reply", id: m.id, ok: true, data }); }

  function fakeTurn(sid, text) {
    const st = { since: Date.now() };
    send({ type: "item", sessionId: sid, item: { id: nid(), kind: "user", text } });
    send({ type: "status", sessionId: sid, running: true, phase: "thinking", since: st.since });
    const think = nid();
    later(500, () => send({ type: "item", sessionId: sid, item: { id: think, kind: "thinking", text: "", done: false } }));
    later(700, () => send({ type: "delta", sessionId: sid, itemId: think, text: "用户要的是……先看看文件，再动手。" }));
    later(1400, () => send({ type: "item", sessionId: sid, item: { id: think, kind: "thinking", text: "用户要的是……先看看文件，再动手。", ms: 900 } }));
    const tool = nid();
    later(1600, () => { send({ type: "status", sessionId: sid, running: true, phase: "tool", since: st.since }); send({ type: "item", sessionId: sid, item: { id: tool, kind: "tool", name: "Glob", input: { pattern: "**/*.css" }, status: "running" } }); });
    later(2300, () => send({ type: "item", sessionId: sid, item: { id: tool, kind: "tool", name: "Glob", input: { pattern: "**/*.css" }, status: "done", output: "app/src/main/assets/desk/desk.css" } }));
    const a = nid();
    const words = ("收到：「" + text + "」。这是模拟回复，用来看界面效果。真正的回复会一段一段流出来，**Markdown** 和 `代码` 都会渲染：\n\n1. 第一步\n2. 第二步\n\n```python\nfor epoch in range(10):\n    loss = train_one(epoch)\n    print(f\"epoch {epoch}: {loss:.3f}\")\n```").match(/[\s\S]{1,6}/g);
    later(2500, () => { send({ type: "status", sessionId: sid, running: true, phase: "thinking", since: st.since }); send({ type: "item", sessionId: sid, item: { id: a, kind: "assistant", text: "" } }); });
    words.forEach((w, k) => later(2600 + k * 40, () => send({ type: "delta", sessionId: sid, itemId: a, text: w })));
    later(2700 + words.length * 40, () => {
      send({ type: "status", sessionId: sid, running: false, phase: "idle" });
      send({ type: "context", sessionId: sid, used: 32000, window: 1000000, autoCompactAt: 965600, breakdown: [{ label: "消息", tokens: 8000 }, { label: "系统工具", tokens: 14000 }, { label: "系统提示", tokens: 9700 }] });
      const s = D.sessions.find((x) => x.id === sid);
      if (s) { s.updatedAt = Date.now(); send({ type: "sessions", value: D.sessions }); }
    });
  }

  window.PiggyDesk = {
    post(json) {
      const m = JSON.parse(json);
      switch (m.type) {
        case "ready": state(); break;
        case "openSession": open(m.sessionId); break;
        case "newSession": {
          const id = "s" + ++n;
          D.sessions.unshift({ id, projectId: m.projectId, title: "新会话", updatedAt: Date.now(), mode: D.prefs.mode, model: D.prefs.model, effort: D.prefs.effort });
          send({ type: "sessions", value: D.sessions });
          reply(m, { sessionId: id });
          break;
        }
        case "send": {
          const s = D.sessions.find((x) => x.id === m.sessionId);
          if (s && s.title === "新会话") { s.title = m.text.slice(0, 24); send({ type: "sessions", value: D.sessions }); }
          fakeTurn(m.sessionId, m.text);
          break;
        }
        case "interrupt":
          send({ type: "status", sessionId: m.sessionId, running: false, phase: "idle" });
          send({ type: "item", sessionId: m.sessionId, item: { id: nid(), kind: "system", text: "已中断", level: "warn" } });
          break;
        case "answer":
          delete PERM[m.sessionId];
          send({ type: "permissionDone", sessionId: m.sessionId, requestId: m.requestId });
          if (m.decision === "deny") { send({ type: "status", sessionId: m.sessionId, running: false, phase: "idle" }); break; }
          send({ type: "status", sessionId: m.sessionId, running: true, phase: "tool", since: Date.now() });
          send({ type: "item", sessionId: m.sessionId, item: { id: "x9", kind: "tool", name: "Bash", input: { command: "npm test -- --watch=false" }, status: "running" } });
          later(1500, () => {
            send({ type: "item", sessionId: m.sessionId, item: { id: "x9", kind: "tool", name: "Bash", input: { command: "npm test -- --watch=false" }, status: "done", output: "PASS  desk/md.test.js\nPASS  desk/thread.test.js\n\nTests: 24 passed, 24 total" } });
            send({ type: "item", sessionId: m.sessionId, item: { id: nid(), kind: "assistant", text: "24 个测试全过了。" } });
            send({ type: "status", sessionId: m.sessionId, running: false, phase: "idle" });
          });
          break;
        case "setPref": D.prefs[m.key] = m.value; if (m.key === "allowSsid") D.net.allow = m.value; send({ type: "prefs", value: D.prefs }); break;
        case "setMode": case "setModel": {
          const s = D.sessions.find((x) => x.id === m.sessionId);
          const tgt = s || D.prefs;
          if (m.mode) tgt.mode = m.mode;
          if (m.model) tgt.model = m.model;
          if (m.effort) tgt.effort = m.effort;
          s ? send({ type: "sessions", value: D.sessions }) : send({ type: "prefs", value: D.prefs });
          break;
        }
        case "renameSession": case "archiveSession": {
          const s = D.sessions.find((x) => x.id === m.sessionId);
          if (s && m.title) s.title = m.title;
          if (s && m.type === "archiveSession") s.archived = m.archived;
          send({ type: "sessions", value: D.sessions });
          break;
        }
        case "deleteSession": D.sessions = D.sessions.filter((x) => x.id !== m.sessionId); send({ type: "sessions", value: D.sessions }); break;
        case "newProject": {
          const id = "p" + ++n;
          D.projects.push({ id, name: m.name, env: m.env, path: m.path || "/root/projects/" + m.name });
          send({ type: "projects", value: D.projects });
          reply(m, { projectId: id });
          break;
        }
        case "editProject": { const p = D.projects.find((x) => x.id === m.projectId); if (p) Object.assign(p, { name: m.name || p.name }); send({ type: "projects", value: D.projects }); break; }
        case "removeProject": D.projects = D.projects.filter((x) => x.id !== m.projectId); send({ type: "projects", value: D.projects }); break;
        case "pickAttachment": {
          const c = document.createElement("canvas");
          c.width = c.height = 64;
          const g = c.getContext("2d");
          g.fillStyle = "#d97757"; g.fillRect(0, 0, 64, 64); g.fillStyle = "#fff"; g.font = "28px sans-serif"; g.fillText("🐷", 14, 44);
          reply(m, { attachments: [{ id: "att" + ++n, name: m.kind === "file" ? "notes.txt" : "photo.png", kind: m.kind === "file" ? "file" : "image", thumb: m.kind === "file" ? "" : c.toDataURL() }] });
          break;
        }
        case "dictate":
          if (m.on) {
            const parts = ["帮我", "帮我把右侧栏", "帮我把右侧栏的卡片间距调大一点"];
            parts.forEach((t, k) => later(400 * (k + 1), () => send({ type: "dictation", text: t, final: k === parts.length - 1, done: k === parts.length - 1 })));
          }
          break;
        case "search": reply(m, { hits: D.sessions.filter((s) => (T[s.id] || []).some((it) => (it.text || "").includes(m.query))).map((s) => ({ sessionId: s.id, title: s.title, snippet: "…" + m.query + "…" })) }); break;
        case "listFiles": reply(m, { entries: m.path ? [{ name: "desk.css", dir: false, size: 18234 }, { name: "app.js", dir: false, size: 30122 }]
          : [{ name: "app", dir: true }, { name: "docs", dir: true }, { name: "README.md", dir: false, size: 1201 }, { name: "build.gradle.kts", dir: false, size: 512 }] }); break;
        case "readFile": reply(m, { text: "# Piggy Desk\n\n平板上的 Claude 工作台。\n" }); break;
        case "gitDiff": reply(m, { branch: "main", diff: "diff --git a/desk.css b/desk.css\nindex 1..2 100644\n--- a/desk.css\n+++ b/desk.css\n@@ -1,4 +1,4 @@\n :root {\n-  --muted: #73726c;\n+  --muted: #6b6a64;\n   --bg: #262624;\n }" }); break;
        case "pcTest": later(700, () => reply(m, { ok: true, detail: "piggy-pc · 往返 820 ms" })); break;
        case "customGet": reply(m, { claudeMd: "# 全局说明\n\n- 回复用中文\n", skills: [{ name: "android-app", description: "在平板上编译安卓应用" }], agents: [], commands: [{ name: "review", description: "审查当前改动" }], mcpServers: [{ name: "pc", type: "stdio", status: "connected" }, { name: "phone", type: "http", status: "connected" }] }); break;
        case "customSave": reply(m, {}); break;
        case "pcSet": Object.assign(D.pc, { on: m.on, host: m.host || D.pc.host, user: m.user || D.pc.user }); send({ type: "pc", value: D.pc }); break;
        case "egressSet": Object.assign(D.egress, { on: m.on, port: m.port, code: m.code }); send({ type: "egress", value: D.egress }); break;
        case "remoteControl": send({ type: "env", sessionId: m.sessionId, env: "tablet", cwd: "/root/projects/piggy-desk", branch: "main", remote: m.on }); send({ type: "toast", text: m.on ? "已交给 Remote Control，可以在手机 Claude App 里接着用" : "已收回" }); break;
        case "signIn": D.account = { signingIn: true }; send({ type: "account", value: D.account }); later(1500, () => { D.account = { signedIn: true, email: "pig@example.com", name: "PIG", plan: "Pro" }; send({ type: "account", value: D.account }); }); break;
        case "installClaudeFile": case "installClaude": D.setup = { claude: "installing", detail: "下载 Claude Code 2.1.311：38.2 / 96.4 MB · 410 KB/s · 还要约 3 分钟" }; send({ type: "setup", value: D.setup }); later(1500, () => { D.setup = { claude: "ready", version: "2.3.4" }; send({ type: "setup", value: D.setup }); }); break;
        case "copy": if (navigator.clipboard) navigator.clipboard.writeText(m.text).catch(() => {}); break;
        case "openUrl": window.open(m.url, "_blank"); break;
        case "openNative": send({ type: "toast", text: "（应用里会打开：" + m.screen + "）" }); break;
        case "toast": send({ type: "toast", text: m.text }); break;
        default: if (m.id) reply(m, {});
      }
    },
  };
})();
