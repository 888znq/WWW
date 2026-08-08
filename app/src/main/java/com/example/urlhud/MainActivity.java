package com.example.urlhud;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * MainActivity — Multi-pane WebView browser with Extension features ported:
 * - HeaderBypassWebViewClient (declarativeNetRequest equivalent)
 * - DownloadManagerBridge (chrome.downloads background bridge)
 * - PasswordBridge (password manager autofill + save)
 * - Firebase RTDB sync (bookmarks, passwords, tabs)
 * - Trinity sync clipboard monitor
 *
 * Pane creation/layout is entirely owned by PaneManager (see its class doc);
 * this class only supplies the WebViewFactory/ZoomListener/ZoomIO callbacks
 * PaneManager needs, and tracks which pane is currently "active" (focused)
 * since PaneManager itself has no concept of focus.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final String START_URL = "https://example.com";
    private static final float ZOOM_MIN = 0.5f;
    private static final float ZOOM_MAX = 2.0f;
    private static final float ZOOM_STEP = 0.1f;

    // UI
    public WebView barWebView;
    private FrameLayout rootContainer;

    // Pane management
    private PaneManager paneManager;
    private SessionStore sessionStore;
    private WebView activePaneWebView;
    private final List<WebView> allPaneWebViews = new ArrayList<>();
    private final Map<WebView, Float> zoomLevels = new HashMap<>();

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
        sessionStore = new SessionStore(this);

        setupBarWebView();
        setupPaneManager();
        setupDownloads();

        // Restore or create initial pane
        WebView initialPane = restoreSessionOrNull();
        if (initialPane == null) {
            initialPane = paneManager.init(START_URL);
        }
        setActivePane(initialPane);
    }

    private WebView restoreSessionOrNull() {
        String sessionJson = sessionStore.load();
        if (sessionJson == null || sessionJson.isEmpty()) return null;
        try {
            JSONObject tree = new JSONObject(sessionJson);
            return paneManager.restore(tree);
        } catch (JSONException e) {
            Log.e(TAG, "Session restore failed, starting fresh", e);
            return null;
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
        barWebView.setWebViewClient(new android.webkit.WebViewClient() {
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
        paneManager = new PaneManager(this, rootContainer, this::createPaneWebView, paneZoomListener, paneZoomIO);
    }

    private final PaneManager.ZoomListener paneZoomListener = new PaneManager.ZoomListener() {
        @Override
        public void onZoomIn(WebView pane) {
            adjustZoom(pane, ZOOM_STEP);
        }

        @Override
        public void onZoomOut(WebView pane) {
            adjustZoom(pane, -ZOOM_STEP);
        }
    };

    private final PaneManager.ZoomIO paneZoomIO = new PaneManager.ZoomIO() {
        @Override
        public float getZoom(WebView pane) {
            Float z = zoomLevels.get(pane);
            return z != null ? z : 1f;
        }

        @Override
        public void setInitialZoom(WebView pane, float zoom) {
            zoomLevels.put(pane, zoom);
            applyZoomVisual(pane, zoom);
        }
    };

    /** WebViewFactory callback: PaneManager calls this whenever it needs a brand new pane. */
    private WebView createPaneWebView(String url) {
        WebView webView = new WebView(this);
        allPaneWebViews.add(webView);
        zoomLevels.put(webView, 1f);
        webView.setTag(url);

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
            public void onPageFinished(WebView view, String pageUrl) {
                super.onPageFinished(view, pageUrl);
                view.setTag(pageUrl); // PaneManager.serialize() reads the last URL off the tag
                injectTrinitySyncScript(view);
                injectPasswordCaptureScript(view);
                applyZoomVisual(view, paneZoomIO.getZoom(view));

                // Only the focused pane should drive the toolbar - a background
                // pane finishing a load shouldn't hijack the address bar.
                if (view == activePaneWebView) {
                    updateToolbarForActivePane(view);
                }
                persistSession();
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
            public void onDownloadStart(String downloadUrl, String userAgent, String contentDisposition,
                                          String mimetype, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
                request.setMimeType(mimetype);
                String cookies = CookieManager.getInstance().getCookie(downloadUrl);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file...");
                request.setTitle(URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype));
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype));

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

        // PaneManager has no built-in focus tracking (unlike the extension's
        // per-pane focusCatcher overlay), so mark this pane active on first
        // touch. Returning false lets the WebView still handle the touch
        // normally (scrolling, tapping links, etc.).
        webView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && v != activePaneWebView) {
                setActivePane((WebView) v);
            }
            return false;
        });

        webView.loadUrl(url);
        return webView;
    }

    /** Marks `webView` as the focused pane and refreshes the toolbar to match it. */
    private void setActivePane(WebView webView) {
        activePaneWebView = webView;
        if (paneManager != null) paneManager.setActivePane(webView);
        if (webView != null) updateToolbarForActivePane(webView);
    }

    private void updateToolbarForActivePane(WebView webView) {
        barWebView.evaluateJavascript(
            "javascript:updateAddress('" + escapeJs(webView.getUrl()) + "')", null);
        barWebView.evaluateJavascript(
            "javascript:setNavState(" + webView.canGoBack() + "," + webView.canGoForward() + ")", null);
        barWebView.evaluateJavascript(
            "javascript:setSplitState(" + paneManager.hasSplit() + ")", null);
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
        if (activePaneWebView == null) return;
        activePaneWebView.loadUrl(url);
        activePaneWebView.setTag(url);
        persistSession();
    }

    public void goBackActivePane() {
        if (activePaneWebView != null && activePaneWebView.canGoBack()) {
            activePaneWebView.goBack();
            persistSession();
        }
    }

    public void goForwardActivePane() {
        if (activePaneWebView != null && activePaneWebView.canGoForward()) {
            activePaneWebView.goForward();
            persistSession();
        }
    }

    public void zoomActivePane(float delta) {
        if (activePaneWebView != null) {
            adjustZoom(activePaneWebView, delta);
        }
    }

    private void adjustZoom(WebView pane, float delta) {
        if (pane == null) return;
        Float current = zoomLevels.get(pane);
        float next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, (current != null ? current : 1f) + delta));
        next = Math.round(next * 100) / 100f;
        zoomLevels.put(pane, next);
        applyZoomVisual(pane, next);
        persistSession();
    }

    private void applyZoomVisual(WebView pane, float zoom) {
        pane.setInitialScale((int) (zoom * 100));
        // Also inject CSS zoom for consistency
        pane.evaluateJavascript(
            "javascript:document.body && (document.body.style.zoom='" + zoom + "')", null);
    }

    public void toggleFullscreenNative() {
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
            "javascript:setSplitState(" + paneManager.hasSplit() + ")", null);
    }

    public void splitActivePane(String direction) {
        if (activePaneWebView == null) return;
        WebView newPane = paneManager.splitPane(activePaneWebView, direction);
        if (newPane != null) {
            setActivePane(newPane);
            persistSession();
        }
    }

    public void closeActivePaneNative() {
        WebView closing = activePaneWebView;
        if (closing == null) return;
        WebView next = paneManager.closePane(closing);
        // closePane() already destroys `closing`'s WebView once it's detached;
        // just drop our own bookkeeping for it.
        allPaneWebViews.remove(closing);
        zoomLevels.remove(closing);
        setActivePane(next); // null if that was the last pane (closePane() refuses to close it)
        persistSession();
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
    private void persistSession() {
        try {
            JSONObject tree = paneManager.serialize();
            if (tree != null) sessionStore.save(tree.toString());
        } catch (Exception e) {
            Log.e(TAG, "Session save failed", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistSession();
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
