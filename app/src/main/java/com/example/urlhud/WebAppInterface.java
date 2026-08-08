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
 * extension-ported features: clipboard, broadcast, downloads, nav, splits,
 * and multi-account session switching.
 */
public class WebAppInterface {
    private final Activity activity;
    private final WebView callerWebView;
    private final BookmarkStore bookmarkStore;
    private final DownloadsStore downloadsStore;

    public WebAppInterface(Activity activity, WebView callerWebView) {
        this.activity = activity;
        this.callerWebView = callerWebView;
        this.bookmarkStore = new BookmarkStore(activity);
        this.downloadsStore = new DownloadsStore(activity);
    }

    // =========================================================
    // ORIGINAL METHODS (keep for backward compat)
    // =========================================================
    // Note: bar.js doesn't currently call these directly - bookmarks are
    // driven live off Firebase RTDB, and session/download state is owned by
    // MainActivity - but they're kept working (and building) against the
    // real BookmarkStore/DownloadsStore APIs for anything that still expects
    // this bridge surface.

    @JavascriptInterface
    public void saveBookmark(String json) {
        bookmarkStore.save(json);
    }

    @JavascriptInterface
    public String getBookmarks() {
        return bookmarkStore.load();
    }

    @JavascriptInterface
    public void saveDownloads(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject record = arr.optJSONObject(i);
                if (record != null) downloadsStore.upsert(record);
            }
        } catch (JSONException ignored) {}
    }

    @JavascriptInterface
    public String getDownloadsStore() {
        return downloadsStore.load().toString();
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
        // Live snapshot from the same bridge that pushes onDownloadUpdate/
        // onDownloadsList - previously this always returned "[]", so bar.js's
        // 800ms poll wiped out real progress almost as soon as it arrived.
        return DownloadManagerBridge.getInstance(activity).getDownloadsJson();
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

    // =========================================================
    // EXTENSION PORT: Multi-account session switcher
    // =========================================================

    @JavascriptInterface
    public String listSessions() {
        if (activity instanceof MainActivity) {
            return ((MainActivity) activity).listSessionsJson();
        }
        return "{\"sessions\":[],\"activeId\":null}";
    }

    @JavascriptInterface
    public void addSession(String name) {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).addSessionNative(name));
        }
    }

    @JavascriptInterface
    public void switchSession(String id) {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).switchSessionNative(id));
        }
    }

    @JavascriptInterface
    public void closeSession(String id) {
        if (activity instanceof MainActivity) {
            activity.runOnUiThread(() -> ((MainActivity) activity).closeSessionNative(id));
        }
    }
}
