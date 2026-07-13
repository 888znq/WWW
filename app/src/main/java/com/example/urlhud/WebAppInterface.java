package com.example.urlhud;

import android.webkit.JavascriptInterface;

/**
 * The JS bridge exposed to bar.html as `window.AndroidAPI`. This is the
 * Android equivalent of Electron's preload-index.js (which exposes
 * window.splitAPI / window.barAPI) plus the ipcMain handlers in main.js -
 * both roles are collapsed into one bridge here since there's no separate
 * main/renderer process split on Android, just this WebView-hosted bar
 * talking directly to MainActivity.
 *
 * Every method here just forwards to a same-named (or clearly corresponding)
 * method on MainActivity, which does the real work and is responsible for
 * hopping to the UI thread - JS interface methods run on a background
 * thread by default, and WebView/View mutations must happen on the UI
 * thread.
 *
 * getBookmarksJson()/getDownloadsJson() are the two exceptions: they only
 * read already-serialized JSON (no View access), so they return
 * synchronously without a UI-thread hop, similar in spirit to Electron's
 * ipcMain.handle('get-bookmarks'/'get-downloads') which also just reads
 * local state and returns it directly.
 */
public class WebAppInterface {

    private final MainActivity activity;

    WebAppInterface(MainActivity activity) {
        this.activity = activity;
    }

    // ---------------------------------------------------------------
    // Navigation - targets whichever pane last had focus, same as
    // ipcMain.on('navigate'/'navigate-to-bookmark') in main.js.
    // ---------------------------------------------------------------
    @JavascriptInterface
    public void navigate(String rawUrl) {
        activity.runOnUiThread(() -> activity.handleNavigate(rawUrl));
    }

    @JavascriptInterface
    public void navigateToBookmark(String url) {
        activity.runOnUiThread(() -> activity.handleNavigateToBookmark(url));
    }

    // ---------------------------------------------------------------
    // Fullscreen - hides the bottom bar and the Android system bars,
    // same as ipcMain.on('toggle-fullscreen').
    // ---------------------------------------------------------------
    @JavascriptInterface
    public void toggleFullscreen() {
        activity.runOnUiThread(activity::handleToggleFullscreen);
    }

    // ---------------------------------------------------------------
    // Split control - same as ipcMain.on('split-pane'/'close-pane').
    // ---------------------------------------------------------------
    @JavascriptInterface
    public void splitPane(String direction) {
        activity.runOnUiThread(() -> activity.handleSplitPane(direction));
    }

    @JavascriptInterface
    public void closePane() {
        activity.runOnUiThread(activity::handleClosePane);
    }

    // ---------------------------------------------------------------
    // Bookmarks - same as ipcMain.handle('get-bookmarks') / ipcMain.on('add-
    // bookmark'/'edit-bookmark'/'delete-bookmark'). label/url are passed as
    // a JSON string (JS does JSON.stringify({label,url}) before calling).
    // ---------------------------------------------------------------
    @JavascriptInterface
    public String getBookmarksJson() {
        return activity.handleGetBookmarksJson();
    }

    @JavascriptInterface
    public void addBookmark(String bookmarkJson) {
        activity.runOnUiThread(() -> activity.handleAddBookmark(bookmarkJson));
    }

    @JavascriptInterface
    public void editBookmark(int index, String bookmarkJson) {
        activity.runOnUiThread(() -> activity.handleEditBookmark(index, bookmarkJson));
    }

    @JavascriptInterface
    public void deleteBookmark(int index) {
        activity.runOnUiThread(() -> activity.handleDeleteBookmark(index));
    }

    // ---------------------------------------------------------------
    // Downloads - same as ipcMain.handle('get-downloads') / ipcMain.on
    // ('download-open-file'/'download-show-in-folder'/'download-cancel'/
    // 'download-remove'/'download-clear-completed').
    // ---------------------------------------------------------------
    @JavascriptInterface
    public String getDownloadsJson() {
        return activity.handleGetDownloadsJson();
    }

    @JavascriptInterface
    public void openDownload(String id) {
        activity.runOnUiThread(() -> activity.handleOpenDownload(id));
    }

    @JavascriptInterface
    public void showDownloadInFolder(String id) {
        activity.runOnUiThread(() -> activity.handleShowDownloadInFolder(id));
    }

    @JavascriptInterface
    public void cancelDownload(String id) {
        activity.runOnUiThread(() -> activity.handleCancelDownload(id));
    }

    @JavascriptInterface
    public void removeDownload(String id) {
        activity.runOnUiThread(() -> activity.handleRemoveDownload(id));
    }

    @JavascriptInterface
    public void clearCompletedDownloads() {
        activity.runOnUiThread(activity::handleClearCompletedDownloads);
    }
}
