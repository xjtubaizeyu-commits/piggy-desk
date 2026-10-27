package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An SSH server (OpenSSH) inside Buddy's Linux, so Termux can sign in to it with a key. It listens on 127.0.0.1
 * only, so only apps on this phone reach it. A key gets in once: Settings copies a Termux command with a one-time
 * code, and the command posts its public key to the local server (POST /ssh-key). Buddy restarts the server if it
 * stops. Dropbear can't be used: it re-executes itself through a file descriptor, which proot doesn't support.
 */
final class SshServer {
    enum State { OFF, STARTING, ACTIVE, ERROR }

    interface Listener {
        void onSshChanged();
    }

    static final int PORT = 8767;
    private static final String CONFIG = "/etc/buddy/sshd_config";
    /** How long a copied command's code works. */
    private static final long CODE_MS = 10 * 60_000;
    private static final Pattern KEY = Pattern.compile("((?:ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)"
            + "|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/]+={0,3})(?: ([^\\r\\n]*))?");

    static volatile State state = State.OFF;
    static volatile String detail = "";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Context app;
    private static Process proc;
    private static long lastStart;
    private static int quickFails;
    private static boolean starting;
    private static String code;
    private static long codeUntil;

    private SshServer() {
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void set(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onSshChanged();
        });
    }

    /** Start if switched on and not running (called on service start and from Settings). */
    static void ensure(Context c) {
        synchronized (SshServer.class) {
            app = c.getApplicationContext();
            if (!Prefs.sshServer(app) || proc != null || starting) return;
            if (!Linux.installed(app)) {
                set(State.ERROR, app.getString(R.string.helper_linux_missing));
                return;
            }
            starting = true;
        }
        set(State.STARTING, "");
        new Thread(() -> run(app)).start();
    }

    static void stop() {
        Process p;
        synchronized (SshServer.class) {
            p = proc;
            proc = null;
            code = null;
        }
        if (p != null) p.destroy();
        quickFails = 0; // turning it off and on again starts with fresh retries
        set(State.OFF, "");
    }

    private static void run(Context app) {
        String prev = "", last = ""; // its last two lines, for the error (a failed bind is two)
        try {
            prepare(app);
            Process p = Linux.builder(app, "/usr/sbin/sshd", "-D", "-e", "-f", CONFIG).start();
            synchronized (SshServer.class) {
                starting = false;
                if (!Prefs.sshServer(app)) {
                    p.destroy();
                    return;
                }
                proc = p;
            }
            p.getOutputStream().close();
            lastStart = System.currentTimeMillis();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    prev = last;
                    last = line.trim();
                    if (state != State.ACTIVE && line.contains("Server listening on")) {
                        quickFails = 0;
                        set(State.ACTIVE, "");
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            if (Prefs.sshServer(app)) set(State.ERROR, app.getString(R.string.remote_start_failed, e.getMessage()));
        }
        boolean wanted;
        synchronized (SshServer.class) {
            starting = false;
            wanted = proc != null && Prefs.sshServer(app);
            proc = null;
        }
        if (!wanted) return;
        // it stopped on its own: start again, backing off if it keeps failing right away
        if (System.currentTimeMillis() - lastStart < 30_000) quickFails++;
        if (quickFails >= 3) {
            set(State.ERROR, app.getString(R.string.ssh_keeps_stopping, (prev + " " + last).trim()));
            return;
        }
        set(State.STARTING, "");
        main.postDelayed(() -> ensure(app), 3000L * (quickFails + 1));
    }

    /** OpenSSH, host keys, the key file and Buddy's own server settings (rewritten each start). */
    private static void prepare(Context app) throws Exception {
        String out = Linux.exec(app, "set -e; [ -x /usr/sbin/sshd ] || apk add -q openssh-server; ssh-keygen -A >/dev/null;"
                + " mkdir -p /etc/buddy /root/.ssh; chmod 700 /root/.ssh; touch /root/.ssh/authorized_keys;"
                + " chmod 600 /root/.ssh/authorized_keys; echo PREP_OK", 300);
        if (!out.contains("PREP_OK")) throw new Exception(out.trim());
        Linux.write(new File(Linux.root(app), CONFIG.substring(1)), "# Piggy's SSH server (Settings > Remote Control)."
                + " Piggy rewrites this file each time it starts the server.\n"
                + "ListenAddress 127.0.0.1\nPort " + PORT + "\n"
                + "PermitRootLogin prohibit-password\nPasswordAuthentication no\nKbdInteractiveAuthentication no\n"
                + "AuthorizedKeysFile .ssh/authorized_keys\nAllowTcpForwarding yes\nPrintMotd no\nPidFile none\n"
                + "Subsystem sftp internal-sftp\n"
                // sessions get a fresh environment: Buddy's PATH (Claude Code lives in ~/.local/bin) and UTF-8
                + "SetEnv PATH=/root/.local/bin:/root/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                + " LANG=C.UTF-8 COLORTERM=truecolor\n");
    }

    /** A fresh one-time code for the Termux command: it lets one key in, within ten minutes. */
    static synchronized String newCode() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        code = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP
                | android.util.Base64.NO_PADDING);
        codeUntil = SystemClock.elapsedRealtime() + CODE_MS;
        return code;
    }

    /** POST /ssh-key: adds the public key in the body if the code is the current one. The reply is for the terminal. */
    static String addKey(String given, String body) {
        Matcher m = KEY.matcher(body.trim());
        Context c;
        synchronized (SshServer.class) {
            c = app;
            if (c == null || code == null || given == null || SystemClock.elapsedRealtime() > codeUntil
                    || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))
                    || !m.matches()) {
                return null;
            }
            code = null; // used once
            // no options in front of the key, and a comment that stays on its line
            String comment = m.group(2) == null ? "" : m.group(2).replaceAll("[^A-Za-z0-9@._:+=-]", "_");
            try {
                File f = new File(Linux.root(c), "root/.ssh/authorized_keys");
                String have = f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
                if (!have.contains(m.group(1))) {
                    try (OutputStream o = new FileOutputStream(f, true)) {
                        o.write(((have.isEmpty() || have.endsWith("\n") ? "" : "\n") + m.group(1)
                                + (comment.isEmpty() ? "" : " " + comment) + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("Piggy", "ssh key not saved", e);
                return null;
            }
        }
        String done = c.getString(R.string.ssh_key_added);
        main.post(() -> Toast.makeText(c, done, Toast.LENGTH_LONG).show());
        return done;
    }
}
