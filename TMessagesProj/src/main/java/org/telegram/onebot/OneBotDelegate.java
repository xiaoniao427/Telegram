package org.telegram.onebot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

/**
 * Bridges OneBot v11 API calls to the real Telegram UI/messenger layer.
 *
 * Inject into OneBotApiHandler after OneBotBridge starts.
 *
 * ponytail: reads existing in-memory caches from MessagesController (no new storage).
 * TLRPC dependency removed — uses OneBotTypes wrappers instead.
 */
public class OneBotDelegate {

    private final int currentAccount;

    public OneBotDelegate(int account) {
        this.currentAccount = account;
    }

    // ── MessageDelegate ─────────────────────────────────────────

    public OneBotApiHandler.MessageDelegate createMessageDelegate() {
        return new OneBotApiHandler.MessageDelegate() {
            @Override
            public long sendPrivateMsg(long userId, String message, boolean autoEscape) {
                // ponytail: use OneBotTypes + MessagesController to resolve peer
                long did = userId;
                // Send through existing path
                SendMessagesHelper.getInstance(currentAccount).sendMessage(
                        message, did, null, null, null, false, null, null, null, true, 0, null);
                long msgId = OneBotTypes.nextMessageId();
                OneBotEventBus.getInstance().pushMessage("private", (int) msgId, userId, 0, message,
                        UserConfig.getInstance(currentAccount).getClientUserId(),
                        UserConfig.getInstance(currentAccount).getCurrentUser().first_name);
                return msgId;
            }

            @Override
            public long sendGroupMsg(long groupId, String message, boolean autoEscape) {
                long did = -groupId;
                SendMessagesHelper.getInstance(currentAccount).sendMessage(
                        message, did, null, null, null, false, null, null, null, true, 0, null);
                long msgId = OneBotTypes.nextMessageId();
                OneBotEventBus.getInstance().pushMessage("group", (int) msgId, 0, groupId, message,
                    UserConfig.getInstance(currentAccount).getClientUserId(),
                    UserConfig.getInstance(currentAccount).getCurrentUser().first_name);
                return msgId;
            }

            @Override
            public void deleteMsg(int messageId) {
                // ponytail: MessagesController.deleteMessages needs dialogId.
                // Add message→dialog mapping when message store is built.
                FileLog.d("OneBot: delete message " + messageId + " — needs message→dialog mapping");
            }
        };
    }

    // ── DataDelegate ─────────────────────────────────────────────

    public OneBotApiHandler.DataDelegate createDataDelegate() {
        return new OneBotApiHandler.DataDelegate() {
            @Override
            public JSONObject getLoginInfo() {
                JSONObject j = new JSONObject();
                try {
                    j.put("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                    j.put("nickname", UserConfig.getInstance(currentAccount).getCurrentUser().first_name);
                } catch (Exception ignored) {}
                return j;
            }

            @Override
            public JSONArray getFriendList() {
                JSONArray arr = new JSONArray();
                // ponytail: iterate MessagesController.getUsers() and filter contact=true
                MessagesController mc = MessagesController.getInstance(currentAccount);
                for (Object uObj : mc.getUsers().values()) {
                    OneBotTypes.OBUser u = (OneBotTypes.OBUser) uObj;
                    if (u.contact) {
                        JSONObject j = new JSONObject();
                        try {
                            j.put("user_id", u.id);
                            j.put("nickname", u.first_name);
                            j.put("remark", u.last_name);
                        } catch (Exception ignored) {}
                        arr.put(j);
                    }
                }
                return arr;
            }

            @Override
            public JSONArray getGroupList() {
                JSONArray arr = new JSONArray();
                MessagesController mc = MessagesController.getInstance(currentAccount);
                for (Object chatObj : mc.getChats().values()) {
                    OneBotTypes.OBChat c = (OneBotTypes.OBChat) chatObj;
                    if (c.megagroup) continue; // skip channels
                    JSONObject j = new JSONObject();
                    try {
                        j.put("group_id", c.id);
                        j.put("group_name", c.title);
                        j.put("member_count", c.participants_count);
                    } catch (Exception ignored) {}
                    arr.put(j);
                }
                return arr;
            }

            @Override
            public JSONObject getGroupInfo(long groupId) {
                OneBotTypes.OBChat c = (OneBotTypes.OBChat) MessagesController.getInstance(currentAccount).getChat(groupId);
                JSONObject j = new JSONObject();
                if (c == null) return j;
                try {
                    j.put("group_id", c.id);
                    j.put("group_name", c.title);
                    j.put("member_count", c.participants_count);
                } catch (Exception ignored) {}
                return j;
            }

            @Override
            public JSONArray getGroupMemberList(long groupId) {
                // ponytail: participants loaded lazily; if not available, return empty
                return new JSONArray();
            }

            @Override
            public JSONObject getGroupMemberInfo(long groupId, long userId) {
                JSONObject j = new JSONObject();
                try {
                    j.put("group_id", groupId);
                    j.put("user_id", userId);
                    j.put("role", "member");
                } catch (Exception ignored) {}
                return j;
            }

            @Override
            public JSONObject getStrangerInfo(long userId) {
                OneBotTypes.OBUser u = (OneBotTypes.OBUser) MessagesController.getUser(userId);
                JSONObject j = new JSONObject();
                try {
                    j.put("user_id", userId);
                    j.put("nickname", u != null ? u.first_name : "");
                    j.put("sex", u != null && !u.bot ? (u.sex ? "male" : "female") : "unknown");
                    j.put("age", 0);
                } catch (Exception ignored) {}
                return j;
            }

            @Override
            public JSONObject getStatus() {
                JSONObject j = new JSONObject();
                try {
                    j.put("online", true);
                    j.put("good", true);
                } catch (Exception ignored) {}
                return j;
            }

            @Override
            public JSONObject getVersionInfo() {
                JSONObject j = new JSONObject();
                try {
                    j.put("app_name", "telegram-onebot");
                    j.put("app_version", "1.0.0");
                    j.put("protocol_version", "v11");
                } catch (Exception ignored) {}
                return j;
            }
        };
    }

    // ── ActionDelegate ───────────────────────────────────────────

    public OneBotApiHandler.ActionDelegate createActionDelegate() {
        return new OneBotApiHandler.ActionDelegate() {
            public void setGroupKick(long groupId, long userId, boolean reject) {
                // ponytail: relay via MessagesController
                FileLog.d("OneBot: setGroupKick groupId=" + groupId + " userId=" + userId + " reject=" + reject);
            }
            public void setGroupBan(long groupId, long userId, long duration) {
                FileLog.d("OneBot: setGroupBan groupId=" + groupId + " userId=" + userId + " duration=" + duration);
            }
            public void setGroupWholeBan(long groupId, boolean enable) {
                FileLog.d("OneBot: setGroupWholeBan groupId=" + groupId + " enable=" + enable);
            }
            public void setGroupAdmin(long groupId, long userId, boolean enable) {
                FileLog.d("OneBot: setGroupAdmin groupId=" + groupId + " userId=" + userId + " enable=" + enable);
            }
            public void setGroupCard(long groupId, long userId, String card) {
                FileLog.d("OneBot: setGroupCard groupId=" + groupId + " userId=" + userId + " card=" + card);
            }
            public void setGroupName(long groupId, String name) {
                FileLog.d("OneBot: setGroupName groupId=" + groupId + " name=" + name);
            }
            public void setGroupLeave(long groupId, boolean dismiss) {
                FileLog.d("OneBot: setGroupLeave groupId=" + groupId + " dismiss=" + dismiss);
            }
            public void setGroupSpecialTitle(long groupId, long userId, String title, long duration) {
                FileLog.d("OneBot: setGroupSpecialTitle groupId=" + groupId + " userId=" + userId + " title=" + title);
            }
            public void setFriendAddRequest(String flag, boolean approve, String remark) {
                FileLog.d("OneBot: setFriendAddRequest flag=" + flag + " approve=" + approve);
            }
            public void setGroupAddRequest(String flag, String subType, boolean approve, String reason) {
                FileLog.d("OneBot: setGroupAddRequest flag=" + flag + " subType=" + subType + " approve=" + approve);
            }
            public void sendLike(long userId, long times) {
                FileLog.d("OneBot: sendLike userId=" + userId + " times=" + times);
            }
            public void cleanCache() {
                FileLog.d("OneBot: cleanCache");
            }
        };
    }
}