package org.telegram.onebot;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import fi.iki.elonen.NanoHTTPD;

/**
 * OneBot v11 HTTP server using NanoHTTPD (already in project deps).
 *
 * ponytail: synchronous per-request NanoHTTPD. Fine for bot use (<100 req/s).
 * Upgrade path: switch to async NanoHTTPD or Netty for high throughput.
 */
public class OneBotServer {

    private final OneBotConfig config;
    private final OneBotApiHandler apiHandler;
    private HttpServer httpServer;

    public OneBotServer(OneBotConfig config, OneBotApiHandler apiHandler) {
        this.config = config;
        this.apiHandler = apiHandler;
    }

    public void start() throws IOException {
        if (config.httpEnable) {
            httpServer = new HttpServer(config.httpHost, config.httpPort);
            httpServer.start();
            FileLog.d("OneBot HTTP started at " + config.httpHost + ":" + config.httpPort);
        }
    }

    public void disable() {
        if (httpServer != null) httpServer.stop();
    }

    // ── Inner server class ──────────────────────────────────────

    private class HttpServer extends NanoHTTPD {

        HttpServer(String host, int port) {
            super(host, port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                Map<String, String> headers = session.getHeaders();
                Map<String, String> parms = session.getParms();
                String uri = session.getUri();

                // Auth
                if (!checkAuth(headers, parms)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN,
                            MIME_PLAINTEXT, "access token not matched");
                }

                Method method = session.getMethod();
                if (method == Method.POST) {
                    String ct = headers.get("content-type");
                    if (ct != null && !ct.contains("application/json") &&
                        !ct.contains("application/x-www-form-urlencoded")) {
                        return newFixedLengthResponse(Response.Status.NOT_ACCEPTABLE,
                                "text/plain", "unsupported Content-Type");
                    }
                }

                // action from path
                String action = uri;
                if (action.startsWith("/")) action = action.substring(1);
                if (action.endsWith("/")) action = action.substring(0, action.length() - 1);
                if (TextUtils.isEmpty(action)) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND,
                            "text/plain", "no action specified");
                }

                // Parse params
                JSONObject params = new JSONObject();
                for (Map.Entry<String, String> e : parms.entrySet()) {
                    params.put(e.getKey(), e.getValue());
                }

                if (method == Method.POST) {
                    String body = readBody(session);
                    if (body != null && !body.trim().isEmpty()) {
                        String ct = headers.get("content-type");
                        if (ct != null && ct.contains("application/json")) {
                            JSONObject bodyJson = new JSONObject(body);
                            for (java.util.Iterator<String> it = bodyJson.keys(); it.hasNext(); ) {
                                String k = it.next();
                                params.put(k, bodyJson.opt(k));
                            }
                        } else {
                            String[] pairs = body.split("&");
                            for (String pair : pairs) {
                                int idx = pair.indexOf('=');
                                if (idx > 0) {
                                    params.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                                               URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                                }
                            }
                        }
                    }
                }

                OneBotModels.ApiRequest req =
                        new OneBotModels.ApiRequest(action, params, null);
                OneBotModels.ApiResponse resp = apiHandler.dispatch(req);

                int httpCode = (resp.status.equals("failed") && resp.retcode != 0)
                        ? mapRetcode(resp.retcode) : 200;

                Response r = newFixedLengthResponse(
                        Response.Status.lookup(httpCode),
                        "application/json",
                        resp.toJSON().toString());
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;

            } catch (Exception e) {
                FileLog.e("OneBotServer HTTP error", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        "text/plain", "internal error");
            }
        }
    }

    private int mapRetcode(int retcode) {
        switch (retcode) {
            case 1400: return 400;
            case 1401: return 401;
            case 1403: return 403;
            case 1404: return 404;
            default:   return 200;
        }
    }

    // ── Auth ────────────────────────────────────────────────────

    boolean checkAuth(Map<String, String> headers, Map<String, String> parms) {
        if (!config.hasAccessToken()) return true;
        // "Authorization: Bearer xxx" header
        String auth = headers.get("authorization");
        if (!TextUtils.isEmpty(auth)) {
            for (String prefix : new String[]{"Bearer ", "Token "}) {
                if (auth.startsWith(prefix)) {
                    return config.accessToken.equals(auth.substring(prefix.length()));
                }
            }
        }
        // "access_token" query param
        String token = parms.get("access_token");
        return config.accessToken.equals(token);
    }

    private static String readBody(IHTTPSession session) {
        try {
            Map<String, String> headers = session.getHeaders();
            String lenStr = headers.get("content-length");
            if (lenStr == null) return "";
            int len = Integer.parseInt(lenStr);
            byte[] buf = new byte[len];
            session.getInputStream().read(buf, 0, len);
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ── HTTP POST event push ────────────────────────────────────

    public void pushEventHttpPost(JSONObject eventJson) {
        if (TextUtils.isEmpty(config.postUrl)) return;
        try {
            String body = eventJson.toString();
            URL url = new URL(config.postUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json");

            // HMAC-SHA1 signature
            if (!TextUtils.isEmpty(config.postSecret)) {
                String sig = hmacSha1(body, config.postSecret);
                conn.setRequestProperty("X-Signature", "sha1=" + sig);
            }

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                FileLog.d("OneBot HTTP POST push returned " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            FileLog.e("OneBot HTTP POST push failed", e);
        }
    }

    private static String hmacSha1(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec spec = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1");
            mac.init(spec);
            byte[] raw = mac.doFinal(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}