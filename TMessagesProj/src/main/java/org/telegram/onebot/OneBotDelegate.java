package org.telegram.onebot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Bridges OneBot v11 API calls to the real Telegram UI/messenger layer.
 *
 * Inject into OneBotApiHandler after OneBotBridge starts.
 *
 * ponytail: this is the ONLY OneBot file that imports TLRPC.
 * Send/receive via ConnectionsManager → real MTProto, with local
 * echo message ids emitted immediately. Updates flow through
 * MessagesController.processUpdates() for the app's normal pipeline.
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
                return sendMsgInternal(userId, false, message);
            }

            @Override
            public long sendGroupMsg(long groupId, String message, boolean autoEscape) {
                return sendMsgInternal(groupId, true, message);
            }

            // ponytail: single codepath for private & group.
            // Uses getInputPeer to build the correct InputPeer, then fires
            // TL_messages_sendMessage via ConnectionManager.
            // The message is asynchronous — we emit a local message_id
            // immediately (same as the rest of the app does for local echo).
            private long sendMsgInternal(long peerId, boolean isGroup, String message) {
                long msgId = OneBotTypes.nextMessageId();
                TLRPC.InputPeer peer = MessagesController.getInstance(currentAccount).getInputPeer(isGroup ? -peerId : peerId);
                if (peer == null) {
                    FileLog.e("OneBot: getInputPeer returned null for " + (isGroup ? "group" : "user") + " " + peerId);
                    return 0;
                }

                TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
                req.peer = peer;
                req.message = message;
                req.random_id = msgId;
                req.flags = 0; // no reply, no attach, no entities
                req.clear_draft = false;

                String messageType = isGroup ? "group" : "private";
                long selfUserId = UserConfig.getInstance(currentAccount).getClientUserId();
                String selfName = UserConfig.getInstance(currentAccount).getCurrentUser().first_name;

                OneBotEventBus.getInstance().pushMessage(messageType, (int) msgId,
                        isGroup ? 0 : peerId, isGroup ? peerId : 0,
                        message, selfUserId, selfName);
                FileLog.d("OneBot: sendMsgInternal type=" + messageType + " peerId=" + peerId + " msgId=" + msgId);

                ConnectionsManager.getInstance(currentAccount).sendRequest(req,
                    (res, err) -> {
                        if (err != null) {
                            FileLog.e("OneBot: sendMsg failed " + err.code + " " + err.text);
                        } else if (res instanceof TLRPC.Updates) {
                            // Updates are processed by the app's normal pipeline;
                            // the real server msgId will replace our echo msgId.
                            MessagesController.getInstance(currentAccount)
                                .processUpdates((TLRPC.Updates) res, false);
                        }
                    },
                    ConnectionsManager.RequestFlagCanCompress | ConnectionsManager.RequestFlagNeedQuickAck
                );
                return msgId;
            }

            @Override
            public void deleteMsg(int messageId) {
                FileLog.d("OneBot: deleteMsg messageId=" + messageId);
                // ponytail: hard without a dialog of mapped message.
                // For now just a stub — need SparseArray of local→server messageId.
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
                MessagesController mc = MessagesController.getInstance(currentAccount);
                for (TLRPC.User u : mc.getUsers().values()) {
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
                for (TLRPC.Chat c : mc.getChats().values()) {
                    if (isChannel(c)) continue;
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

            // ponytail: avoid importing ChatObject — inline the check
            private boolean isChannel(TLRPC.Chat chat) {
                return chat instanceof TLRPC.TL_channel || chat instanceof TLRPC.TL_channelForbidden;
            }

            @Override
            public JSONObject getGroupInfo(long groupId) {
                TLRPC.Chat c = MessagesController.getInstance(currentAccount).getChat(groupId);
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
                TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(userId);
                JSONObject j = new JSONObject();
                try {
                    j.put("user_id", userId);
                    j.put("nickname", u != null ? u.first_name : "");
                    j.put("sex", "unknown");
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

            // ponytail: getInputPeer for general peers, then cast to exact types
            private TLRPC.InputPeer getPeer(long id) {
                return MessagesController.getInstance(currentAccount).getInputPeer(id);
            }

            public void setGroupKick(long groupId, long userId, boolean reject) {
                FileLog.d("OneBot: setGroupKick groupId=" + groupId + " userId=" + userId);
                TLRPC.TL_messages_deleteChatUser req = new TLRPC.TL_messages_deleteChatUser();
                req.chat_id = groupId;
                req.user_id = MessagesController.getInstance(currentAccount).getInputUser(getPeer(userId));
                req.revoke_history = reject;
                send(req, "setGroupKick");
            }
            public void setGroupBan(long groupId, long userId, long duration) {
                FileLog.d("OneBot: setGroupBan groupId=" + groupId + " userId=" + userId);
                TLRPC.TL_channels_editBanned req = new TLRPC.TL_channels_editBanned();
                req.channel = MessagesController.getInstance(currentAccount).getInputChannel(-groupId);
                req.participant = getPeer(userId);
                TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
                rights.view_messages = false;
                rights.send_messages = true;
                rights.send_media = true;
                rights.send_stickers = true;
                rights.send_gifs = true;
                rights.send_games = true;
                rights.send_inline = true;
                rights.send_polls = true;
                rights.change_info = true;
                rights.invite_users = true;
                rights.pin_messages = true;
                rights.until_date = duration > 0 ? (int)(System.currentTimeMillis() / 1000 + duration) : 0;
                req.banned_rights = rights;
                send(req, "setGroupBan");
            }
            public void setGroupWholeBan(long groupId, boolean enable) {
                FileLog.d("OneBot: setGroupWholeBan groupId=" + groupId + " enable=" + enable);
                // ponytail: OneBot "whole group mute" has no perfect Telegram API match — skip for now
            }
            public void setGroupAdmin(long groupId, long userId, boolean enable) {
                FileLog.d("OneBot: setGroupAdmin groupId=" + groupId + " userId=" + userId + " enable=" + enable);
                TLRPC.TL_channels_editAdmin req = new TLRPC.TL_channels_editAdmin();
                req.channel = MessagesController.getInstance(currentAccount).getInputChannel(-groupId);
                req.user_id = MessagesController.getInstance(currentAccount).getInputUser(getPeer(userId));
                TLRPC.TL_chatAdminRights rights = new TLRPC.TL_chatAdminRights();
                if (enable) {
                    rights.change_info = true;
                    rights.delete_messages = true;
                    rights.ban_users = true;
                    rights.invite_users = true;
                    rights.pin_messages = true;
                    rights.manage_call = true;
                }
                req.admin_rights = rights;
                req.rank = "";
                send(req, "setGroupAdmin");
            }
            public void setGroupCard(long groupId, long userId, String card) {
                FileLog.d("OneBot: setGroupCard groupId=" + groupId + " userId=" + userId);
            }
            public void setGroupName(long groupId, String name) {
                FileLog.d("OneBot: setGroupName groupId=" + groupId);
            }
            public void setGroupLeave(long groupId, boolean dismiss) {
                FileLog.d("OneBot: setGroupLeave groupId=" + groupId);
            }
            public void setGroupSpecialTitle(long groupId, long userId, String title, long duration) {
                FileLog.d("OneBot: setGroupSpecialTitle groupId=" + groupId + " userId=" + userId);
            }
            public void setFriendAddRequest(String flag, boolean approve, String remark) {
                FileLog.d("OneBot: setFriendAddRequest flag=" + flag);
            }
            public void setGroupAddRequest(String flag, String subType, boolean approve, String reason) {
                FileLog.d("OneBot: setGroupAddRequest flag=" + flag);
            }
            public void sendLike(long userId, long times) {
                FileLog.d("OneBot: sendLike userId=" + userId);
            }
            public void cleanCache() {
                FileLog.d("OneBot: cleanCache");
            }

            private void send(TLObject req, String name) {
                ConnectionsManager.getInstance(currentAccount).sendRequest(req,
                    (res, err) -> {
                        if (err != null) {
                            FileLog.e("OneBot: " + name + " failed " + err.code + " " + err.text);
                        } else if (res instanceof TLRPC.Updates) {
                            MessagesController.getInstance(currentAccount)
                                .processUpdates((TLRPC.Updates) res, false);
                        }
                    });
            }
        };
    }
}