package app.piggy.desk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the workspace keeps: projects, sessions (files/desk/state.json), each session's conversation as the page shows it
 * (files/desk/t/&lt;id&gt;.jsonl, one item per line, a later line with the same id replacing the earlier one) and its side
 * panel (…/&lt;id&gt;.meta.json), plus the settings (shared preferences "desk"). Thread-safe.
 */
final class DeskStore {
    static final String TABLET = "tablet", PC = "pc";
    /** Where tablet projects live inside Linux, and the empty working folders for PC projects. */
    static final String PROJECTS_DIR = "/root/projects", PC_DIR = "/root/.desk/pc";

    private static DeskStore instance;
    private final Context app;
    private final File dir, tdir;
    private final List<JSONObject> projects = new ArrayList<>();
    private final List<JSONObject> sessions = new ArrayList<>();

    static synchronized DeskStore get(Context c) {
        if (instance == null) instance = new DeskStore(c.getApplicationContext());
        return instance;
    }

    private DeskStore(Context c) {
        app = c;
        dir = new File(c.getFilesDir(), "desk");
        tdir = new File(dir, "t");
        tdir.mkdirs();
        try {
            JSONObject s = new JSONObject(read(new File(dir, "state.json")));
            JSONArray p = s.optJSONArray("projects"), ss = s.optJSONArray("sessions");
            for (int i = 0; p != null && i < p.length(); i++) projects.add(p.getJSONObject(i));
            for (int i = 0; ss != null && i < ss.length(); i++) sessions.add(ss.getJSONObject(i));
        } catch (Exception ignored) { // first run
        }
        if (projects.isEmpty()) {
            try {
                projects.add(new JSONObject().put("id", newId("p")).put("name", "默认").put("env", TABLET)
                        .put("path", PROJECTS_DIR + "/default"));
            } catch (JSONException ignored) {
            }
            save();
        }
    }

    // ------------------------------------------------------------------ projects and sessions

    synchronized JSONArray projects() {
        return new JSONArray(projects);
    }

    synchronized JSONArray sessions() {
        return new JSONArray(sessions);
    }

    synchronized JSONObject project(String id) {
        for (JSONObject p : projects) if (p.optString("id").equals(id)) return p;
        return null;
    }

    synchronized JSONObject session(String id) {
        for (JSONObject s : sessions) if (s.optString("id").equals(id)) return s;
        return null;
    }

    synchronized JSONObject addProject(String name, String env, String path) throws JSONException {
        JSONObject p = new JSONObject().put("id", newId("p")).put("name", name).put("env", PC.equals(env) ? PC : TABLET)
                .put("path", path);
        projects.add(p);
        save();
        return p;
    }

    synchronized void removeProject(String id) {
        for (int i = projects.size() - 1; i >= 0; i--) if (projects.get(i).optString("id").equals(id)) projects.remove(i);
        save();
    }

    synchronized JSONObject addSession(String projectId, JSONObject prefs) throws JSONException {
        long now = System.currentTimeMillis();
        JSONObject s = new JSONObject().put("id", newId("s")).put("projectId", projectId).put("title", "新会话")
                .put("createdAt", now).put("updatedAt", now).put("model", prefs.optString("model"))
                .put("effort", prefs.optString("effort")).put("mode", prefs.optString("mode")).put("archived", false);
        sessions.add(0, s);
        save();
        return s;
    }

    synchronized void removeSession(String id) {
        for (int i = sessions.size() - 1; i >= 0; i--) if (sessions.get(i).optString("id").equals(id)) sessions.remove(i);
        new File(tdir, id + ".jsonl").delete();
        new File(tdir, id + ".meta.json").delete();
        save();
    }

    /** Sets fields of a session (or project) record and saves. */
    synchronized void update(JSONObject record, Object... kv) {
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) record.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        save();
    }

    synchronized void save() {
        try {
            JSONObject s = new JSONObject().put("projects", new JSONArray(projects)).put("sessions", new JSONArray(sessions));
            write(new File(dir, "state.json"), s.toString());
        } catch (Exception e) {
            android.util.Log.w("PiggyDesk", "state not saved", e);
        }
    }

    // ------------------------------------------------------------------ conversations

    /** Adds an item to a session's conversation, or replaces the one with the same id. */
    synchronized void putItem(String sid, JSONObject item) {
        try (OutputStream o = new FileOutputStream(new File(tdir, sid + ".jsonl"), true)) {
            o.write((item.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            android.util.Log.w("PiggyDesk", "item not saved", e);
        }
    }

    /** The conversation in order of first appearance, each item as last written. */
    synchronized List<JSONObject> items(String sid) {
        Map<String, JSONObject> m = new LinkedHashMap<>();
        File f = new File(tdir, sid + ".jsonl");
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject it = new JSONObject(line);
                        m.put(it.optString("id"), it);
                    } catch (JSONException ignored) { // a line cut short by a crash
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return new ArrayList<>(m.values());
    }

    /** Rewrites a conversation file without its superseded lines once it has grown (called when a session closes). */
    synchronized void compact(String sid) {
        File f = new File(tdir, sid + ".jsonl");
        if (!f.exists() || f.length() < 256 * 1024) return;
        StringBuilder b = new StringBuilder();
        for (JSONObject it : items(sid)) b.append(it).append('\n');
        try {
            write(f, b.toString());
        } catch (IOException ignored) {
        }
    }

    /** The session's side panel: outputs, todos, context, env. */
    synchronized JSONObject meta(String sid) {
        try {
            return new JSONObject(read(new File(tdir, sid + ".meta.json")));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    synchronized void putMeta(String sid, String key, Object value) {
        JSONObject m = meta(sid);
        try {
            m.put(key, value);
            write(new File(tdir, sid + ".meta.json"), m.toString());
        } catch (Exception ignored) {
        }
    }

    /** Sessions whose saved conversation contains the text (case-insensitive), with a snippet. */
    synchronized JSONArray search(String query) {
        JSONArray hits = new JSONArray();
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (JSONObject s : sessions) {
            File f = new File(tdir, s.optString("id") + ".jsonl");
            if (!f.exists()) continue;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JSONObject it;
                    try {
                        it = new JSONObject(line);
                    } catch (JSONException e) {
                        continue;
                    }
                    String text = it.optString("text");
                    int at = text.toLowerCase(java.util.Locale.ROOT).indexOf(q);
                    if (at < 0) continue;
                    int from = Math.max(0, at - 30);
                    String snip = (from > 0 ? "…" : "") + text.substring(from, Math.min(text.length(), at + q.length() + 50))
                            .replace('\n', ' ');
                    hits.put(new JSONObject().put("sessionId", s.optString("id")).put("title", s.optString("title"))
                            .put("snippet", snip));
                    break;
                }
            } catch (Exception ignored) {
            }
            if (hits.length() >= 30) break;
        }
        return hits;
    }

    // ------------------------------------------------------------------ settings

    private SharedPreferences sp() {
        return app.getSharedPreferences("desk", Context.MODE_PRIVATE);
    }

    /** The page's preferences: model, effort, mode, theme, fontSize, allowSsid. */
    JSONObject prefs() {
        SharedPreferences p = sp();
        JSONObject o = new JSONObject();
        try {
            o.put("model", p.getString("model", "opus")).put("effort", p.getString("effort", "high"))
                    .put("mode", p.getString("mode", "manual")).put("theme", p.getString("theme", "dark"))
                    .put("fontSize", p.getInt("fontSize", 15)).put("allowSsid", new JSONArray(allowSsid()));
        } catch (JSONException ignored) {
        }
        return o;
    }

    void setPref(String key, Object value) {
        SharedPreferences.Editor e = sp().edit();
        if ("fontSize".equals(key)) e.putInt(key, value instanceof Number ? ((Number) value).intValue() : 15);
        else if ("allowSsid".equals(key)) e.putString(key, value instanceof JSONArray ? value.toString() : "[]");
        else if ("model".equals(key) || "effort".equals(key) || "mode".equals(key) || "theme".equals(key)) {
            e.putString(key, String.valueOf(value));
        }
        e.apply();
    }

    /** Wi-Fi names on which Claude may connect: the phone's hotspot. */
    List<String> allowSsid() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp().getString("allowSsid", "[\"MIX 4\"]"));
            for (int i = 0; i < a.length(); i++) if (!a.optString(i).trim().isEmpty()) out.add(a.optString(i).trim());
        } catch (JSONException ignored) {
        }
        return out;
    }

    String str(String key, String def) {
        return sp().getString(key, def);
    }

    boolean bool(String key, boolean def) {
        return sp().getBoolean(key, def);
    }

    int num(String key, int def) {
        return sp().getInt(key, def);
    }

    void put(String key, Object value) {
        SharedPreferences.Editor e = sp().edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else e.putString(key, value == null ? null : String.valueOf(value));
        e.apply();
    }

    // ------------------------------------------------------------------ helpers

    static String newId(String prefix) {
        byte[] r = new byte[6];
        new SecureRandom().nextBytes(r);
        StringBuilder b = new StringBuilder(prefix);
        for (byte x : r) b.append(String.format("%02x", x));
        return b.toString();
    }

    static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("can't replace " + f);
        }
    }
}
