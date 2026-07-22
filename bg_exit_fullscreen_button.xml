package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the serialized pane tree (see PaneManager.serialize()) across
 * process death / relaunch, the same role a saved-window-state file plays
 * on desktop. Just a single JSON string under one key - PaneManager owns
 * the shape of that JSON.
 */
public class SessionStore {

    private static final String PREFS_NAME = "urlhud_session";
    private static final String KEY_TREE = "pane_tree_json";

    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the last saved pane-tree JSON string, or null if none was saved yet. */
    public String load() {
        if (!prefs.contains(KEY_TREE)) return null;
        return prefs.getString(KEY_TREE, null);
    }

    public void save(String treeJson) {
        prefs.edit().putString(KEY_TREE, treeJson).apply();
    }

    public void clear() {
        prefs.edit().remove(KEY_TREE).apply();
    }
}
