package com.example.urlhud;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * MainActivity — Multi-pane WebView browser with Extension features ported:
 * - HeaderBypassWebViewClient (declarativeNetRequest equivalent)
 * - DownloadManagerBridge (chrome.downloads background bridge)
 * - PasswordBridge (password manager autofill + save)
 * - Firebase RTDB sync (bookmarks, passwords, tabs)
 * - Trinity sync clipboard monitor
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    // UI
    public WebView barWebView;
    private FrameLayout rootContainer;

    // Pane management
    private PaneManager paneManager;
    private WebView activePaneWebView;
    private final List<WebView> allPaneWebViews = new ArrayList<>();

    // Bridges
    private DownloadManagerBridge downloadBridge;
    private ClipboardBridge clipboardBridge;

    // State
    private boolean isFullscreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootContainer = findViewById(R.id.root_container);
        barWebView = findViewById(R.id.bar_webview);

        setupBarWebView();
        setupPaneManager();
        setupDownloads();

        // Restore or create initial pane
        String session = SessionStore.load(this);
        if (session != null && !session.isEmpty()) {
            paneManager.restoreSession(session);
        } else {
            paneManager.addPane("https://example.com");
        }
    }

    // =========================================================
    // Toolbar WebView Setup
    // =========================================================
    private void setupBarWebView() {
        WebSettings s = barWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);        // REQUIRED for Firebase
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        barWebView.setWebChromeClient(new WebChromeClient());
        barWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Push initial download list to toolbar
                if (downloadBridge != null) downloadBridge.broadcastAll();
            }
        });

        barWebView.addJavascriptInterface(new WebAppInterface(this, barWebView), "AndroidAPI");

        barWebView.loadUrl("file:///android_asset/bar.html");

        // Start download bridge
        downloadBridge = DownloadManagerBridge.getInstance(this);
        downloadBridge.attachBarWebView(barWebView);
        downloadBridge.startPolling();
    }

    // =========================================================
    // Pane Manager Setup
    // =========================================================
    private void setupPaneManager() {
        paneManager = new PaneManager(rootContainer, this::onPaneCreated, this::onActivePaneChanged);
    }

    private void onPaneCreated(WebView webView) {
        allPaneWebViews.add(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 1:1 extension header bypass
        webView.setWebViewClient(new HeaderBypassWebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectTrinitySyncScript(view);
                injectPasswordCaptureScript(view);

                // Update toolbar
                barWebView.evaluateJavascript(
                    "javascript:updateAddress('" + escapeJs(url) + "')", null);
                barWebView.evaluateJavascript(
                    "javascript:setNavState(" + view.canGoBack() + "," + view.canGoForward() + ")", null);

                // Update split button state
                boolean canClose = paneManager.getPaneCount() > 1;
                barWebView.evaluateJavascript(
                    "javascript:setSplitState(" + canClose + ")", null);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error: " + description + " at " + failingUrl);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // Optional: show progress in toolbar
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                          String mimetype, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file...");
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype));

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(request);

                Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(this, webView), "AndroidAPI");
        webView.addJavascriptInterface(new PasswordBridge(this), "PasswordBridge");

        // Clipboard bridge for specific domains (original feature preserved)
        clipboardBridge = new ClipboardBridge(this);
        webView.addJavascriptInterface(clipboardBridge, "ClipboardBridge");
    }

    private void onActivePaneChanged(WebView webView) {
        activePaneWebView = webView;
        if (webView != null) {
            barWebView.evaluateJavascript(
                "javascript:updateAddress('" + escapeJs(webView.getUrl()) + "')", null);
            barWebView.evaluateJavascript(
                "javascript:setNavState(" + webView.canGoBack() + "," + webView.canGoForward() + ")", null);
        }
    }

    // =========================================================
    // Script Injection
    // =========================================================
    private void injectTrinitySyncScript(WebView webView) {
        try {
            InputStream is = getAssets().open("trinity_sync.js");
            Scanner sc = new Scanner(is).useDelimiter("\\A");
            String js = sc.hasNext() ? sc.next() : "";
            is.close();
            if (!js.isEmpty()) {
                webView.evaluateJavascript(js, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject trinity_sync.js", e);
        }
    }

    private void injectPasswordCaptureScript(WebView webView) {
        String js = "(function(){" +
            "if(window.__pwdCaptureInstalled)return;window.__pwdCaptureInstalled=true;" +
            "document.addEventListener('submit',function(e){" +
            "var f=e.target;var u=f.querySelector('input[type=text],input[type=email],input[name=username],input[name=login]');" +
            "var p=f.querySelector('input[type=password]');" +
            "if(u&&p&&u.value&&p.value){" +
            "try{PasswordBridge.reportPassword(location.hostname,u.value,p.value);}catch(err){}" +
            "}},true);" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    // =========================================================
    // Toolbar JS Bridge Methods
    // =========================================================
    public void broadcastJsToAllPanes(String jsonPayload) {
        runOnUiThread(() -> {
            String js = "javascript:(function(){" +
                "try{ window.postMessage(" + jsonPayload + ", '*'); }catch(e){}" +
                "})();";
            for (WebView wv : allPaneWebViews) {
                wv.evaluateJavascript(js, null);
            }
        });
    }

    public void loadUrlInActivePane(String url) {
        runOnUiThread(() -> {
            if (activePaneWebView != null) {
                activePaneWebView.loadUrl(url);
            }
        });
    }

    public void goBackActivePane() {
        runOnUiThread(() -> {
            if (activePaneWebView != null && activePaneWebView.canGoBack()) {
                activePaneWebView.goBack();
            }
        });
    }

    public void goForwardActivePane() {
        runOnUiThread(() -> {
            if (activePaneWebView != null && activePaneWebView.canGoForward()) {
                activePaneWebView.goForward();
            }
        });
    }

    public void zoomActivePane(float delta) {
        runOnUiThread(() -> {
            if (activePaneWebView != null) {
                float current = activePaneWebView.getScale();
                float next = Math.max(0.5f, Math.min(2.0f, current + delta));
                activePaneWebView.setInitialScale((int) (next * 100));
                // Also inject CSS zoom for consistency
                activePaneWebView.evaluateJavascript(
                    "javascript:document.body.style.zoom='" + next + "'", null);
            }
        });
    }

    public void toggleFullscreenNative() {
        runOnUiThread(() -> {
            if (!isFullscreen) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                barWebView.setVisibility(View.GONE);
                isFullscreen = true;
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                barWebView.setVisibility(View.VISIBLE);
                isFullscreen = false;
            }
            barWebView.evaluateJavascript(
                "javascript:setSplitState(" + (paneManager.getPaneCount() > 1) + ")", null);
        });
    }

    public void splitActivePane(String direction) {
        runOnUiThread(() -> {
            if (activePaneWebView != null) {
                String currentUrl = activePaneWebView.getUrl();
                paneManager.splitPane(activePaneWebView, direction, currentUrl);
            }
        });
    }

    public void closeActivePaneNative() {
        runOnUiThread(() -> {
            if (activePaneWebView != null) {
                paneManager.removePane(activePaneWebView);
            }
        });
    }

    // =========================================================
    // Download Helpers
    // =========================================================
    public void openDownloadById(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        android.database.Cursor c = dm.query(query);
        if (c != null && c.moveToFirst()) {
            String uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            c.close();
            if (uri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(uri), "*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        }
    }

    public void showDownloadById(long id) {
        Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        startActivity(intent);
    }

    private void setupDownloads() {
        // Downloads are handled by DownloadListener on each WebView + DownloadManagerBridge polling
    }

    // =========================================================
    // Session Persistence
    // =========================================================
    @Override
    protected void onPause() {
        super.onPause();
        try {
            String session = paneManager.serializeSession();
            SessionStore.save(this, session);
        } catch (Exception e) {
            Log.e(TAG, "Session save failed", e);
        }
    }

    // =========================================================
    // Utilities
    // =========================================================
    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }

    public WebView getActivePaneWebView() {
        return activePaneWebView;
    }

    public List<WebView> getAllPaneWebViews() {
        return new ArrayList<>(allPaneWebViews);
    }

    // Inner class placeholder for URLUtil if not imported
    private static class URLUtil {
        static String guessFileName(String url, String contentDisposition, String mimeType) {
            return android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
        }
    }
}
