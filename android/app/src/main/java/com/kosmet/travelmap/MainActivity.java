package com.kosmet.travelmap;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosts the GlobeTint web app (bundled in assets/www) in a full-screen WebView.
 * Pages are served from https://appassets.androidplatform.net so IndexedDB and
 * geolocation behave like on a normal https site.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_PICK_FILE = 1;
    private static final int REQ_SAVE_FILE = 2;
    private static final int REQ_LOCATION = 3;

    /** How long a downloaded page gets to report it started before the bundled page is used again. */
    private static final long PAGE_START_TIMEOUT = 15000;
    /** A page update downloaded in the background is applied when returning after this long. */
    private static final long RELOAD_AFTER_AWAY = 10 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private WebUpdater web;
    private ValueCallback<Uri[]> pendingPick;
    private byte[] pendingSave;
    private String pendingSaveMessage;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private boolean pageReady;
    private boolean pageUpdatePending;
    private boolean installAfterPermission;
    private long pausedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebUpdater(this);
        // A downloaded page (see WebUpdater) is served at the same URL as the bundled one.
        final WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", path -> {
                    WebResourceResponse page = web.intercept(path);
                    return page != null ? page : assets.handle(path);
                })
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF4F6FA);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.addJavascriptInterface(new Bridge(), "GlobeTintAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                // Anything outside the app opens in the browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                // Backups are JSON, but file managers label them differently; the page validates the file.
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingPick = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }
                if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, false, false);
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_URL);
        watchPageStart();

        ApkInstaller.handleStatus(this, getIntent(), this::toast);
        if (savedInstanceState == null) checkForUpdate(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION || pendingGeoCallback == null) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
    }

    /** If a downloaded page never reports that it started, fall back to the bundled page. */
    private void watchPageStart() {
        pageReady = false;
        handler.removeCallbacks(pageStartCheck);
        if (web.isActive()) handler.postDelayed(pageStartCheck, PAGE_START_TIMEOUT);
    }

    private final Runnable pageStartCheck = () -> {
        if (pageReady || !web.isActive()) return;
        web.rollBack();
        webView.loadUrl(START_URL);
        pageReady = false;
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ApkInstaller.handleStatus(this, intent, this::toast);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (installAfterPermission && getPackageManager().canRequestPackageInstalls()) {
            installAfterPermission = false;
            startApkInstall();
        } else if (pageUpdatePending && pausedAt > 0
                && System.currentTimeMillis() - pausedAt > RELOAD_AFTER_AWAY) {
            pageUpdatePending = false;
            webView.loadUrl(START_URL);
            watchPageStart();
        }
    }

    /* ---------- update check ---------- */

    private static final long UPDATE_CHECK_INTERVAL = 12 * 60 * 60 * 1000L;

    /**
     * Two kinds of updates:
     * 1. The web page (WebUpdater): downloaded silently and used from the next start.
     * 2. The APK: the latest GitHub Release (tagged v1.0.<versionCode>); offered in a dialog
     *    and installed from inside the app (ApkInstaller).
     * On launch the page is checked every time and the APK at most every 12 hours, silently;
     * "Check for updates" in the settings checks both right away and reports the result.
     */
    private void checkForUpdate(final boolean manual) {
        final SharedPreferences prefs = getSharedPreferences("update", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        final boolean checkApk = manual || now - prefs.getLong("lastCheck", 0) >= UPDATE_CHECK_INTERVAL;
        if (checkApk) prefs.edit().putLong("lastCheck", now).apply();
        if (manual) toast(Strings.get(this, "checking"));

        new Thread(() -> {
            boolean newPage = false, pageChecked = false;
            try {
                newPage = web.check();
                pageChecked = true;
            } catch (Exception ignored) {
                // Offline or GitHub unreachable: keep the current page.
            }
            final boolean pageUpdated = newPage;
            if (pageUpdated && !manual) runOnUiThread(() -> pageUpdatePending = true);
            if (!checkApk) return;
            try {
                URL api = new URL("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest");
                HttpURLConnection c = (HttpURLConnection) api.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                String body;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                    body = buf.toString("UTF-8");
                }
                JSONObject release = new JSONObject(body);
                String tag = release.optString("tag_name", "");
                final long latest = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
                final String name = tag.startsWith("v") ? tag.substring(1) : tag;
                // A newer release with the same native part as this app only has web changes, which
                // WebUpdater already brings in quietly; only offer the APK when the native part changed.
                Matcher nat = Pattern.compile("native: ([0-9a-f]{12})").matcher(release.optString("body", ""));
                boolean sameNative = nat.find() && nat.group(1).equals(BuildConfig.NATIVE_HASH);
                if (latest > installedVersionCode() && !sameNative) runOnUiThread(() -> showUpdateDialog(name));
                else if (manual && pageUpdated) runOnUiThread(this::showPageUpdatedDialog);
                else if (manual) toast(Strings.get(this, "latest"));
            } catch (Exception e) {
                // No network, rate limit or unexpected response: the automatic check tries again later.
                if (manual && pageUpdated) runOnUiThread(this::showPageUpdatedDialog);
                else if (manual && pageChecked) toast(Strings.get(this, "latest"));
                else if (manual) toast(Strings.get(this, "offline"));
            }
        }).start();
    }

    private void showPageUpdatedDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(Strings.get(this, "pageTitle"))
                .setMessage(Strings.get(this, "pageText"))
                .setPositiveButton(Strings.get(this, "restart"), (d, w) -> {
                    pageUpdatePending = false;
                    webView.loadUrl(START_URL);
                    watchPageStart();
                })
                .setNegativeButton(Strings.get(this, "later"), (d, w) -> pageUpdatePending = true)
                .show();
    }

    private void startApkInstall() {
        if (!ApkInstaller.ensureAllowed(this)) {
            installAfterPermission = true;
            Toast.makeText(this, Strings.get(this, "allowInstall"), Toast.LENGTH_LONG).show();
            return;
        }
        toast(Strings.get(this, "downloading"));
        new Thread(() -> ApkInstaller.downloadAndInstall(this, this::toast)).start();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private long installedVersionCode() throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void showUpdateDialog(String version) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(Strings.get(this, "apkTitle"))
                .setMessage(String.format(Strings.get(this, "apkText"), version))
                .setPositiveButton(Strings.get(this, "update"), (d, w) -> startApkInstall())
                .setNegativeButton(Strings.get(this, "later"), null)
                .show();
    }

    /** Opens the system "save as" screen for bytes the page produced (backup JSON, map image). */
    private void saveBytes(String name, String mime, byte[] data, String doneMessage) {
        pendingSave = data;
        pendingSaveMessage = doneMessage;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_SAVE_FILE);
        } catch (ActivityNotFoundException e) {
            pendingSave = null;
            Toast.makeText(this, Strings.get(this, "noSaver"), Toast.LENGTH_LONG).show();
        }
    }

    /** Methods index.html can call as window.GlobeTintAndroid.*. */
    private class Bridge {
        @JavascriptInterface
        public String getVersion() {
            String page = web.activeId();
            return page.isEmpty() ? BuildConfig.VERSION_NAME : BuildConfig.VERSION_NAME + " · page " + page;
        }

        /** Called by index.html once it has rendered; proves a downloaded page works. */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> pageReady = true);
        }

        @JavascriptInterface
        public void checkForUpdate() {
            runOnUiThread(() -> MainActivity.this.checkForUpdate(true));
        }

        /** Saves a backup; WebView cannot download blob: URLs. */
        @JavascriptInterface
        public void saveFile(final String name, final String text) {
            final byte[] data = text.getBytes(StandardCharsets.UTF_8);
            runOnUiThread(() -> saveBytes(name, "application/json", data, Strings.get(MainActivity.this, "backupSaved")));
        }

        /** Saves binary data passed as base64 (the map image, a CSV export). */
        @JavascriptInterface
        public void saveBase64(final String name, final String mime, final String base64) {
            final byte[] data = Base64.decode(base64, Base64.DEFAULT);
            final String done = mime.startsWith("image/") ? "imageSaved" : "fileSaved";
            runOnUiThread(() -> saveBytes(name, mime, data, Strings.get(MainActivity.this, done)));
        }

        /** Shares a PNG (base64) through Android's share sheet. */
        @JavascriptInterface
        public void shareImage(final String base64, final String name) {
            try {
                File dir = new File(getCacheDir(), "share");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no dir");
                File file = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(Base64.decode(base64, Base64.DEFAULT));
                }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", file);
                runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, null));
                });
            } catch (Exception e) {
                toast(Strings.get(MainActivity.this, "shareFailed"));
            }
        }

        /** Matches the system bars to the page theme. */
        @JavascriptInterface
        public void setBars(final String color, final boolean lightTheme) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(color);
                    Window w = getWindow();
                    w.setStatusBarColor(c);
                    w.setNavigationBarColor(c);
                    webView.setBackgroundColor(c);
                    int flags = w.getDecorView().getSystemUiVisibility();
                    int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    w.getDecorView().setSystemUiVisibility(lightTheme ? flags | light : flags & ~light);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_PICK_FILE && pendingPick != null) {
            pendingPick.onReceiveValue(uri != null ? new Uri[]{uri} : null);
            pendingPick = null;
        } else if (requestCode == REQ_SAVE_FILE) {
            byte[] bytes = pendingSave;
            String done = pendingSaveMessage;
            pendingSave = null;
            if (uri == null || bytes == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(bytes);
                Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, Strings.get(this, "saveFailed"), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** The page closes its own sheets and screens first; only then does Back leave the app. */
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.gtBack ? gtBack() : false", value -> {
            if (!"true".equals(value)) super.onBackPressed();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
