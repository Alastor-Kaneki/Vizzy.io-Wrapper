package dev.alastorkaneki.vizzywrapper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MainActivity extends Activity {
    private static final String TAG = "VizzyWrapper";
    private static final String PREFS = "vizzy_wrapper";
    private static final int PICK_WEB_FILE = 1001;
    private static final int PICK_SAVE_FILE = 1002;
    private static final int WEB_PERMISSION = 1003;

    private static final String UA_CHROME = "windows_chrome";
    private static final String UA_EDGE = "windows_edge";
    private static final String UA_LINUX = "linux_chrome";
    private static final String UA_MAC = "mac_chrome";
    private static final String UA_NATIVE = "native";
    private static final String UA_CUSTOM = "custom";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, OutputStream> saveStreams = new ConcurrentHashMap<>();

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView webView;
    private ProgressBar progress;
    private TextView menuButton;
    private String nativeUserAgent;
    private ValueCallback<Uri[]> fileCallback;
    private PendingSave pendingSave;
    private PermissionRequest pendingPermission;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        applyOrientation();
        ImmersiveMode.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        createWebView();
        createProgress();
        createMenuButton();

        if (state == null) webView.loadUrl(BuildConfig.HOME_URL);
        else webView.restoreState(state);
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        nativeUserAgent = WebSettings.getDefaultUserAgent(this);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        applyUserAgent(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new SaveBridge(), "AndroidSave");
        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new ChromeClient());
        webView.setDownloadListener(new Downloads());
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void createProgress() {
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(142, 82, 182)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        root.addView(progress, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP
        ));
    }

    private void createMenuButton() {
        menuButton = new TextView(this);
        menuButton.setText("⋮");
        menuButton.setTextSize(26);
        menuButton.setTextColor(Color.WHITE);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setContentDescription("Wrapper controls");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(210, 0, 0, 0));
        bg.setStroke(dp(1), Color.rgb(142, 82, 182));
        bg.setCornerRadius(dp(14));
        menuButton.setBackground(bg);
        menuButton.setElevation(dp(10));
        menuButton.setOnClickListener(v -> showControls());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        lp.topMargin = dp(8);
        lp.rightMargin = dp(8);
        root.addView(menuButton, lp);
        menuButton.setVisibility(prefs.getBoolean("menu_visible", true) ? View.VISIBLE : View.GONE);
    }

    private void showControls() {
        String toggle = menuButton.getVisibility() == View.VISIBLE ? "Hide floating control" : "Show floating control";
        String[] items = {
                "Reload editor", "Back", "Forward", "Editor home",
                "Browser identity / user agent", "Desktop viewport width", "Screen orientation",
                "Open current page in browser", "Clear Vizzy site data", toggle,
                "Compatibility status", "Exit app"
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vizzy.io Wrapper")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0 -> webView.reload();
                        case 1 -> { if (webView.canGoBack()) webView.goBack(); }
                        case 2 -> { if (webView.canGoForward()) webView.goForward(); }
                        case 3 -> webView.loadUrl(BuildConfig.HOME_URL);
                        case 4 -> showUserAgents();
                        case 5 -> showViewports();
                        case 6 -> showOrientations();
                        case 7 -> openExternal(webView.getUrl());
                        case 8 -> confirmClearData();
                        case 9 -> toggleMenu();
                        case 10 -> showCompatibility();
                        case 11 -> finishAndRemoveTask();
                    }
                }).create();
        immersiveDialog(dialog);
    }

    private void showUserAgents() {
        String[] labels = {
                "Windows Chrome 150 (recommended)", "Windows Edge 150", "Linux Chrome 150",
                "macOS Chrome 150", "Android System WebView", "Custom user agent"
        };
        String mode = prefs.getString("ua_mode", UA_CHROME);
        int selected = switch (mode) {
            case UA_EDGE -> 1; case UA_LINUX -> 2; case UA_MAC -> 3;
            case UA_NATIVE -> 4; case UA_CUSTOM -> 5; default -> 0;
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Browser identity")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    if (which == 5) { d.dismiss(); showCustomUserAgent(); return; }
                    String value = switch (which) {
                        case 1 -> UA_EDGE; case 2 -> UA_LINUX; case 3 -> UA_MAC;
                        case 4 -> UA_NATIVE; default -> UA_CHROME;
                    };
                    prefs.edit().putString("ua_mode", value).apply();
                    d.dismiss();
                    applyUserAgent(true);
                }).setNegativeButton("Cancel", null).create();
        immersiveDialog(dialog);
    }

    private void showCustomUserAgent() {
        EditText input = new EditText(this);
        input.setMinLines(3);
        input.setText(prefs.getString("custom_ua", UserAgentPresets.WINDOWS_CHROME_150));
        input.setPadding(dp(18), dp(12), dp(18), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Custom user agent")
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    prefs.edit().putString("ua_mode", UA_CUSTOM).putString("custom_ua", value).apply();
                    applyUserAgent(true);
                }).setNegativeButton("Cancel", null).create();
        immersiveDialog(dialog);
    }

    private void applyUserAgent(boolean reload) {
        if (webView == null) return;
        String mode = prefs.getString("ua_mode", UA_CHROME);
        String ua = switch (mode) {
            case UA_EDGE -> UserAgentPresets.WINDOWS_EDGE_150;
            case UA_LINUX -> UserAgentPresets.LINUX_CHROME_150;
            case UA_MAC -> UserAgentPresets.MAC_CHROME_150;
            case UA_NATIVE -> nativeUserAgent;
            case UA_CUSTOM -> prefs.getString("custom_ua", UserAgentPresets.WINDOWS_CHROME_150);
            default -> UserAgentPresets.WINDOWS_CHROME_150;
        };
        webView.getSettings().setUserAgentString(ua);
        if (reload) { webView.clearCache(false); webView.reload(); }
    }

    private void showViewports() {
        String[] labels = {"Device width", "980 px", "1280 px (recommended)", "1440 px", "1920 px"};
        int[] values = {0, 980, 1280, 1440, 1920};
        int current = prefs.getInt("viewport", 1280);
        int selected = current == 0 ? 0 : current == 980 ? 1 : current == 1440 ? 3 : current == 1920 ? 4 : 2;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Desktop viewport")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    prefs.edit().putInt("viewport", values[which]).apply();
                    d.dismiss(); injectCompatibility();
                }).setNegativeButton("Cancel", null).create();
        immersiveDialog(dialog);
    }

    private void showOrientations() {
        String[] labels = {"Automatic", "Landscape", "Portrait"};
        String current = prefs.getString("orientation", "auto");
        int selected = "landscape".equals(current) ? 1 : "portrait".equals(current) ? 2 : 0;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Orientation")
                .setSingleChoiceItems(labels, selected, (d, which) -> {
                    String value = which == 1 ? "landscape" : which == 2 ? "portrait" : "auto";
                    prefs.edit().putString("orientation", value).apply();
                    d.dismiss(); applyOrientation();
                }).setNegativeButton("Cancel", null).create();
        immersiveDialog(dialog);
    }

    private void applyOrientation() {
        if (prefs == null) return;
        String value = prefs.getString("orientation", "auto");
        int orientation = "landscape".equals(value)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : "portrait".equals(value)
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (getRequestedOrientation() != orientation) setRequestedOrientation(orientation);
    }

    private void toggleMenu() {
        boolean show = menuButton.getVisibility() != View.VISIBLE;
        menuButton.setVisibility(show ? View.VISIBLE : View.GONE);
        prefs.edit().putBoolean("menu_visible", show).apply();
        Toast.makeText(this, show ? "Control shown." : "Press Back on the editor home page to reopen controls.", Toast.LENGTH_LONG).show();
    }

    private void confirmClearData() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear Vizzy site data?")
                .setMessage("This removes cookies, login state, cache, and locally stored website projects.")
                .setPositiveButton("Clear", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    webView.clearCache(true);
                    webView.clearHistory();
                    webView.loadUrl(BuildConfig.HOME_URL);
                }).setNegativeButton("Cancel", null).create();
        immersiveDialog(dialog);
    }

    private void showCompatibility() {
        String text = "Desktop UA spoofing, 1280 px desktop layout by default, hardware acceleration, WebGL/Web Audio, native file uploads, direct downloads, blob downloads, and a showSaveFilePicker bridge are enabled.\n\n"
                + "Actual codec and rendering support still depends on Android System WebView and the device GPU. Google may block OAuth inside embedded browsers.";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Compatibility status").setMessage(text).setPositiveButton("OK", null).create();
        immersiveDialog(dialog);
    }

    private void immersiveDialog(AlertDialog dialog) {
        dialog.setOnDismissListener(d -> ImmersiveMode.apply(this));
        dialog.show();
        ImmersiveMode.apply(dialog.getWindow());
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return navigate(request.getUrl());
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return navigate(Uri.parse(url));
        }
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            progress.setVisibility(View.VISIBLE);
            injectCompatibility();
        }
        @Override public void onPageFinished(WebView view, String url) {
            injectCompatibility();
            CookieManager.getInstance().flush();
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) Toast.makeText(MainActivity.this, "Load failed: " + error.getDescription(), Toast.LENGTH_LONG).show();
        }
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Toast.makeText(MainActivity.this, "The editor renderer stopped. Reloading…", Toast.LENGTH_LONG).show();
            String url = view.getUrl() == null ? BuildConfig.HOME_URL : view.getUrl();
            root.removeView(view);
            view.destroy();
            createWebView();
            progress.bringToFront();
            menuButton.bringToFront();
            webView.loadUrl(url);
            return true;
        }
    }

    private boolean navigate(Uri uri) {
        if (uri == null || uri.getScheme() == null) return false;
        String scheme = uri.getScheme().toLowerCase(Locale.US);
        if (scheme.equals("http") || scheme.equals("https")) {
            String host = uri.getHost();
            if (host != null && (host.equals("vizzy.io") || host.endsWith(".vizzy.io"))) return false;
            openExternal(uri.toString());
            return true;
        }
        if (scheme.equals("blob")) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (Exception e) { Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show(); }
        return true;
    }

    private final class ChromeClient extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int value) {
            progress.setProgress(value);
            progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
        }
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            String[] types = acceptTypes(params.getAcceptTypes());
            if (types.length == 1) intent.setType(types[0]);
            else { intent.setType("*/*"); intent.putExtra(Intent.EXTRA_MIME_TYPES, types); }
            try { startActivityForResult(Intent.createChooser(intent, "Choose files for Vizzy"), PICK_WEB_FILE); return true; }
            catch (ActivityNotFoundException e) { fileCallback = null; return false; }
        }
        @Override public void onPermissionRequest(PermissionRequest request) {
            mainHandler.post(() -> requestWebPermissions(request));
        }
        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view; customViewCallback = callback;
            webView.setVisibility(View.GONE); menuButton.setVisibility(View.GONE);
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImmersiveMode.apply(MainActivity.this);
        }
        @Override public void onHideCustomView() { hideCustomView(); }
        @Override public boolean onConsoleMessage(ConsoleMessage message) {
            Log.d(TAG, message.message() + " @ " + message.sourceId() + ":" + message.lineNumber());
            return true;
        }
    }

    private void requestWebPermissions(PermissionRequest request) {
        List<String> needed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.RECORD_AUDIO);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.CAMERA);
        }
        if (needed.isEmpty()) request.grant(request.getResources());
        else { pendingPermission = request; requestPermissions(needed.toArray(new String[0]), WEB_PERMISSION); }
    }

    private String[] acceptTypes(String[] raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw != null) for (String group : raw) if (group != null) for (String item : group.split(",")) {
            String type = item.trim(); if (!type.isEmpty() && !type.startsWith(".")) result.add(type);
        }
        if (result.isEmpty()) result.add("*/*");
        return result.toArray(new String[0]);
    }

    private final class Downloads implements DownloadListener {
        @Override public void onDownloadStart(String url, String userAgent, String disposition, String mime, long length) {
            String name = URLUtil.guessFileName(url, disposition, mime);
            if (url.startsWith("blob:")) {
                webView.evaluateJavascript("window.__vizzyOfferBlobUrl&&window.__vizzyOfferBlobUrl("
                        + JSONObject.quote(url) + "," + JSONObject.quote(name) + "," + JSONObject.quote(mime) + ");", null);
                return;
            }
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(name);
                request.setDescription("Downloading from Vizzy.io");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                request.setMimeType(mime);
                request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                Toast.makeText(MainActivity.this, "Downloading " + name, Toast.LENGTH_LONG).show();
            } catch (Exception e) { openExternal(url); }
        }
    }

    public final class SaveBridge {
        @JavascriptInterface public void pick(String id, String name, String mime) {
            mainHandler.post(() -> {
                if (pendingSave != null) {
                    webView.evaluateJavascript("window.__vizzySavePicked(" + JSONObject.quote(id) + ",false,'');", null);
                    return;
                }
                pendingSave = new PendingSave(id, safeName(name), validMime(mime));
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(pendingSave.mime);
                intent.putExtra(Intent.EXTRA_TITLE, pendingSave.name);
                try { startActivityForResult(intent, PICK_SAVE_FILE); }
                catch (ActivityNotFoundException e) {
                    PendingSave failed = pendingSave; pendingSave = null;
                    webView.evaluateJavascript("window.__vizzySavePicked(" + JSONObject.quote(failed.id) + ",false,'');", null);
                }
            });
        }
        @JavascriptInterface public boolean write(String id, String encoded) {
            OutputStream stream = saveStreams.get(id); if (stream == null) return false;
            try { if (encoded != null && !encoded.isEmpty()) stream.write(Base64.decode(encoded, Base64.DEFAULT)); return true; }
            catch (Exception e) { closeQuietly(stream); saveStreams.remove(id); return false; }
        }
        @JavascriptInterface public boolean close(String id) {
            OutputStream stream = saveStreams.remove(id); if (stream == null) return false;
            try { stream.flush(); stream.close(); mainHandler.post(() -> Toast.makeText(MainActivity.this, "Vizzy export saved.", Toast.LENGTH_LONG).show()); return true; }
            catch (IOException e) { return false; }
        }
        @JavascriptInterface public void abort(String id, String reason) {
            closeQuietly(saveStreams.remove(id));
            mainHandler.post(() -> Toast.makeText(MainActivity.this, "Export cancelled.", Toast.LENGTH_SHORT).show());
        }
    }

    private void injectCompatibility() {
        if (webView == null) return;
        int viewport = prefs.getInt("viewport", 1280);
        String mode = prefs.getString("ua_mode", UA_CHROME);
        String platform = mode.equals(UA_LINUX) ? "Linux x86_64" : mode.equals(UA_MAC) ? "MacIntel" : mode.equals(UA_NATIVE) ? "Linux armv8l" : "Win32";
        String name = mode.equals(UA_LINUX) ? "Linux" : mode.equals(UA_MAC) ? "macOS" : mode.equals(UA_NATIVE) ? "Android" : "Windows";
        webView.evaluateJavascript(WebCompat.script(viewport, platform, name), null);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_WEB_FILE) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    result = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) result = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(result); fileCallback = null; return;
        }
        if (requestCode == PICK_SAVE_FILE) {
            PendingSave request = pendingSave; pendingSave = null; if (request == null) return;
            boolean accepted = resultCode == RESULT_OK && data != null && data.getData() != null;
            if (accepted) try {
                OutputStream stream = getContentResolver().openOutputStream(data.getData(), "w");
                if (stream == null) throw new IOException("No output stream");
                saveStreams.put(request.id, stream);
            } catch (IOException e) { accepted = false; }
            String js = "window.__vizzySavePicked(" + JSONObject.quote(request.id) + "," + accepted + "," + JSONObject.quote(request.name) + ");";
            webView.evaluateJavascript(js, null);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != WEB_PERMISSION || pendingPermission == null) return;
        PermissionRequest request = pendingPermission; pendingPermission = null;
        List<String> allowed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) allowed.add(resource);
            else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) allowed.add(resource);
            else if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && !PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) allowed.add(resource);
        }
        if (allowed.isEmpty()) request.deny(); else request.grant(allowed.toArray(new String[0]));
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView); customView = null; webView.setVisibility(View.VISIBLE);
        menuButton.setVisibility(prefs.getBoolean("menu_visible", true) ? View.VISIBLE : View.GONE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null; ImmersiveMode.apply(this);
    }

    private void openExternal(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "No browser is installed.", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onSaveInstanceState(Bundle out) { webView.saveState(out); super.onSaveInstanceState(out); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); ImmersiveMode.apply(this); }
    @Override protected void onPause() { webView.onPause(); CookieManager.getInstance().flush(); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) ImmersiveMode.apply(this); }
    @Override public void onBackPressed() {
        if (customView != null) hideCustomView();
        else if (webView.canGoBack()) webView.goBack();
        else showControls();
    }
    @Override protected void onDestroy() {
        for (OutputStream stream : saveStreams.values()) closeQuietly(stream);
        saveStreams.clear();
        if (webView != null) { webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeAllViews(); webView.destroy(); }
        super.onDestroy();
    }

    private static String safeName(String value) {
        if (value == null || value.trim().isEmpty()) return "vizzy-export.webm";
        String cleaned = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "vizzy-export.webm" : cleaned;
    }
    private static String validMime(String value) { return value == null || !value.contains("/") ? "application/octet-stream" : value; }
    private static void closeQuietly(OutputStream stream) { if (stream != null) try { stream.close(); } catch (IOException ignored) {} }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class PendingSave {
        final String id; final String name; final String mime;
        PendingSave(String id, String name, String mime) { this.id=id; this.name=name; this.mime=mime; }
    }
}
