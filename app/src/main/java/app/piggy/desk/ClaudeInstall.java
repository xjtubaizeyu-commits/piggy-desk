package app.piggy.desk;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Puts Claude Code's native build into the app's Linux without the official install script, because that one fetches
 * the whole 286 MB program in a single go with no resume, which a roaming connection rarely survives. Instead:
 * the zstd-compressed build (about 100 MB) from the same place (downloads.claude.ai), downloaded with resume over the
 * allowed Wi-Fi, checked against the release manifest, unpacked inside Linux, and laid out the way the native installer
 * does it (~/.local/share/claude/versions/&lt;version&gt; and the ~/.local/bin/claude link). Or the same from a file the
 * user brings (claude.zst or the plain program, e.g. downloaded on the PC and sent over).
 */
final class ClaudeInstall {
    static final String BASE = "https://downloads.claude.ai/claude-code-releases", PLATFORM = "linux-arm64-musl";
    private static final String STAGE = "root/.desk/claude.incoming";

    interface Status {
        void say(String text);
    }

    private ClaudeInstall() {
    }

    /** Claude's own files come only over the allowed Wi-Fi (the phone's hotspot). */
    static final Download.Net GUARDED = () -> {
        if (!NetGuard.allowed || NetGuard.wifi == null) throw new IOException("等待连上允许的 Wi-Fi：" + NetGuard.reason);
        return NetGuard.wifi;
    };

    /** The latest release, downloaded (resuming any earlier partial download) and installed. */
    static String download(Context c, Status st) throws Exception {
        st.say("查询最新版本…");
        String ver = Download.text(BASE + "/latest", GUARDED).trim();
        if (!ver.matches("\\d+\\.\\d+\\.\\d+.*")) throw new IOException("版本号不对：" + ver);
        JSONObject zman = new JSONObject(Download.text(BASE + "/" + ver + "/manifest.zst.json", GUARDED));
        JSONObject zp = zman.getJSONObject("platforms").getJSONObject(PLATFORM);
        String rawSum = "";
        try {
            rawSum = new JSONObject(Download.text(BASE + "/" + ver + "/manifest.json", GUARDED))
                    .getJSONObject("platforms").getJSONObject(PLATFORM).optString("checksum");
        } catch (Exception ignored) { // the unpacked program's check is extra; zstd checks its own content too
        }
        File dir = new File(c.getFilesDir(), "claude-dl");
        dir.mkdirs();
        File zst = new File(dir, "claude-" + ver + ".zst");
        File[] old = dir.listFiles();
        for (int i = 0; old != null && i < old.length; i++) { // partial downloads of older versions
            if (!old[i].getName().startsWith(zst.getName())) old[i].delete();
        }
        final String v = ver;
        Download.fetch(BASE + "/" + ver + "/" + PLATFORM + "/" + zp.optString("binary", "claude.zst"), zst,
                zp.optLong("size"), zp.optString("checksum"), GUARDED, new Throttle(st, "下载 Claude Code " + v));
        File staged = new File(Linux.root(c), STAGE);
        staged.getParentFile().mkdirs();
        staged.delete();
        if (!zst.renameTo(staged)) throw new IOException("挪不动下载好的文件");
        return place(c, ver, rawSum, true, st);
    }

    /** From a file the user picked: claude.zst (as on downloads.claude.ai) or the plain program. */
    static String fromFile(Context c, Uri uri, Status st) throws Exception {
        File staged = new File(Linux.root(c), STAGE);
        staged.getParentFile().mkdirs();
        st.say("复制文件…");
        long n = 0, last = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(staged)) {
            if (in == null) throw new IOException("打不开这个文件");
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
                if (n - last > 8 * 1048576) {
                    last = n;
                    st.say("复制文件… " + Download.mb(n));
                }
            }
        }
        byte[] h = Download.head(staged, 4);
        boolean zstd = h.length == 4 && (h[0] & 0xff) == 0x28 && (h[1] & 0xff) == 0xb5 && (h[2] & 0xff) == 0x2f && (h[3] & 0xff) == 0xfd;
        boolean elf = h.length == 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        if (!zstd && !elf) {
            staged.delete();
            throw new IOException("这不是 Claude Code 的安装文件（要 claude.zst，或者 linux-arm64-musl 的 claude 程序）");
        }
        return place(c, "", "", zstd, st);
    }

    /** Unpacks (if needed) the staged file inside Linux and installs it; returns the version. */
    private static String place(Context c, String ver, String rawSum, boolean zstd, Status st) throws Exception {
        st.say(zstd ? "解压并安装…" : "安装…");
        String in = "/" + STAGE;
        String script = "set -e; d=/root/.local/share/claude/versions; mkdir -p \"$d\" /root/.local/bin; t=\"$d/.incoming\"; rm -f \"$t\"; "
                + (zstd ? "command -v zstd >/dev/null || apk add -q zstd; zstd -d -q -f " + in + " -o \"$t\"; rm -f " + in + "; "
                : "mv -f " + in + " \"$t\"; ")
                + (rawSum.isEmpty() ? "" : "echo '" + rawSum + "  '\"$t\" | sha256sum -c - >/dev/null || { rm -f \"$t\"; echo BAD_SUM; exit 1; }; ")
                + "chmod 755 \"$t\"; v='" + ver + "'; "
                + "[ -n \"$v\" ] || v=$(\"$t\" --version 2>/dev/null | grep -o '^[0-9][0-9.]*' | head -1); "
                + "[ -n \"$v\" ] || { rm -f \"$t\"; echo NO_VERSION; exit 1; }; "
                + "mv -f \"$t\" \"$d/$v\"; ln -sfn \"$d/$v\" /root/.local/bin/claude; "
                + "echo VER=$(/root/.local/bin/claude --version); echo CLAUDE_OK";
        String out = Linux.exec(c, script, 1200);
        if (out.contains("BAD_SUM")) throw new IOException("解压出来的程序校验不对");
        if (out.contains("NO_VERSION")) throw new IOException("这个程序跑不起来（是 linux-arm64-musl 版吗？）");
        if (!out.contains("CLAUDE_OK")) {
            String t = out.trim();
            throw new IOException(t.length() > 300 ? "…" + t.substring(t.length() - 300) : t);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("VER=\\D*(\\d+\\.\\d+\\.\\d+)").matcher(out);
        return m.find() ? m.group(1) : ver;
    }

    /** Progress lines at most once a second. */
    private static final class Throttle implements Download.Progress {
        private final Status st;
        private final String what;
        private long last;

        Throttle(Status st, String what) {
            this.st = st;
            this.what = what;
        }

        @Override
        public void update(long done, long total, long perSec, String note) {
            long now = System.currentTimeMillis();
            if (note == null && now - last < 1000) return;
            last = now;
            st.say(note != null ? what + "：" + note : what + "：" + Download.line(done, total, perSec));
        }
    }
}
