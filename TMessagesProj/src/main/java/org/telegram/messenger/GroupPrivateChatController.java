package org.telegram.messenger;

import android.content.SharedPreferences;

import androidx.collection.LongSparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

public class GroupPrivateChatController extends BaseController {

    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile GroupPrivateChatController[] Instance = new GroupPrivateChatController[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    private static final String PREF_KEY_PREFIX = "safelink_private_chat_forbidden_";

    private final LongSparseArray<Boolean> forbiddenByChatId = new LongSparseArray<>();

    public static GroupPrivateChatController getInstance(int num) {
        GroupPrivateChatController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new GroupPrivateChatController(num);
                }
            }
        }
        return localInstance;
    }

    private GroupPrivateChatController(int num) {
        super(num);
    }

    public boolean isForbidden(long chatId) {
        Boolean value = forbiddenByChatId.get(chatId);
        if (value != null) {
            return value;
        }
        boolean persisted = getMessagesController().getMainSettings().getBoolean(cacheKey(chatId), false);
        forbiddenByChatId.put(chatId, persisted);
        return persisted;
    }

    public void setCached(long chatId, boolean enabled) {
        applyCached(chatId, enabled);
    }

    private static String cacheKey(long chatId) {
        return PREF_KEY_PREFIX + chatId;
    }

    private void applyCached(long chatId, boolean enabled) {
        Boolean previousValue = forbiddenByChatId.get(chatId);
        boolean previous = previousValue != null ? previousValue : getMessagesController().getMainSettings().getBoolean(cacheKey(chatId), false);
        forbiddenByChatId.put(chatId, enabled);
        SharedPreferences.Editor editor = getMessagesController().getMainSettings().edit();
        editor.putBoolean(cacheKey(chatId), enabled);
        editor.apply();
        if (previous != enabled) {
            getNotificationCenter().postNotificationName(NotificationCenter.safeLinkGroupPrivateChatForbiddenChanged, chatId, enabled);
        }
    }

    public void reloadFromChannelUpdate(long chatId) {
        TLRPC.Chat chat = getMessagesController().getChat(chatId);
        if (chat != null && !chat.min) {
            load(chatId, chat, null);
        }
    }

    public void load(long chatId, TLRPC.Chat chat, Utilities.Callback<Boolean> callback) {
        if (!ChatObject.isMegagroup(chat)) {
            applyCached(chatId, false);
            if (callback != null) {
                callback.run(false);
            }
            return;
        }
        TLRPC.InputChannel inputChannel = MessagesController.getInputChannel(chat);
        if (inputChannel instanceof TLRPC.TL_inputChannelEmpty) {
            if (callback != null) {
                callback.run(isForbidden(chatId));
            }
            return;
        }
        TLRPC.TL_safelink_getGroupPrivateChatForbidden req = new TLRPC.TL_safelink_getGroupPrivateChatForbidden();
        req.channel = inputChannel;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            boolean enabled = isForbidden(chatId);
            if (error == null) {
                enabled = response instanceof TLRPC.TL_boolTrue;
                applyCached(chatId, enabled);
            }
            if (callback != null) {
                callback.run(enabled);
            }
        }));
    }

    public void setForbidden(long chatId, TLRPC.Chat chat, boolean enabled, Utilities.Callback2<Boolean, TLRPC.TL_error> callback) {
        TLRPC.InputChannel inputChannel = MessagesController.getInputChannel(chat);
        if (inputChannel instanceof TLRPC.TL_inputChannelEmpty) {
            if (callback != null) {
                callback.run(isForbidden(chatId), null);
            }
            return;
        }
        boolean previous = isForbidden(chatId);
        applyCached(chatId, enabled);
        TLRPC.TL_safelink_toggleGroupPrivateChatForbidden req = new TLRPC.TL_safelink_toggleGroupPrivateChatForbidden();
        req.channel = inputChannel;
        req.enabled = enabled;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error == null && response instanceof TLRPC.Updates) {
                getMessagesController().processUpdates((TLRPC.Updates) response, false);
                applyCached(chatId, enabled);
            } else if (error != null) {
                applyCached(chatId, previous);
            }
            if (callback != null) {
                callback.run(isForbidden(chatId), error);
            }
        }), ConnectionsManager.RequestFlagInvokeAfter);
    }

    public boolean shouldBlockPrivateChatFromGroup(TLRPC.Chat chat, TLRPC.ChatFull chatFull, long userId) {
        if (chat == null || userId <= 0 || userId == getUserConfig().getClientUserId()) {
            return false;
        }
        if (!ChatObject.isMegagroup(chat) || !isForbidden(chat.id)) {
            return false;
        }
        if (ChatObject.hasAdminRights(chat)) {
            return false;
        }
        return !isParticipantAdmin(chatFull, userId);
    }

    public boolean canMentionParticipant(TLRPC.Chat chat, TLRPC.ChatFull chatFull, long userId) {
        return !shouldBlockPrivateChatFromGroup(chat, chatFull, userId);
    }

    public boolean isParticipantAdmin(TLRPC.ChatFull chatFull, long userId) {
        if (chatFull == null || chatFull.participants == null) {
            return false;
        }
        for (int i = 0, count = chatFull.participants.participants.size(); i < count; i++) {
            TLRPC.ChatParticipant participant = chatFull.participants.participants.get(i);
            if (participant == null || participant.user_id != userId) {
                continue;
            }
            if (participant instanceof TLRPC.TL_chatChannelParticipant) {
                TLRPC.ChannelParticipant channelParticipant = ((TLRPC.TL_chatChannelParticipant) participant).channelParticipant;
                return channelParticipant instanceof TLRPC.TL_channelParticipantCreator ||
                        channelParticipant instanceof TLRPC.TL_channelParticipantAdmin;
            }
            return participant instanceof TLRPC.TL_chatParticipantCreator ||
                    participant instanceof TLRPC.TL_chatParticipantAdmin;
        }
        return false;
    }
}
