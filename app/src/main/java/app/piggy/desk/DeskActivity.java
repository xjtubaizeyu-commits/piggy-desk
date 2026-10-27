package app.piggy.desk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * The tablet workspace: one WebView showing assets/desk/ (sidebar, conversation, side panel), talking to the app
 * through a small message bridge (docs/desk-bridge.md). The pages (assets/desk/, and assets/fonts/ for the typefaces) are
 * served from https://appassets.androidplatform.net/ straight out of the APK, so they get a normal https origin instead
 * of file:// rules. Nothing else is loaded: the
 * WebView never goes to the network itself.
 */
public class DeskActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String HOME = "https://" + HOST + "/desk/index.html";
    /** Sidebar colour of the dark theme, behind the system bars and before the page paints. */
    private static final int BACKDROP = Color.rgb(0x1f, 0x1e, 0x1d);

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        if (state == null) web.loadUrl(HOME);
        else web.restoreState(state);
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

    /** Answers a page request that carried an id. */
    void reply(JSONObject req, Object data, String error) {
        try {
            JSONObject r = new JSONObject().put("type", "reply").put("id", req.opt("id"));
            if (error != null) r.put("ok", false).put("error", error);
            else r.put("ok", true).put("data", data == null ? JSONObject.NULL : data);
            send(r);
        } catch (JSONException ignored) {
        }
    }

    private void onMessage(String json) {
        JSONObject m;
        try {
            m = new JSONObject(json);
        } catch (JSONException e) {
            return;
        }
        switch (m.optString("type")) {
            case "ready":
                sendState();
                break;
            case "openUrl": {
                Uri u = Uri.parse(m.optString("url"));
                if ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {
                    }
                }
                break;
            }
            case "copy":
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Piggy Desk", m.optString("text")));
                break;
            case "toast":
                Toast.makeText(this, m.optString("text"), Toast.LENGTH_SHORT).show();
                break;
            default:
                if (m.has("id")) reply(m, null, "unknown request: " + m.optString("type"));
        }
    }

    /** Everything the page shows at start. */
    private void sendState() {
        try {
            JSONObject app = new JSONObject().put("version", BuildInfo.VERSION).put("id", BuildInfo.APP_ID);
            send(new JSONObject().put("type", "state").put("app", app));
        } catch (JSONException ignored) {
        }
    }

    /** The page's calls into the app; they arrive on a WebView thread and are handled on the main thread. */
    private final class Bridge {
        @JavascriptInterface
        public void post(String json) {
            main.post(() -> {
                if (web != null) onMessage(json);
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
