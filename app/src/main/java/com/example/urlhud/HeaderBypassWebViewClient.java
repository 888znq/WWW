package com.example.urlhud;

import android.os.Build;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Native equivalent of the extension's declarativeNetRequest rule (see
 * background.js): strips X-Frame-Options and Content-Security-Policy so
 * embedded iframe-like content isn't blocked from rendering.
 *
 * Scoped the same way the extension's rule is scoped - only sub-frame
 * (non-main-frame) GET requests are proxied through OkHttp to strip those
 * two headers; the main document request, and everything else, passes
 * straight through untouched by returning null from shouldInterceptRequest.
 * Every other response header - Set-Cookie included - is forwarded as-is,
 * since login/session persistence (and the password manager / download
 * cookie forwarding that depend on it) relies on cookies actually surviving
 * the trip.
 */
public class HeaderBypassWebViewClient extends WebViewClient {

    private final OkHttpClient client = new OkHttpClient();

    @Nullable
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return super.shouldInterceptRequest(view, request);
        }
        // Extension only rewrites headers on sub_frame (iframe) loads - the
        // top-level document's own headers are left completely alone.
        if (request.isForMainFrame()) {
            return super.shouldInterceptRequest(view, request);
        }
        String method = request.getMethod();
        if (method != null && !method.equalsIgnoreCase("GET")) {
            // WebResourceRequest doesn't expose the request body, so
            // non-GET requests (form posts, etc.) can't be safely replayed
            // through OkHttp - let the WebView handle those natively.
            return super.shouldInterceptRequest(view, request);
        }

        try {
            Request.Builder reqBuilder = new Request.Builder().url(url);
            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    reqBuilder.header(entry.getKey(), entry.getValue());
                }
            }

            Response response = client.newCall(reqBuilder.build()).execute();

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

            // Preserve every response header except the two frame-busting
            // ones - in particular Set-Cookie must survive, unlike a naive
            // proxy that rebuilds the response from scratch.
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (String name : response.headers().names()) {
                if (name.equalsIgnoreCase("X-Frame-Options") || name.equalsIgnoreCase("Content-Security-Policy")) {
                    continue;
                }
                responseHeaders.put(name, response.header(name));
            }

            ResponseBody body = response.body();
            InputStream is = body != null ? body.byteStream() : new ByteArrayInputStream(new byte[0]);

            WebResourceResponse resourceResponse = new WebResourceResponse(mime, charset, is);
            resourceResponse.setResponseHeaders(responseHeaders);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                String reason = response.message();
                resourceResponse.setStatusCodeAndReasonPhrase(response.code(), reason.isEmpty() ? "OK" : reason);
            }
            return resourceResponse;

        } catch (IOException e) {
            e.printStackTrace();
            return super.shouldInterceptRequest(view, request);
        }
    }
}
