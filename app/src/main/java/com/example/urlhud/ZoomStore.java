package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the last zoom level a pane was set to (see
 * MainActivity#applyZoomStep) across process death / relaunch, so panes
 * come back at the zoom the user left them at instead of resetting to
 * 100% every time the app is reopened. Same single-value-in-SharedPreferences
 * pattern as SessionStore.
 */
public class ZoomStore {

    private static final String PREFS_NAME = "urlhud_zoom";
    private static final String KEY_ZOOM = "last_zoom_level";

    private final SharedPreferences prefs;

    public ZoomStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the last saved zoom level, or 0f (100%, no zoom) if none was saved yet. */
    public float load() {
        return prefs.getFloat(KEY_ZOOM, 0f);
    }

    public void save(float level) {
        prefs.edit().putFloat(KEY_ZOOM, level).apply();
    }
}
