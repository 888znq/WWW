package com.example.urlhud;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 1:1 replacement for chrome.declarativeNetRequest that strips
 * X-Frame-Options and Content-Security-Policy from ALL responses.
 *
 * This uses OkHttp to fetch the resource, removes the headers,
 * then pipes the body back to the WebView.
 */
public class HeaderBypassWebViewClient extends WebViewClient {

    private final OkHttpClient client = new OkHttpClient();

    @Nullable
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        // Only intercept HTTP(S) requests
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return super.shouldInterceptRequest(view, request);
        }

        try {
            Request.Builder reqBuilder = new Request.Builder().url(url);
            // Copy headers from original request
            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    reqBuilder.header(entry.getKey(), entry.getValue());
                }
            }
            if (request.getMethod().equalsIgnoreCase("POST")) {
                // For simplicity, let POSTs through without intercepting
                return super.shouldInterceptRequest(view, request);
            }

            Response response = client.newCall(reqBuilder.build()).execute();

            // Strip frame-busting headers
            String contentType = response.header("Content-Type", "text/html");
            String mime = contentType;
            String charset = "UTF-8";
            if (contentType.contains(";")) {
                String[] parts = contentType.split(";");
                mime = parts[0].trim();
                for (String part : parts) {
                    if (part.trim().toLowerCase().startsWith("charset=")) {
                        charset = part.split("=")[1].trim();
                    }
                }
            }

            ResponseBody body = response.body();
            InputStream is = body != null ? body.byteStream() : new ByteArrayInputStream(new byte[0]);

            return new WebResourceResponse(mime, charset, is);

        } catch (IOException e) {
            e.printStackTrace();
            return super.shouldInterceptRequest(view, request);
        }
    }
}
