package com.example.urlhud;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements DownloadsController.Listener {

    private static final String START_URL = "https://example.com";

    private static final float ZOOM_MIN = -6f; 
    private static final float ZOOM_MAX = 9f;  
    private static final float ZOOM_STEP = 0.5f;

    private static final int FILE_CHOOSER_REQUEST_CODE = 51426;

    // TRINITY_SYNC: Authorized domains for script injection
    private static final List<String> TRINITY_SYNC_DOMAINS = Arrays.asList(
            "olymptrade.com",
            "pocketoption.com"
    );

    private FrameLayout paneSlot;
    private WebView barWebView;
    private View exitFullscreenButton;
    private PaneManager paneManager;
    private SessionStore sessionStore;
    private ZoomStore zoomStore;
    private DownloadsController downloadsController;
    private BookmarkStore bookmarkStore;

    private WebView activePane;
    private final Map<WebView, Float> zoomLevels = new HashMap<>();
    // Panes whose very first page load should keep the zoom level they were
    // seeded with (persistedZoom) instead of having it reset to 0f by
    // PaneWebViewClient#onPageStarted - see createPaneWebView().
    private final java.util.Set<WebView> pendingInitialZoom = new java.util.HashSet<>();
    // Last zoom level the user set, persisted via ZoomStore so panes come
    // back at this zoom after the app is closed and reopened.
    private float persistedZoom = 0f;
    private boolean fullscreenActive = false;

    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        paneSlot = findViewById(R.id.pane_slot);
        barWebView = findViewById(R.id.bar_webview);
        exitFullscreenButton = findViewById(R.id.exit_fullscreen_button);
        exitFullscreenButton.setOnClickListener(v -> handleToggleFullscreen());

        sessionStore = new SessionStore(this);
        zoomStore = new ZoomStore(this);
        persistedZoom = zoomStore.load();
        downloadsController = new DownloadsController(this, this);
        bookmarkStore = new BookmarkStore(this);

        setupBarWebView();
        paneManager = new PaneManager(this, paneSlot, this::createPaneWebView, new PaneManager.ZoomListener() {
            @Override
            public void onZoomIn(WebView pane) {
                setActivePane(pane);
                applyZoomStep(pane, ZOOM_STEP);
            }

            @Override
            public void onZoomOut(WebView pane) {
                setActivePane(pane);
                applyZoomStep(pane, -ZOOM_STEP);
            }
        }, new PaneManager.ZoomIO() {
            @Override
            public float getZoom(WebView pane) {
                Float z = zoomLevels.get(pane);
                return z != null ? z : 0f;
            }

            @Override
            public void setInitialZoom(WebView pane, float zoom) {
                zoomLevels.put(pane, zoom);
                if (zoom != 0f) pendingInitialZoom.add(pane); else pendingInitialZoom.remove(pane);
            }
        });

        WebView firstPane = null;
        String savedTree = sessionStore.load();
        if (savedTree != null) {
            try {
                firstPane = paneManager.restore(new JSONObject(savedTree));
            } catch (JSONException e) {
                firstPane = null;
            }
        }
        if (firstPane == null) firstPane = paneManager.init(START_URL);

        setActivePane(firstPane);
    }

    private void setupBarWebView() {
        WebSettings s = barWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        barWebView.addJavascriptInterface(new WebAppInterface(this), "AndroidAPI");
        barWebView.setBackgroundColor(0x00000000);
        barWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pushActivePaneState();
            }
        });
        barWebView.loadUrl("file:///android_asset/bar.html");
    }

    private WebView createPaneWebView(String url) {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // TRINITY_SYNC: Bind the clipboard bridge to the window object
        wv.addJavascriptInterface(new ClipboardBridge(this), "AndroidClipboard");

        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
        wv.setTag(url);

        wv.setWebViewClient(new PaneWebViewClient());
        wv.setWebChromeClient(new PaneWebChromeClient());

        wv.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            String cookie = CookieManager.getInstance().getCookie(downloadUrl);
            downloadsController.startDownload(downloadUrl, userAgent, contentDisposition, mimeType, contentLength, cookie);
        });

        wv.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && v != activePane) {
                setActivePane((WebView) v);
            }
            return false;
        });

        zoomLevels.put(wv, persistedZoom);
        if (persistedZoom != 0f) pendingInitialZoom.add(wv);
        wv.loadUrl(url);
        return wv;
    }

    private class PaneWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            // A pane's very first load keeps the persisted zoom it was seeded
            // with in createPaneWebView(); only real navigations after that
            // reset zoom back to 100%.
            if (!pendingInitialZoom.remove(view)) {
                zoomLevels.put(view, 0f);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.setTag(url);
            Float z = zoomLevels.get(view);
            if (z != null && z != 0f) applyZoomCss(view, z);
            if (view == activePane) pushActivePaneState();
            saveSession();

            // TRINITY_SYNC: Check domain and inject script
            if (url != null) {
                for (String domain : TRINITY_SYNC_DOMAINS) {
                    if (url.contains(domain)) {
                        injectTrinitySyncScript(view);
                        break;
                    }
                }
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            view.setTag(url);
            if (view == activePane) pushActivePaneState();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }
    }

    // TRINITY_SYNC: Helper to read the asset file and execute it in the WebView context
    private void injectTrinitySyncScript(WebView webView) {
        try {
            InputStream is = getAssets().open("trinity_sync.js");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();

            String script = new String(buffer, StandardCharsets.UTF_8);
            webView.evaluateJavascript(script, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class PaneWebChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView transportView = new WebView(MainActivity.this);
            transportView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    view.loadUrl(request.getUrl().toString());
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(transportView);
            resultMsg.sendToTarget();
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    private void setActivePane(WebView pane) {
        activePane = pane;
        paneManager.setActivePane(pane);
        if (pane != null) pane.requestFocus();
        pushActivePaneState();
    }

    private void pushActivePaneState() {
        JSONObject state = new JSONObject();
        try {
            if (activePane != null) {
                String url = activePane.getUrl();
                state.put("url", url != null ? url : "");
                state.put("canGoBack", activePane.canGoBack());
                state.put("canGoForward", activePane.canGoForward());
            } else {
                state.put("url", "");
                state.put("canGoBack", false);
                state.put("canGoForward", false);
            }
            state.put("hasSplit", paneManager.hasSplit());
        } catch (JSONException ignored) {}
        runJs("window.onActivePaneState && window.onActivePaneState(" + state.toString() + ")");
    }

    public void handleNavigate(String rawUrl) {
        String target = normalizeUrl(rawUrl);
        if (target == null || activePane == null) return;
        activePane.loadUrl(target);
        activePane.setTag(target);
        pushActivePaneState();
        saveSession();
    }

    public void handleZoomIn() {
        applyZoomStep(activePane, ZOOM_STEP);
    }

    public void handleZoomOut() {
        applyZoomStep(activePane, -ZOOM_STEP);
    }

    private void applyZoomStep(WebView pane, float step) {
        if (pane == null) return;
        
        float current = zoomLevels.containsKey(pane) ? zoomLevels.get(pane) : 0f;
        float next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, current + step));
        
        if (next == current) return;
        zoomLevels.put(pane, next);

        // Remember this as the zoom to restore panes to next time the app
        // is launched (see createPaneWebView() / ZoomStore).
        persistedZoom = next;
        zoomStore.save(next);

        applyZoomCss(pane, next);
    }

    /** Injects the CSS zoom (and dimensional compensation) for an absolute level onto an already-loaded pane. */
    private void applyZoomCss(WebView pane, float level) {
        // 1. Calculate the precise scale multiplier (e.g., 0.83 for zoom out)
        float scaleMultiplier = (float) Math.pow(1.2, level);
        
        // 2. Calculate the compensation size to prevent those massive empty gaps.
        // If zoom is 80% (0.8), the page canvas needs to stretch to 125vw/125vh to reach the edges.
        float compensatedSize = 100f / scaleMultiplier; 
        
        // 3. Inject the CSS zoom AND the dimensional fixes to the root HTML element AND body,
        //    so pages that hardcode body height/width to 100vh/100vw still fill the screen.
        String js = "try { " +
                    "  var h = document.documentElement, b = document.body; " +
                    "  h.style.zoom = '" + scaleMultiplier + "'; " +
                    "  h.style.width = '" + compensatedSize + "vw'; " +
                    "  h.style.height = '" + compensatedSize + "vh'; " +
                    "  if (b) { " +
                    "    b.style.width = '" + compensatedSize + "vw'; " +
                    "    b.style.height = '" + compensatedSize + "vh'; " +
                    "  } " +
                    "} catch(e) { console.error('Zoom injection failed', e); }";
        
        pane.evaluateJavascript(js, null);
    }

    public void handleToggleFullscreen() {
        fullscreenActive = !fullscreenActive;
        applySystemBars(fullscreenActive);
        barWebView.setVisibility(fullscreenActive ? View.GONE : View.VISIBLE);
        exitFullscreenButton.setVisibility(fullscreenActive ? View.VISIBLE : View.GONE);
        runJs("window.onFullscreenChanged && window.onFullscreenChanged(" + fullscreenActive + ")");
    }

    private void applySystemBars(boolean hidden) {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                if (hidden) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            if (hidden) {
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            } else {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
    }

    public void handleSplitPane(String direction) {
        if (activePane == null) return;
        WebView newPane = paneManager.splitPane(activePane, direction);
        if (newPane != null) {
            setActivePane(newPane);
            saveSession();
        }
    }

    public void handleClosePane() {
        if (activePane == null) return;
        WebView newActive = paneManager.closePane(activePane);
        if (newActive != null) {
            zoomLevels.remove(activePane);
            pendingInitialZoom.remove(activePane);
            setActivePane(newActive);
            saveSession();

            // The surviving pane just jumped from a fraction of the screen
            // to full size (or a new fraction). The zoom is injected CSS
            // (style.zoom + compensated vw/vh), and that doesn't reliably
            // repaint itself across a WebView bounds change, so without
            // this the pane can render blank/stale until the user touches
            // it. Re-inject on the next frame, after the resize from
            // render() has actually been laid out.
            Float z = zoomLevels.get(newActive);
            if (z != null && z != 0f) {
                final WebView pane = newActive;
                final float level = z;
                pane.post(() -> applyZoomCss(pane, level));
            }
        }
    }

    public String handleGetDownloadsJson() {
        return downloadsController.serialize().toString();
    }

    public void handleOpenDownload(String id) {
        String uriString = downloadsController.openUri(id);
        if (uriString == null || uriString.isEmpty()) return;
        try {
            Uri uri = Uri.parse(uriString);
            String mime = getContentResolver().getType(uri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime != null ? mime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    public void handleShowDownloadInFolder(String id) {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No Downloads app found", Toast.LENGTH_SHORT).show();
        }
    }

    public void handleCancelDownload(String id) {
        downloadsController.cancel(id);
    }

    public void handleRemoveDownload(String id) {
        downloadsController.remove(id);
    }

    public void handleClearCompletedDownloads() {
        downloadsController.clearCompleted();
    }

    @Override
    public void onDownloadsChanged(org.json.JSONArray list) {
        runJs("window.onDownloadsUpdated && window.onDownloadsUpdated(" + list.toString() + ")");
    }

    public String handleGetBookmarksJson() {
        return bookmarkStore.toJson();
    }

    public void handleAddBookmark(String bookmarkJson) {
        try {
            bookmarkStore.add(new JSONObject(bookmarkJson));
        } catch (JSONException ignored) {
            return;
        }
        pushBookmarksUpdated();
    }

    public void handleEditBookmark(int index, String bookmarkJson) {
        try {
            bookmarkStore.edit(index, new JSONObject(bookmarkJson));
        } catch (JSONException ignored) {
            return;
        }
        pushBookmarksUpdated();
    }

    public void handleDeleteBookmark(int index) {
        bookmarkStore.delete(index);
        pushBookmarksUpdated();
    }

    public void handleNavigateToBookmark(String url) {
        handleNavigate(url);
    }

    private void pushBookmarksUpdated() {
        runJs("window.onBookmarksUpdated && window.onBookmarksUpdated(" + bookmarkStore.toJson() + ")");
    }

    private void saveSession() {
        JSONObject tree = paneManager.serialize();
        if (tree != null) sessionStore.save(tree.toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSession();
    }

    @Override
    protected void onDestroy() {
        downloadsController.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenActive) {
            handleToggleFullscreen();
            return;
        }
        if (activePane != null && activePane.canGoBack()) {
            activePane.goBack();
            return;
        }
        super.onBackPressed();
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.matches("(?i)^https?://.*")) return value;
        if (value.matches("^[\\w-]+(\\.[\\w-]+)+.*")) return "https://" + value;
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + value;
        }
    }

    private void runJs(String script) {
        barWebView.post(() -> {
            if (!isFinishing() && !isDestroyed()) barWebView.evaluateJavascript(script, null);
        });
    }
}