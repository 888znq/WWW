package com.example.urlhud;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.URLUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Android's equivalent of the download manager main.js builds around
 * session.defaultSession's 'will-download' event: WebView has no built-in
 * download interception, so pane WebViews forward anything downloadable to
 * startDownload() here (see MainActivity's DownloadListener), which hands
 * it to Android's own DownloadManager and polls it for progress the same
 * way main.js listens to item.on('updated'/'done').
 *
 * Records live in memory (`downloads`) while the app is running; finished /
 * cancelled / interrupted ones are mirrored into DownloadsStore, matching
 * main.js's persistDownloads() (only non-progressing entries survive a
 * restart - in-flight downloads are memory-only there too).
 */
public class DownloadsController {

    public interface Listener {
        void onDownloadsChanged(JSONArray list);
    }

    private static final long POLL_INTERVAL_MS = 500;

    private static class Record {
        String id;
        String filename;
        String uri; // content:// URI from DownloadManager, used to open the file
        String url;
        String state; // progressing | completed | cancelled | interrupted
        long receivedBytes;
        long totalBytes;
        long startTime;
        long endTime;
        long downloadManagerId = -1;
    }

    private final Context context;
    private final DownloadManager downloadManager;
    private final DownloadsStore store;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Record> downloads = new LinkedHashMap<>();
    private final Random random = new Random();
    private Runnable pollRunnable;

    public DownloadsController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.store = new DownloadsStore(context);
        this.listener = listener;
        loadPersisted();
    }

    private void loadPersisted() {
        JSONArray arr = store.load();
        for (int i = 0; i < arr.length(); i++) {
            try {
                Record r = fromJson(arr.getJSONObject(i));
                downloads.put(r.id, r);
            } catch (JSONException ignored) {}
        }
    }

    /** Mirrors will-download in main.js: assign a unique save name, enqueue, start tracking. */
    public void startDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength, String cookie) {
        String filename = uniqueFilename(URLUtil.guessFileName(url, contentDisposition, mimeType));

        DownloadManager.Request request;
        try {
            request = new DownloadManager.Request(Uri.parse(url));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
        if (cookie != null) request.addRequestHeader("Cookie", cookie);
        if (mimeType != null) request.setMimeType(mimeType);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);
        try {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        } catch (IllegalStateException e) {
            // Fall back to DownloadManager's own default location if the
            // public Downloads directory isn't available for some reason.
        }

        long dmId;
        try {
            dmId = downloadManager.enqueue(request);
        } catch (Exception e) {
            return;
        }

        Record r = new Record();
        r.id = "dl_" + System.currentTimeMillis() + "_" + Math.abs(random.nextInt(1_000_000));
        r.filename = filename;
        r.url = url;
        r.state = "progressing";
        r.receivedBytes = 0;
        r.totalBytes = Math.max(contentLength, 0);
        r.startTime = System.currentTimeMillis();
        r.downloadManagerId = dmId;
        downloads.put(r.id, r);
        notifyChanged();
        startPollingIfNeeded();
    }

    /**
     * Best-effort equivalent of uniqueSavePath() in main.js, which checks the
     * real filesystem for a collision. DownloadManager doesn't expose an easy
     * existence check for the public Downloads dir, so this checks against
     * filenames we already know about (in-flight + persisted) instead.
     */
    private String uniqueFilename(String filename) {
        Set<String> used = new HashSet<>();
        for (Record r : downloads.values()) used.add(r.filename);
        JSONArray persisted = store.load();
        for (int i = 0; i < persisted.length(); i++) {
            try {
                used.add(persisted.getJSONObject(i).getString("filename"));
            } catch (JSONException ignored) {}
        }
        if (!used.contains(filename)) return filename;

        String base = filename;
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            base = filename.substring(0, dot);
            ext = filename.substring(dot);
        }
        int n = 1;
        String candidate;
        do {
            candidate = base + " (" + n + ")" + ext;
            n++;
        } while (used.contains(candidate));
        return candidate;
    }

    private void startPollingIfNeeded() {
        if (pollRunnable != null) return;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                boolean anyActive = pollOnce();
                if (anyActive) {
                    handler.postDelayed(this, POLL_INTERVAL_MS);
                } else {
                    pollRunnable = null;
                }
            }
        };
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    /** Mirrors item.on('updated'/'done') in main.js. Returns true if any download is still in flight. */
    private boolean pollOnce() {
        boolean anyActive = false;
        boolean changed = false;

        for (Record r : downloads.values()) {
            if (!"progressing".equals(r.state) || r.downloadManagerId <= 0) continue;

            DownloadManager.Query query = new DownloadManager.Query().setFilterById(r.downloadManagerId);
            try (Cursor c = downloadManager.query(query)) {
                if (c != null && c.moveToFirst()) {
                    int statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int bytesIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    int uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                    int status = statusIdx >= 0 ? c.getInt(statusIdx) : DownloadManager.STATUS_RUNNING;
                    long bytes = bytesIdx >= 0 ? c.getLong(bytesIdx) : r.receivedBytes;
                    long total = totalIdx >= 0 ? c.getLong(totalIdx) : r.totalBytes;

                    if (bytes != r.receivedBytes || (total > 0 && total != r.totalBytes)) changed = true;
                    r.receivedBytes = bytes;
                    if (total > 0) r.totalBytes = total;

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        r.state = "completed";
                        r.endTime = System.currentTimeMillis();
                        if (uriIdx >= 0) {
                            String localUri = c.getString(uriIdx);
                            if (localUri != null) r.uri = localUri;
                        }
                        persist(r);
                        changed = true;
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        r.state = "interrupted";
                        r.endTime = System.currentTimeMillis();
                        persist(r);
                        changed = true;
                    } else {
                        anyActive = true;
                    }
                } else {
                    // Vanished from DownloadManager (e.g. cancelled from the system UI).
                    r.state = "cancelled";
                    r.endTime = System.currentTimeMillis();
                    persist(r);
                    changed = true;
                }
            }
        }

        if (changed) notifyChanged();
        return anyActive;
    }

    public void cancel(String id) {
        Record r = downloads.get(id);
        if (r == null || r.downloadManagerId <= 0) return;
        downloadManager.remove(r.downloadManagerId);
        r.state = "cancelled";
        r.endTime = System.currentTimeMillis();
        persist(r);
        notifyChanged();
    }

    public void remove(String id) {
        downloads.remove(id);
        store.remove(id);
        notifyChanged();
    }

    public void clearCompleted() {
        Iterator<Map.Entry<String, Record>> it = downloads.entrySet().iterator();
        while (it.hasNext()) {
            if (!"progressing".equals(it.next().getValue().state)) it.remove();
        }
        store.clearCompleted();
        notifyChanged();
    }

    /** Returns the content:// URI to open, or null if the id is unknown / not completed. */
    public String openUri(String id) {
        Record r = downloads.get(id);
        if (r == null || !"completed".equals(r.state) || r.uri == null || r.uri.isEmpty()) return null;
        return r.uri;
    }

    public void stop() {
        if (pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void persist(Record r) {
        try {
            store.upsert(toJson(r));
        } catch (JSONException ignored) {}
    }

    private JSONObject toJson(Record r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", r.id);
        o.put("filename", r.filename);
        o.put("uri", r.uri != null ? r.uri : "");
        o.put("url", r.url != null ? r.url : "");
        o.put("state", r.state);
        o.put("receivedBytes", r.receivedBytes);
        o.put("totalBytes", r.totalBytes);
        o.put("startTime", r.startTime);
        o.put("endTime", r.endTime);
        return o;
    }

    private Record fromJson(JSONObject o) throws JSONException {
        Record r = new Record();
        r.id = o.getString("id");
        r.filename = o.optString("filename", "");
        r.uri = o.optString("uri", "");
        r.url = o.optString("url", "");
        r.state = o.optString("state", "interrupted");
        r.receivedBytes = o.optLong("receivedBytes", 0);
        r.totalBytes = o.optLong("totalBytes", 0);
        r.startTime = o.optLong("startTime", 0);
        r.endTime = o.optLong("endTime", 0);
        return r;
    }

    /** Mirrors serializeDownloads() in main.js - newest first. */
    public JSONArray serialize() {
        List<Record> list = new ArrayList<>(downloads.values());
        Collections.sort(list, (a, b) -> Long.compare(b.startTime, a.startTime));
        JSONArray arr = new JSONArray();
        for (Record r : list) {
            try {
                arr.put(toJson(r));
            } catch (JSONException ignored) {}
        }
        return arr;
    }

    private void notifyChanged() {
        handler.post(() -> listener.onDownloadsChanged(serialize()));
    }
}
