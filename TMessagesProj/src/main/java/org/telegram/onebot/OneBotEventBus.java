package org.telegram.onebot;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OneBot event bus — the single point where UI-layer events are posted
 * and dispatched to all connected clients (HTTP POST, forward WS, reverse WS).
 *
 * ponytail: CopyOnWriteArrayList for subscriber list; no fancy event library.
 * Ceiling: O(n) notify across subscribers; fine for typical bot setups (1–10 subscribers).
 * If subscribers grow >100, replace with a ring buffer + async dispatch.
 */
public class OneBotEventBus {

    private static volatile OneBotEventBus instance;

    public static OneBotEventBus getInstance() {
        if (instance == null) {
            synchronized (OneBotEventBus.class) {
                if (instance == null) {
                    instance = new OneBotEventBus();
                }
            }
        }
        return instance;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private long selfId = 0;
    private OneBotConfig config;

    public void init(OneBotConfig config) {
        this.config = config;
        this.selfId = config.selfId;
    }

    public interface Listener {
        /** @param eventJson 完整的 OneBot v11 事件 JSON */
        void onEvent(JSONObject eventJson);
    }

    public void subscribe(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    // ── 快捷推送 ──────────────────────────────────────────────

    public void pushEvent(String postType, JSONObject fields) {
        OneBotModels.Event event = new OneBotModels.Event(selfId, postType, fields);
        JSONObject json = event.toJSON();
        for (Listener l : listeners) {
            try { l.onEvent(json); } catch (Exception ignored) {}
        }
    }

    /** 推送消息事件 */
    public void pushMessage(String messageType, int messageId, long userId,
                            long groupId, String rawMessage,
                            long senderUserId, String senderNickname) {
        JSONObject fields = OneBotModels.makeMessageEvent(
                selfId, messageType, messageId, userId, groupId,
                rawMessage, senderUserId, senderNickname);
        pushEvent("message", fields);
    }

    /** 推送通知事件 */
    public void pushNotice(JSONObject fields) {
        pushEvent("notice", fields);
    }

    /** 推送请求事件 */
    public void pushRequest(JSONObject fields) {
        pushEvent("request", fields);
    }

    /** 推送元事件 (heartbeat / lifecycle) */
    public void pushMetaEvent(String metaEventType) {
        JSONObject fields = new JSONObject();
        try { fields.put("meta_event_type", metaEventType); } catch (Exception ignored) {}
        pushEvent("meta_event", fields);
    }
}