package app.piggy.desk;

import android.net.Network;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Big downloads over a slow, flaky connection (the phone's roaming data): byte-level resume from a .part file that
 * survives the app being closed, automatic retries with growing pauses, progress with speed, and an optional SHA-256
 * check. With a {@link Net}, every attempt goes over the network it names (the allowed Wi-Fi for Claude's files).
 */
final class Download {
    interface Progress {
        /** done/total bytes (total 0 if unknown), bytes per second; or a note (waiting, retrying) with done = -1. */
        void update(long done, long total, long perSec, String note);
    }

    /** The network for the next attempt; throws (with the reason) when there is none to use right now. */
    interface Net {
        Network get() throws IOException;
    }

    private static final int MAX_FAILURES = 400;

    private Download() {
    }

    /** Downloads {@code url} to {@code to}. size and sha256 may be unknown (0 / null). */
    static void fetch(String url, File to, long size, String sha256, Net net, Progress p) throws IOException {
        File part = new File(to.getPath() + ".part");
        to.getParentFile().mkdirs();
        int failures = 0;
        while (true) {
            long have = part.length();
            if (size > 0 && have == size) break;
            if (size > 0 && have > size) {
                part.delete();
                have = 0;
            }
            HttpURLConnection con = null;
            try {
                con = open(url, net, have > 0 ? "bytes=" + have + "-" : null);
                int code = con.getResponseCode();
                if (code == 416 && have > 0) { // asked past the end: what we have is all of it, or it's stale
                    if (size <= 0) break;
                    part.delete();
                    continue;
                }
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                boolean append = code == 206 && have > 0;
                if (!append) have = 0; // the server sent the whole file again
                long len = con.getContentLengthLong();
                long total = size > 0 ? size : len > 0 ? have + len : 0;
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = have, windowStart = System.currentTimeMillis(), windowBytes = 0, perSec = 0;
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        done += r;
                        windowBytes += r;
                        long now = System.currentTimeMillis();
                        if (now - windowStart >= 1000) {
                            perSec = windowBytes * 1000 / (now - windowStart);
                            windowStart = now;
                            windowBytes = 0;
                            if (p != null) p.update(done, total, perSec, null);
                        }
                    }
                }
                if (total > 0 && part.length() < total) throw new IOException("连接中途断了");
                failures = 0;
                if (size <= 0 || part.length() == size) break;
            } catch (IOException e) {
                if (++failures >= MAX_FAILURES) throw e;
                int wait = Math.min(30, 2 + failures * 2);
                if (p != null) p.update(-1, 0, 0, (e.getMessage() == null ? "连接出错" : e.getMessage())
                        + "，" + wait + " 秒后接着下（已下 " + mb(part.length()) + "）");
                try {
                    Thread.sleep(wait * 1000L);
                } catch (InterruptedException ie) {
                    throw new IOException("下载被中断");
                }
            } finally {
                if (con != null) con.disconnect();
            }
        }
        if (sha256 != null && !sha256.isEmpty()) {
            if (p != null) p.update(-1, 0, 0, "校验文件…");
            String got = sha256(part);
            if (!got.equalsIgnoreCase(sha256)) {
                part.delete();
                throw new IOException("下载的文件校验不对，已经删掉，请重试");
            }
        }
        to.delete();
        if (!part.renameTo(to)) throw new IOException("保存不了 " + to.getName());
    }

    /** A small text file (version numbers, manifests), with a few quick retries. */
    static String text(String url, Net net) throws IOException {
        IOException last = null;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection con = null;
            try {
                con = open(url, net, null);
                if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode() + "：" + url);
                try (InputStream in = con.getInputStream()) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
                    return new String(o.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(2000L * (i + 1));
                } catch (InterruptedException ie) {
                    break;
                }
            } finally {
                if (con != null) con.disconnect();
            }
        }
        throw last != null ? last : new IOException("下载失败：" + url);
    }

    /**
     * A connection over the given network, or the system route when there is none or binding to it is refused
     * (see {@link NetGuard#bindBlocked}); the Net still decides whether to connect at all.
     */
    private static HttpURLConnection open(String url, Net net, String range) throws IOException {
        Network n = net == null ? null : net.get();
        URL u = new URL(url);
        if (n != null && !NetGuard.bindBlocked) {
            HttpURLConnection con = null;
            try {
                con = prepare((HttpURLConnection) n.openConnection(u), range);
                con.connect();
                return con;
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (con != null) con.disconnect();
                if (!m.contains("EPERM") && !m.contains("not permitted")) throw e;
                NetGuard.bindBlocked = true;
            }
        }
        return prepare((HttpURLConnection) u.openConnection(), range);
    }

    private static HttpURLConnection prepare(HttpURLConnection con, String range) {
        con.setConnectTimeout(20000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "PiggyDesk/" + BuildInfo.VERSION);
        con.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) con.setRequestProperty("Range", range);
        return con;
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** The first bytes of a file (to tell a zstd archive from a plain program). */
    static byte[] head(File f, int n) throws IOException {
        byte[] b = new byte[n];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            int got = r.read(b);
            if (got < n) {
                byte[] s = new byte[Math.max(got, 0)];
                System.arraycopy(b, 0, s, 0, s.length);
                return s;
            }
        }
        return b;
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** "45.3 / 101.1 MB · 520 KB/s" */
    static String line(long done, long total, long perSec) {
        String speed = perSec >= 1048576 ? String.format(Locale.ROOT, "%.1f MB/s", perSec / 1048576.0)
                : (perSec / 1024) + " KB/s";
        String eta = "";
        if (total > 0 && perSec > 0) {
            long s = (total - done) / perSec;
            eta = s < 60 ? " · 还要 " + s + " 秒" : " · 还要约 " + (s / 60 + 1) + " 分钟";
        }
        return String.format(Locale.ROOT, "%.1f", done / 1048576.0) + (total > 0 ? " / " + mb(total) : " MB") + " · " + speed + eta;
    }
}
