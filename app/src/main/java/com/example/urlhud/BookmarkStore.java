package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Simple JSON-array-in-SharedPreferences bookmark store. Each bookmark is
 * {"label": "...", "url": "..."}; bar.js addresses entries by their
 * position in the array, matching the index-based edit/delete calls from
 * WebAppInterface.
 */
public class BookmarkStore {

    private static final String PREFS_NAME = "urlhud_bookmarks";
    private static final String KEY_BOOKMARKS = "bookmarks_json";

    private final SharedPreferences prefs;

    public BookmarkStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String toJson() {
        return loadArray().toString();
    }

    public synchronized void add(JSONObject bookmark) {
        JSONArray arr = loadArray();
        arr.put(bookmark);
        saveArray(arr);
    }

    public synchronized void edit(int index, JSONObject bookmark) {
        JSONArray arr = loadArray();
        if (index < 0 || index >= arr.length()) return;
        try {
            arr.put(index, bookmark);
            saveArray(arr);
        } catch (JSONException ignored) {}
    }

    public synchronized void delete(int index) {
        JSONArray arr = loadArray();
        if (index < 0 || index >= arr.length()) return;
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i == index) continue;
            next.put(arr.opt(i));
        }
        saveArray(next);
    }

    private JSONArray loadArray() {
        try {
            return new JSONArray(prefs.getString(KEY_BOOKMARKS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void saveArray(JSONArray arr) {
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply();
    }
}
