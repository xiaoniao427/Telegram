package org.telegram.onebot;

import android.content.SharedPreferences;

import org.telegram.messenger.FileLog;

/**
 * OneBot bridge — wires together HTTP server, forward WS server, reverse WS client, event bus.
 * Singleton per process.
 *
 * Usage (in ApplicationLoader.postInitApplication):
 *   OneBotBridge.init(prefs);
 *   OneBotBridge.getInstance().start();
 *
 * UI calls OneBotEventBus.getInstance().pushXxx(...) to emit events.
 */
public class OneBotBridge {

    private static volatile OneBotBridge instance;

    public static OneBotBridge getInstance() { return instance; }

    public static void init(SharedPreferences prefs) {
        if (instance != null) return;
        synchronized (OneBotBridge.class) {
            if (instance != null) return;
            OneBotConfig config = OneBotConfig.load(prefs);
            instance = new OneBotBridge(config);
        }
    }

    private final OneBotConfig config;
    private final OneBotApiHandler apiHandler;
    private OneBotServer httpServer;
    private OneBotWsServer wsServer;
    private OneBotReverseWsClient revWsClient;

    private OneBotBridge(OneBotConfig config) {
        this.config = config;
        this.apiHandler = new OneBotApiHandler();
        OneBotEventBus.getInstance().init(config);
    }

    public void start() {
        FileLog.d("OneBotBridge starting...");

        // HTTP server (NanoHTTPD)
        httpServer = new OneBotServer(config, apiHandler);
        try { httpServer.start(); } catch (Exception e) { FileLog.e("OneBot HTTP start failed", e); }

        // Forward WebSocket server
        wsServer = new OneBotWsServer(config, apiHandler);
        try { wsServer.start(); } catch (Exception e) { FileLog.e("OneBot WS start failed", e); }

        // Reverse WS client
        revWsClient = new OneBotReverseWsClient(config, apiHandler);
        revWsClient.start();

        // Wire event bus: push events to HTTP POST, forward WS, and reverse WS
        OneBotEventBus.getInstance().subscribe(eventJson -> {
            if (httpServer != null) httpServer.pushEventHttpPost(eventJson);
            if (wsServer != null) wsServer.sendEvent(eventJson);
            if (revWsClient != null) revWsClient.sendEvent(eventJson);
        });

        // Fire lifecycle meta event
        OneBotEventBus.getInstance().pushMetaEvent("lifecycle");

        FileLog.d("OneBotBridge started");
    }

    public void stop() {
        FileLog.d("OneBotBridge stopping...");
        if (httpServer != null) httpServer.disable();
        if (wsServer != null) wsServer.disable();
        if (revWsClient != null) revWsClient.disable();
    }

    public OneBotConfig getConfig() { return config; }
    public OneBotApiHandler getApiHandler() { return apiHandler; }
}