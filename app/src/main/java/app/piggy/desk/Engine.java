package app.piggy.desk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claude Code for the workspace: one long-running `claude -p --input-format stream-json --output-format stream-json`
 * per open session, inside the app's Linux, driven with Claude Code's SDK control protocol (initialize, interrupt,
 * set_permission_mode, set_model, can_use_tool, get_context_usage, get_usage, remote_control). Its output is turned into
 * the page's items (docs/desk-bridge.md). A session that sits idle is stopped and resumed (--resume) on its next message.
 */
final class Engine {
    static final String CLAUDE = "/root/.local/bin/claude";
    private static final long IDLE_MS = 15 * 60 * 1000;
    private static final int MAX_LIVE = 3;
    /** The PC's versions of the built-in tools take over these when a project lives on the PC. */
    private static final String PC_DISALLOWED = "Bash,Write,Edit,MultiEdit,NotebookEdit,Glob,Grep";

    private static final Map<String, Run> runs = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Engine() {
    }

    static synchronized Run run(String sid) {
        Run r = runs.get(sid);
        return r != null && r.alive ? r : null;
    }

    private static synchronized Run runFor(Context c, String sid) {
        Run r = runs.get(sid);
        if (r != null && r.alive) return r;
        // room for one more: stop the session idle the longest
        List<Run> live = new ArrayList<>();
        for (Run x : runs.values()) if (x.alive) live.add(x);
        while (live.size() >= MAX_LIVE) {
            Run oldest = null;
            for (Run x : live) if (!x.busy && (oldest == null || x.lastUsed < oldest.lastUsed)) oldest = x;
            if (oldest == null) break;
            oldest.stop();
            live.remove(oldest);
        }
        r = new Run(c.getApplicationContext(), sid);
        runs.put(sid, r);
        return r;
    }

    static boolean busy(String sid) {
        Run r = run(sid);
        return r != null && r.busy;
    }

    // ------------------------------------------------------------------ what the page asks for

    /** Sends a user message (content blocks) to the session, starting Claude Code if it isn't running. */
    static void send(Context c, String sid, JSONArray content) {
        Run r = runFor(c, sid);
        r.exec.execute(() -> r.sendUser(content));
    }

    static void interrupt(String sid) {
        Run r = run(sid);
        if (r != null) r.exec.execute(r::interrupt);
    }

    static void answer(String sid, String requestId, String decision, String message, JSONObject updatedInput) {
        Run r = run(sid);
        if (r != null) r.exec.execute(() -> r.answer(requestId, decision, message, updatedInput));
    }

    static void setMode(String sid, String mode) {
        Run r = run(sid);
        // the command line says "manual" (ask first), the control protocol "default"
        String live = "manual".equals(mode) ? "default" : mode;
        if (r != null) r.exec.execute(() -> r.control(new Obj("subtype", "set_permission_mode", "mode", live).o, resp -> {
            if (resp == null) r.restartWhenIdle = true; // not switchable live: the next start uses it
        }));
    }

    static void setModel(String sid, String model, String effort) {
        Run r = run(sid);
        if (r == null) return;
        r.exec.execute(() -> {
            if (model != null && !model.equals(r.model)) {
                r.control(new Obj("subtype", "set_model", "model", model).o, resp -> {
                    if (resp != null) r.model = model;
                    else r.restartWhenIdle = true;
                });
            }
            if (effort != null && !effort.equals(r.effort)) r.restartWhenIdle = true; // set at start (--effort)
            r.reapIfDue();
        });
    }

    static void remoteControl(Context c, String sid, boolean on) {
        Run r = on ? runFor(c, sid) : run(sid);
        if (r == null) return;
        r.exec.execute(() -> {
            if (!r.ensureStarted()) return;
            JSONObject s = DeskStore.get(r.app).session(sid);
            r.control(new Obj("subtype", "remote_control", "enabled", on, "name", s == null ? "Piggy Desk" : s.optString("title")).o,
                    resp -> {
                        r.remote = on && resp != null;
                        r.keepAlive = r.remote;
                        Desk.get(r.app).sessionEvent(sid, r.envEvent());
                        if (resp == null) Desk.get(r.app).toast("Remote Control 没打开：" + r.lastControlError);
                        else if (on) {
                            String url = findUrl(resp);
                            Desk.get(r.app).toast(url.isEmpty() ? "已打开 Remote Control：在手机的 Claude App 里能接着用"
                                    : "已打开 Remote Control：" + url);
                        }
                    });
        });
    }

    /** Asks a running session for the plan's rate limits (the 额度 ring). */
    static void refreshUsage() {
        Run any = null;
        synchronized (Engine.class) {
            for (Run x : runs.values()) if (x.alive && x.initialized) any = x;
        }
        if (any == null) return;
        Run r = any;
        r.exec.execute(() -> r.control(new Obj("subtype", "get_usage", "skip_behaviors", true).o, resp -> {
            if (resp != null) Desk.get(r.app).onUsage(resp.optJSONObject("rate_limits"), false);
        }));
    }

    static void refreshContext(String sid) {
        Run r = run(sid);
        if (r != null) r.exec.execute(r::askContext);
    }

    static void refreshPlan(String sid) {
        Run r = run(sid);
        if (r != null && r.initialized) r.exec.execute(r::refreshPlan);
    }

    static void stopTask(String sid, String taskId) {
        Run r = run(sid);
        if (r != null) r.exec.execute(() -> r.control(new Obj("subtype", "stop_task", "task_id", taskId).o, null));
    }

    /** The end of a background shell's output (get_task_output), or null with the reason in lastControlError. */
    static void taskOutput(String sid, String taskId, Run.Callback cb) {
        Run r = run(sid);
        if (r == null) {
            cb.done(null);
            return;
        }
        r.exec.execute(() -> r.control(new Obj("subtype", "get_task_output", "task_id", taskId).o, cb));
    }

    /** /btw: a question on the side, answered from the conversation without disturbing the turn in progress. */
    static void sideQuestion(Context c, String sid, String question, Run.Callback cb) {
        Run r = runFor(c, sid);
        r.exec.execute(() -> {
            if (!r.ensureStarted()) {
                cb.done(null);
                return;
            }
            r.control(new Obj("subtype", "side_question", "question", question).o, cb);
        });
    }

    /** The permission cards still waiting in a session (shown again when the page opens it). */
    static List<JSONObject> pending(String sid) {
        Run r = run(sid);
        return r == null ? new ArrayList<JSONObject>() : r.pendingEvents();
    }

    static void stop(String sid) {
        Run r = run(sid);
        if (r != null) r.stop();
    }

    static synchronized void stopAll() {
        for (Run r : runs.values()) if (r.alive) r.stop();
    }

    // ------------------------------------------------------------------ one session's Claude Code

    static final class Run {
        final Context app;
        final String sid;
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        volatile boolean alive, busy, initialized, remote, keepAlive, restartWhenIdle;
        volatile long lastUsed = System.currentTimeMillis(), since;
        String model = "", effort = "", lastControlError = "";
        private Process proc;
        private OutputStream stdin;
        private int ctl;
        private final Map<String, Callback> waiting = new HashMap<>();
        private final Map<String, JSONObject> perms = new LinkedHashMap<>();
        private final Map<String, JSONObject> tools = new HashMap<>();
        private final Set<String> denied = new HashSet<>();
        private final Map<String, Integer> finalBlocks = new HashMap<>(), streamBlocks = new HashMap<>();
        private final Map<Integer, String> streamIds = new HashMap<>();
        private final Map<String, Long> thinkingStart = new HashMap<>();
        private String streamMsg = "";
        private boolean turnHadText, resumed, sawOutput, forking;
        /** Background tasks (shells and agents sent to the background), for the side panel. */
        private final Map<String, JSONObject> tasks = new LinkedHashMap<>();
        private JSONObject lastUser;
        private final StringBuilder errTail = new StringBuilder();

        Run(Context app, String sid) {
            this.app = app;
            this.sid = sid;
        }

        interface Callback {
            /** The control response's payload, or null if it failed (see lastControlError). */
            void done(JSONObject response);
        }

        private Desk desk() {
            return Desk.get(app);
        }

        // ---------------------------------------------------------------- starting

        /** Starts Claude Code if needed (on this run's thread). False, with the reason shown, if it can't. */
        boolean ensureStarted() {
            if (alive) return true;
            DeskStore st = DeskStore.get(app);
            JSONObject s = st.session(sid);
            JSONObject p = s == null ? null : st.project(s.optString("projectId"));
            if (s == null || p == null) {
                fail("这个会话的项目不见了");
                return false;
            }
            // the launcher is a symlink with a target inside Linux (/root/...): look at the link itself
            if (!java.nio.file.Files.exists(new File(Linux.root(app), CLAUDE.substring(1)).toPath(),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                fail("Claude Code 还没装好（设置 → 账号）");
                return false;
            }
            if (!NetGuard.allowed) {
                fail("Claude 已断开：" + NetGuard.reason);
                return false;
            }
            boolean onPc = DeskStore.PC.equals(p.optString("env"));
            String cwd = onPc ? DeskStore.PC_DIR + "/" + p.optString("id") : p.optString("path");
            new File(Linux.root(app), cwd.substring(1)).mkdirs();

            List<String> args = new ArrayList<>();
            String[] base = {CLAUDE, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                    "--include-partial-messages", "--permission-prompt-tool", "stdio", "--allow-dangerously-skip-permissions",
                    "--forward-subagent-text"};
            for (String a : base) args.add(a);
            JSONObject prefs = st.prefs();
            String mode = Desk.cliMode(s.optString("mode", prefs.optString("mode")));
            model = s.optString("model", prefs.optString("model"));
            effort = s.optString("effort", prefs.optString("effort"));
            args.add("--permission-mode");
            args.add(mode);
            if (!model.isEmpty()) {
                args.add("--model");
                args.add(model);
            }
            if (!effort.isEmpty()) {
                args.add("--effort");
                args.add(effort);
            }
            String csid = s.optString("claudeSessionId");
            String forkFrom = s.optString("forkFrom");
            resumed = !csid.isEmpty() && s.optBoolean("started");
            forking = !forkFrom.isEmpty();
            if (forking) { // a fork's first start: a copy of the old conversation under a new id
                csid = UUID.randomUUID().toString();
                st.update(s, "claudeSessionId", csid);
                args.add("--resume");
                args.add(forkFrom);
                args.add("--fork-session");
                args.add("--session-id");
                args.add(csid);
                resumed = true;
            } else if (resumed) {
                args.add("--resume");
                args.add(csid);
            } else {
                csid = UUID.randomUUID().toString();
                st.update(s, "claudeSessionId", csid, "started", false);
                args.add("--session-id");
                args.add(csid);
            }
            StringBuilder prompt = new StringBuilder(
                    "You are running inside Piggy Desk, a desktop-style app on the user's Android tablet (Honor MagicPad 13). "
                            + "Claude Code itself runs in the app's Alpine Linux (proot, as root); the user reads your replies "
                            + "in the app. Reply in the user's language.");
            JSONObject servers = new JSONObject();
            if (onPc) {
                PcLink.Ctx ctx;
                try {
                    status("connecting");
                    ctx = PcLink.prepare(app, p.optString("path"));
                    put(servers, "pc", new JSONObject(ctx.mcpConfig).getJSONObject("mcpServers").getJSONObject("pc"));
                } catch (Exception e) {
                    fail("连不上电脑：" + e.getMessage() + "（设置 → 电脑）");
                    return false;
                }
                args.add("--disallowedTools");
                args.add(PC_DISALLOWED);
                prompt.append("\n\n").append(ctx.prompt);
                st.putMeta(sid, "branch", ctx.branch);
            }
            if (prefs.optBoolean("browserTools", true)) { // the side panel's browser (mcp__browser__*)
                DeskBrowser.startServer();
                put(servers, "browser", DeskBrowser.mcpEntry());
                prompt.append("\n\nThe app's right-hand panel has a web browser the user can see; the mcp__browser__* tools "
                        + "drive it. Prefer it over WebFetch when a page needs JavaScript, a login, or the user should watch.");
            }
            if (servers.length() > 0) {
                args.add("--mcp-config");
                args.add(new Obj("mcpServers", servers).o.toString());
            }
            if (prefs.optBoolean("suggestions", true)) args.add("--prompt-suggestions");
            args.add("--append-system-prompt");
            args.add(prompt.toString());

            List<String> env = new ArrayList<>();
            env.add("IS_SANDBOX=1"); // root inside proot; lets "bypass permissions" be chosen
            env.add("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1"); // roaming data: no telemetry or self-updates
            env.add("MCP_TIMEOUT=90000"); // the PC is a slow relay away
            env.add("MCP_TOOL_TIMEOUT=900000");
            try {
                proc = Linux.builder(app, cwd, env, args.toArray(new String[0])).start();
            } catch (IOException e) {
                fail("Claude Code 启动失败：" + e.getMessage());
                return false;
            }
            stdin = proc.getOutputStream();
            alive = true;
            initialized = false;
            sawOutput = false;
            errTail.setLength(0);
            restartWhenIdle = false;
            Process mine = proc;
            Thread reader = new Thread(() -> read(mine), "desk-" + sid);
            reader.setDaemon(true);
            reader.start();
            control(new Obj("subtype", "initialize").o, resp -> {
                if (resp == null) return;
                initialized = true;
                desk().onInit(resp);
            });
            return true;
        }

        // ---------------------------------------------------------------- page requests (run's thread)

        void sendUser(JSONArray content) {
            lastUsed = System.currentTimeMillis();
            if (restartWhenIdle && alive && !busy) stop();
            if (!busy) {
                busy = true;
                since = System.currentTimeMillis();
                turnHadText = false;
            }
            status(alive ? "thinking" : "starting");
            if (!ensureStarted()) return; // it said why
            JSONObject msg = new Obj("type", "user", "message", new Obj("role", "user", "content", content).o,
                    "parent_tool_use_id", JSONObject.NULL, "session_id", "").o;
            lastUser = msg;
            status("thinking");
            write(msg);
        }

        synchronized void interrupt() {
            for (String id : new ArrayList<>(perms.keySet())) answer(id, "deny", "用户中断了。", null);
            control(new Obj("subtype", "interrupt").o, null);
        }

        synchronized void answer(String requestId, String decision, String message, JSONObject updatedInput) {
            JSONObject req = perms.remove(requestId);
            if (req == null) return;
            JSONObject input = req.optJSONObject("input");
            JSONObject resp;
            if ("allow".equals(decision) || "allowAlways".equals(decision)) {
                resp = new Obj("behavior", "allow", "updatedInput", updatedInput != null ? updatedInput
                        : input != null ? input : new JSONObject()).o;
                JSONArray sug = req.optJSONArray("permission_suggestions");
                boolean always = "allowAlways".equals(decision) && sug != null;
                if (always) put(resp, "updatedPermissions", sug);
                put(resp, "decisionClassification", always ? "user_permanent" : "user_temporary");
            } else {
                resp = new Obj("behavior", "deny", "message", message == null || message.isEmpty()
                        ? "The user declined this." : "The user declined this: " + message,
                        "decisionClassification", "user_reject").o;
                denied.add(req.optString("tool_use_id"));
            }
            write(new Obj("type", "control_response", "response",
                    new Obj("subtype", "success", "request_id", requestId, "response", resp).o).o);
            desk().sessionEvent(sid, new Obj("type", "permissionDone", "requestId", requestId).o);
            if (perms.isEmpty() && busy) status("tool");
        }

        void askContext() {
            if (!alive || !initialized) return;
            control(new Obj("subtype", "get_context_usage", "detail", "summary").o, resp -> {
                if (resp == null) return;
                JSONArray cats = resp.optJSONArray("categories"), out = new JSONArray();
                for (int i = 0; cats != null && i < cats.length(); i++) {
                    JSONObject cat = cats.optJSONObject(i);
                    if (cat == null) continue;
                    String name = cat.optString("name");
                    long tokens = cat.optLong("tokens");
                    if (tokens <= 0 || name.toLowerCase(java.util.Locale.ROOT).startsWith("free")) continue;
                    out.put(new Obj("label", ctxLabel(name), "tokens", tokens).o);
                }
                JSONObject ev = new Obj("type", "context", "used", resp.optLong("totalTokens"), "window", resp.optLong("maxTokens"),
                        "breakdown", out).o;
                if (resp.has("autoCompactThreshold")) put(ev, "autoCompactAt", resp.optLong("autoCompactThreshold"));
                DeskStore.get(app).putMeta(sid, "context", ev);
                desk().sessionEvent(sid, ev);
            });
        }

        /** Sends a control request; the callback (if any) runs on the reader thread with the response or null. */
        synchronized void control(JSONObject request, Callback cb) {
            if (!alive) {
                lastControlError = "Claude Code 没在运行";
                if (cb != null) cb.done(null);
                return;
            }
            String id = "d" + (++ctl);
            if (cb != null) waiting.put(id, cb);
            write(new Obj("type", "control_request", "request_id", id, "request", request).o);
        }

        /** Ends Claude Code on purpose (idle, a setting that needs a restart, the user): no "it crashed" message. */
        void stop() {
            List<Callback> cbs;
            List<String> open;
            Process p;
            synchronized (this) {
                p = proc;
                proc = null; // its reader then sees an old process and stays quiet
                stdin = null;
                alive = false;
                initialized = false;
                cbs = new ArrayList<>(waiting.values());
                waiting.clear();
                open = new ArrayList<>(perms.keySet());
                perms.clear();
            }
            if (p != null) p.destroy();
            lastControlError = "Claude Code 已停止";
            endTasks();
            for (Callback cb : cbs) cb.done(null);
            for (String id : open) desk().sessionEvent(sid, new Obj("type", "permissionDone", "requestId", id).o);
            if (busy) {
                busy = false;
                status("idle");
            }
            if (remote) {
                remote = false;
                keepAlive = false;
                desk().sessionEvent(sid, envEvent());
            }
        }

        /** After a turn: stop now if a setting needs a restart, or later if nothing happens for a while. */
        void reapIfDue() {
            if (restartWhenIdle && !busy && !keepAlive) {
                stop();
                return;
            }
            final long mark = lastUsed;
            main.postDelayed(() -> {
                if (alive && !busy && !keepAlive && lastUsed == mark) stop();
            }, IDLE_MS);
        }

        private synchronized void write(JSONObject o) {
            if (stdin == null) return;
            try {
                stdin.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException e) {
                android.util.Log.w("PiggyDesk", "write to claude failed", e);
            }
        }

        // ---------------------------------------------------------------- Claude Code's output (reader thread)

        private void read(Process p) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (!t.startsWith("{")) {
                        if (!t.isEmpty()) noteErr(t);
                        continue;
                    }
                    JSONObject m;
                    try {
                        m = new JSONObject(t);
                    } catch (JSONException e) {
                        noteErr(t);
                        continue;
                    }
                    try {
                        handle(m);
                    } catch (Exception e) {
                        android.util.Log.w("PiggyDesk", "bad message " + t, e);
                    }
                }
            } catch (IOException ignored) {
            }
            exited(p);
        }

        private void noteErr(String s) {
            synchronized (errTail) {
                errTail.append(s).append('\n');
                if (errTail.length() > 4000) errTail.delete(0, errTail.length() - 4000);
            }
        }

        private void exited(Process p) {
            List<Callback> cbs;
            List<String> open;
            synchronized (this) {
                if (p != proc) return; // an older process; a new one is running
                alive = false;
                initialized = false;
                stdin = null;
                cbs = new ArrayList<>(waiting.values());
                waiting.clear();
                open = new ArrayList<>(perms.keySet());
                perms.clear();
            }
            lastControlError = "Claude Code 退出了";
            endTasks();
            for (Callback cb : cbs) cb.done(null);
            for (String id : open) desk().sessionEvent(sid, new Obj("type", "permissionDone", "requestId", id).o);
            String err;
            synchronized (errTail) {
                err = errTail.toString().trim();
            }
            if (resumed && !sawOutput && err.toLowerCase(java.util.Locale.ROOT).contains("no conversation found")) {
                // the saved conversation is gone: start a fresh one and send the message again
                DeskStore st = DeskStore.get(app);
                JSONObject s = st.session(sid);
                if (s != null) st.update(s, "claudeSessionId", "", "started", false, "forkFrom", "");
                item(new Obj("id", "sys" + System.currentTimeMillis(), "kind", "system", "level", "warn",
                        "text", "原来的对话在 Claude Code 里找不到了，接着开了一个新对话（上面的内容 Claude 看不到）。").o, true);
                JSONObject again = lastUser;
                if (again != null && busy) {
                    exec.execute(() -> {
                        if (ensureStarted()) write(again); // a failure already says why and goes idle
                    });
                    return;
                }
            }
            if (busy) {
                busy = false;
                String tail = err.length() > 600 ? "…" + err.substring(err.length() - 600) : err;
                item(new Obj("id", "sys" + System.currentTimeMillis(), "kind", "system", "level", "error",
                        "text", "Claude Code 意外退出了" + (tail.isEmpty() ? "。" : "：\n" + tail)).o, true);
                status("idle");
            }
            desk().sessionEvent(sid, envEvent());
        }

        private void handle(JSONObject m) {
            sawOutput = true;
            String type = m.optString("type");
            switch (type) {
                case "control_response": {
                    JSONObject r = m.optJSONObject("response");
                    if (r == null) return;
                    Callback cb;
                    synchronized (this) {
                        cb = waiting.remove(r.optString("request_id"));
                    }
                    if (cb == null) return;
                    if ("success".equals(r.optString("subtype"))) {
                        JSONObject payload = r.optJSONObject("response");
                        cb.done(payload == null ? new JSONObject() : payload);
                    } else {
                        lastControlError = r.optString("error", "失败");
                        cb.done(null);
                    }
                    return;
                }
                case "control_request":
                    onControlRequest(m.optString("request_id"), m.optJSONObject("request"));
                    return;
                case "control_cancel_request": {
                    String id = m.optString("request_id");
                    synchronized (this) {
                        if (perms.remove(id) == null) return;
                    }
                    desk().sessionEvent(sid, new Obj("type", "permissionDone", "requestId", id).o);
                    return;
                }
                case "system":
                    onSystem(m);
                    return;
                case "stream_event":
                    onStream(m);
                    return;
                case "assistant":
                    onAssistant(m);
                    return;
                case "user":
                    onToolResults(m);
                    return;
                case "result":
                    onResult(m);
                    return;
                case "rate_limit_event":
                    desk().onRateLimit(m.optJSONObject("rate_limit_info"));
                    return;
                case "prompt_suggestion":
                    desk().sessionEvent(sid, new Obj("type", "suggestion", "text", m.optString("suggestion")).o);
                    return;
                default:
            }
        }

        private void onControlRequest(String id, JSONObject req) {
            if (req == null) return;
            if (!"can_use_tool".equals(req.optString("subtype"))) {
                // hooks, elicitations and dialogs aren't offered by this host: say so rather than leave Claude waiting
                write(new Obj("type", "control_response", "response", new Obj("subtype", "error", "request_id", id,
                        "error", "Piggy Desk does not handle " + req.optString("subtype")).o).o);
                return;
            }
            synchronized (this) {
                perms.put(id, req);
            }
            status("waiting");
            desk().sessionEvent(sid, permissionEvent(id, req));
            String tool = req.optString("tool_name");
            desk().onWaiting(sid, "ExitPlanMode".equals(tool) ? "计划拟好了，等你批准" : "AskUserQuestion".equals(tool) ? "Claude 有问题想问你" : "Claude 在等你确认");
        }

        private static JSONObject permissionEvent(String id, JSONObject req) {
            String tool = req.optString("tool_name");
            String kind = "ExitPlanMode".equals(tool) ? "plan" : "AskUserQuestion".equals(tool) ? "question" : "tool";
            JSONObject ev = new Obj("type", "permission", "requestId", id, "tool", tool, "kind", kind,
                    "input", req.optJSONObject("input") == null ? new JSONObject() : req.optJSONObject("input")).o;
            if (req.has("permission_suggestions")) put(ev, "suggestions", req.optJSONArray("permission_suggestions"));
            if (req.has("decision_reason")) put(ev, "reason", req.optString("decision_reason"));
            if (req.has("blocked_path")) put(ev, "blockedPath", req.optString("blocked_path"));
            return ev;
        }

        synchronized List<JSONObject> pendingEvents() {
            List<JSONObject> out = new ArrayList<>();
            for (Map.Entry<String, JSONObject> e : perms.entrySet()) out.add(permissionEvent(e.getKey(), e.getValue()));
            return out;
        }

        private void onSystem(JSONObject m) {
            String sub = m.optString("subtype");
            if ("init".equals(sub)) {
                JSONObject s = DeskStore.get(app).session(sid);
                String csid = m.optString("session_id");
                if (s != null && !csid.isEmpty() && !csid.equals(s.optString("claudeSessionId"))) {
                    DeskStore.get(app).update(s, "claudeSessionId", csid);
                }
                if (s != null && forking) {
                    forking = false;
                    DeskStore.get(app).update(s, "forkFrom", "");
                }
                if (m.has("model")) model = modelAlias(m.optString("model"), model);
                JSONArray servers = m.optJSONArray("mcp_servers");
                for (int i = 0; servers != null && i < servers.length(); i++) {
                    JSONObject sv = servers.optJSONObject(i);
                    if (sv != null && "pc".equals(sv.optString("name")) && !"connected".equals(sv.optString("status"))) {
                        item(new Obj("id", "sys-pc-" + System.currentTimeMillis(), "kind", "system", "level", "warn",
                                "text", "电脑的工具没连上（" + sv.optString("status") + "）：Claude 现在动不了电脑上的文件。").o, true);
                    }
                }
            } else if ("compact_boundary".equals(sub)) {
                JSONObject meta = m.optJSONObject("compact_metadata");
                long pre = meta == null ? 0 : meta.optLong("pre_tokens");
                item(new Obj("id", "cmp" + System.currentTimeMillis(), "kind", "compact",
                        "text", pre > 0 ? "对话已压缩（之前约 " + (pre / 1000) + "k tokens）" : "对话已压缩").o, true);
            } else if ("status".equals(sub)) {
                if ("compacting".equals(m.optString("status"))) status("compacting");
            } else if ("memory_saved".equals(sub) || "memory_recall".equals(sub)) {
                onMemory(m, "memory_recall".equals(sub));
            } else if (sub.startsWith("task_") || "background_tasks_changed".equals(sub)) {
                onTask(sub, m);
            } else if ("api_retry".equals(sub)) {
                status("retrying");
            } else if ("local_command_output".equals(sub)) {
                String text = m.optString("content", m.optString("output"));
                if (!text.isEmpty()) {
                    turnHadText = true;
                    item(new Obj("id", "loc" + System.currentTimeMillis(), "kind", "assistant", "text", text).o, true);
                }
            }
        }

        private void onStream(JSONObject m) {
            if (!m.isNull("parent_tool_use_id")) return; // subagents: their finished blocks are enough
            JSONObject ev = m.optJSONObject("event");
            if (ev == null) return;
            switch (ev.optString("type")) {
                case "message_start": {
                    JSONObject msg = ev.optJSONObject("message");
                    streamMsg = msg == null ? "" : msg.optString("id");
                    streamIds.clear();
                    return;
                }
                case "content_block_start": {
                    JSONObject b = ev.optJSONObject("content_block");
                    if (b == null) return;
                    String bt = b.optString("type");
                    if ("text".equals(bt) || "thinking".equals(bt)) {
                        int k = next(streamBlocks, streamMsg);
                        String id = streamMsg + ":" + k;
                        streamIds.put(ev.optInt("index"), id);
                        boolean thinking = "thinking".equals(bt);
                        if (thinking) thinkingStart.put(id, System.currentTimeMillis());
                        JSONObject it = new Obj("id", id, "kind", thinking ? "thinking" : "assistant", "text", "").o;
                        if (thinking) put(it, "done", false);
                        desk().sessionEvent(sid, new Obj("type", "item", "item", it).o);
                        status("thinking");
                    } else if ("tool_use".equals(bt) || "server_tool_use".equals(bt)) {
                        status("tool");
                    }
                    return;
                }
                case "content_block_delta": {
                    JSONObject d = ev.optJSONObject("delta");
                    String id = streamIds.get(ev.optInt("index"));
                    if (d == null || id == null) return;
                    String text = "text_delta".equals(d.optString("type")) ? d.optString("text")
                            : "thinking_delta".equals(d.optString("type")) ? d.optString("thinking") : null;
                    if (text != null && !text.isEmpty()) {
                        desk().sessionEvent(sid, new Obj("type", "delta", "itemId", id, "text", text).o);
                    }
                    return;
                }
                default:
            }
        }

        private void onAssistant(JSONObject m) {
            JSONObject msg = m.optJSONObject("message");
            if (msg == null) return;
            String parent = m.isNull("parent_tool_use_id") ? null : m.optString("parent_tool_use_id");
            String mid = msg.optString("id", "m" + System.currentTimeMillis());
            JSONArray content = msg.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b == null) continue;
                String bt = b.optString("type");
                if ("text".equals(bt) || "thinking".equals(bt)) {
                    String id = (parent == null ? "" : parent + "/") + mid + ":" + next(finalBlocks, (parent == null ? "" : parent + "/") + mid);
                    boolean thinking = "thinking".equals(bt);
                    JSONObject it = new Obj("id", id, "kind", thinking ? "thinking" : "assistant",
                            "text", thinking ? b.optString("thinking") : b.optString("text")).o;
                    if (thinking) {
                        Long t0 = thinkingStart.remove(id);
                        if (t0 != null) put(it, "ms", System.currentTimeMillis() - t0);
                    } else if (parent == null) {
                        turnHadText = true;
                    }
                    if (parent != null) put(it, "parentId", parent);
                    item(it, true);
                } else if ("tool_use".equals(bt) || "server_tool_use".equals(bt) || "mcp_tool_use".equals(bt)) {
                    String name = b.optString("name");
                    if ("mcp_tool_use".equals(bt) && b.has("server_name")) name = "mcp__" + b.optString("server_name") + "__" + name;
                    JSONObject input = b.optJSONObject("input") == null ? new JSONObject() : b.optJSONObject("input");
                    JSONObject it = new Obj("id", b.optString("id"), "kind", "tool", "name", name, "input", input,
                            "status", "running").o;
                    if (parent != null) put(it, "parentId", parent);
                    synchronized (this) {
                        tools.put(b.optString("id"), it);
                    }
                    item(it, true);
                    sideEffects(name, input);
                }
            }
            JSONObject usage = msg.optJSONObject("usage");
            if (parent == null && usage != null) {
                long used = usage.optLong("input_tokens") + usage.optLong("cache_creation_input_tokens")
                        + usage.optLong("cache_read_input_tokens") + usage.optLong("output_tokens");
                JSONObject prev = DeskStore.get(app).meta(sid).optJSONObject("context");
                long window = prev != null && prev.optLong("window") > 0 ? prev.optLong("window") : 200000;
                JSONObject ev = new Obj("type", "context", "used", used, "window", window,
                        "breakdown", prev != null && prev.has("breakdown") ? prev.optJSONArray("breakdown") : new JSONArray()).o;
                if (prev != null && prev.has("autoCompactAt")) put(ev, "autoCompactAt", prev.optLong("autoCompactAt"));
                desk().sessionEvent(sid, ev);
            }
        }

        /** To-dos and the files Claude wrote, for the side panel. */
        private void sideEffects(String name, JSONObject input) {
            DeskStore st = DeskStore.get(app);
            if ("ExitPlanMode".equals(name) && !input.optString("plan").isEmpty()) {
                JSONObject old = st.meta(sid).optJSONObject("plan");
                plan(input.optString("plan"), input.optString("planFilePath", old == null ? "" : old.optString("path")), true);
                return;
            }
            String written = input.optString("file_path");
            if (("Write".equals(name) || "Edit".equals(name)) && written.startsWith("/root/.claude/plans/") && written.endsWith(".md")) {
                // plan mode writes its plan to a file first: show it as it grows
                main.postDelayed(() -> {
                    try {
                        plan(DeskStore.read(new File(Linux.root(app), written.substring(1))), written, false);
                    } catch (IOException ignored) {
                    }
                }, 1500);
                return;
            }
            if ("TodoWrite".equals(name)) {
                JSONArray todos = input.optJSONArray("todos");
                if (todos == null) return;
                st.putMeta(sid, "todos", todos);
                desk().sessionEvent(sid, new Obj("type", "todos", "todos", todos).o);
                return;
            }
            String action = null;
            if ("Write".equals(name) || "mcp__pc__write".equals(name)) action = "write";
            else if ("Edit".equals(name) || "MultiEdit".equals(name) || "NotebookEdit".equals(name) || "mcp__pc__edit".equals(name)) {
                action = "edit";
            }
            if (action == null) return;
            String path = input.optString("file_path", input.optString("notebook_path"));
            if (path.isEmpty()) return;
            JSONArray old = st.meta(sid).optJSONArray("outputs"), files = new JSONArray();
            for (int i = 0; old != null && i < old.length(); i++) {
                JSONObject f = old.optJSONObject(i);
                if (f != null && !path.equals(f.optString("path"))) files.put(f);
            }
            files.put(new Obj("path", path, "action", action, "at", System.currentTimeMillis()).o);
            st.putMeta(sid, "outputs", files);
            desk().sessionEvent(sid, new Obj("type", "outputs", "files", files).o);
        }

        private void onToolResults(JSONObject m) {
            JSONObject msg = m.optJSONObject("message");
            JSONArray content = msg == null ? null : msg.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b == null || !"tool_result".equals(b.optString("type"))) continue;
                String id = b.optString("tool_use_id");
                JSONObject it;
                synchronized (this) {
                    it = tools.remove(id);
                }
                if (it == null) continue;
                StringBuilder text = new StringBuilder();
                JSONArray images = new JSONArray();
                Object c = b.opt("content");
                if (c instanceof String) text.append((String) c);
                else if (c instanceof JSONArray) {
                    JSONArray parts = (JSONArray) c;
                    for (int k = 0; k < parts.length(); k++) {
                        JSONObject part = parts.optJSONObject(k);
                        if (part == null) continue;
                        if ("text".equals(part.optString("type"))) {
                            if (text.length() > 0) text.append('\n');
                            text.append(part.optString("text"));
                        } else if ("image".equals(part.optString("type")) && images.length() < 3) {
                            JSONObject src = part.optJSONObject("source");
                            String data = src == null ? "" : src.optString("data");
                            if (!data.isEmpty() && data.length() < 2_000_000) {
                                images.put("data:" + src.optString("media_type", "image/png") + ";base64," + data);
                            }
                        }
                    }
                }
                String out = text.length() > 30000 ? text.substring(0, 30000) + "\n…" : text.toString();
                boolean err = b.optBoolean("is_error");
                put(it, "status", err ? (denied.remove(id) ? "denied" : "error") : "done");
                put(it, "output", out);
                if (images.length() > 0) put(it, "images", images);
                item(it, true);
            }
            if (busy && perms.isEmpty()) status("thinking");
        }

        private void onResult(JSONObject m) {
            busy = false;
            lastUsed = System.currentTimeMillis();
            DeskStore st = DeskStore.get(app);
            JSONObject s = st.session(sid);
            if (s != null) st.update(s, "started", true, "updatedAt", System.currentTimeMillis());
            String sub = m.optString("subtype");
            String result = m.optString("result");
            if (m.optBoolean("is_error") || (!sub.isEmpty() && !"success".equals(sub))) {
                String text = !result.isEmpty() ? result : "error_max_turns".equals(sub) ? "到了最大轮数，停下了。"
                        : "error_during_execution".equals(sub) ? "这一轮中途停了（被中断，或者出了错）。" : "出错了：" + sub;
                JSONArray errors = m.optJSONArray("errors");
                if (errors != null && errors.length() > 0) text += "\n" + errors.optString(0);
                item(new Obj("id", "res" + System.currentTimeMillis(), "kind", "system", "level",
                        "error_during_execution".equals(sub) ? "warn" : "error", "text", text).o, true);
            } else if (!turnHadText && !result.trim().isEmpty()) {
                // a slash command's answer (/cost, /context …) comes only in the result
                item(new Obj("id", "res" + System.currentTimeMillis(), "kind", "assistant", "text", result).o, true);
            }
            JSONObject usage = m.optJSONObject("modelUsage");
            if (usage != null && usage.length() > 0) {
                long window = 0;
                JSONArray names = usage.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    JSONObject u = usage.optJSONObject(names.optString(i));
                    if (u != null) window = Math.max(window, u.optLong("contextWindow"));
                }
                if (window > 0) {
                    JSONObject ctx = st.meta(sid).optJSONObject("context");
                    if (ctx == null) ctx = new Obj("type", "context").o;
                    put(ctx, "window", window);
                    st.putMeta(sid, "context", ctx);
                }
            }
            status("idle");
            desk().onTurnDone(sid);
            askContext();
            nameIfNew();
            reapIfDue();
        }

        /** The session's plan, for the 计划 tab (open: switch to it, as when Claude asks to start on it). */
        private void plan(String text, String path, boolean open) {
            JSONObject ev = new Obj("type", "plan", "text", text, "path", path, "at", System.currentTimeMillis(), "open", open).o;
            DeskStore.get(app).putMeta(sid, "plan", ev);
            desk().sessionEvent(sid, ev);
        }

        void refreshPlan() {
            control(new Obj("subtype", "get_plan").o, resp -> {
                if (resp != null && resp.optBoolean("exists")) plan(resp.optString("content"), resp.optString("path"), false);
            });
        }

        /** Claude Code's auto-memory: notes it saved, or recalled for this turn. */
        private void onMemory(JSONObject m, boolean recalled) {
            JSONArray paths = new JSONArray();
            if (recalled) {
                JSONArray mem = m.optJSONArray("memories");
                for (int i = 0; mem != null && i < mem.length(); i++) {
                    JSONObject x = mem.optJSONObject(i);
                    if (x != null) paths.put(x.optString("path"));
                }
            } else {
                JSONArray w = m.optJSONArray("written_paths");
                for (int i = 0; w != null && i < w.length(); i++) paths.put(w.optString(i));
            }
            if (paths.length() == 0) return;
            String verb = m.optString("verb", "Saved");
            if (recalled) verb = "想起了";
            else if ("Updated".equalsIgnoreCase(verb)) verb = "更新了";
            else if ("Deleted".equalsIgnoreCase(verb) || "Removed".equalsIgnoreCase(verb)) verb = "删掉了";
            else verb = "记住了";
            JSONObject it = new Obj("id", (recalled ? "mr" : "ms") + System.currentTimeMillis(), "kind", "memory", "paths", paths,
                    "recalled", recalled, "verb", verb).o;
            item(it, true);
            if (!recalled) desk().sessionEvent(sid, new Obj("type", "memoryChanged").o);
        }

        /** Background tasks: shells and agents sent to the background, their state, and the end of their output. */
        private void onTask(String sub, JSONObject m) {
            String id = m.optString("task_id");
            synchronized (tasks) {
                if ("background_tasks_changed".equals(sub)) {
                    JSONArray live = m.optJSONArray("tasks");
                    Set<String> ids = new HashSet<>();
                    for (int i = 0; live != null && i < live.length(); i++) {
                        JSONObject t = live.optJSONObject(i);
                        if (t == null || t.optBoolean("ambient")) continue;
                        String tid = t.optString("task_id");
                        ids.add(tid);
                        JSONObject cur = tasks.get(tid);
                        if (cur == null) {
                            cur = task(tid, t.optString("task_type"), t.optString("description"));
                            tasks.put(tid, cur);
                        }
                        put(cur, "status", "running");
                    }
                    for (JSONObject t : tasks.values()) {
                        if ("running".equals(t.optString("status")) && !ids.contains(t.optString("id"))) put(t, "status", "completed");
                    }
                } else if ("task_started".equals(sub)) {
                    if (!m.optBoolean("is_backgrounded") || m.optBoolean("skip_transcript")) return;
                    tasks.put(id, task(id, m.optString("task_type"), m.optString("description")));
                } else {
                    JSONObject t = tasks.get(id);
                    JSONObject patch = m.optJSONObject("patch");
                    if (t == null && patch != null && patch.optBoolean("is_backgrounded")) {
                        t = task(id, "", patch.optString("description"));
                        tasks.put(id, t);
                    }
                    if (t == null) return;
                    if (patch != null && patch.has("status")) put(t, "status", patch.optString("status"));
                    if ("task_notification".equals(sub)) put(t, "status", m.optString("status", "completed"));
                    if ("task_progress".equals(sub) && !m.optString("summary").isEmpty()) put(t, "summary", m.optString("summary"));
                }
                while (tasks.size() > 30) tasks.remove(tasks.keySet().iterator().next());
            }
            pushTasks();
        }

        private JSONObject task(String id, String type, String description) {
            String kind = type.contains("bash") || type.contains("shell") || type.contains("monitor") ? "shell" : type.contains("agent") ? "agent" : type;
            return new Obj("id", id, "kind", kind, "description", description, "status", "running", "at", System.currentTimeMillis()).o;
        }

        private void pushTasks() {
            JSONArray list = new JSONArray();
            synchronized (tasks) {
                for (JSONObject t : tasks.values()) list.put(t);
            }
            DeskStore.get(app).putMeta(sid, "tasks", list);
            desk().sessionEvent(sid, new Obj("type", "tasks", "tasks", list).o);
        }

        private void endTasks() {
            boolean any = false;
            synchronized (tasks) {
                for (JSONObject t : tasks.values()) {
                    if ("running".equals(t.optString("status"))) {
                        put(t, "status", "stopped");
                        any = true;
                    }
                }
            }
            if (any) pushTasks();
        }

        /** A short title for a session still called by its first words, from Claude Code (generate_session_title). */
        private void nameIfNew() {
            DeskStore st = DeskStore.get(app);
            JSONObject s = st.session(sid);
            if (s == null || s.optBoolean("titleSet") || s.optBoolean("titled")) return;
            String first = "";
            for (JSONObject it : st.items(sid)) {
                if ("user".equals(it.optString("kind")) && !it.optString("text").trim().isEmpty()) {
                    first = it.optString("text").trim();
                    break;
                }
            }
            if (first.isEmpty() || first.startsWith("/")) return;
            st.update(s, "titled", true);
            control(new Obj("subtype", "generate_session_title", "description", first.length() > 2000 ? first.substring(0, 2000) : first,
                    "persist", false).o, resp -> {
                String title = resp == null ? "" : resp.optString("title").trim();
                JSONObject now = DeskStore.get(app).session(sid);
                if (title.isEmpty() || now == null || now.optBoolean("titleSet")) return;
                DeskStore.get(app).update(now, "title", title.length() > 60 ? title.substring(0, 60) : title);
                desk().pushSessionsSoon();
            });
        }

        // ---------------------------------------------------------------- helpers

        /** Shows (and keeps) an item. */
        private void item(JSONObject it, boolean keep) {
            if (keep) DeskStore.get(app).putItem(sid, it);
            desk().sessionEvent(sid, new Obj("type", "item", "item", it).o);
        }

        private void status(String phase) {
            boolean running = busy && !"idle".equals(phase);
            desk().sessionEvent(sid, new Obj("type", "status", "running", running,
                    "phase", running ? phase : "idle", "since", since).o);
            desk().onRunning(sid, running);
        }

        private void fail(String text) {
            busy = false;
            item(new Obj("id", "sys" + System.currentTimeMillis(), "kind", "system", "level", "error", "text", text).o, true);
            status("idle");
        }

        JSONObject envEvent() {
            return Desk.get(app).envFor(sid, remote && alive);
        }

        private static int next(Map<String, Integer> counters, String key) {
            Integer n = counters.get(key);
            int v = n == null ? 0 : n + 1;
            counters.put(key, v);
            return v;
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** Translates Claude Code's context category names for the side panel. */
    static String ctxLabel(String name) {
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "messages": return "消息";
            case "system tools": return "系统工具";
            case "mcp tools": return "MCP 工具";
            case "system prompt": return "系统提示";
            case "skills": return "技能";
            case "memory files": return "记忆文件";
            case "custom agents": case "agents": return "子代理";
            case "autocompact buffer": return "自动压缩缓冲";
            case "slash commands": return "斜杠命令";
            default: return name;
        }
    }

    /** "claude-opus-5-5" → "opus" when the session was started with that alias, so the picker keeps its tick. */
    static String modelAlias(String full, String current) {
        if (current != null && !current.isEmpty() && full.contains(current)) return current;
        return full;
    }

    /** The first http(s) link anywhere in a JSON value (Remote Control's session address). */
    static String findUrl(Object v) {
        if (v instanceof String) return ((String) v).startsWith("http") ? (String) v : "";
        if (v instanceof JSONObject) {
            JSONArray names = ((JSONObject) v).names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String u = findUrl(((JSONObject) v).opt(names.optString(i)));
                if (!u.isEmpty()) return u;
            }
        }
        if (v instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) v).length(); i++) {
                String u = findUrl(((JSONArray) v).opt(i));
                if (!u.isEmpty()) return u;
            }
        }
        return "";
    }

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    /** new Obj("a", 1, "b", "x").o: a JSONObject from key-value pairs, without checked exceptions. */
    static final class Obj {
        final JSONObject o = new JSONObject();

        Obj(Object... kv) {
            for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        }
    }
}
