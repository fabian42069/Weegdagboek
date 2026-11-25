package nl.weegdagboek.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    /** Tekst in de taal die in de app is gekozen (NL, EN of JP). */
    private String tr(String nl, String en, String ja) {
        String l = WeightWidget.lang(this);
        return "en".equals(l) ? en : "ja".equals(l) ? ja : nl;
    }


    private static final String ORIGIN = "https://weegdagboek.app/";
    private static final int REQ_FILE_CHOOSER = 1;
    private static final int REQ_SAVE_FILE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSaveText;
    private SecureStore secure;
    private volatile int loginGeneration = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secure = new SecureStore(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF24273A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "WeegApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                openExternal(request.getUrl());
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, tr("Back-up kiezen", "Choose a backup", "バックアップを選択")), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadDataWithBaseURL(ORIGIN, readAsset("index.html"), "text/html", "utf-8", null);
        }
    }

    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return new String(Nextcloud.readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<p>Kan de app niet laden.</p>";
        }
    }

    private void callJs(String fn, String jsonArg) {
        final String js = "window." + fn + " && window." + fn + "(" + jsonArg + ")";
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback != null) {
                Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
                fileCallback.onReceiveValue(uri != null ? new Uri[]{uri} : null);
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE_FILE) {
            String text = pendingSaveText;
            pendingSaveText = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || text == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, tr("Bestand opgeslagen", "File saved", "ファイルを保存しました"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, tr("Opslaan mislukt", "Saving failed", "保存できませんでした"), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /**
     * Wordt vanuit de pagina aangeroepen als window.WeegApp.
     * Het app-wachtwoord komt nooit in de pagina: alleen deze kant kent het.
     */
    private class Bridge {

        @JavascriptInterface
        public void saveFile(final String name, final String text, final String mime) {
            ui.post(() -> {
                pendingSaveText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_SAVE_FILE); }
                catch (Exception e) { pendingSaveText = null; }
            });
        }

        /** Metingen doorgeven aan de startscherm-widget: [{"date":"YYYY-MM-DD","kg":79.1}, ...] */
        @JavascriptInterface
        public void widgetData(final String entriesJson) {
            try { WeightWidget.saveAndRefresh(getApplicationContext(), entriesJson); } catch (Exception ignored) { }
        }

        /** {"linked":bool,"server":..,"user":..} — zonder wachtwoord. */
        @JavascriptInterface
        public String ncStatus() {
            try {
                SecureStore.Account a = secure.load();
                JSONObject o = new JSONObject();
                o.put("linked", a != null);
                if (a != null) { o.put("server", a.server); o.put("user", a.userId); }
                return o.toString();
            } catch (Exception e) {
                return "{\"linked\":false}";
            }
        }

        /** Start Login Flow v2: opent de browser en wacht tot de gebruiker heeft ingelogd. Resultaat via window.__weegLogin. */
        @JavascriptInterface
        public void ncStartLogin(final String serverInput) {
            final int gen = ++loginGeneration;
            new Thread(() -> {
                JSONObject result = new JSONObject();
                try {
                    String server = Nextcloud.normalizeServer(serverInput);
                    JSONObject start = Nextcloud.startLogin(server);
                    String loginUrl = start.getString("login");
                    JSONObject poll = start.getJSONObject("poll");
                    String token = poll.getString("token");
                    String endpoint = poll.getString("endpoint");
                    // Het token alleen naar dezelfde server sturen
                    if (!new URL(endpoint).getHost().equalsIgnoreCase(new URL(server).getHost())) {
                        throw new IllegalStateException("host");
                    }
                    callJs("__weegLoginWaiting", "null");
                    ui.post(() -> openExternal(Uri.parse(loginUrl)));

                    long deadline = System.currentTimeMillis() + 20 * 60 * 1000L;
                    JSONObject done = null;
                    while (done == null && System.currentTimeMillis() < deadline) {
                        Thread.sleep(2000);
                        if (gen != loginGeneration) return; // geannuleerd of opnieuw gestart
                        try { done = Nextcloud.poll(endpoint, token); }
                        catch (java.io.IOException netHiccup) { /* even geen netwerk: blijf proberen */ }
                    }
                    if (done == null) throw new IllegalStateException("timeout");

                    String srv = Nextcloud.normalizeServer(done.getString("server"));
                    String login = done.getString("loginName");
                    String pass = done.getString("appPassword");
                    String userId = Nextcloud.userId(srv, login, pass);
                    secure.save(new SecureStore.Account(srv, userId, login, pass));
                    result.put("ok", true);
                    result.put("server", srv);
                    result.put("user", userId);
                } catch (Exception e) {
                    if (gen != loginGeneration) return;
                    String m = String.valueOf(e.getMessage());
                    String code = m.startsWith("start:") ? "not_nextcloud"
                            : m.equals("timeout") ? "timeout"
                            : m.equals("host") ? "host"
                            : (e instanceof java.io.IOException) ? "network" : "failed";
                    try { result.put("ok", false); result.put("error", code); } catch (Exception ignored) { }
                }
                callJs("__weegLogin", result.toString());
            }).start();
        }

        @JavascriptInterface
        public void ncCancelLogin() {
            loginGeneration++;
        }

        /** GET of PUT van <map>/weegdagboek.json. Resultaat via window.__weegRequestDone(id, status, body). */
        @JavascriptInterface
        public void ncRequest(final String id, final String method, final String folder, final String body) {
            new Thread(() -> {
                int code = 0;
                String text = "";
                try {
                    SecureStore.Account a = secure.load();
                    if (a == null) { code = -1; }
                    else if (!"GET".equals(method) && !"PUT".equals(method)) { code = -2; }
                    else {
                        String dir = folder == null ? "" : folder;
                        String url = Nextcloud.davFileUrl(a, dir, "weegdagboek.json");
                        Nextcloud.Response r = Nextcloud.http(method, url, a.loginName, a.appPassword,
                                "application/json; charset=utf-8", "PUT".equals(method) ? body : null, false);
                        // Map bestaat nog niet: aanmaken en het opslaan één keer opnieuw proberen
                        if ("PUT".equals(method) && (r.status == 404 || r.status == 409)) {
                            if (Nextcloud.mkdirs(a, dir)) {
                                r = Nextcloud.http(method, url, a.loginName, a.appPassword,
                                        "application/json; charset=utf-8", body, false);
                            }
                        }
                        code = r.status;
                        text = r.body;
                    }
                } catch (Exception e) {
                    code = 0;
                }
                final String js = "window.__weegRequestDone(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")";
                ui.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        /** Ontkoppelen: app-wachtwoord intrekken bij Nextcloud en lokaal wissen. Resultaat via window.__weegUnlinked. */
        @JavascriptInterface
        public void ncUnlink() {
            loginGeneration++;
            new Thread(() -> {
                SecureStore.Account a = secure.load();
                boolean revoked = a != null && Nextcloud.revoke(a);
                secure.clear();
                callJs("__weegUnlinked", revoked ? "true" : "false");
            }).start();
        }
    }
}
