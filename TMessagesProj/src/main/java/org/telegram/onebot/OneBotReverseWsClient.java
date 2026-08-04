package org.telegram.onebot;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * Reverse WebSocket client — OneBot connects to user's server.
 *
 * Minimal RFC 6455 implementation; no library dependency needed.
 * ponytail: single-threaded blocking I/O per connection. Reconnect loop on failure.
 * Ceiling: one WS connection per client instance; spawn multiple if needed.
 */
public class OneBotReverseWsClient {

    private final OneBotConfig config;
    private final OneBotApiHandler apiHandler;
    private Thread connectThread;
    private volatile boolean running;
    private Socket socket;
    private OutputStream out;
    private InputStream in;

    public OneBotReverseWsClient(OneBotConfig config, OneBotApiHandler apiHandler) {
        this.config = config;
        this.apiHandler = apiHandler;
    }

    public void start() {
        if (!config.wsReverseEnable) return;
        running = true;
        connectThread = new Thread(this::connectLoop, "OneBotRevWS");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    public void disable() {
        running = false;
        closeSocket();
        if (connectThread != null) {
            connectThread.interrupt();
            connectThread = null;
        }
    }

    // ── Event push (thread-safe) ─────────────────────────────────

    public void sendEvent(JSONObject eventJson) {
        synchronized (this) {
            if (out != null && socket != null && socket.isConnected() && !socket.isClosed()) {
                sendTextFrame(eventJson.toString());
            }
        }
    }

    // ── Connect loop ─────────────────────────────────────────────

    private void connectLoop() {
        while (running) {
            try {
                String url = config.wsReverseApiUrl;
                if (TextUtils.isEmpty(url)) url = config.wsReverseUrl;
                if (TextUtils.isEmpty(url)) {
                    try { Thread.sleep(config.wsReverseReconnectInterval); } catch (InterruptedException ignored) {}
                    continue;
                }
                connectOne(url);
            } catch (Exception e) {
                FileLog.e("Reverse WS connect error", e);
            }
            if (running) {
                try { Thread.sleep(config.wsReverseReconnectInterval); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ── Connect & handshake ─────────────────────────────────────

    private void connectOne(String urlStr) throws Exception {
        URI uri = new URI(urlStr);
        String scheme = "";
        try { scheme = uri.getScheme(); } catch (Exception ignored) {}
        boolean secure = "wss".equals(scheme);
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) port = secure ? 443 : 80;
        String path = uri.getPath();
        if (TextUtils.isEmpty(path)) path = "/";
        if (uri.getQuery() != null) path += "?" + uri.getQuery();

        FileLog.d("Reverse WS connecting to " + urlStr);

        socket = (secure ? SSLSocketFactory.getDefault() : SocketFactory.getDefault()).createSocket();
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(0);
        out = socket.getOutputStream();
        in = socket.getInputStream();

        // SecureRandom.getSeed can give <16 bytes; use nextBytes
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);

        StringBuilder hs = new StringBuilder();
        hs.append("GET ").append(path).append(" HTTP/1.1\r\n");
        hs.append("Host: ").append(host).append(":").append(port).append("\r\n");
        hs.append("Upgrade: websocket\r\n");
        hs.append("Connection: Upgrade\r\n");
        hs.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        hs.append("Sec-WebSocket-Version: 13\r\n");

        if (config.selfId != 0) {
            hs.append("X-Self-ID: ").append(config.selfId).append("\r\n");
        }
        if (config.wsReverseUseUniversalClient) {
            hs.append("X-Client-Role: Universal\r\n");
        } else if (!TextUtils.isEmpty(config.wsReverseApiUrl)) {
            hs.append("X-Client-Role: API\r\n");
        } else {
            hs.append("X-Client-Role: Event\r\n");
        }
        if (config.hasAccessToken()) {
            hs.append("Authorization: Bearer ").append(config.accessToken).append("\r\n");
        }
        hs.append("\r\n");

        out.write(hs.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read handshake response
        String line = readLine(in);
        if (!line.startsWith("101 ")) {
            throw new IOException("Handshake failed: " + line);
        }
        String accept = null;
        while (true) {
            line = readLine(in);
            if (line == null || line.isEmpty()) break;
            if (line.toLowerCase().startsWith("sec-websocket-accept:")) {
                accept = line.substring(line.indexOf(':') + 1).trim();
            }
        }

        // Verify accept
        String expectedAccept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes()));
        if (accept == null || !expectedAccept.equals(accept)) {
            throw new IOException("WebSocket accept mismatch");
        }

        FileLog.d("Reverse WS connected to " + urlStr);
        readFrames();
    }

    // ── Frame I/O ────────────────────────────────────────────────

    private void readFrames() throws IOException {
        while (running && in != null) {
            int b0 = in.read();
            if (b0 == -1) throw new EOFException();
            int opcode = b0 & 0x0f;
            int b1 = in.read();
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
            }

            // Server frames are never masked per RFC 6455
            byte[] payload = new byte[(int) len];
            int offset = 0;
            while (offset < len) {
                int n = in.read(payload, offset, (int) (len - offset));
                if (n == -1) throw new EOFException();
                offset += n;
            }

            switch (opcode) {
                case 1: // text
                    String text = new String(payload, StandardCharsets.UTF_8);
                    handleTextFrame(text);
                    break;
                case 8: // close
                    return;
                case 9: // ping → pong
                    sendFrame((byte) 0x8A, payload);
                    break;
            }
        }
    }

    private void handleTextFrame(String text) {
        try {
            JSONObject json = new JSONObject(text);
            OneBotModels.ApiRequest req = OneBotModels.ApiRequest.fromJson(json);
            if (req.action != null) {
                OneBotModels.ApiResponse resp = apiHandler.dispatch(req);
                sendTextFrame(resp.toJSON().toString());
            }
        } catch (Exception e) {
            FileLog.e("RevWS handle error", e);
        }
    }

    private void sendTextFrame(String text) {
        sendFrame((byte) 0x81, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(byte opcode, byte[] data) {
        synchronized (this) {
            try {
                if (out == null) return;
                out.write(opcode & 0xff);
                writeVarLen(data.length);
                out.write(data);
                out.flush();
            } catch (IOException e) {
                FileLog.e("WS send error", e);
            }
        }
    }

    private void writeVarLen(int len) throws IOException {
        if (len < 126) {
            out.write(len);
        } else if (len <= 0xffff) {
            out.write(126);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((int) ((len >> (8 * i)) & 0xff));
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        in = null;
        out = null;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read(); // \n
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }
}