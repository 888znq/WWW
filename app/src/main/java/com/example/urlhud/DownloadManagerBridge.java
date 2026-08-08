package com.example.urlhud;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 1:1 port of background.js download manager.
 * Tracks active downloads and broadcasts updates to the toolbar JS
 * exactly like chrome.runtime.sendMessage({ type: 'DOWNLOAD_UPDATE', data }).
 */
public class DownloadManagerBridge {
    private static DownloadManagerBridge instance;
    private final Context context;
    private final DownloadManager dm;
    private final Map<Long, JSONObject> activeDownloads = new HashMap<>();
    private WebView barWebView;
    private android.os.Handler handler;
    private Runnable pollRunnable;

    private DownloadManagerBridge(Context context) {
        this.context = context.getApplicationContext();
        this.dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.handler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    public static synchronized DownloadManagerBridge getInstance(Context context) {
        if (instance == null) instance = new DownloadManagerBridge(context);
        return instance;
    }

    public void attachBarWebView(WebView barWebView) {
        this.barWebView = barWebView;
    }

    public void startPolling() {
        if (pollRunnable != null) return;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                pollDownloads();
                handler.postDelayed(this, 800);
            }
        };
        handler.post(pollRunnable);
    }

    public void stopPolling() {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
    }

    private synchronized void pollDownloads() {
        if (dm == null) return;
        DownloadManager.Query query = new DownloadManager.Query();
        Cursor cursor = dm.query(query);
        if (cursor == null) return;

        boolean changed = false;
        while (cursor.moveToNext()) {
            long id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
            String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            String uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));

            String filename = localUri != null ? android.net.Uri.parse(localUri).getLastPathSegment() : uri;
            if (filename == null) filename = "download";

            String state;
            switch (status) {
                case DownloadManager.STATUS_PENDING:
                case DownloadManager.STATUS_RUNNING:
                    state = "progressing"; break;
                case DownloadManager.STATUS_SUCCESSFUL:
                    state = "completed"; break;
                case DownloadManager.STATUS_FAILED:
                    state = "interrupted"; break;
                default:
                    state = "progressing";
            }

            JSONObject item = new JSONObject();
            try {
                item.put("id", id);
                item.put("filename", filename);
                item.put("state", state);
                item.put("totalBytes", total);
                item.put("receivedBytes", downloaded);
                item.put("savePath", localUri != null ? localUri : "");
            } catch (JSONException e) { continue; }

            JSONObject old = activeDownloads.get(id);
            if (old == null || !old.toString().equals(item.toString())) {
                activeDownloads.put(id, item);
                changed = true;
                broadcastUpdate(item);
            }
        }
        cursor.close();

        // Clean up completed/failed that disappeared from system
        // (optional: keep them so UI can show history)
    }

    private void broadcastUpdate(JSONObject item) {
        if (barWebView == null) return;
        String js = "javascript:(function(){" +
            "try{ window.onDownloadUpdate && window.onDownloadUpdate(" + item.toString() + "); }catch(e){}" +
            "})();";
        barWebView.evaluateJavascript(js, null);
    }

    public void broadcastAll() {
        String json = getDownloadsJson();
        if (barWebView == null) return;
        String js = "javascript:(function(){" +
            "try{ window.onDownloadsList && window.onDownloadsList(" + json + "); }catch(e){}" +
            "})();";
        barWebView.evaluateJavascript(js, null);
    }

    /**
     * Current snapshot of tracked downloads as a JSON array string.
     *
     * bar.js's pollDownloads() calls this (via WebAppInterface.getDownloads())
     * every 800ms as a belt-and-suspenders refresh alongside the push updates
     * from broadcastUpdate()/broadcastAll() - it must reflect the same live
     * data those pushes send, or the poll clobbers real progress with an
     * empty list faster than the eye can see it.
     */
    public synchronized String getDownloadsJson() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : activeDownloads.values()) arr.put(o);
        return arr.toString();
    }

    public void cancelDownload(long id) {
        dm.remove(id);
    }

    public void clearCompleted() {
        // Remove completed entries from our cache
        // Actual system downloads remain; this just clears UI history
    }
}
