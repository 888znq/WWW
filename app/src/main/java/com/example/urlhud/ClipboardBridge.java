package com.example.urlhud;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

public class ClipboardBridge {
    private final ClipboardManager clipboardManager;
    private final Handler mainHandler;

    public ClipboardBridge(Context context) {
        this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public void write(final String text) {
        if (clipboardManager != null && text != null) {
            // WebViews run JS interfaces on a background thread; must execute clipboard writes on the UI thread
            mainHandler.post(() -> {
                ClipData clip = ClipData.newPlainText("trinity_sync_pair", text);
                clipboardManager.setPrimaryClip(clip);
            });
        }
    }

    @JavascriptInterface
    public String read() {
        // Direct clipboard reading is thread-safe, but returns an empty string if clipboard is empty
        if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                return text != null ? text.toString() : "";
            }
        }
        return "";
    }
}