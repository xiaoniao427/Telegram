package org.telegram.onebot;

import org.json.JSONObject;

/**
 * OneBot v11 data models — requests, responses, events, message segments.
 * ponytail: all JSON I/O uses org.json (already in Android stdlib, no new dependency).
 */
public final class OneBotModels {

    // ── API Request ──────────────────────────────────────────────

    public static class ApiRequest {
        public final String action;
        public final JSONObject params;   // may be null
        public final Object echo;         // String / Number / JSONArray / JSONObject / null

        public ApiRequest(String action, JSONObject params, Object echo) {
            this.action = action;
            this.params = params;
            this.echo = echo;
        }

        public static ApiRequest fromJson(JSONObject json) {
            String action = json.optString("action", null);
            JSONObject params = json.optJSONObject("params");
            Object echo = json.opt("echo");
            return new ApiRequest(action, params, echo);
        }
    }

    // ── API Response ─────────────────────────────────────────────

    public static class ApiResponse {
        public final String status;   // "ok" | "failed" | "async"
        public final int retcode;
        public final Object data;     // JSONObject | JSONArray | null
        public final Object echo;

        public ApiResponse(String status, int retcode, Object data, Object echo) {
            this.status = status;
            this.retcode = retcode;
            this.data = data;
            this.echo = echo;
        }

        public static ApiResponse ok(Object data, Object echo) {
            return new ApiResponse("ok", 0, data, echo);
        }

        public static ApiResponse failed(int retcode, Object echo) {
            return new ApiResponse("failed", retcode, null, echo);
        }

        public static ApiResponse async(Object echo) {
            return new ApiResponse("async", 1, null, echo);
        }

        public JSONObject toJSON() {
            JSONObject j = new JSONObject();
            try {
                j.put("status", status);
                j.put("retcode", retcode);
                j.put("data", data == null ? JSONObject.NULL : data);
                if (echo != null) {
                    j.put("echo", echo);
                }
            } catch (Exception ignored) {}
            return j;
        }
    }

    // ── Message Segment ──────────────────────────────────────────

    public static class MessageSegment {
        public final String type;
        public final JSONObject data;

        public MessageSegment(String type, JSONObject data) {
            this.type = type;
            this.data = data != null ? data : new JSONObject();
        }

        public JSONObject toJSON() {
            JSONObject j = new JSONObject();
            try {
                j.put("type", type);
                j.put("data", data);
            } catch (Exception ignored) {}
            return j;
        }
    }

    // ── Event ────────────────────────────────────────────────────

    public static class Event {
        public final long time;
        public final long selfId;
        public final String postType;   // "message" | "notice" | "request" | "meta_event"
        public final JSONObject fields; // type-specific fields

        public Event(long selfId, String postType, JSONObject fields) {
            this.time = System.currentTimeMillis() / 1000;
            this.selfId = selfId;
            this.postType = postType;
            this.fields = (fields != null) ? fields : new JSONObject();
        }

        public JSONObject toJSON() {
            JSONObject j = new JSONObject();
            try {
                j.put("time", time);
                j.put("self_id", selfId);
                j.put("post_type", postType);
                java.util.Iterator<String> it = fields.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    j.put(key, fields.opt(key));
                }
            } catch (Exception ignored) {}
            return j;
        }
    }

    // ── Message Event ────────────────────────────────────────────

    public static JSONObject makeMessageEvent(long selfId, String messageType,
                                               int messageId, long userId,
                                               long groupId, String rawMessage,
                                               long senderUserId, String senderNickname) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("message_type", messageType);   // "private" | "group"
            msg.put("sub_type", "normal");
            msg.put("message_id", messageId);
            msg.put("user_id", userId);
            msg.put("message", rawMessage);
            msg.put("raw_message", rawMessage);
            msg.put("font", 0);
            JSONObject sender = new JSONObject();
            sender.put("user_id", senderUserId);
            sender.put("nickname", senderNickname);
            sender.put("sex", "unknown");
            sender.put("age", 0);
            msg.put("sender", sender);
            if (messageType.equals("group")) {
                msg.put("group_id", groupId);
            }
        } catch (Exception ignored) {}
        return msg;
    }
}