package org.telegram.onebot;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Forward WebSocket server — clients connect to OneBot.
 *
 * ponytail: single acceptor thread + one reader thread per connection.
 * Ceiling: O(n) threads for n clients; fine for typical bot setups (<100 connections).
 * Upgrade path: NIO selector or Netty for thousands of connections.
 */
public class OneBotWsServer {

    private final OneBotConfig config;
    private final OneBotApiHandler apiHandler;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final CopyOnWriteArrayList<WsConnection> connections = new CopyOnWriteArrayList<>();

    public OneBotWsServer(OneBotConfig config, OneBotApiHandler apiHandler) {
        this.config = config;
        this.apiHandler = apiHandler;
    }

    public void start() throws IOException {
        if (!config.wsEnable) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.wsHost, config.wsPort));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "OneBotFwdWS");
        acceptThread.setDaemon(true);
        acceptThread.start();
        FileLog.d("OneBot forward WS started at " + config.wsHost + ":" + config.wsPort);
    }

    public void disable() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (WsConnection c : connections) c.close();
        connections.clear();
        if (acceptThread != null) acceptThread.interrupt();
    }

    // ── Push events to all connected clients ─────────────────────

    public void sendEvent(JSONObject eventJson) {
        String text = eventJson.toString();
        for (WsConnection c : connections) {
            c.sendText(text);
        }
    }

    // ── Accept loop ──────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = serverSocket.accept();
                WsConnection conn = new WsConnection(sock);
                connections.add(conn);
                conn.start();
            } catch (IOException e) {
                if (running) FileLog.e("Forward WS accept error", e);
            }
        }
    }

    // ── Per-connection handler ───────────────────────────────────

    private class WsConnection {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean open;

        WsConnection(Socket socket) {
            this.socket = socket;
        }

        void start() {
            new Thread(this::run, "OneBotFwdWS-conn").start();
        }

        private void run() {
            try {
                socket.setSoTimeout(30000);
                in = socket.getInputStream();
                out = socket.getOutputStream();

                // Read HTTP upgrade request
                String line = readLine(in);
                if (!line.startsWith("GET ")) { close(); return; }

                String authHeader = null;
                String wsKey = null;
                while (true) {
                    line = readLine(in);
                    if (line == null || line.isEmpty()) break;
                    String lc = line.toLowerCase();
                    if (lc.startsWith("sec-websocket-key:")) {
                        wsKey = line.substring(line.indexOf(':') + 1).trim();
                    } else if (lc.startsWith("authorization:")) {
                        authHeader = line.substring(line.indexOf(':') + 1).trim();
                    }
                }

                // Check auth
                if (!checkAuth(authHeader)) {
                    sendHttp("403 Forbidden", "text/plain", "access token not matched");
                    close();
                    return;
                }
                if (wsKey == null) {
                    sendHttp("400 Bad Request", "text/plain", "missing Sec-WebSocket-Key");
                    close();
                    return;
                }

                // Handshake response
                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes()));

                StringBuilder resp = new StringBuilder();
                resp.append("HTTP/1.1 101 Switching Protocols\r\n");
                resp.append("Upgrade: websocket\r\n");
                resp.append("Connection: Upgrade\r\n");
                resp.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
                resp.append("\r\n");
                out.write(resp.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();

                open = true;
                socket.setSoTimeout(0);
                readFrames();
            } catch (Exception e) {
                if (open) FileLog.e("Forward WS error", e);
            } finally {
                open = false;
                connections.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private boolean checkAuth(String authHeader) {
            if (!config.hasAccessToken()) return true;
            if (authHeader == null) return false;
            for (String prefix : new String[]{"Bearer ", "Token "}) {
                if (authHeader.startsWith(prefix)) {
                    return config.accessToken.equals(authHeader.substring(prefix.length()));
                }
            }
            return false;
        }

        private void readFrames() throws IOException {
            while (open && in != null) {
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

                // Client frames are always masked per RFC 6455
                byte[] mask = new byte[4];
                for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();

                byte[] payload = new byte[(int) len];
                int offset = 0;
                while (offset < len) {
                    int n = in.read(payload, offset, (int) (len - offset));
                    if (n == -1) throw new EOFException();
                    offset += n;
                }
                for (int i = 0; i < len; i++) payload[i] ^= mask[i & 3];

                switch (opcode) {
                    case 1: // text
                        String text = new String(payload, StandardCharsets.UTF_8);
                        handleTextFrame(text);
                        break;
                    case 8: // close
                        return;
                    case 9: // ping → pong
                        sendFrame((byte) 0x8A, payload, false);
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
                    sendText(resp.toJSON().toString());
                }
            } catch (Exception e) {
                FileLog.e("FwdWS handle", e);
            }
        }

        void sendText(String text) {
            sendFrame((byte) 0x81, text.getBytes(StandardCharsets.UTF_8), false);
        }

        private void sendFrame(byte opcode, byte[] data, boolean mask) {
            synchronized (this) {
                try {
                    if (out == null || !open) return;
                    out.write(opcode & 0xff);
                    int len = data.length;
                    int flags = mask ? 0x80 : 0;
                    if (len < 126) {
                        out.write(len | flags);
                    } else if (len <= 0xffff) {
                        out.write(126 | flags);
                        out.write((len >> 8) & 0xff);
                        out.write(len & 0xff);
                    } else {
                        out.write(127 | flags);
                        for (int i = 7; i >= 0; i--) out.write((int) ((len >> (8 * i)) & 0xff));
                    }
                    if (mask) {
                        byte[] key = new byte[4];
                        new SecureRandom().nextBytes(key);
                        out.write(key);
                        for (int i = 0; i < len; i++) data[i] ^= key[i & 3];
                    }
                    out.write(data);
                    out.flush();
                } catch (IOException e) {
                    FileLog.e("FwdWS send error", e);
                }
            }
        }

        void close() {
            open = false;
            try { socket.close(); } catch (IOException ignored) {}
        }

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

        private void sendHttp(String status, String contentType, String body) throws IOException {
            String resp = "HTTP/1.1 " + status + "\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "Connection: close\r\n\r\n" + body;
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}