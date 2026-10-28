package app.piggy.desk;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The user's Windows PC as a workspace (Settings → 电脑): SSH over Tailscale with the tablet's own key, and
 * pc-tools.py (assets/pc/) as the MCP server that gives Claude the PC's files and commands. The PC never talks to
 * Anthropic; Claude Code stays on the tablet. The PC side is set up once with PiggyNet's tools/setup-remote-pc.ps1
 * and the public key shown in the settings.
 */
final class PcLink {
    static final String KEY = "/root/.ssh/piggy_desk";
    private static final String TOOLS_LOCAL = "/root/.desk/pc-tools.py", TOOLS_REMOTE = ".piggy-desk/pc-tools.py";

    static volatile String status = "off", detail = "";
    private static String pushedTo = "";

    private PcLink() {
    }

    /** What a session on a PC project needs: the MCP config, the extra system prompt, the git branch. */
    static final class Ctx {
        String mcpConfig, prompt, branch = "";
    }

    static boolean on(Context c) {
        return DeskStore.get(c).bool("pcOn", false);
    }

    static String host(Context c) {
        return DeskStore.get(c).str("pcHost", "").trim();
    }

    static String user(Context c) {
        return DeskStore.get(c).str("pcUser", "").trim();
    }

    /** The tablet's public key ("" before it exists). */
    static String publicKey(Context c) {
        try {
            return DeskStore.read(new File(Linux.root(c), KEY.substring(1) + ".pub")).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** ssh and a key inside Linux (first time: installs openssh-client, a few MB). Returns the public key. */
    static synchronized String ensureKey(Context c) throws Exception {
        String pub = publicKey(c);
        if (!pub.isEmpty() && java.nio.file.Files.exists(new File(Linux.root(c), "usr/bin/ssh").toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) return pub;
        if (!Linux.installed(c)) throw new IOException("平板上的 Linux 还没装好");
        String out = Linux.exec(c, "command -v ssh >/dev/null || apk add -q openssh-client || exit 1; mkdir -p /root/.ssh /root/.desk"
                + " && chmod 700 /root/.ssh && { [ -s " + KEY + " ] || ssh-keygen -q -t ed25519 -N '' -C piggy-desk -f " + KEY + "; }"
                + " && echo KEY_OK", 600);
        if (!out.contains("KEY_OK")) throw new IOException("装不上 ssh：" + tail(out));
        return publicKey(c);
    }

    private static String target(Context c) throws IOException {
        String h = host(c), u = user(c);
        if (h.isEmpty() || u.isEmpty()) throw new IOException("先在设置里填电脑的地址和用户名");
        if (!h.matches("[A-Za-z0-9._:-]+") || !u.matches("[A-Za-z0-9._ -]+")) throw new IOException("地址或用户名里有不认识的字符");
        return u + "@" + h;
    }

    private static String[] sshArgs(Context c) throws IOException {
        return new String[]{"ssh", "-T", "-i", KEY, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
                "-o", "UserKnownHostsFile=/root/.ssh/known_hosts_desk", "-o", "LogLevel=ERROR", "-o", "ConnectTimeout=25",
                "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=4", target(c)};
    }

    /** A shell command for the app's terminal: an interactive SSH session on the PC. */
    static String terminal(Context c) {
        try {
            String[] a = sshArgs(c);
            a[1] = "-t";
            return "exec " + sh(a);
        } catch (IOException e) {
            return "echo " + shq(e.getMessage());
        }
    }

    private static String sh(String[] args) {
        StringBuilder b = new StringBuilder();
        for (String a : args) b.append(shq(a)).append(' ');
        return b.toString().trim();
    }

    static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** cmd.exe on the PC: python running pc-tools.py on a project folder. */
    private static String remoteTools(String root, String extra) {
        return "python " + TOOLS_REMOTE.replace('/', '\\') + " --root \"" + root.replace("\"", "") + "\"" + extra;
    }

    /** Copies the current pc-tools.py to the PC (once per app run and PC). */
    private static synchronized void push(Context c) throws Exception {
        String to = target(c);
        if (to.equals(pushedTo)) return;
        File local = new File(Linux.root(c), TOOLS_LOCAL.substring(1));
        local.getParentFile().mkdirs();
        try (InputStream in = c.getAssets().open("pc/pc-tools.py"); OutputStream out = new FileOutputStream(local)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        String[] ssh = sshArgs(c);
        String[] scp = new String[ssh.length];
        System.arraycopy(ssh, 0, scp, 0, ssh.length);
        scp[0] = "scp";
        scp[1] = "-q"; // in place of ssh's -T
        String scpOpts = sh(java.util.Arrays.copyOfRange(scp, 0, scp.length - 1));
        String out = Linux.exec(c, sh(ssh) + " " + shq("if not exist .piggy-desk mkdir .piggy-desk") + " && "
                + scpOpts + " " + TOOLS_LOCAL + " " + shq(to + ":" + TOOLS_REMOTE) + " && echo PUSH_OK", 120);
        if (!out.contains("PUSH_OK")) throw new IOException(tail(out));
        pushedTo = to;
    }

    /** Copies one file from the PC into the tablet's Linux (scp with the same key). */
    static void fetch(Context c, String winPath, String linuxPath) throws Exception {
        String[] ssh = sshArgs(c);
        String[] scp = java.util.Arrays.copyOf(ssh, ssh.length);
        scp[0] = "scp";
        scp[1] = "-q";
        String opts = sh(java.util.Arrays.copyOfRange(scp, 0, scp.length - 1));
        String out = Linux.exec(c, opts + " " + shq(target(c) + ":" + winPath.replace('\\', '/')) + " " + shq(linuxPath)
                + " && echo FETCH_OK", 600);
        if (!out.contains("FETCH_OK")) throw new IOException("没能从电脑取回文件：" + tail(out));
    }

    /** Facts about the PC and the project (pc-tools.py --context). */
    static JSONObject context(Context c, String root) throws Exception {
        ensureKey(c);
        push(c);
        String out = Linux.exec(c, sh(sshArgs(c)) + " " + shq(remoteTools(root, " --context --mkdir")), 90);
        int from = out.indexOf('{'), to = out.lastIndexOf('}');
        if (from < 0 || to < from) throw new IOException(tail(out));
        return new JSONObject(out.substring(from, to + 1));
    }

    /** Connects for a session on a PC project. */
    static Ctx prepare(Context c, String root) throws Exception {
        if (!on(c)) throw new IOException("电脑连接没开");
        setStatus(c, "connecting", "");
        JSONObject x;
        try {
            x = context(c, root);
        } catch (Exception e) {
            setStatus(c, "error", e.getMessage());
            throw e;
        }
        setStatus(c, "ok", describe(x));
        Ctx ctx = new Ctx();
        JSONObject server = new JSONObject().put("type", "stdio").put("command", "ssh")
                .put("args", new JSONArray(java.util.Arrays.asList(sshArgs(c))).put(remoteTools(root, " --mkdir")));
        ctx.mcpConfig = new JSONObject().put("mcpServers", new JSONObject().put("pc", server)).toString();
        ctx.branch = x.optString("git_branch");
        StringBuilder p = new StringBuilder();
        p.append("This project lives on the user's Windows PC \"").append(x.optString("hostname")).append("\" (")
                .append(x.optString("windows"));
        JSONArray gpu = x.optJSONArray("gpu");
        if (gpu != null && gpu.length() > 0) p.append("; GPU: ").append(gpu.optString(0));
        p.append("), not on this tablet. Its folder on the PC is ").append(root).append(".\n")
                .append("Work on it only through the mcp__pc__* tools: bash (Git Bash) and powershell for commands; read, write, "
                        + "edit, glob, grep and ls for files; bg_start, bg_output, bg_kill and bg_list for long jobs. Use Windows "
                        + "paths (C:/...). The local Bash, Write, Edit, Glob and Grep tools are off here; the local Read tool only "
                        + "sees files the user attached on the tablet (/root/.desk/att).\n");
        if (!ctx.branch.isEmpty()) p.append("Git branch on the PC: ").append(ctx.branch).append(".\n");
        JSONArray mds = x.optJSONArray("claude_md");
        if (mds != null && mds.length() > 0) {
            p.append("\nThe PC has these CLAUDE.md instructions. Follow them as your own project and user instructions:\n");
            for (int i = 0; i < mds.length(); i++) {
                JSONObject md = mds.optJSONObject(i);
                if (md == null) continue;
                p.append("\n### ").append(md.optString("path")).append("\n").append(md.optString("text")).append("\n");
            }
        }
        ctx.prompt = p.toString();
        return ctx;
    }

    /** Settings → 测试: reach the PC and read its facts. */
    static JSONObject test(Context c) {
        JSONObject r = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            setStatus(c, "connecting", "");
            JSONObject x = context(c, "%USERPROFILE%");
            String d = describe(x) + " · " + (System.currentTimeMillis() - t0) + " ms";
            setStatus(c, "ok", describe(x));
            r.put("ok", true).put("detail", d);
        } catch (Exception e) {
            setStatus(c, "error", e.getMessage());
            Engine.put(r, "ok", false);
            Engine.put(r, "detail", e.getMessage());
        }
        return r;
    }

    private static String describe(JSONObject x) {
        JSONArray gpu = x.optJSONArray("gpu");
        String g = gpu != null && gpu.length() > 0 ? gpu.optString(0).replaceAll(",.*$", "").replace("NVIDIA GeForce ", "") : "";
        return x.optString("hostname") + (g.isEmpty() ? "" : " · " + g);
    }

    private static void setStatus(Context c, String s, String d) {
        status = s;
        detail = d == null ? "" : d;
        Desk.get(c).pushPc();
    }

    /**
     * Runs one pc-tools tool for the app itself (file list, a file, git diff), over its own short SSH session.
     * Returns the tool's result object ({content: [...], isError}).
     */
    static JSONObject call(Context c, String root, String tool, JSONObject args) throws Exception {
        ensureKey(c);
        push(c);
        String[] ssh = sshArgs(c);
        String[] cmd = new String[ssh.length + 1];
        System.arraycopy(ssh, 0, cmd, 0, ssh.length);
        cmd[ssh.length] = remoteTools(root, "");
        Process p = Linux.builder(c, cmd).start();
        try {
            OutputStream in = p.getOutputStream();
            String req = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                    .put("params", new JSONObject().put("protocolVersion", "2025-06-18")) + "\n"
                    + new JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
                    .put("params", new JSONObject().put("name", tool).put("arguments", args)) + "\n";
            in.write(req.getBytes(StandardCharsets.UTF_8));
            in.flush();
            final JSONObject[] got = new JSONObject[1];
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int at = line.indexOf('{');
                        if (at < 0) continue;
                        try {
                            JSONObject m = new JSONObject(line.substring(at));
                            if (m.optInt("id") == 2) {
                                got[0] = m;
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            reader.start();
            reader.join(60000);
            in.close();
            if (got[0] == null) throw new IOException("电脑没有回应");
            if (got[0].has("error")) throw new IOException(got[0].optJSONObject("error").optString("message"));
            return got[0].optJSONObject("result");
        } finally {
            p.destroy();
            p.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** The text parts of a tool result. */
    static String text(JSONObject result) {
        StringBuilder b = new StringBuilder();
        JSONArray content = result == null ? null : result.optJSONArray("content");
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject x = content.optJSONObject(i);
            if (x != null && "text".equals(x.optString("type"))) b.append(x.optString("text")).append('\n');
        }
        return b.toString().trim();
    }

    private static String tail(String s) {
        s = s.replaceAll("\\n\\[exit \\d+\\]$", "").trim();
        return s.length() > 300 ? "…" + s.substring(s.length() - 300) : s.isEmpty() ? "没有输出" : s;
    }
}
