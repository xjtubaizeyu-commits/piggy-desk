package app.piggy.desk;

import android.net.Network;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tablet's exit for Claude traffic (ported from PiggyNet's Termux piggy-egress.py). An HTTP proxy (CONNECT and
 * plain requests) that forwards only while {@link NetGuard} allows the current Wi-Fi, over that Wi-Fi network itself,
 * and cuts every open connection the moment it doesn't.
 * <ul>
 * <li>127.0.0.1:{@value #LOCAL_PORT}: Claude Code and everything else in the app's Linux (no code needed).</li>
 * <li>The mesh port (settings, 8899 by default): the PC's PiggyNet "tablet" line, over Tailscale (100.64.0.0/10),
 * with the pairing code as Basic auth (user "piggy"). Other neighbours are dropped without a word.</li>
 * </ul>
 * GET /piggy/status answers with JSON that PiggyNet reads ("egress": true|false, "ssid").
 */
final class EgressProxy {
    static final int LOCAL_PORT = 8897;
    private static final int HEAD_LIMIT = 32 * 1024, VERSION = 2;

    static volatile boolean meshOn;
    static volatile int meshPort;
    static volatile String meshError = "";
    static volatile long lastPeer;

    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static final Set<Socket> open = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private static ServerSocket local, mesh;
    private static volatile String code = "";

    private EgressProxy() {
    }

    /** The always-on local listener; and the mesh one if it is switched on in the settings. */
    static synchronized void start(android.content.Context c) {
        if (local == null) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_PORT));
                local = s;
                pool.execute(() -> accept(s, false));
            } catch (IOException e) {
                android.util.Log.w("PiggyDesk", "local proxy failed", e);
            }
        }
        applySettings(c);
    }

    /** (Re)opens or closes the mesh listener after the egress settings changed. */
    static synchronized void applySettings(android.content.Context c) {
        DeskStore st = DeskStore.get(c);
        boolean on = st.bool("egressOn", false);
        int port = st.num("egressPort", 8899);
        code = st.str("egressCode", "");
        if (mesh != null && (!on || port != meshPort)) {
            try {
                mesh.close();
            } catch (IOException ignored) {
            }
            mesh = null;
        }
        meshOn = on;
        meshPort = port;
        if (!on) {
            meshError = "";
            return;
        }
        if (mesh != null) return;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port)); // every interface; only Tailscale peers are served
            mesh = s;
            meshError = "";
            pool.execute(() -> accept(s, true));
        } catch (IOException e) {
            meshError = "端口 " + port + " 打不开（Termux 里的旧出口还在跑？）：" + e.getMessage();
        }
    }

    /** Closes every connection going through: the Wi-Fi is no longer allowed. */
    static void closeAll() {
        for (Socket s : new ArrayList<>(open)) quietClose(s);
        open.clear();
    }

    static int tunnels() {
        return open.size() / 2;
    }

    /** This tablet's Tailscale address (100.64.0.0/10), or "" if Tailscale isn't up. */
    static String meshIp() {
        try {
            for (java.net.NetworkInterface ni : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (inMesh(a)) return a.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ------------------------------------------------------------------ serving

    private static void accept(ServerSocket server, boolean fromMesh) {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                pool.execute(() -> serve(s, fromMesh));
            } catch (IOException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private static void serve(Socket client, boolean fromMesh) {
        Socket up = null;
        boolean tunneling = false;
        try {
            InetAddress peer = client.getInetAddress();
            boolean loop = peer.isLoopbackAddress();
            if (!loop && !(fromMesh && inMesh(peer))) return; // hotspot neighbours and anyone else: drop
            client.setSoTimeout(20000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] head = readHead(in);
            if (head == null) return;
            String text = new String(head, StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n");
            String[] first = lines[0].split(" ", 3);
            if (first.length < 3) {
                answer(out, 400, "Bad Request", "text/plain", "bad request line\n");
                return;
            }
            String method = first[0].toUpperCase(Locale.ROOT), target = first[1], version = first[2];
            List<String[]> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.add(new String[]{lines[i].substring(0, colon).trim(), lines[i].substring(colon + 1).trim()});
            }
            if (!loop) {
                if (!authorized(headers)) {
                    answer(out, 407, "Proxy Authentication Required", "text/plain", "pairing code required\n",
                            "Proxy-Authenticate: Basic realm=\"piggy\"");
                    return;
                }
                lastPeer = System.currentTimeMillis();
            }
            if (target.startsWith("/piggy/status")) {
                answer(out, 200, "OK", "application/json", status());
                return;
            }
            if (!NetGuard.allowed) {
                answer(out, 503, "Service Unavailable", "text/plain", "tablet egress closed: " + NetGuard.reason + "\n");
                return;
            }
            if ("CONNECT".equals(method)) {
                String[] hp = hostPort(target, 443);
                try {
                    up = connect(hp[0], Integer.parseInt(hp[1]));
                } catch (IOException e) {
                    answer(out, 502, "Bad Gateway", "text/plain", hp[0] + ":" + hp[1] + ": " + e.getMessage() + "\n");
                    return;
                }
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } else if (target.toLowerCase(Locale.ROOT).startsWith("http://")) {
                URI u = URI.create(target);
                String host = u.getHost() == null ? "" : u.getHost();
                int port = u.getPort() > 0 ? u.getPort() : 80;
                try {
                    up = connect(host, port);
                } catch (IOException e) {
                    answer(out, 502, "Bad Gateway", "text/plain", host + ":" + port + ": " + e.getMessage() + "\n");
                    return;
                }
                String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                        + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
                StringBuilder b = new StringBuilder(method + " " + path + " " + version + "\r\n");
                for (String[] h : headers) {
                    String k = h[0].toLowerCase(Locale.ROOT);
                    if (!k.startsWith("proxy-") && !k.equals("connection")) b.append(h[0]).append(": ").append(h[1]).append("\r\n");
                }
                b.append("Connection: close\r\n\r\n");
                up.getOutputStream().write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
            } else {
                answer(out, 400, "Bad Request", "text/plain", "not a proxy request\n");
                return;
            }
            client.setSoTimeout(0);
            tunneling = true;
            tunnel(client, up);
        } catch (Exception ignored) {
        } finally {
            if (!tunneling) {
                quietClose(client);
                if (up != null) quietClose(up);
            }
        }
    }

    /** Copies both ways until either side closes; the two sockets are in {@link #open} meanwhile. */
    private static void tunnel(Socket a, Socket b) {
        open.add(a);
        open.add(b);
        Runnable done = () -> {
            open.remove(a);
            open.remove(b);
            quietClose(a);
            quietClose(b);
        };
        pool.execute(() -> {
            pipe(b, a);
            done.run();
        });
        pipe(a, b);
        done.run();
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[32 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    /** An upstream connection over the allowed Wi-Fi network only (loopback stays local). */
    private static Socket connect(String host, int port) throws IOException {
        if (host.isEmpty()) throw new IOException("no host");
        if ("localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "::1".equals(host)) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getByName(host), port), 15000);
            return s;
        }
        Network net = NetGuard.wifi;
        if (net == null || !NetGuard.allowed) throw new IOException("tablet egress closed: " + NetGuard.reason);
        InetAddress[] addrs = net.getAllByName(host);
        IOException last = null;
        for (InetAddress a : addrs) {
            Socket s = new Socket();
            try {
                net.bindSocket(s);
                s.connect(new InetSocketAddress(a, port), 15000);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                last = e;
                quietClose(s);
            }
        }
        throw last != null ? last : new IOException("can't resolve " + host);
    }

    private static boolean authorized(List<String[]> headers) {
        String c = code;
        if (c == null || c.isEmpty()) return true;
        String want = Base64.encodeToString(("piggy:" + c).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Proxy-Authorization") && h[1].startsWith("Basic ")) {
                return MessageDigest.isEqual(h[1].substring(6).trim().getBytes(StandardCharsets.ISO_8859_1),
                        want.getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        return false;
    }

    /** The JSON PiggyNet's daemon reads (it looks for "egress": true and "ssid"). */
    static String status() {
        return "{\"ok\": true, \"egress\": " + NetGuard.allowed + ", \"ssid\": " + jsonStr(NetGuard.ssid)
                + ", \"reason\": " + jsonStr(NetGuard.reason) + ", \"since\": " + NetGuard.since / 1000
                + ", \"tunnels\": " + tunnels() + ", \"version\": " + VERSION + ", \"app\": \"piggy-desk\"}";
    }

    private static String jsonStr(String s) {
        return org.json.JSONObject.quote(s == null ? "" : s);
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (b.size() > HEAD_LIMIT) return null;
            // \r\n\r\n
            state = (c == '\r') ? (state == 2 ? 3 : 1) : (c == '\n') ? (state == 1 ? 2 : state == 3 ? 4 : 0) : 0;
            if (state == 4) return b.toByteArray();
        }
        return null;
    }

    private static void answer(OutputStream out, int code, String reason, String type, String body, String... extra)
            throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type
                + (type.startsWith("text/") ? "; charset=utf-8" : "") + "\r\nContent-Length: " + data.length
                + "\r\nConnection: close\r\n");
        for (String x : extra) h.append(x).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static String[] hostPort(String s, int def) {
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            String host = s.substring(1, end);
            String rest = s.substring(end + 1);
            return new String[]{host, rest.startsWith(":") ? rest.substring(1) : String.valueOf(def)};
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) return new String[]{s.substring(0, colon), s.substring(colon + 1)};
        return new String[]{s, String.valueOf(def)};
    }

    private static boolean inMesh(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        byte[] b = a.getAddress();
        return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
    }

    private static void quietClose(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
