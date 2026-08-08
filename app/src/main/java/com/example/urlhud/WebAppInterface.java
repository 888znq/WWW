package com.example.urlhud;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

public class WebAppInterface {
    private Activity activity;
    private WebView webView;

    public WebAppInterface(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void openDownloadById(long id) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).openDownloadById(id);
        }
    }

    @JavascriptInterface
    public void showDownloadById(long id) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showDownloadById(id);
        }
    }

    @JavascriptInterface
    public void cancelDownload(long id) {
        // Safe no-op hook for app.js cancellations
    }
}
