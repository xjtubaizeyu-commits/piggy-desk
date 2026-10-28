package app.piggy.desk;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static app.piggy.desk.Engine.put;

/**
 * The app side of the workspace page: answers what the page asks (docs/desk-bridge.md), keeps its picture of the
 * world (account, network, the PC, usage, projects, sessions) and passes on what the engine reports. Lives for the
 * whole app process; the page attaches while it is on screen.
 */
final class Desk implements Account.Listener, NetGuard.Listener {
    private static Desk instance;
    private final Context app;
    private final DeskStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newCachedThreadPool();
    private final Set<String> running = new HashSet<>();
    private DeskActivity page;
    /** The session open on the page (it doesn't get "unread"). */
    private volatile String viewing;
    /** The page is on screen (not behind another app): no notifications then. */
    private volatile boolean visible;
    private boolean dictating;

    static synchronized Desk get(Context c) {
        if (instance == null) instance = new Desk(c.getApplicationContext());
        return instance;
    }

    private Desk(Context c) {
        app = c;
        store = DeskStore.get(c);
        NetGuard.start(c);
        EgressProxy.start(c);
        NetGuard.addListener(this);
        Account.addListener(this);
        Account.CLAUDE.refresh(c);
    }

    void attach(DeskActivity a) {
        page = a;
    }

    void detach(DeskActivity a) {
        if (page == a) page = null;
    }

    void setVisible(boolean v) {
        visible = v;
        if (v) DeskService.clearDone(app);
    }

    // ------------------------------------------------------------------ to the page

    /** Sends a message to the page if it is showing (from any thread). */
    void emit(JSONObject m) {
        main.post(() -> {
            if (page != null) page.send(m);
        });
    }

    void emitPart(String type, Object value) {
        emit(new Engine.Obj("type", type, "value", value).o);
    }

    void sessionEvent(String sid, JSONObject ev) {
        put(ev, "sessionId", sid);
        emit(ev);
    }

    void toast(String text) {
        emit(new Engine.Obj("type", "toast", "text", text).o);
    }

    @Override
    public void onAccountChanged() {
        emitPart("setup", setup());
        emitPart("account", account());
    }

    @Override
    public void onNetChanged() {
        emitPart("net", net());
        emitPart("egress", egress());
    }

    void pushPc() {
        emitPart("pc", pc());
    }

    JSONObject state() {
        JSONObject s = new JSONObject();
        try {
            s.put("type", "state").put("app", new JSONObject().put("version", BuildInfo.VERSION).put("id", BuildInfo.APP_ID))
                    .put("setup", setup()).put("account", account()).put("net", net()).put("egress", egress()).put("pc", pc())
                    .put("prefs", prefsForPage()).put("models", models()).put("commands", json(store.str("commands", "[]")))
                    .put("efforts", new JSONArray(Arrays.asList("low", "medium", "high", "xhigh", "max")))
                    .put("modes", new JSONArray(Arrays.asList("default", "acceptEdits", "plan", "auto", "bypassPermissions")))
                    .put("usage", jsonObj(store.str("usage", "{}"))).put("projects", store.projects()).put("sessions", sessions());
        } catch (JSONException ignored) {
        }
        return s;
    }

    private JSONObject setup() {
        Account a = Account.CLAUDE;
        String claude;
        switch (a.step) {
            case NEED_INSTALL: claude = "missing"; break;
            case INSTALLING: claude = "installing"; break;
            case ERROR: claude = a.version.isEmpty() ? "error" : "ready"; break;
            case CHECKING: claude = "NEED_INSTALL".equals(Prefs.agentState(app, "claude")) || !Linux.installed(app) ? "missing" : "ready"; break;
            default: claude = "ready";
        }
        String detail = a.step == Account.Step.INSTALLING || a.step == Account.Step.ERROR ? a.detail : "";
        // a failed install: what the tablet's exit last ran into (DNS, a refused connection …) says more than "502"
        if (a.step == Account.Step.ERROR && !EgressProxy.lastError.isEmpty()) detail += "\n出口最后的错误：" + EgressProxy.lastError;
        return new Engine.Obj("linux", Linux.installed(app), "claude", claude, "version", a.version, "detail", detail).o;
    }

    private JSONObject account() {
        Account a = Account.CLAUDE;
        boolean signed = a.step == Account.Step.SIGNED_IN
                || a.step == Account.Step.CHECKING && "SIGNED_IN".equals(Prefs.agentState(app, "claude"));
        String email = !a.email.isEmpty() ? a.email : store.str("acctEmail", "");
        String plan = !a.plan.isEmpty() ? a.plan : store.str("acctPlan", "");
        if (!plan.isEmpty()) plan = plan.substring(0, 1).toUpperCase(Locale.ROOT) + plan.substring(1);
        String name = store.str("acctName", "");
        if (name.isEmpty() && email.contains("@")) name = email.substring(0, email.indexOf('@'));
        return new Engine.Obj("signedIn", signed, "signingIn", a.step == Account.Step.WAITING_BROWSER || a.step == Account.Step.FINISHING,
                "email", email, "name", name, "plan", plan).o;
    }

    private JSONObject net() {
        return new Engine.Obj("ssid", NetGuard.ssid, "allowed", NetGuard.allowed, "reason", NetGuard.reason,
                "allow", new JSONArray(store.allowSsid())).o;
    }

    private JSONObject egress() {
        return new Engine.Obj("on", store.bool("egressOn", false), "port", store.num("egressPort", 8899),
                "code", store.str("egressCode", ""), "meshIp", EgressProxy.meshIp(), "lastPeer", EgressProxy.lastPeer,
                "error", EgressProxy.meshError, "tunnels", EgressProxy.tunnels()).o;
    }

    private JSONObject pc() {
        boolean on = PcLink.on(app);
        return new Engine.Obj("on", on, "host", PcLink.host(app), "user", PcLink.user(app),
                "status", on ? PcLink.status : "off", "detail", PcLink.detail, "publicKey", PcLink.publicKey(app)).o;
    }

    private JSONObject prefsForPage() {
        JSONObject p = store.prefs();
        put(p, "mode", pageMode(p.optString("mode")));
        return p;
    }

    private JSONArray models() {
        JSONArray m = json(store.str("models", ""));
        if (m.length() > 0) return m;
        JSONArray d = new JSONArray();
        d.put(new Engine.Obj("value", "opus", "label", "Opus", "description", "最强，适合难活").o);
        d.put(new Engine.Obj("value", "sonnet", "label", "Sonnet", "description", "快，日常够用").o);
        d.put(new Engine.Obj("value", "haiku", "label", "Haiku", "description", "最快最省").o);
        return d;
    }

    private JSONArray sessions() {
        JSONArray all = store.sessions(), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.optJSONObject(i);
            JSONObject c;
            try {
                c = new JSONObject(s.toString());
            } catch (JSONException e) {
                continue;
            }
            put(c, "running", running.contains(s.optString("id")));
            put(c, "mode", pageMode(s.optString("mode")));
            out.put(c);
        }
        return out;
    }

    private void pushSessions() {
        emitPart("sessions", sessions());
    }

    void pushSessionsSoon() {
        main.post(this::pushSessions);
    }

    /** The page says "default" for Claude Code's "manual" (ask before acting). */
    static String cliMode(String m) {
        return m == null || m.isEmpty() || "default".equals(m) ? "manual" : m;
    }

    static String pageMode(String m) {
        return m == null || m.isEmpty() || "manual".equals(m) ? "default" : m;
    }

    // ------------------------------------------------------------------ from the engine

    void onInit(JSONObject resp) {
        JSONArray models = resp.optJSONArray("models");
        if (models != null && models.length() > 0) {
            JSONArray m = new JSONArray();
            for (int i = 0; i < models.length(); i++) {
                JSONObject x = models.optJSONObject(i);
                if (x != null) m.put(new Engine.Obj("value", x.optString("value"), "label", x.optString("displayName", x.optString("value")),
                        "description", x.optString("description")).o);
            }
            store.put("models", m.toString());
            emitPart("models", m);
        }
        JSONArray cmds = resp.optJSONArray("commands");
        if (cmds != null) {
            JSONArray c = new JSONArray();
            for (int i = 0; i < cmds.length(); i++) {
                JSONObject x = cmds.optJSONObject(i);
                if (x != null) c.put(new Engine.Obj("name", x.optString("name"), "description", x.optString("description"),
                        "argumentHint", x.optString("argumentHint")).o);
            }
            store.put("commands", c.toString());
            emitPart("commands", c);
        }
        JSONObject acct = resp.optJSONObject("account");
        if (acct != null) {
            if (!acct.optString("email").isEmpty()) store.put("acctEmail", acct.optString("email"));
            if (!acct.optString("subscriptionType").isEmpty()) store.put("acctPlan", acct.optString("subscriptionType"));
            emitPart("account", account());
        }
    }

    /** rate_limit_event: utilization 0–1, resetsAt in epoch seconds. */
    void onRateLimit(JSONObject info) {
        if (info == null) return;
        JSONObject u = jsonObj(store.str("usage", "{}"));
        JSONObject windows = info.optJSONObject("unifiedWindows");
        String[][] keys = {{"five_hour", "fiveHour"}, {"seven_day", "weekly"}, {"seven_day_opus", "weeklyOpus"}};
        for (String[] k : keys) {
            JSONObject w = windows == null ? null : windows.optJSONObject(k[0]);
            if (w != null) {
                JSONObject cur = u.optJSONObject(k[1]) == null ? new JSONObject() : u.optJSONObject(k[1]);
                put(cur, "pct", Math.round(w.optDouble("utilization") * 100));
                if (w.has("resetsAt")) put(cur, "resetsAt", w.optLong("resetsAt") * 1000);
                put(u, k[1], cur);
            }
        }
        String type = info.optString("rateLimitType");
        for (String[] k : keys) {
            if (!k[0].equals(type)) continue;
            JSONObject cur = u.optJSONObject(k[1]) == null ? new JSONObject() : u.optJSONObject(k[1]);
            put(cur, "status", info.optString("status"));
            if (info.has("utilization") && !cur.has("pct")) {
                double v = info.optDouble("utilization");
                put(cur, "pct", Math.round(v <= 1.0 ? v * 100 : v));
            }
            if (info.has("resetsAt")) put(cur, "resetsAt", info.optLong("resetsAt") * 1000);
            put(u, k[1], cur);
        }
        saveUsage(u);
    }

    /** get_usage's rate_limits: utilization 0–100, resets_at an ISO time. */
    void onUsage(JSONObject limits, boolean unused) {
        if (limits == null) return;
        JSONObject u = jsonObj(store.str("usage", "{}"));
        String[][] keys = {{"five_hour", "fiveHour"}, {"seven_day", "weekly"}, {"seven_day_opus", "weeklyOpus"}};
        for (String[] k : keys) {
            JSONObject w = limits.optJSONObject(k[0]);
            if (w == null) continue;
            JSONObject cur = u.optJSONObject(k[1]) == null ? new JSONObject() : u.optJSONObject(k[1]);
            if (!w.isNull("utilization")) put(cur, "pct", Math.round(w.optDouble("utilization")));
            long at = isoMillis(w.optString("resets_at"));
            if (at > 0) put(cur, "resetsAt", at);
            put(u, k[1], cur);
        }
        saveUsage(u);
    }

    private void saveUsage(JSONObject u) {
        store.put("usage", u.toString());
        emitPart("usage", u);
    }

    void onRunning(String sid, boolean on) {
        boolean changed;
        synchronized (running) {
            changed = on ? running.add(sid) : running.remove(sid);
        }
        if (changed) pushSessions();
    }

    void onTurnDone(String sid) {
        JSONObject s = store.session(sid);
        if (s != null && !sid.equals(viewing)) store.update(s, "unread", true);
        pushSessions();
        if (s != null && !visible && store.bool("notify", true)) DeskService.notifyDone(app, sid, s.optString("title"), "Claude 做完了");
    }

    /** Claude is waiting for an OK while nobody is looking: say so. */
    void onWaiting(String sid, String what) {
        JSONObject s = store.session(sid);
        if (s != null && !visible && store.bool("notify", true)) DeskService.notifyDone(app, sid, s.optString("title"), what);
    }

    /** The 环境 panel for a session. */
    JSONObject envFor(String sid, boolean remote) {
        JSONObject s = store.session(sid);
        JSONObject p = s == null ? null : store.project(s.optString("projectId"));
        String env = p == null ? DeskStore.TABLET : p.optString("env");
        String path = p == null ? "" : p.optString("path");
        String branch = DeskStore.PC.equals(env) ? store.meta(sid).optString("branch") : gitBranch(path);
        return new Engine.Obj("type", "env", "env", env, "cwd", path, "branch", branch, "remote", remote).o;
    }

    private String gitBranch(String path) {
        try {
            String head = DeskStore.read(new File(Linux.root(app), (path + "/.git/HEAD").substring(1))).trim();
            return head.startsWith("ref: refs/heads/") ? head.substring(16) : head.length() >= 7 ? head.substring(0, 7) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ from the page

    void onMessage(DeskActivity a, JSONObject m) {
        String type = m.optString("type");
        String sid = m.optString("sessionId");
        try {
            switch (type) {
                case "ready":
                    emit(state());
                    break;
                case "newSession": {
                    JSONObject p = store.project(m.optString("projectId"));
                    if (p == null) throw new IOException("项目不存在");
                    JSONObject prefs = store.prefs();
                    JSONObject s = store.addSession(p.optString("id"), prefs);
                    viewing = s.optString("id");
                    pushSessions();
                    reply(m, new Engine.Obj("sessionId", s.optString("id")).o, null);
                    break;
                }
                case "openSession":
                    openSession(sid);
                    break;
                case "send":
                    send(sid, m.optString("text"), m.optJSONArray("attachments"));
                    break;
                case "interrupt":
                    Engine.interrupt(sid);
                    break;
                case "answer":
                    Engine.answer(sid, m.optString("requestId"), m.optString("decision"), m.optString("message"),
                            m.optJSONObject("updatedInput"));
                    break;
                case "setMode": {
                    String mode = cliMode(m.optString("mode"));
                    JSONObject s = sid.isEmpty() ? null : store.session(sid);
                    if (s != null) {
                        store.update(s, "mode", mode);
                        Engine.setMode(sid, mode);
                        pushSessions();
                    } else {
                        store.setPref("mode", mode);
                        emitPart("prefs", prefsForPage());
                    }
                    break;
                }
                case "setModel": {
                    JSONObject s = sid.isEmpty() ? null : store.session(sid);
                    String model = m.optString("model"), effort = m.optString("effort");
                    if (s != null) {
                        store.update(s, "model", model, "effort", effort);
                        Engine.setModel(sid, model, effort);
                        pushSessions();
                    } else {
                        store.setPref("model", model);
                        store.setPref("effort", effort);
                        emitPart("prefs", prefsForPage());
                    }
                    break;
                }
                case "renameSession": {
                    JSONObject s = store.session(sid);
                    String title = m.optString("title").trim();
                    if (s != null && !title.isEmpty()) store.update(s, "title", title, "titleSet", true);
                    pushSessions();
                    break;
                }
                case "archiveSession": {
                    JSONObject s = store.session(sid);
                    if (s != null) store.update(s, "archived", m.optBoolean("archived", true));
                    pushSessions();
                    break;
                }
                case "deleteSession":
                    Engine.stop(sid);
                    store.removeSession(sid);
                    pushSessions();
                    break;
                case "newProject":
                    reply(m, new Engine.Obj("projectId", newProject(m.optString("name"), m.optString("env"), m.optString("path"))).o, null);
                    emitPart("projects", store.projects());
                    break;
                case "editProject": {
                    JSONObject p = store.project(m.optString("projectId"));
                    if (p != null && !m.optString("name").trim().isEmpty()) store.update(p, "name", m.optString("name").trim());
                    emitPart("projects", store.projects());
                    break;
                }
                case "removeProject":
                    store.removeProject(m.optString("projectId"));
                    emitPart("projects", store.projects());
                    break;
                case "pickAttachment":
                    a.pick(m.optString("kind"), uris -> work.execute(() -> importPicked(m, uris)));
                    break;
                case "dictate":
                    dictate(a, m.optBoolean("on"));
                    break;
                case "search":
                    reply(m, new Engine.Obj("hits", store.search(m.optString("query"))).o, null);
                    break;
                case "listFiles":
                case "readFile":
                case "gitDiff":
                case "pcTest":
                case "customGet":
                case "customSave":
                case "fileIndex":
                case "memoryGet":
                case "memoryRead":
                case "memorySave":
                case "memoryDelete":
                case "memorySet":
                case "exportSession":
                case "openFile":
                    work.execute(() -> slow(m, a));
                    break;
                case "pinSession": {
                    JSONObject s = store.session(sid);
                    if (s != null) store.update(s, "pinned", m.optBoolean("pinned"));
                    pushSessions();
                    break;
                }
                case "forkSession": {
                    JSONObject s = store.forkSession(sid);
                    pushSessions();
                    reply(m, new Engine.Obj("sessionId", s.optString("id")).o, null);
                    break;
                }
                case "planRefresh":
                    Engine.refreshPlan(sid);
                    break;
                case "stopTask":
                    Engine.stopTask(sid, m.optString("taskId"));
                    break;
                case "taskOutput":
                    Engine.taskOutput(sid, m.optString("taskId"), resp -> {
                        if (resp == null) reply(m, null, "读不到输出（Claude Code 没在运行，或者任务已经清掉了）");
                        else reply(m, new Engine.Obj("output", resp.optString("output"), "truncated", resp.optBoolean("truncated")).o, null);
                    });
                    break;
                case "sideQuestion":
                    if (!NetGuard.allowed) throw new IOException("Claude 已断开：" + NetGuard.reason);
                    Engine.sideQuestion(app, sid, m.optString("question"), resp -> {
                        Engine.Run r = Engine.run(sid);
                        if (resp == null) reply(m, null, "没问成：" + (r == null ? "Claude Code 没在运行" : r.lastControlError));
                        else reply(m, new Engine.Obj("answer", resp.optString("response")).o, null);
                    });
                    break;
                case "browser":
                    if (a.browser() != null) a.browser().op(m.optString("op"), m.optString("url"));
                    break;
                case "browserSlot":
                    if (a.browser() != null) a.browser().place(m.optBoolean("visible"), m.optDouble("x"), m.optDouble("y"), m.optDouble("w"), m.optDouble("h"));
                    break;
                case "remoteControl":
                    Engine.remoteControl(app, sid, m.optBoolean("on"));
                    break;
                case "refreshUsage":
                    Engine.refreshUsage();
                    break;
                case "refreshContext":
                    Engine.refreshContext(sid);
                    break;
                case "signIn":
                    a.startActivity(new Intent(a, SignInActivity.class).putExtra("agent", "claude"));
                    break;
                case "installClaude": // progress shows on the page (setup.detail)
                    Account.CLAUDE.install(app);
                    break;
                case "installClaudeFile":
                    a.pick("file", uris -> {
                        if (uris != null && !uris.isEmpty()) Account.CLAUDE.install(app, uris.get(0));
                    });
                    break;
                case "signOut":
                    Engine.stopAll();
                    Account.CLAUDE.logout(app);
                    store.put("acctEmail", "");
                    store.put("acctPlan", "");
                    break;
                case "updateClaude":
                    Engine.stopAll();
                    Account.CLAUDE.update(app);
                    break;
                case "setPref": {
                    String key = m.optString("key");
                    store.setPref(key, m.opt("value"));
                    if ("allowSsid".equals(key)) NetGuard.recheck();
                    if ("outputStyle".equals(key)) { // Claude Code reads it from its own settings file
                        String v = m.optString("value");
                        work.execute(() -> {
                            try {
                                DeskMemory.setSetting(app, "outputStyle", v.isEmpty() ? null : v);
                            } catch (Exception e) {
                                toast(e.getMessage());
                            }
                        });
                    }
                    emitPart("prefs", prefsForPage());
                    break;
                }
                case "egressSet": {
                    boolean on = m.optBoolean("on");
                    String code = m.optString("code").trim();
                    if (on && code.isEmpty()) code = DeskStore.newId("").replaceAll("(.{4})(?=.)", "$1-");
                    if (!code.matches("[A-Za-z0-9-]{0,64}")) throw new IOException("配对码只能有字母、数字和 -");
                    store.put("egressOn", on);
                    store.put("egressPort", Math.max(1025, Math.min(65535, m.optInt("port", 8899))));
                    store.put("egressCode", code);
                    EgressProxy.applySettings(app);
                    emitPart("egress", egress());
                    DeskService.update(app);
                    break;
                }
                case "pcSet": {
                    boolean on = m.optBoolean("on");
                    if (!m.optString("host").trim().isEmpty()) store.put("pcHost", m.optString("host").trim());
                    if (!m.optString("user").trim().isEmpty()) store.put("pcUser", m.optString("user").trim());
                    store.put("pcOn", on);
                    PcLink.status = on ? "connecting" : "off";
                    pushPc();
                    if (on) work.execute(() -> {
                        try {
                            PcLink.ensureKey(app); // the key to show, even before the PC is set up
                            if (PcLink.host(app).isEmpty() || PcLink.user(app).isEmpty()) {
                                PcLink.status = "error";
                                PcLink.detail = "填上电脑的地址和用户名";
                            } else {
                                PcLink.test(app);
                            }
                        } catch (Exception e) {
                            PcLink.status = "error";
                            PcLink.detail = e.getMessage();
                        }
                        pushPc();
                    });
                    break;
                }
                case "openNative":
                    openNative(a, m.optString("screen"), sid);
                    break;
                case "openUrl": {
                    Uri u = Uri.parse(m.optString("url"));
                    if ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) a.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    break;
                }
                case "copy":
                    app.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Piggy Desk", m.optString("text")));
                    break;
                case "toast":
                    Toast.makeText(app, m.optString("text"), Toast.LENGTH_SHORT).show();
                    break;
                default:
                    if (m.has("id")) reply(m, null, "unknown request: " + type);
            }
        } catch (Exception e) {
            if (m.has("id")) reply(m, null, e.getMessage() == null ? e.toString() : e.getMessage());
            else toast(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    void reply(JSONObject req, Object data, String error) {
        JSONObject r = new Engine.Obj("type", "reply", "id", req.opt("id")).o;
        if (error != null) {
            put(r, "ok", false);
            put(r, "error", error);
        } else {
            put(r, "ok", true);
            put(r, "data", data == null ? new JSONObject() : data);
        }
        emit(r);
    }

    // ------------------------------------------------------------------ sessions

    private void openSession(String sid) {
        JSONObject s = store.session(sid);
        if (s == null) return;
        viewing = sid;
        if (s.optBoolean("unread")) {
            store.update(s, "unread", false);
            pushSessions();
        }
        boolean live = Engine.busy(sid);
        JSONArray items = new JSONArray();
        for (JSONObject it : store.items(sid)) {
            if (!live && "tool".equals(it.optString("kind")) && "running".equals(it.optString("status"))) {
                put(it, "status", "error");
                put(it, "output", "（没有完成）");
            }
            items.put(it);
        }
        sessionEvent(sid, new Engine.Obj("type", "transcript", "items", items).o);
        Engine.Run r = Engine.run(sid);
        sessionEvent(sid, envFor(sid, r != null && r.remote));
        JSONObject meta = store.meta(sid);
        JSONObject ctx = meta.optJSONObject("context");
        if (ctx != null && ctx.optLong("used") > 0) sessionEvent(sid, ctx);
        if (meta.has("outputs")) sessionEvent(sid, new Engine.Obj("type", "outputs", "files", meta.optJSONArray("outputs")).o);
        if (meta.has("todos")) sessionEvent(sid, new Engine.Obj("type", "todos", "todos", meta.optJSONArray("todos")).o);
        if (meta.optJSONObject("plan") != null) {
            JSONObject plan = meta.optJSONObject("plan");
            put(plan, "open", false);
            sessionEvent(sid, plan);
        }
        if (meta.has("tasks")) {
            JSONArray tasks = meta.optJSONArray("tasks");
            for (int i = 0; tasks != null && !live && i < tasks.length(); i++) { // a stopped Claude Code took its tasks with it
                JSONObject t = tasks.optJSONObject(i);
                if (t != null && "running".equals(t.optString("status"))) put(t, "status", "stopped");
            }
            sessionEvent(sid, new Engine.Obj("type", "tasks", "tasks", tasks).o);
        }
        sessionEvent(sid, new Engine.Obj("type", "status", "running", live, "phase", live ? "thinking" : "idle",
                "since", r == null ? 0 : r.since).o);
        for (JSONObject p : Engine.pending(sid)) sessionEvent(sid, p);
    }

    private void send(String sid, String text, JSONArray attIds) throws IOException {
        JSONObject s = store.session(sid);
        if (s == null) throw new IOException("会话不存在");
        if (!s.optBoolean("titleSet") && "新会话".equals(s.optString("title")) && !text.trim().isEmpty()) {
            String t = text.trim().split("\n")[0];
            store.update(s, "title", t.length() > 30 ? t.substring(0, 30) + "…" : t);
        }
        store.update(s, "updatedAt", System.currentTimeMillis(), "archived", false);
        JSONArray content = new JSONArray(), shown = new JSONArray();
        StringBuilder notes = new StringBuilder();
        for (int i = 0; attIds != null && i < attIds.length(); i++) {
            String id = attIds.optString(i);
            File f = Attachments.file(app, id);
            if (f == null || !f.exists()) continue;
            boolean image = Attachments.isImage(id);
            shown.put(new Engine.Obj("id", id, "name", Attachments.name(id), "kind", image ? "image" : "file",
                    "thumb", image ? thumb(id) : "").o);
            if (image && f.length() < 4_500_000) {
                content.put(new Engine.Obj("type", "image", "source", new Engine.Obj("type", "base64",
                        "media_type", mediaType(id), "data", base64(f)).o).o);
            } else {
                // a file: copied into Linux so Claude can open it with Read
                File dst = new File(Linux.root(app), "root/.desk/att/" + id);
                dst.getParentFile().mkdirs();
                copy(f, dst);
                notes.append("\n[附件：/root/.desk/att/").append(id).append("（").append(Attachments.name(id)).append("）]");
            }
        }
        String full = text + notes;
        if (!full.trim().isEmpty()) content.put(new Engine.Obj("type", "text", "text", full).o);
        if (content.length() == 0) return;
        JSONObject it = new Engine.Obj("id", "u" + System.currentTimeMillis(), "kind", "user", "text", text, "attachments", shown).o;
        store.putItem(sid, it);
        sessionEvent(sid, new Engine.Obj("type", "item", "item", it).o);
        pushSessions();
        Engine.send(app, sid, content);
    }

    private String newProject(String name, String env, String path) throws Exception {
        name = name.trim();
        if (name.isEmpty()) throw new IOException("项目要有名字");
        if (DeskStore.PC.equals(env)) {
            path = path.trim();
            if (!path.matches("^[A-Za-z]:[\\\\/].*")) throw new IOException("电脑上的文件夹要写完整路径，例如 C:\\Users\\bzy15\\Desktop\\cell");
        } else {
            if (path.trim().isEmpty()) {
                String slug = name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
                if (slug.isEmpty()) slug = "project";
                String base = DeskStore.PROJECTS_DIR + "/" + slug;
                path = base;
                for (int n = 2; new File(Linux.root(app), path.substring(1)).exists(); n++) path = base + "-" + n;
            } else {
                path = path.trim();
                if (!path.startsWith("/")) path = DeskStore.PROJECTS_DIR + "/" + path;
            }
            new File(Linux.root(app), path.substring(1)).mkdirs();
        }
        return store.addProject(name, env, path).optString("id");
    }

    // ------------------------------------------------------------------ slow requests (worker threads)

    private void slow(JSONObject m, DeskActivity a) {
        try {
            String type = m.optString("type");
            if (type.startsWith("memory")) {
                JSONObject p = store.project(m.optString("projectId"));
                if (p == null) throw new IOException("项目不存在");
                if ("memoryGet".equals(type)) reply(m, DeskMemory.get(app, p), null);
                else if ("memoryRead".equals(type)) reply(m, new Engine.Obj("text", DeskMemory.read(app, p, m.optString("where"), m.optString("path"))).o, null);
                else if ("memorySave".equals(type)) {
                    DeskMemory.save(app, p, m.optString("where"), m.optString("path"), m.optString("text"));
                    reply(m, new JSONObject(), null);
                } else if ("memoryDelete".equals(type)) {
                    DeskMemory.delete(app, p, m.optString("path"));
                    reply(m, new JSONObject(), null);
                } else if ("memorySet".equals(type)) {
                    if (m.has("autoMemoryEnabled")) DeskMemory.setSetting(app, "autoMemoryEnabled", m.optBoolean("autoMemoryEnabled") ? null : false);
                    if (m.has("autoDreamEnabled")) DeskMemory.setSetting(app, "autoDreamEnabled", m.optBoolean("autoDreamEnabled"));
                    reply(m, new JSONObject(), null);
                }
                return;
            }
            if ("exportSession".equals(type)) {
                reply(m, new Engine.Obj("where", exportSession(m.optString("sessionId"))).o, null);
                return;
            }
            if ("fileIndex".equals(type) && m.optString("sessionId").isEmpty()) {
                JSONObject p = store.project(m.optString("projectId"));
                if (p == null) throw new IOException("项目不存在");
                reply(m, new Engine.Obj("files", DeskFiles.index(app, p)).o, null);
                return;
            }
            if ("pcTest".equals(type)) {
                reply(m, PcLink.test(app), null);
                return;
            }
            if ("customGet".equals(type)) {
                reply(m, customGet(), null);
                return;
            }
            if ("customSave".equals(type)) {
                File f = new File(Linux.root(app), "root/.claude/CLAUDE.md");
                f.getParentFile().mkdirs();
                DeskStore.write(f, m.optString("claudeMd"));
                reply(m, new JSONObject(), null);
                return;
            }
            JSONObject s = store.session(m.optString("sessionId"));
            JSONObject p = s == null ? null : store.project(s.optString("projectId"));
            if (p == null) throw new IOException("没有打开的项目");
            boolean onPc = DeskStore.PC.equals(p.optString("env"));
            String root = p.optString("path");
            if ("listFiles".equals(type)) reply(m, onPc ? pcList(root, m.optString("path")) : tabletList(root, m.optString("path")), null);
            else if ("readFile".equals(type)) reply(m, DeskFiles.read(app, p, m.optString("path"), m.optString("where")), null);
            else if ("gitDiff".equals(type)) reply(m, onPc ? pcDiff(root) : tabletDiff(root), null);
            else if ("fileIndex".equals(type)) reply(m, new Engine.Obj("files", DeskFiles.index(app, p)).o, null);
            else if ("openFile".equals(type)) {
                JSONObject r = DeskFiles.read(app, p, m.optString("path"), m.optString("where"));
                File f = DeskFiles.file(r.optString("token"));
                if (f != null) main.post(() -> DeskFiles.openWith(a, f));
            }
        } catch (Exception e) {
            reply(m, null, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private File inLinux(String root, String path) throws IOException {
        String full = path.startsWith("/") ? path : root + (path.isEmpty() ? "" : "/" + path);
        if (full.contains("/../") || full.endsWith("/..")) throw new IOException("路径不对");
        return new File(Linux.root(app), full.substring(1));
    }

    private JSONObject tabletList(String root, String path) throws IOException {
        File d = inLinux(root, path);
        File[] list = d.listFiles();
        if (list == null) throw new IOException("打不开 " + path);
        Arrays.sort(list, (x, y) -> x.isDirectory() != y.isDirectory() ? (x.isDirectory() ? -1 : 1)
                : x.getName().compareToIgnoreCase(y.getName()));
        JSONArray e = new JSONArray();
        for (File f : list) {
            if (e.length() >= 500) break;
            e.put(new Engine.Obj("name", f.getName(), "dir", f.isDirectory(), "size", f.isDirectory() ? JSONObject.NULL : f.length()).o);
        }
        return new Engine.Obj("entries", e).o;
    }

    private JSONObject tabletRead(String root, String path) throws IOException {
        File f = inLinux(root, path);
        if (!f.isFile()) throw new IOException("不是文件：" + path);
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.matches(".*\\.(png|jpe?g|gif|webp)$")) {
            if (f.length() > 8_000_000) throw new IOException("图片太大");
            return new Engine.Obj("image", "data:" + mediaType(n) + ";base64," + base64(f)).o;
        }
        byte[] data = new byte[(int) Math.min(f.length(), 300_000)];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, r;
            while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) off += r;
        }
        String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (f.length() > data.length) text += "\n…（文件太长，只显示开头）";
        return new Engine.Obj("text", text).o;
    }

    private JSONObject tabletDiff(String root) throws Exception {
        String out = Linux.exec(app, "cd " + PcLink.shq(root) + " 2>/dev/null || exit 0; git rev-parse --abbrev-ref HEAD 2>/dev/null;"
                + " echo __NEW__; git ls-files --others --exclude-standard 2>/dev/null | head -200;"
                + " echo __DIFF__; git --no-pager diff HEAD --no-color 2>/dev/null | head -c 400000", 60);
        return splitDiff(out.replaceAll("\\n\\[exit \\d+\\]\\s*$", ""));
    }

    private JSONObject pcList(String root, String path) throws Exception {
        String dir = path.isEmpty() ? root : root.replaceAll("[\\\\/]+$", "") + "\\" + path.replace('/', '\\');
        JSONObject r = PcLink.call(app, root, "ls", new Engine.Obj("path", dir).o);
        String[] lines = PcLink.text(r).split("\n");
        JSONArray e = new JSONArray();
        for (int i = 1; i < lines.length; i++) { // the first line is the folder itself
            String l = lines[i];
            if (l.startsWith("（") || l.startsWith("…")) continue;
            boolean isDir = l.endsWith("/");
            String name = isDir ? l.substring(0, l.length() - 1) : l.replaceAll("  \\(.*\\)$", "");
            e.put(new Engine.Obj("name", name, "dir", isDir, "size", JSONObject.NULL).o);
        }
        return new Engine.Obj("entries", e).o;
    }

    private JSONObject pcRead(String root, String path) throws Exception {
        boolean abs = path.matches("^[A-Za-z]:[\\\\/].*");
        String file = abs ? path : root.replaceAll("[\\\\/]+$", "") + "\\" + path.replace('/', '\\');
        JSONObject r = PcLink.call(app, root, "read", new Engine.Obj("file_path", file, "limit", 3000).o);
        JSONArray content = r == null ? null : r.optJSONArray("content");
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject x = content.optJSONObject(i);
            if (x != null && "image".equals(x.optString("type"))) {
                return new Engine.Obj("image", "data:" + x.optString("mimeType") + ";base64," + x.optString("data")).o;
            }
        }
        if (r != null && r.optBoolean("isError")) throw new IOException(PcLink.text(r));
        return new Engine.Obj("text", PcLink.text(r).replaceAll("(?m)^ *\\d+\\t", "")).o;
    }

    private JSONObject pcDiff(String root) throws Exception {
        JSONObject r = PcLink.call(app, root, "bash", new Engine.Obj("command",
                "git rev-parse --abbrev-ref HEAD 2>/dev/null; echo __NEW__; git ls-files --others --exclude-standard 2>/dev/null | head -200;"
                        + " echo __DIFF__; git --no-pager diff HEAD --no-color 2>/dev/null | head -c 400000").o);
        return splitDiff(PcLink.text(r));
    }

    /** "branch __NEW__ untracked files __DIFF__ the diff" → {branch, untracked, diff}. */
    private static JSONObject splitDiff(String out) {
        int n = out.indexOf("__NEW__"), d = out.indexOf("__DIFF__");
        JSONArray untracked = new JSONArray();
        if (n >= 0 && d > n) for (String l : out.substring(n + 7, d).split("\n")) if (!l.trim().isEmpty()) untracked.put(l.trim());
        return new Engine.Obj("branch", n > 0 ? out.substring(0, n).trim() : "", "untracked", untracked,
                "diff", d >= 0 ? out.substring(d + 8).trim() : "").o;
    }

    /** The conversation as Markdown, saved to Downloads/Piggy Desk/. Returns where, for the page to say. */
    private String exportSession(String sid) throws Exception {
        JSONObject s = store.session(sid);
        if (s == null) throw new IOException("会话不存在");
        StringBuilder b = new StringBuilder("# ").append(s.optString("title")).append("\n\n");
        for (JSONObject it : store.items(sid)) {
            if (it.has("parentId")) continue;
            switch (it.optString("kind")) {
                case "user":
                    b.append("## 你\n\n").append(it.optString("text")).append("\n\n");
                    break;
                case "assistant":
                    b.append("## Claude\n\n").append(it.optString("text")).append("\n\n");
                    break;
                case "tool": {
                    JSONObject in = it.optJSONObject("input");
                    String arg = in == null ? "" : in.optString("command", in.optString("file_path", in.optString("url", in.optString("pattern", ""))));
                    b.append("> 🔧 ").append(it.optString("name")).append(arg.isEmpty() ? "" : "：`" + arg.split("\n")[0] + "`").append("\n\n");
                    break;
                }
                case "system":
                case "compact":
                    b.append("> ").append(it.optString("text")).append("\n\n");
                    break;
                default:
            }
        }
        String name = s.optString("title").replaceAll("[\\\\/:*?\"<>|\\n]", " ").trim();
        if (name.length() > 60) name = name.substring(0, 60);
        name = (name.isEmpty() ? "会话" : name) + " " + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new java.util.Date()) + ".md";
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/markdown");
        v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Piggy Desk");
        Uri uri = app.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("存不了文件");
        try (OutputStream o = app.getContentResolver().openOutputStream(uri)) {
            if (o == null) throw new IOException("存不了文件");
            o.write(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return "下载/Piggy Desk/" + name;
    }

    /** 自定义: the tablet's ~/.claude (memory, skills, agents, commands) and its MCP servers. */
    private JSONObject customGet() {
        File home = new File(Linux.root(app), "root/.claude");
        String md = "";
        try {
            md = DeskStore.read(new File(home, "CLAUDE.md"));
        } catch (IOException ignored) {
        }
        JSONArray skills = new JSONArray(), agents = new JSONArray(), commands = new JSONArray(), mcp = new JSONArray();
        File[] sk = new File(home, "skills").listFiles();
        for (int i = 0; sk != null && i < sk.length; i++) {
            File f = new File(sk[i], "SKILL.md");
            if (f.isFile()) skills.put(new Engine.Obj("name", sk[i].getName(), "description", frontmatter(f, "description")).o);
        }
        listMd(new File(home, "agents"), agents);
        listMd(new File(home, "commands"), commands);
        try {
            JSONObject cfg = new JSONObject(DeskStore.read(new File(Linux.root(app), "root/.claude.json")));
            JSONObject servers = cfg.optJSONObject("mcpServers");
            JSONArray names = servers == null ? null : servers.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                JSONObject sv = servers.optJSONObject(names.optString(i));
                mcp.put(new Engine.Obj("name", names.optString(i), "type", sv == null ? "" : sv.optString("type", "stdio")).o);
            }
        } catch (Exception ignored) {
        }
        return new Engine.Obj("claudeMd", md, "skills", skills, "agents", agents, "commands", commands, "mcpServers", mcp).o;
    }

    private static void listMd(File dir, JSONArray out) {
        File[] fs = dir.listFiles();
        for (int i = 0; fs != null && i < fs.length; i++) {
            if (fs[i].isFile() && fs[i].getName().endsWith(".md")) {
                out.put(new Engine.Obj("name", fs[i].getName().replaceAll("\\.md$", ""), "description", frontmatter(fs[i], "description")).o);
            }
        }
    }

    private static String frontmatter(File f, String key) {
        try {
            String s = DeskStore.read(f);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^" + key + ":\\s*(.+)$").matcher(s);
            return m.find() ? m.group(1).trim().replaceAll("^[\"']|[\"']$", "") : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ attachments, voice, native screens

    private void importPicked(JSONObject m, List<Uri> uris) {
        if (uris == null) {
            reply(m, null, "cancelled");
            return;
        }
        JSONArray out = new JSONArray();
        String error = null;
        for (Uri u : uris) {
            try {
                String id = Attachments.importUri(app, u);
                boolean image = Attachments.isImage(id);
                out.put(new Engine.Obj("id", id, "name", Attachments.name(id), "kind", image ? "image" : "file",
                        "thumb", image ? thumb(id) : "").o);
            } catch (Exception e) {
                error = e.getMessage();
            }
        }
        if (out.length() == 0 && error != null) reply(m, null, error);
        else reply(m, new Engine.Obj("attachments", out).o, null);
    }

    private String thumb(String id) {
        try {
            Bitmap b = Attachments.thumb(app, id, 160);
            if (b == null) return "";
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 80, o);
            return "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private void dictate(DeskActivity a, boolean on) {
        if (!on) {
            if (dictating) Voice.finishListening();
            return;
        }
        if (!Voice.micAllowed(a)) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, DeskActivity.REQ_MIC);
            emit(new Engine.Obj("type", "dictation", "text", "", "final", true, "done", true).o);
            return;
        }
        String err = Voice.listen(a, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                emit(new Engine.Obj("type", "dictation", "text", text, "final", false).o);
            }

            @Override
            public void onFinal(String text) {
                dictating = false;
                emit(new Engine.Obj("type", "dictation", "text", text, "final", true, "done", true).o);
            }

            @Override
            public void onEnd(String message) {
                dictating = false;
                emit(new Engine.Obj("type", "dictation", "text", "", "final", true, "done", true).o);
                if (message != null) toast(message);
            }
        });
        dictating = err == null;
        if (err != null) {
            toast(err);
            emit(new Engine.Obj("type", "dictation", "text", "", "final", true, "done", true).o);
        }
    }

    private void openNative(DeskActivity a, String screen, String sid) {
        switch (screen) {
            case "terminal": {
                Intent i = new Intent(a, TerminalActivity.class);
                JSONObject s = sid.isEmpty() ? null : store.session(sid);
                JSONObject p = s == null ? null : store.project(s.optString("projectId"));
                if (p != null && DeskStore.TABLET.equals(p.optString("env"))) {
                    i.putExtra("run", "cd " + PcLink.shq(p.optString("path")) + " && exec bash -l");
                } else if (p != null && PcLink.on(app)) {
                    i.putExtra("run", PcLink.terminal(app));
                }
                a.startActivity(i);
                break;
            }
            case "bubble":
                a.startActivity(new Intent(a, SetupActivity.class));
                break;
            case "apps":
                a.startActivity(new Intent(a, AppPermissionsActivity.class));
                break;
            case "rules":
            case "schedules":
                a.startActivity(new Intent(a, RulesActivity.class));
                break;
            case "update":
                Updates.showSheet(a);
                break;
            case "location":
                if (!NetGuard.locationAllowed(a)) {
                    a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                            DeskActivity.REQ_LOCATION);
                } else if (!NetGuard.locationOn(a)) {
                    a.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } else {
                    NetGuard.recheck();
                }
                break;
            default:
        }
    }

    /** Back on the page (from a native screen): look again at what may have changed. */
    void onPageResumed() {
        NetGuard.recheck();
        if (!Account.CLAUDE.busy()) Account.CLAUDE.refresh(app);
        emitPart("egress", egress());
    }

    // ------------------------------------------------------------------ helpers

    static JSONArray json(String s) {
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static JSONObject jsonObj(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static long isoMillis(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                return java.time.Instant.parse(s).toEpochMilli();
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private static String mediaType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") ? "image/png" : n.endsWith(".gif") ? "image/gif" : n.endsWith(".webp") ? "image/webp" : "image/jpeg";
    }

    private static String base64(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** For Activity callbacks that pick files: the chosen URIs, or null if cancelled. */
    interface Picked {
        void done(List<Uri> uris);
    }

    static List<Uri> uris(Intent data, Uri fallback) {
        List<Uri> out = new ArrayList<>();
        if (data != null && data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) out.add(data.getClipData().getItemAt(i).getUri());
        } else if (data != null && data.getData() != null) {
            out.add(data.getData());
        } else if (fallback != null) {
            out.add(fallback);
        }
        return out;
    }
}
