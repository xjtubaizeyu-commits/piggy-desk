package app.piggy.desk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * The tablet workspace: one WebView showing assets/desk/ (sidebar, conversation, side panel), talking to the app
 * through a small message bridge (docs/desk-bridge.md) that {@link Desk} answers. The pages (assets/desk/, and
 * assets/fonts/ for the typefaces) are served from https://appassets.androidplatform.net/ straight out of the APK, so
 * they get a normal https origin instead of file:// rules. Nothing else is loaded: the WebView never goes to the network.
 */
public class DeskActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String HOME = "https://" + HOST + "/desk/index.html";
    static final int REQ_MIC = 11, REQ_LOCATION = 12;
    private static final int REQ_PICK = 21, REQ_CAMERA = 22;
    /** Sidebar colour of the dark theme, behind the system bars and before the page paints. */
    private static final int BACKDROP = Color.rgb(0x1f, 0x1e, 0x1d);

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private Desk desk;
    private Desk.Picked picking;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        desk = Desk.get(this);
        DeskService.start(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKDROP);
        root.setFitsSystemWindows(true);
        web = new WebView(this);
        web.setBackgroundColor(BACKDROP);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new Client());
        web.addJavascriptInterface(new Bridge(), "PiggyDesk");
        root.addView(web);
        setContentView(root);
        desk.attach(this);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        if (state == null) web.loadUrl(HOME);
        else web.restoreState(state);
        if (!NetGuard.locationAllowed(this)) {
            // the Wi-Fi name (is this the phone's hotspot?) can only be read with location access
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        desk.attach(this);
        desk.onPageResumed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        back();
    }

    /** Back closes the page's open menu or dialog first; with none open, the app goes to the background. */
    private void back() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript("window.desk&&desk.back?desk.back():false", r -> {
            if (!"true".equals(r)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        desk.detach(this);
        if (web != null) {
            web.removeJavascriptInterface("PiggyDesk");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Sends one message to the page (desk.recv). Safe from any thread. */
    void send(JSONObject msg) {
        final String js = "window.desk&&desk.recv(" + msg + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    // ------------------------------------------------------------------ pickers and permissions

    /** Lets the user pick images or files, or take a photo; {@code done} gets the URIs, or null if cancelled. */
    void pick(String kind, Desk.Picked done) {
        if (picking != null) picking.done(null);
        picking = done;
        try {
            if ("camera".equals(kind)) {
                FilesProvider.captureFile(this).delete();
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, FilesProvider.captureUri())
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMERA);
            } else {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image".equals(kind) ? "image/*" : "*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_PICK);
            }
        } catch (ActivityNotFoundException e) {
            picking = null;
            done.done(null);
            desk.toast("这台平板上没有能用的" + ("camera".equals(kind) ? "相机" : "文件选择器"));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_PICK && request != REQ_CAMERA) return;
        Desk.Picked p = picking;
        picking = null;
        if (p == null) return;
        if (result != RESULT_OK) {
            p.done(null);
            return;
        }
        boolean captured = request == REQ_CAMERA && FilesProvider.captureFile(this).length() > 0;
        // the photo through our own provider, so it is imported as a photo (scaled, turned upright)
        p.done(Desk.uris(request == REQ_PICK ? data : null, captured ? FilesProvider.captureUri() : null));
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_LOCATION) {
            NetGuard.recheck();
            if (NetGuard.locationAllowed(this) && !NetGuard.locationOn(this)) desk.toast("还要打开定位，才能读到 Wi-Fi 名字");
        }
    }

    // ------------------------------------------------------------------ the page

    /** The page's calls into the app; they arrive on a WebView thread and are handled on the main thread. */
    private final class Bridge {
        @JavascriptInterface
        public void post(String json) {
            main.post(() -> {
                if (web == null) return;
                try {
                    desk.onMessage(DeskActivity.this, new JSONObject(json));
                } catch (JSONException ignored) {
                }
            });
        }
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String path = u.getPath();
            if (!HOST.equals(u.getHost()) || path == null || path.contains("..")
                    || !(path.startsWith("/desk/") || path.startsWith("/fonts/"))) {
                return blocked(); // only the bundled pages; the WebView never reaches the network
            }
            String asset = path.substring(1);
            try {
                InputStream in = getAssets().open(asset);
                String type = mime(asset);
                boolean binary = type.startsWith("font/") || (type.startsWith("image/") && !type.equals("image/svg+xml"));
                WebResourceResponse r = new WebResourceResponse(type, binary ? null : "utf-8", in);
                r.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-cache"));
                return r;
            } catch (IOException e) {
                return blocked();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            if ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
            }
            return true;
        }
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Collections.<String, String>emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
