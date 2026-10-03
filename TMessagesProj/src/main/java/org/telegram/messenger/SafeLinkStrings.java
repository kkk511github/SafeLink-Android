package org.telegram.messenger;

import java.util.Locale;

final class SafeLinkStrings {
    private SafeLinkStrings() {
    }

    static String chineseOverride(String key, int res, String languageCode) {
        if (languageCode == null) {
            return null;
        }
        String code = languageCode.toLowerCase(Locale.ROOT).replace('_', '-');
        if (!code.equals("zh") && !code.startsWith("zh-")) {
            return null;
        }
        boolean traditional = code.contains("hant") || code.equals("zh-tw") || code.equals("zh-hk") || code.equals("zh-mo");
        if ("SafeLinkGroupPrivateChatForbidden".equals(key) || key == null && res == R.string.SafeLinkGroupPrivateChatForbidden) {
            return "禁止私聊";
        }
        if ("SafeLinkGroupPrivateChatForbiddenToast".equals(key) || key == null && res == R.string.SafeLinkGroupPrivateChatForbiddenToast) {
            return traditional ? "此群已禁止與普通成員私聊" : "此群已禁止与普通成员私聊";
        }
        return null;
    }
}
