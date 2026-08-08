package com.example.urlhud;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Legacy local bookmark persistence, kept for backward compatibility with
 * WebAppInterface's original saveBookmark()/getBookmarks() bridge methods.
 * Bookmarks are normally synced live through Firebase RTDB from bar.js
 * (see bookmarksDbRef there); this store is just a local mirror so the
 * old bridge calls have something real to talk to.
 *
 * Same single-value-in-SharedPreferences pattern as SessionStore/DownloadsStore.
 */
public class BookmarkStore {

    private static final String PREFS_NAME = "urlhud_bookmarks";
    private static final String KEY_BOOKMARKS = "bookmarks_json";

    private final SharedPreferences prefs;

    public BookmarkStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String load() {
        return prefs.getString(KEY_BOOKMARKS, "[]");
    }

    public void save(String bookmarksJson) {
        prefs.edit().putString(KEY_BOOKMARKS, bookmarksJson).apply();
    }
}
