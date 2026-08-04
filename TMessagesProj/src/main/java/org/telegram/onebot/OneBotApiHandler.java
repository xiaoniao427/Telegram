package org.telegram.onebot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.Map;

/**
 * OneBot v11 API handler with injectable delegates.
 *
 * ponytail: plain HashMap dispatch. Call setMessageDelegate/setDataDelegate
 * from the UI layer to bridge OneBot API calls to real UI actions.
 */
public class OneBotApiHandler {

    // ── Delegate interfaces (inject real UI logic here) ──────────

    /** Called when OneBot wants to send a message. */
    public interface MessageDelegate {
        /** @return message_id */
        long sendPrivateMsg(long userId, String message, boolean autoEscape);
        long sendGroupMsg(long groupId, String message, boolean autoEscape);
        void deleteMsg(int messageId);
    }

    /** Called when OneBot wants to query data from the app's cache/state. */
    public interface DataDelegate {
        JSONObject getLoginInfo();
        JSONArray  getFriendList();
        JSONArray  getGroupList();
        JSONObject getGroupInfo(long groupId);
        JSONArray  getGroupMemberList(long groupId);
        JSONObject getGroupMemberInfo(long groupId, long userId);
        JSONObject getStrangerInfo(long userId);
        JSONObject getStatus();
        JSONObject getVersionInfo();
    }

    public interface ActionDelegate {
        void setGroupKick(long groupId, long userId, boolean rejectAddRequest);
        void setGroupBan(long groupId, long userId, long duration);
        void setGroupWholeBan(long groupId, boolean enable);
        void setGroupAdmin(long groupId, long userId, boolean enable);
        void setGroupCard(long groupId, long userId, String card);
        void setGroupName(long groupId, String name);
        void setGroupLeave(long groupId, boolean dismiss);
        void setGroupSpecialTitle(long groupId, long userId, String title, long duration);
        void setFriendAddRequest(String flag, boolean approve, String remark);
        void setGroupAddRequest(String flag, String subType, boolean approve, String reason);
        void sendLike(long userId, long times);
        void cleanCache();
    }

    private MessageDelegate messageDelegate;
    private DataDelegate dataDelegate;
    private ActionDelegate actionDelegate;

    public void setMessageDelegate(MessageDelegate d) { this.messageDelegate = d; }
    public void setDataDelegate(DataDelegate d) { this.dataDelegate = d; }
    public void setActionDelegate(ActionDelegate d) { this.actionDelegate = d; }

    // ── Dispatch ──────────────────────────────────────────────────

    private final Map<String, Handler> handlers = new HashMap<>();

    public interface Handler {
        OneBotModels.ApiResponse handle(OneBotModels.ApiRequest req);
    }

    public OneBotApiHandler() {
        registerAll();
    }

    private void register(String action, Handler handler) {
        handlers.put(action, handler);
    }

    public OneBotModels.ApiResponse dispatch(OneBotModels.ApiRequest req) {
        if (req == null || req.action == null)
            return OneBotModels.ApiResponse.failed(1400, null);

        String action = req.action;
        boolean async = action.endsWith("_async");
        boolean rateLimited = action.endsWith("_rate_limited");
        if (async) action = action.substring(0, action.length() - 6);
        if (rateLimited) action = action.substring(0, action.length() - 13);

        Handler h = handlers.get(action);
        if (h == null) return OneBotModels.ApiResponse.failed(1404, req.echo);

        try {
            if (async || rateLimited) return OneBotModels.ApiResponse.async(req.echo);
            return h.handle(req);
        } catch (Exception e) {
            FileLog.e("OneBot API error for " + req.action, e);
            return OneBotModels.ApiResponse.failed(1500, req.echo);
        }
    }

    // ── All OneBot v11 public APIs ──────────────────────────────

    private void registerAll() {
        register("send_private_msg", req -> {
            if (messageDelegate == null) return ok("{\"message_id\":0}", req);
            long msgId = messageDelegate.sendPrivateMsg(
                    req.params.optLong("user_id"),
                    req.params.optString("message"),
                    req.params.optBoolean("auto_escape", false));
            return ok("{\"message_id\":" + msgId + "}", req);
        });
        register("send_group_msg", req -> {
            if (messageDelegate == null) return ok("{\"message_id\":0}", req);
            long msgId = messageDelegate.sendGroupMsg(
                    req.params.optLong("group_id"),
                    req.params.optString("message"),
                    req.params.optBoolean("auto_escape", false));
            return ok("{\"message_id\":" + msgId + "}", req);
        });
        register("send_msg", req -> {
            if (messageDelegate == null) return ok("{\"message_id\":0}", req);
            String type = req.params.optString("message_type", "private");
            long msgId;
            if ("group".equals(type)) {
                msgId = messageDelegate.sendGroupMsg(
                    req.params.optLong("group_id"),
                    req.params.optString("message"),
                    req.params.optBoolean("auto_escape", false));
            } else {
                msgId = messageDelegate.sendPrivateMsg(
                    req.params.optLong("user_id"),
                    req.params.optString("message"),
                    req.params.optBoolean("auto_escape", false));
            }
            return ok("{\"message_id\":" + msgId + "}", req);
        });
        register("delete_msg", req -> {
            if (messageDelegate != null)
                messageDelegate.deleteMsg(req.params.optInt("message_id", 0));
            return okNull(req);
        });
        register("get_msg", req -> ok("{\"time\":0,\"message_type\":\"private\",\"message_id\":0,\"real_id\":0,\"sender\":{},\"message\":\"\"}", req));
        register("get_forward_msg", req -> ok("{\"message\":[]}", req));

        // ── 操作 ──────────────────────────────────────────
        register("send_like", req -> {
            exec(actionDelegate, d -> d.sendLike(param(req, "user_id", 0), param(req, "times", 1)));
            return okNull(req);
        });
        register("set_group_kick", req -> {
            exec(actionDelegate, d -> d.setGroupKick(param(req, "group_id", 0), param(req, "user_id", 0)));
            return okNull(req);
        });
        register("set_group_ban", req -> {
            exec(actionDelegate, d -> d.setGroupBan(param(req, "group_id", 0), param(req, "user_id", 0), param(req, "duration", 30 * 60)));
            return okNull(req);
        });
        register("set_group_anonymous_ban", req -> okNull(req)); // no delegate needed yet
        register("set_group_whole_ban", req -> {
            exec(actionDelegate, d -> d.setGroupWholeBan(param(req, "group_id", 0), param(req, "enable", true)));
            return okNull(req);
        });
        register("set_group_admin", req -> {
            exec(actionDelegate, d -> d.setGroupAdmin(param(req, "group_id", 0), param(req, "user_id", 0), param(req, "enable", true)));
            return okNull(req);
        });
        register("set_group_anonymous", req -> okNull(req));
        register("set_group_card", req -> {
            exec(actionDelegate, d -> d.setGroupCard(param(req, "group_id", 0), param(req, "user_id", 0), param(req, "card", "")));
            return okNull(req);
        });
        register("set_group_name", req -> {
            exec(actionDelegate, d -> d.setGroupName(param(req, "group_id", 0), param(req, "group_name", "")));
            return okNull(req);
        });
        register("set_group_leave", req -> {
            exec(actionDelegate, d -> d.setGroupLeave(param(req, "group_id", 0), param(req, "is_dismiss", false)));
            return okNull(req);
        });
        register("set_group_special_title", req -> {
            exec(actionDelegate, d -> d.setGroupSpecialTitle(
                param(req, "group_id", 0), param(req, "user_id", 0),
                param(req, "special_title", ""), param(req, "duration", -1)));
            return okNull(req);
        });
        register("set_friend_add_request", req -> {
            exec(actionDelegate, d -> d.setFriendAddRequest(
                param(req, "flag", ""), param(req, "approve", true), param(req, "remark", "")));
            return okNull(req);
        });
        register("set_group_add_request", req -> {
            exec(actionDelegate, d -> d.setGroupAddRequest(
                param(req, "flag", ""), param(req, "sub_type", "add"),
                param(req, "approve", true), param(req, "reason", "")));
            return okNull(req);
        });

        // ── 信息获取 ───────────────────────────────────────
        register("get_login_info", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getLoginInfo(), req.echo);
            return ok("{\"user_id\":0,\"nickname\":\"\"}", req);
        });
        register("get_stranger_info", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getStrangerInfo(param(req, "user_id", 0)), req.echo);
            return ok("{\"user_id\":0,\"nickname\":\"\",\"sex\":\"unknown\",\"age\":0}", req);
        });
        register("get_friend_list", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getFriendList(), req.echo);
            return OneBotModels.ApiResponse.ok(new JSONArray(), req.echo);
        });
        register("get_group_info", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getGroupInfo(param(req, "group_id", 0)), req.echo);
            return ok("{\"group_id\":0,\"group_name\":\"\",\"member_count\":0,\"max_member_count\":0}", req);
        });
        register("get_group_list", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getGroupList(), req.echo);
            return OneBotModels.ApiResponse.ok(new JSONArray(), req.echo);
        });
        register("get_group_member_info", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getGroupMemberInfo(param(req, "group_id", 0), param(req, "user_id", 0)), req.echo);
            return ok("{\"group_id\":0,\"user_id\":0,\"nickname\":\"\",\"card\":\"\",\"sex\":\"unknown\",\"age\":0,\"role\":\"member\"}", req);
        });
        register("get_group_member_list", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getGroupMemberList(param(req, "group_id", 0)), req.echo);
            return OneBotModels.ApiResponse.ok(new JSONArray(), req.echo);
        });
        register("get_group_honor_info", req -> ok("{\"group_id\":0}", req));
        register("get_cookies", req -> ok("{\"cookies\":\"\"}", req));
        register("get_csrf_token", req -> ok("{\"token\":0}", req));
        register("get_credentials", req -> ok("{\"cookies\":\"\",\"csrf_token\":0}", req));
        register("get_record", req -> ok("{\"file\":\"\"}", req));
        register("get_image", req -> ok("{\"file\":\"\"}", req));
        register("can_send_image", req -> ok("{\"yes\":false}", req));
        register("can_send_record", req -> ok("{\"yes\":false}", req));
        register("get_status", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getStatus(), req.echo);
            return ok("{\"online\":true,\"good\":true}", req);
        });
        register("get_version_info", req -> {
            if (dataDelegate != null) return OneBotModels.ApiResponse.ok(dataDelegate.getVersionInfo(), req.echo);
            return ok("{\"app_name\":\"telegram-onebot\",\"app_version\":\"1.0.0\",\"protocol_version\":\"v11\"}", req);
        });
        register("set_restart", req -> OneBotModels.ApiResponse.async(req.echo));
        register("clean_cache", req -> {
            exec(actionDelegate, ActionDelegate::cleanCache);
            return okNull(req);
        });
    }

    // ── helpers ─────────────────────────────────────────────────

    private static long param(OneBotModels.ApiRequest req, String key, long def) {
        return req.params != null ? req.params.optLong(key, def) : def;
    }
    private static boolean param(OneBotModels.ApiRequest req, String key, boolean def) {
        return req.params != null ? req.params.optBoolean(key, def) : def;
    }
    private static String param(OneBotModels.ApiRequest req, String key, String def) {
        return req.params != null ? req.params.optString(key, def) : def;
    }

    private OneBotModels.ApiResponse ok(String json, OneBotModels.ApiRequest req) {
        try { return OneBotModels.ApiResponse.ok(new JSONObject(json), req.echo); } catch (Exception e) { return okNull(req); }
    }
    private OneBotModels.ApiResponse okNull(OneBotModels.ApiRequest req) {
        return OneBotModels.ApiResponse.ok(null, req.echo);
    }

    private interface ThrowingConsumer<T> { void call(T t) throws Exception; }
    @SuppressWarnings("unchecked")
    private static <T> void exec(T delegate, ThrowingConsumer<T> block) {
        if (delegate == null) return;
        try { block.call(delegate); } catch (Exception e) { FileLog.e("OneBot delegate error", e); }
    }
}