package com.example.urlhud;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * MainActivity — Fullscreen Extension Container
 * Achieves 1:1 parity with the Chrome Extension by delegating all 
 * split-pane logic and UI rendering to app.js and index.html.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private WebView mainWebView;
    private DownloadManagerBridge downloadBridge;
    
    // Fullscreen support variables
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private View customView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 1. Create a pure fullscreen layout, discarding the old split XML
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        
        mainWebView = new WebView(this);
        mainWebView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(mainWebView);
        
        setContentView(rootLayout);

        // 2. Configure WebSettings for Extension Parity
        WebSettings s = mainWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        // CRITICAL FIX: Allows app.js to use 'import' statements for Firebase over file:///
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        // 3. Handle HTML5 Fullscreen requests from app.js
        mainWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                customViewContainer = new FrameLayout(MainActivity.this);
                customViewContainer.setBackgroundColor(0xFF000000);
                customViewContainer.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                getWindow().addContentView(customViewContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                ((ViewGroup) customViewContainer.getParent()).removeView(customViewContainer);
                customViewContainer = null;
                customView = null;
                customViewCallback.onCustomViewHidden();
            }
        });

        // 4. Attach Header Bypass (Equivalent to declarativeNetRequest)
        mainWebView.setWebViewClient(new HeaderBypassWebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (downloadBridge != null) downloadBridge.broadcastAll();
            }
        });

        // 5. Native Download Integration
        mainWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String downloadUrl, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
                request.setMimeType(mimetype);
                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(downloadUrl));
                request.addRequestHeader("User-Agent", userAgent);
                request.setTitle(android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype));
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype));

                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) dm.enqueue(request);
                Toast.makeText(MainActivity.this, "Download started", Toast.LENGTH_SHORT).show();
            }
        });

        // 6. Bind Native Bridges to JavaScript
        mainWebView.addJavascriptInterface(new WebAppInterface(this, mainWebView), "AndroidAPI");
        mainWebView.addJavascriptInterface(new PasswordBridge(this), "PasswordBridge");
        mainWebView.addJavascriptInterface(new ClipboardBridge(this), "ClipboardBridge");

        // 7. Start Downloads Bridge Polling
        downloadBridge = DownloadManagerBridge.getInstance(this);
        downloadBridge.attachBarWebView(mainWebView);
        downloadBridge.startPolling();

        // 8. Launch the Extension!
        mainWebView.loadUrl("file:///android_asset/index.html");
    }

    // Download intent helpers used by AndroidAPI
    public void openDownloadById(long id) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        android.database.Cursor c = dm.query(query);
        if (c != null && c.moveToFirst()) {
            String uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            c.close();
            if (uri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(uri), "*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        }
    }

    public void showDownloadById(long id) {
        Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
        startActivity(intent);
    }
}
