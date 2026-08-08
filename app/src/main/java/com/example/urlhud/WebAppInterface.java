package com.example.urlhud;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * JS bridge between toolbar (bar.html) and native Android.
 * Preserves original bookmark/session/download methods and adds
 * extension-ported features: clipboard, broadcast, downloads, nav, splits.
 */
public class WebAppInterface {
    private final Activity activity;
    private final WebView callerWebView;

    public WebAppInterface(Activity activity, WebView callerWebView) {
        this.activity = activity;
        this.callerWebView = callerWebView;
    }

    // =========================================================
    // ORIGINAL METHODS (keep for backward compat)
    // =========================================================

    @JavascriptInterface
    public void saveBookmark(String json) {
        BookmarkStore.save(activity, json);
    }

    @JavascriptInterface
    public String getBookmarks() {
        return BookmarkStore.load(activity);
    }

    @JavascriptInterface
    public void saveSession(String json) {
        SessionStore.save(activity, json);
    }

    @JavascriptInterface
    public String getSession() {
        return SessionStore.load(activity);
    }

    @JavascriptInterface
    public void saveDownloads(String json) {
        DownloadsStore.save(activity, json);
    }

    @JavascriptInterface
    public String getDownloadsStore() {
        return DownloadsStore.load(activity);
    }

    @JavascriptInterface
    public void toast(String msg) {
        activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }

    // =========================================================
    // EXTENSION PORT: Clipboard (Trinity Sync)
    // =========================================================

    @JavascriptInterface
    public String getClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                return item.getText() != null ? item.getText().toString() : "";
            }
        } catch (Exception e) { e.printStackTrace(); }
        return "";
    }

    // =========================================================
    // EXTENSION PORT: Broadcast to all pane WebViews
    // =========================================================

    @JavascriptInterface
    public void broadcastToPanes(String jsonPayload) {
        if (!(activity instanceof MainActivity)) return;
        ((MainActivity) activity).broadcastJsToAllPanes(jsonPayload);
    }

    // =========================================================
    // EXTENSION PORT: Download controls
    // =========================================================

    @JavascriptInterface
    public String getDownloads() {
        // Return cached downloads as JSON array
        JSONArray arr = new JSONArray();
        // Let DownloadManagerBridge fill this; we return empty here
        // and the bridge pushes updates via evaluateJavascript.
        // For initial load, MainActivity will call broadcastAll().
        return arr.toString();
    }

    @JavascriptInterface
    public void cancelDownload(long id) {
        DownloadManagerBridge.getInstance(activity).cancelDownload(id);
    }

    @JavascriptInterface
    public void openDownload(long id) {
        // Delegated to MainActivity
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).openDownloadById(id);
        }
    }

    @JavascriptInterface
    public void showDownload(long id) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showDownloadById(id);
        }
    }

    @JavascriptInterface
    public void clearCompletedDownloads() {
        DownloadManagerBridge.getInstance(activity).clearCompleted();
    }

    // =========================================================
    // EXTENSION PORT: Navigation
    // =========================================================

    @JavascriptInterface
    public void loadUrl(String url) {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).loadUrlInActivePane(url));
        }
    }

    @JavascriptInterface
    public void goBack() {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).goBackActivePane());
        }
    }

    @JavascriptInterface
    public void goForward() {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).goForwardActivePane());
        }
    }

    @JavascriptInterface
    public void zoomIn() {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).zoomActivePane(0.1f));
        }
    }

    @JavascriptInterface
    public void zoomOut() {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).zoomActivePane(-0.1f));
        }
    }

    @JavascriptInterface
    public void toggleFullscreen() {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).toggleFullscreenNative());
        }
    }

    // =========================================================
    // EXTENSION PORT: Split pane controls
    // =========================================================

    @JavascriptInterface
    public void splitPane(String direction) {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).splitActivePane(direction));
        }
    }

    @JavascriptInterface
    public void closeActivePane() {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).closeActivePaneNative());
        }
    }
}
