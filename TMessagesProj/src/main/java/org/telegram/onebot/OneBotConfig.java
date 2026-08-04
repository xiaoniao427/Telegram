package org.telegram.onebot;

import android.text.TextUtils;

/**
 * OneBot v11 configuration.
 * ponytail: plain fields mirroring OneBot standard config keys. No DI framework.
 */
public class OneBotConfig {

    // ── HTTP ─────────────────────────────────────────────
    public boolean httpEnable = true;
    public String httpHost = "0.0.0.0";
    public int httpPort = 5700;

    // ── HTTP POST (event上报) ────────────────────────────
    public String postUrl = "";              // 事件推送到这个 URL
    public String postSecret = "";           // HMAC-SHA1 签名 key

    // ── 正向 WebSocket ─────────────────────────────────
    public boolean wsEnable = false;
    public String wsHost = "0.0.0.0";
    public int wsPort = 6700;

    // ── 反向 WebSocket ─────────────────────────────────
    public boolean wsReverseEnable = false;
    public String wsReverseUrl = "";         // API/Event/Universal 共用URL
    public String wsReverseApiUrl = "";
    public String wsReverseEventUrl = "";
    public boolean wsReverseUseUniversalClient = false;
    public int wsReverseReconnectInterval = 3000;

    // ── 鉴权 ─────────────────────────────────────────────
    public String accessToken = "";

    // ── 其他 ─────────────────────────────────────────────
    public String messageFormat = "string";  // "string" | "array"
    public int rateLimitInterval = 500;      // 限速调用排队间隔 ms

    // ── 机器人身份 ────────────────────────────────────────
    public long selfId = 0;

    // ── 从 SharedPreferences 加载 ────────────────────────
    public static OneBotConfig load(android.content.SharedPreferences prefs) {
        OneBotConfig c = new OneBotConfig();
        c.httpEnable = prefs.getBoolean("onebot_http_enable", true);
        c.httpHost = prefs.getString("onebot_http_host", "0.0.0.0");
        c.httpPort = prefs.getInt("onebot_http_port", 5700);
        c.wsEnable = prefs.getBoolean("onebot_ws_enable", false);
        c.wsHost = prefs.getString("onebot_ws_host", "0.0.0.0");
        c.wsPort = prefs.getInt("onebot_ws_port", 6700);
        c.wsReverseEnable = prefs.getBoolean("onebot_ws_reverse_enable", false);
        c.wsReverseUrl = prefs.getString("onebot_ws_reverse_url", "");
        c.wsReverseApiUrl = prefs.getString("onebot_ws_reverse_api_url", "");
        c.wsReverseEventUrl = prefs.getString("onebot_ws_reverse_event_url", "");
        c.wsReverseUseUniversalClient = prefs.getBoolean("onebot_ws_reverse_use_universal", false);
        c.wsReverseReconnectInterval = prefs.getInt("onebot_ws_reverse_reconnect_interval", 3000);
        c.accessToken = prefs.getString("onebot_access_token", "");
        c.postUrl = prefs.getString("onebot_post_url", "");
        c.postSecret = prefs.getString("onebot_post_secret", "");
        c.messageFormat = prefs.getString("onebot_message_format", "string");
        c.rateLimitInterval = prefs.getInt("onebot_rate_limit_interval", 500);
        c.selfId = prefs.getLong("onebot_self_id", 0);
        return c;
    }

    public boolean hasAccessToken() {
        return !TextUtils.isEmpty(accessToken);
    }
}