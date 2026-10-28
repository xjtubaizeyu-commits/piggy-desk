package app.piggy.desk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Files for the side panel: reads a file from the project (on the tablet, or from the PC over scp into a cache), says
 * how the page should show it, and serves the bytes the page asks for. The page sees a file only through an
 * unguessable token: https://appassets.androidplatform.net/fs/&lt;token&gt;/&lt;name&gt; for the file itself and
 * …/pdf/&lt;token&gt;/&lt;page&gt;.png?w=… for a PDF page drawn by Android's PdfRenderer. Tokens live for this run of the app.
 */
final class DeskFiles {
    private static final long TEXT_MAX = 1_000_000, HTML_MAX = 2_000_000, PC_MAX = 60_000_000;
    private static final int INDEX_MAX = 20000;
    private static final Map<String, File> tokens = Collections.synchronizedMap(new LinkedHashMap<String, File>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, File> e) {
            return size() > 400;
        }
    });

    private DeskFiles() {
    }

    // ------------------------------------------------------------------ reading

    /**
     * What the panel needs to show a file. where: "" = where the project lives, "tablet" = this tablet's Linux,
     * "pc" = the PC. A relative path is inside the project folder.
     */
    static JSONObject read(Context c, JSONObject project, String path, String where) throws Exception {
        boolean projectOnPc = DeskStore.PC.equals(project.optString("env"));
        boolean pc = "pc".equals(where) || (where.isEmpty() && projectOnPc);
        File f = pc ? fetchFromPc(c, project.optString("path"), path) : tabletFile(c, projectOnPc ? "/root" : project.optString("path"), path);
        return describe(f, !pc ? f.getParentFile() : null);
    }

    /** A file inside the tablet's Linux, from an absolute path or one inside the folder {@code root}. */
    static File tabletFile(Context c, String root, String path) throws IOException {
        if (path.startsWith("~/")) path = "/root/" + path.substring(2);
        String full = path.startsWith("/") ? path : root.replaceAll("/+$", "") + "/" + path;
        if (full.contains("/../") || full.endsWith("/..")) throw new IOException("路径不对");
        File f = new File(Linux.root(c), full.substring(1));
        if (!f.exists()) throw new IOException("找不到 " + path);
        if (f.isDirectory()) throw new IOException("这是文件夹：" + path);
        return f;
    }

    private static JSONObject describe(File f, File dir) throws IOException {
        String ext = ext(f.getName());
        String token = token(f);
        String url = "https://" + DeskActivity.HOST + "/fs/" + token + "/" + Uri.encode(f.getName());
        JSONObject r = new Engine.Obj("size", f.length(), "shareable", true, "token", token).o;
        if (ext.matches("png|jpe?g|gif|webp|bmp|ico|svg|avif|heic")) return put(r, "kind", "image", "url", url);
        if (ext.matches("mp4|webm|m4v|3gp|mp3|wav|ogg|oga|m4a|aac|flac|opus")) return put(r, "kind", "media", "url", url);
        if ("pdf".equals(ext)) return pdfInfo(f, r, token);
        if (ext.matches("html?|xhtml")) {
            if (f.length() > HTML_MAX) return put(r, "kind", "big");
            put(r, "kind", "html", "text", text(f, HTML_MAX));
            if (dir != null) put(r, "browseUrl", DeskBrowser.localUrl(dir, f));
            return r;
        }
        if (f.length() > 50_000_000 || binary(f)) return put(r, "kind", f.length() > 50_000_000 && !binary(f) ? "big" : "binary");
        put(r, "kind", "text", "text", text(f, TEXT_MAX));
        if (f.length() > TEXT_MAX) put(r, "truncated", true);
        return r;
    }

    private static JSONObject pdfInfo(File f, JSONObject r, String token) {
        put(r, "kind", "pdf", "url", "https://" + DeskActivity.HOST + "/pdf/" + token);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(fd)) {
            int n = pdf.getPageCount();
            JSONArray sizes = new JSONArray();
            for (int i = 0; i < n && i < 400; i++) {
                try (PdfRenderer.Page p = pdf.openPage(i)) {
                    sizes.put(p.getWidth() > 0 ? (double) p.getHeight() / p.getWidth() : 1.414);
                }
            }
            put(r, "pages", Math.min(n, 400), "sizes", sizes);
            if (n > 400) put(r, "note", "只显示前 400 页");
        } catch (Exception e) {
            put(r, "kind", "binary", "error", "PDF 打不开：" + e.getMessage());
        }
        return r;
    }

    /** A PC file, copied into the tablet's cache with scp (again only when it changed). */
    private static File fetchFromPc(Context c, String root, String path) throws Exception {
        boolean abs = path.matches("^[A-Za-z]:[\\\\/].*");
        String win = (abs ? path : root.replaceAll("[\\\\/]+$", "") + "\\" + path.replace('/', '\\'));
        JSONObject st = PcLink.call(c, root, "powershell", new Engine.Obj("command",
                "$i = Get-Item -LiteralPath '" + win.replace("'", "''") + "' -ErrorAction Stop; \"$($i.Length) $($i.LastWriteTimeUtc.Ticks) $($i.PSIsContainer)\"").o);
        String[] info = PcLink.text(st).trim().split("\\s+");
        if (st.optBoolean("isError") || info.length < 3) throw new IOException("电脑上找不到 " + path);
        if ("True".equalsIgnoreCase(info[2])) throw new IOException("这是文件夹：" + path);
        long size = Long.parseLong(info[0]);
        if (size > PC_MAX) throw new IOException("文件太大（" + Download.mb(size) + "），先别在平板上打开");
        String name = win.substring(Math.max(win.lastIndexOf('\\'), win.lastIndexOf('/')) + 1);
        String rel = "root/.desk/cache/pc/" + hash(win + "|" + info[1]);
        String safe = name.replaceAll("[\\\\/:*?\"<>|']", "_");
        File dir = new File(Linux.root(c), rel);
        File local = new File(dir, safe);
        if (!local.isFile() || local.length() != size) {
            dir.mkdirs();
            PcLink.fetch(c, win, "/" + rel + "/" + safe);
            if (!local.isFile()) throw new IOException("没能从电脑取回 " + name);
        }
        return local;
    }

    // ------------------------------------------------------------------ the project's file list (for @ and 文件)

    static JSONArray index(Context c, JSONObject project) throws Exception {
        JSONArray out = new JSONArray();
        String root = project.optString("path");
        if (DeskStore.PC.equals(project.optString("env"))) {
            JSONObject r = PcLink.call(c, root, "bash", new Engine.Obj("command",
                    "{ git ls-files -co --exclude-standard 2>/dev/null || find . -type f -not -path '*/.git/*' -not -path '*/node_modules/*' "
                            + "-not -path '*/.venv/*' -not -path '*/__pycache__/*' | sed 's|^\\./||'; } | head -1500").o);
            for (String l : PcLink.text(r).split("\n")) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("…") && !l.startsWith("（") && !l.startsWith("[exit")) out.put(l);
            }
            return out;
        }
        File base = new File(Linux.root(c), root.substring(1));
        Deque<File> dirs = new ArrayDeque<>();
        dirs.add(base);
        int prefix = base.getPath().length() + 1;
        while (!dirs.isEmpty() && out.length() < INDEX_MAX) {
            File[] list = dirs.poll().listFiles();
            if (list == null) continue;
            java.util.Arrays.sort(list);
            for (File f : list) {
                String n = f.getName();
                if (f.isDirectory()) {
                    if (!n.matches("\\.git|node_modules|\\.gradle|build|\\.venv|venv|__pycache__|\\.idea|\\.cache|dist|target|\\.next")) dirs.add(f);
                } else if (out.length() < INDEX_MAX) {
                    out.put(f.getPath().substring(prefix));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ serving (DeskActivity's WebView asks)

    static WebResourceResponse serve(Uri u) {
        java.util.List<String> seg = u.getPathSegments();
        if (seg.size() < 3) return null;
        File f = tokens.get(seg.get(1));
        if (f == null || !f.isFile()) return null;
        try {
            if ("pdf".equals(seg.get(0))) {
                int page = Integer.parseInt(seg.get(2).replaceAll("\\.png$", ""));
                int w = 1200;
                try {
                    w = Math.max(200, Math.min(2400, Integer.parseInt(u.getQueryParameter("w"))));
                } catch (Exception ignored) {
                }
                byte[] png = pdfPage(f, page, w);
                return response("image/jpeg", new ByteArrayInputStream(png));
            }
            return response(mime(f.getName()), new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    private static WebResourceResponse response(String type, InputStream in) {
        WebResourceResponse r = new WebResourceResponse(type, type.startsWith("text/") ? "utf-8" : null, in);
        r.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-store"));
        return r;
    }

    private static final Object pdfLock = new Object();

    /** One page as a JPEG, {@code width} pixels wide (PdfRenderer draws one page at a time). */
    private static byte[] pdfPage(File f, int page, int width) throws IOException {
        synchronized (pdfLock) {
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer pdf = new PdfRenderer(fd);
                 PdfRenderer.Page p = pdf.openPage(Math.max(0, Math.min(page, pdf.getPageCount() - 1)))) {
                int h = Math.max(1, Math.round((float) width * p.getHeight() / Math.max(1, p.getWidth())));
                if ((long) width * h > 16_000_000L) h = (int) (16_000_000L / width);
                Bitmap b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 88, o);
                b.recycle();
                return o.toByteArray();
            }
        }
    }

    /** The file behind a token (FilesProvider hands it to another app). */
    static File file(String token) {
        return tokens.get(token);
    }

    /** Opens a file in another app (a PDF reader, an office app, a video player …). */
    static void openWith(android.app.Activity a, File f) {
        String t = token(f);
        Uri uri = Uri.parse("content://" + FilesProvider.AUTHORITY + "/f/" + t + "/" + Uri.encode(f.getName()));
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(i, f.getName()));
        } catch (Exception e) {
            Desk.get(a).toast("没有能打开这个文件的应用");
        }
    }

    // ------------------------------------------------------------------ helpers

    static String token(File f) {
        synchronized (tokens) { // the map reorders itself on every get: iterate under its own lock
            for (Map.Entry<String, File> e : tokens.entrySet()) if (e.getValue().equals(f)) return e.getKey();
            String t = DeskStore.newId("") + Long.toHexString(Double.doubleToLongBits(Math.random()));
            tokens.put(t, f);
            return t;
        }
    }

    static String ext(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int k = n.lastIndexOf('.');
        return k >= 0 ? n.substring(k + 1) : "";
    }

    static String mime(String name) {
        switch (ext(name)) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "ico": return "image/x-icon";
            case "svg": return "image/svg+xml";
            case "avif": return "image/avif";
            case "heic": return "image/heic";
            case "pdf": return "application/pdf";
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "3gp": return "video/3gpp";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": case "oga": case "opus": return "audio/ogg";
            case "m4a": case "aac": return "audio/mp4";
            case "flac": return "audio/flac";
            case "html": case "htm": case "xhtml": return "text/html";
            case "css": return "text/css";
            case "js": case "mjs": return "text/javascript";
            case "json": return "application/json";
            case "csv": return "text/csv";
            case "md": case "txt": case "log": case "py": case "java": case "kt": case "sh": return "text/plain";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }

    private static boolean binary(File f) throws IOException {
        byte[] b = Download.head(f, 8192);
        for (byte x : b) if (x == 0) return true;
        return false;
    }

    private static String text(File f, long max) throws IOException {
        byte[] data = new byte[(int) Math.min(f.length(), max)];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, r;
            while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) off += r;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static JSONObject put(JSONObject o, Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) Engine.put(o, (String) kv[i], kv[i + 1]);
        return o;
    }
}
