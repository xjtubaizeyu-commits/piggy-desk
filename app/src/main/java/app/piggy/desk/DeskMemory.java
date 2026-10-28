package app.piggy.desk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude Code's memory, level by level, for the 记忆 page and the side panel. Instructions it reads at the start of
 * every session, broad to specific: the organisation's policy (/etc/claude-code/CLAUDE.md), the user's own
 * (~/.claude/CLAUDE.md), the project's (CLAUDE.md, .claude/CLAUDE.md) and the project's local file (CLAUDE.local.md,
 * not in git). For a project on the PC the PC's files are added to the system prompt by PcLink, so they are listed
 * here too. And Claude Code's auto-memory: small typed notes (user, feedback, project, reference) with an index,
 * MEMORY.md, under ~/.claude/projects/&lt;the working folder, with every other character a dash&gt;/memory/.
 */
final class DeskMemory {
    static final String USER_MD = "/root/.claude/CLAUDE.md", POLICY_MD = "/etc/claude-code/CLAUDE.md", SETTINGS = "/root/.claude/settings.json";
    private static final Pattern FRONT = Pattern.compile("\\A---\\s*\\n([\\s\\S]*?)\\n---");

    private DeskMemory() {
    }

    /** The folder Claude Code runs in for a project (a PC project runs in an empty folder on the tablet). */
    static String cwd(JSONObject project) {
        return DeskStore.PC.equals(project.optString("env")) ? DeskStore.PC_DIR + "/" + project.optString("id") : project.optString("path");
    }

    static String autoDir(JSONObject project) {
        return "/root/.claude/projects/" + cwd(project).replaceAll("[^A-Za-z0-9]", "-") + "/memory";
    }

    static JSONObject get(Context c, JSONObject project) throws Exception {
        boolean pc = DeskStore.PC.equals(project.optString("env"));
        String root = project.optString("path");
        JSONArray levels = new JSONArray();
        levels.put(level("policy", "组织策略", "管理员统一下发的规则，这里只能看", "tablet", POLICY_MD, exists(c, POLICY_MD), true));
        levels.put(level("user", "全局（这台平板）", "所有项目都会读：你的习惯、偏好、常用规则", "tablet", USER_MD, exists(c, USER_MD), false));
        if (pc) {
            String home = "C:\\Users\\" + PcLink.user(c);
            String[] paths = {home + "\\.claude\\CLAUDE.md", root + "\\CLAUDE.md", root + "\\.claude\\CLAUDE.md", root + "\\CLAUDE.local.md"};
            boolean[] have = pcExists(c, root, paths);
            levels.put(level("pcUser", "电脑全局", "电脑上的 ~/.claude/CLAUDE.md，连电脑的项目都会带上", "pc", paths[0], have[0], false));
            levels.put(level("project", "项目", "跟着项目走，可以提交进 git，和别人共享", "pc", paths[1], have[1], false));
            levels.put(level("project-alt", "项目（.claude 文件夹）", "放在项目 .claude 文件夹里的说明，作用同上", "pc", paths[2], have[2], false));
            levels.put(level("local", "本地（不进 git）", "只给自己的项目说明，别提交", "pc", paths[3], have[3], false));
        } else {
            levels.put(level("project", "项目", "跟着项目走，可以提交进 git，和别人共享", "tablet", root + "/CLAUDE.md", exists(c, root + "/CLAUDE.md"), false));
            levels.put(level("project-alt", "项目（.claude 文件夹）", "放在项目 .claude 文件夹里的说明，作用同上", "tablet", root + "/.claude/CLAUDE.md",
                    exists(c, root + "/.claude/CLAUDE.md"), false));
            levels.put(level("local", "本地（不进 git）", "只给自己的项目说明，Claude Code 会把它加进 .gitignore", "tablet", root + "/CLAUDE.local.md",
                    exists(c, root + "/CLAUDE.local.md"), false));
        }

        String dir = autoDir(project);
        JSONObject settings = settings(c);
        JSONArray memories = new JSONArray();
        File d = file(c, dir);
        File[] fs = d.listFiles();
        if (fs != null) {
            Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            for (File f : fs) {
                if (f.isDirectory()) {
                    File[] inner = f.listFiles();
                    for (int i = 0; inner != null && i < inner.length; i++) if (isNote(inner[i])) memories.put(note(inner[i], dir + "/" + f.getName()));
                } else if (isNote(f)) {
                    memories.put(note(f, dir));
                }
            }
        }
        JSONObject auto = new Engine.Obj("enabled", settings.optBoolean("autoMemoryEnabled", true),
                "dream", settings.optBoolean("autoDreamEnabled", false), "dir", dir, "memories", memories).o;
        if (new File(d, "MEMORY.md").isFile()) Engine.put(auto, "index", dir + "/MEMORY.md");
        return new Engine.Obj("levels", levels, "auto", auto).o;
    }

    private static boolean isNote(File f) {
        return f.isFile() && f.getName().endsWith(".md") && !f.getName().equals("MEMORY.md");
    }

    private static JSONObject note(File f, String dir) {
        String text = "";
        try {
            text = DeskStore.read(f);
        } catch (IOException ignored) {
        }
        String fm = "";
        Matcher m = FRONT.matcher(text);
        if (m.find()) fm = m.group(1);
        String title = field(fm, "name");
        if (title.isEmpty()) {
            Matcher h = Pattern.compile("(?m)^#\\s+(.+)$").matcher(text);
            title = h.find() ? h.group(1).trim() : f.getName();
        }
        String type = field(fm, "type");
        return new Engine.Obj("name", f.getName(), "title", title, "path", dir + "/" + f.getName(), "description", field(fm, "description"),
                "type", type.matches("user|feedback|project|reference") ? type : "", "modified", f.lastModified(), "size", f.length()).o;
    }

    private static String field(String fm, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + ":\\s*(.+)$").matcher(fm);
        return m.find() ? m.group(1).trim().replaceAll("^[\"']|[\"']$", "") : "";
    }

    private static JSONObject level(String kind, String label, String desc, String where, String path, boolean exists, boolean readOnly) {
        return new Engine.Obj("kind", kind, "label", label, "desc", desc, "where", where, "path", path, "exists", exists, "readOnly", readOnly).o;
    }

    // ------------------------------------------------------------------ reading and writing

    static String read(Context c, JSONObject project, String where, String path) throws Exception {
        check(project, where, path, false);
        if ("pc".equals(where)) {
            JSONObject r = PcLink.call(c, project.optString("path"), "powershell", new Engine.Obj("command",
                    "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-Content -LiteralPath '" + path.replace("'", "''") + "' -Raw -Encoding UTF8").o);
            if (r.optBoolean("isError")) throw new IOException(PcLink.text(r));
            return PcLink.text(r).replaceAll("\\n?\\[exit \\d+\\]\\s*$", "");
        }
        File f = file(c, path);
        return f.isFile() ? DeskStore.read(f) : "";
    }

    static void save(Context c, JSONObject project, String where, String path, String text) throws Exception {
        check(project, where, path, true);
        if ("pc".equals(where)) {
            JSONObject r = PcLink.call(c, project.optString("path"), "write", new Engine.Obj("file_path", path, "content", text).o);
            if (r.optBoolean("isError")) throw new IOException(PcLink.text(r));
            return;
        }
        File f = file(c, path);
        f.getParentFile().mkdirs();
        DeskStore.write(f, text);
    }

    /** Deletes one auto-memory note, and its line in the MEMORY.md index. */
    static void delete(Context c, JSONObject project, String path) throws Exception {
        String dir = autoDir(project);
        if (!path.startsWith(dir + "/") || path.contains("/../") || path.endsWith("/MEMORY.md")) throw new IOException("只能删自动记忆里的笔记");
        File f = file(c, path);
        if (!f.delete() && f.exists()) throw new IOException("删不掉 " + f.getName());
        File index = file(c, dir + "/MEMORY.md");
        if (index.isFile()) {
            String name = f.getName();
            StringBuilder b = new StringBuilder();
            for (String line : DeskStore.read(index).split("\n", -1)) {
                if (!line.contains("(" + name + ")") && !line.contains("[[" + name.replaceAll("\\.md$", "") + "]]")) b.append(line).append('\n');
            }
            DeskStore.write(index, b.toString().replaceAll("\\n+$", "\n"));
        }
    }

    /** Only instruction files and auto-memory notes, never anything else on the tablet or the PC. */
    private static void check(JSONObject project, String where, String path, boolean write) throws IOException {
        String p = path.replace('\\', '/');
        boolean instructions = p.endsWith("/CLAUDE.md") || p.endsWith("/CLAUDE.local.md");
        boolean note = !"pc".equals(where) && p.startsWith(autoDir(project) + "/") && p.endsWith(".md");
        boolean plan = !"pc".equals(where) && !write && p.startsWith("/root/.claude/plans/");
        if (p.contains("/../") || !(instructions || note || plan)) throw new IOException("这里只能看和改记忆文件");
        if (write && POLICY_MD.equals(p)) throw new IOException("组织策略只能看，不能改");
    }

    // ------------------------------------------------------------------ Claude Code's settings.json

    static JSONObject settings(Context c) {
        try {
            return new JSONObject(DeskStore.read(file(c, SETTINGS)));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Sets (or with null removes) one key of the tablet's ~/.claude/settings.json, keeping everything else. */
    static synchronized void setSetting(Context c, String key, Object value) throws Exception {
        File f = file(c, SETTINGS);
        JSONObject s;
        try {
            s = new JSONObject(DeskStore.read(f));
        } catch (Exception e) {
            if (f.isFile() && f.length() > 0) throw new IOException("~/.claude/settings.json 格式不对，先修好它");
            s = new JSONObject();
        }
        if (value == null || "".equals(value)) s.remove(key);
        else s.put(key, value);
        f.getParentFile().mkdirs();
        DeskStore.write(f, s.toString(2));
    }

    // ------------------------------------------------------------------ helpers

    private static File file(Context c, String linuxPath) {
        return new File(Linux.root(c), linuxPath.substring(1));
    }

    private static boolean exists(Context c, String linuxPath) {
        return file(c, linuxPath).isFile();
    }

    private static boolean[] pcExists(Context c, String root, String[] paths) {
        boolean[] out = new boolean[paths.length];
        try {
            StringBuilder cmd = new StringBuilder();
            for (String p : paths) cmd.append("Test-Path -LiteralPath '").append(p.replace("'", "''")).append("' -PathType Leaf; ");
            String[] lines = PcLink.text(PcLink.call(c, root, "powershell", new Engine.Obj("command", cmd.toString()).o)).trim().split("\\s+");
            for (int i = 0; i < out.length && i < lines.length; i++) out[i] = "True".equalsIgnoreCase(lines[i]);
        } catch (Exception ignored) { // the PC is away: show the rows as missing
        }
        return out;
    }
}
