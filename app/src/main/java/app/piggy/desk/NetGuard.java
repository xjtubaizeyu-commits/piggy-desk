package app.piggy.desk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Which Wi-Fi the tablet is on, and whether Claude may use it: only the allowed networks (the phone's hotspot), so
 * every Claude request leaves through the phone. Reading a Wi-Fi name needs the location permission with location
 * turned on; without it the name is unknown and nothing is allowed. {@link #wifi} is the Wi-Fi network itself, which
 * EgressProxy binds its outgoing connections to (never a VPN or anything else).
 */
final class NetGuard {
    interface Listener {
        void onNetChanged();
    }

    static volatile String ssid = "";
    static volatile boolean allowed;
    static volatile String reason = "正在检查网络";
    static volatile Network wifi;
    static volatile long since = System.currentTimeMillis();
    /** Binding sockets to the Wi-Fi network is refused (a VPN without bypass, e.g. Tailscale): use the system route. */
    static volatile boolean bindBlocked;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Context app;
    private static boolean started;

    private NetGuard() {
    }

    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        ConnectivityManager.NetworkCallback cb = Build.VERSION.SDK_INT >= 31
                ? new Callback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) : new Callback();
        cm.registerNetworkCallback(req, cb, main);
        recheck();
    }

    static void addListener(Listener l) {
        main.post(() -> {
            if (!listeners.contains(l)) listeners.add(l);
        });
    }

    static void removeListener(Listener l) {
        main.post(() -> listeners.remove(l));
    }

    private static final class Callback extends ConnectivityManager.NetworkCallback {
        Callback() {
            super();
        }

        @android.annotation.TargetApi(31)
        Callback(int flags) {
            super(flags);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            wifi = network;
            String name = null;
            if (Build.VERSION.SDK_INT >= 29) {
                TransportInfo ti = caps.getTransportInfo();
                if (ti instanceof WifiInfo) name = ((WifiInfo) ti).getSSID();
            }
            update(name);
        }

        @Override
        public void onLost(Network network) {
            if (network.equals(wifi)) {
                wifi = null;
                update(null);
            }
        }
    }

    /** Looks again (after a permission was granted, the allow list changed, …). */
    static void recheck() {
        main.post(() -> update(null));
    }

    private static void update(String fromCaps) {
        Context c = app;
        if (c == null) return;
        String name = clean(fromCaps);
        if (wifi != null && (name.isEmpty() || "<unknown ssid>".equals(name))) {
            try { // Android 11, or a callback without location details: ask the Wi-Fi service
                @SuppressWarnings("deprecation")
                WifiInfo info = c.getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
                if (info != null) name = clean(info.getSSID());
            } catch (Exception ignored) {
            }
        }
        boolean ok;
        String why;
        List<String> allow = DeskStore.get(c).allowSsid();
        if (wifi == null) {
            name = "";
            ok = false;
            why = "没有连 Wi-Fi";
        } else if (name.isEmpty() || "<unknown ssid>".equals(name)) {
            name = "";
            ok = false;
            why = !locationAllowed(c) ? "读不到 Wi-Fi 名：需要定位权限" : !locationOn(c) ? "读不到 Wi-Fi 名：请打开定位" : "读不到 Wi-Fi 名";
        } else if (!allow.contains(name)) {
            ok = false;
            why = "「" + name + "」不在允许的 Wi-Fi 里";
        } else {
            ok = true;
            why = "在「" + name + "」上";
        }
        boolean changed = ok != allowed || !name.equals(ssid) || !why.equals(reason);
        if (ok != allowed) since = System.currentTimeMillis();
        ssid = name;
        allowed = ok;
        reason = why;
        if (!ok) EgressProxy.closeAll();
        if (changed) for (Listener l : new ArrayList<>(listeners)) l.onNetChanged();
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    static boolean locationAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean locationOn(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        return lm != null && (Build.VERSION.SDK_INT < 28 || lm.isLocationEnabled());
    }
}
