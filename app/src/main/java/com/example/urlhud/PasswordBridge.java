package com.example.urlhud;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

public class PasswordBridge {
    private Activity activity;

    public PasswordBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void reportPassword(String domain, String username, String password) {
        activity.runOnUiThread(() -> {
            // Find the active WebView dynamically
            ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
            WebView webView = findWebView(root);
            
            if (webView != null) {
                String js = "javascript:(function(){" +
                        "window.postMessage({type: 'save-credential', credentials: {domain:'" + 
                        escapeJs(domain) + "', username:'" + escapeJs(username) + 
                        "', password:'" + escapeJs(password) + "'}}, '*');" +
                        "})();";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private WebView findWebView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof WebView) return (WebView) child;
            if (child instanceof ViewGroup) {
                WebView wv = findWebView((ViewGroup) child);
                if (wv != null) return wv;
            }
        }
        return null;
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
