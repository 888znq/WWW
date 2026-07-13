package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists finished/cancelled/interrupted download records (in-flight ones
 * are memory-only, see DownloadsController's class doc). Records are keyed
 * by their "id" field for upsert/remove.
 */
public class DownloadsStore {

    private static final String PREFS_NAME = "urlhud_downloads";
    private static final String KEY_DOWNLOADS = "downloads_json";

    private final SharedPreferences prefs;

    public DownloadsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized JSONArray load() {
        try {
            return new JSONArray(prefs.getString(KEY_DOWNLOADS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** Inserts, or replaces the existing record with the same "id". */
    public synchronized void upsert(JSONObject record) {
        String id = record.optString("id", null);
        JSONArray arr = load();
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject existing = arr.optJSONObject(i);
            if (existing != null && id != null && id.equals(existing.optString("id", null))) {
                next.put(record);
                replaced = true;
            } else if (existing != null) {
                next.put(existing);
            }
        }
        if (!replaced) next.put(record);
        saveArray(next);
    }

    public synchronized void remove(String id) {
        JSONArray arr = load();
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject existing = arr.optJSONObject(i);
            if (existing != null && !id.equals(existing.optString("id", null))) {
                next.put(existing);
            }
        }
        saveArray(next);
    }

    public synchronized void clearCompleted() {
        JSONArray arr = load();
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject existing = arr.optJSONObject(i);
            if (existing != null && "progressing".equals(existing.optString("state", ""))) {
                next.put(existing);
            }
        }
        saveArray(next);
    }

    private void saveArray(JSONArray arr) {
        prefs.edit().putString(KEY_DOWNLOADS, arr.toString()).apply();
    }
}
