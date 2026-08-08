package com.example.urlhud;

import android.webkit.JavascriptInterface;

/**
 * Injected into every content WebView so inner pages can report
 * detected login credentials back to the toolbar for the password manager.
 */
public class PasswordBridge {
    private final MainActivity activity;

    public PasswordBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void reportPassword(String domain, String username, String password) {
        if (activity == null || domain == null || username == null || password == null) return;
        activity.runOnUiThread(() -> {
            String safeDomain = domain.replace("\\", "\\\\").replace("'", "\\'");
            String safeUser = username.replace("\\", "\\\\").replace("'", "\\'");
            String safePass = password.replace("\\", "\\\\").replace("'", "\\'");
            if (activity.barWebView != null) {
                activity.barWebView.evaluateJavascript(
                    "javascript:onPasswordDetected('" + safeDomain + "','" + safeUser + "','" + safePass + "')",
                    null
                );
            }
        });
    }
}
