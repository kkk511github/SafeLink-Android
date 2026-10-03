package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;



import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class AuthTokensHelper {

    public static ArrayList<TLRPC.TL_auth_loggedOut> getSavedLogOutTokens(int account) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("saved_tokens_" + SafeLinkServers.account(account).id, Context.MODE_PRIVATE);
        int count = Math.min(20, Math.max(0, preferences.getInt("count", 0)));

        if (count == 0) {
            return null;
        }

        ArrayList<TLRPC.TL_auth_loggedOut> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String value = preferences.getString("log_out_token_" + i, "");
            try {
                SerializedData serializedData = new SerializedData(Utilities.hexToBytes(value));
                TLRPC.TL_auth_loggedOut token = TLRPC.TL_auth_loggedOut.TLdeserialize(serializedData, serializedData.readInt32(true), true);
                if (token != null && token.future_auth_token != null && token.future_auth_token.length > 0) {
                    tokens.add(token);
                }
                serializedData.cleanup();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        return tokens;
    }

    public static void saveLogOutTokens(int account, ArrayList<TLRPC.TL_auth_loggedOut> tokens) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("saved_tokens_" + SafeLinkServers.account(account).id, Context.MODE_PRIVATE);
        ArrayList<TLRPC.TL_auth_loggedOut> activeTokens = new ArrayList<>();
        preferences.edit().clear().apply();
        int date = (int) (System.currentTimeMillis() / 1000L);
        for (int i = 0; i < Math.min(20, tokens.size()); i++) {
            activeTokens.add(tokens.get(i));
        }
        if (activeTokens.size() > 0) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putInt("count", activeTokens.size());
            for (int i = 0; i < activeTokens.size(); i++) {
                SerializedData data = new SerializedData(activeTokens.get(i).getObjectSize());
                activeTokens.get(i).serializeToStream(data);
                editor.putString("log_out_token_" + i, Utilities.bytesToHex(data.toByteArray()));
            }
            editor.apply();
            //   BackupAgent.requestBackup(ApplicationLoader.applicationContext);
        }
    }

    public static ArrayList<TLRPC.TL_auth_authorization> getSavedLogInTokens(int account) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("saved_tokens_login_" + SafeLinkServers.account(account).id, Context.MODE_PRIVATE);
        int count = preferences.getInt("count", 0);

        if (count == 0) {
            return null;
        }

        ArrayList<TLRPC.TL_auth_authorization> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String value = preferences.getString("log_in_token_" + i, "");
            try {
                SerializedData serializedData = new SerializedData(Utilities.hexToBytes(value));
                TLRPC.auth_Authorization token = TLRPC.auth_Authorization.TLdeserialize(serializedData, serializedData.readInt32(true), true);
                if (token instanceof TLRPC.TL_auth_authorization) {
                    tokens.add((TLRPC.TL_auth_authorization) token);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        return tokens;
    }

    public static void saveLogInToken(int account, TLRPC.TL_auth_authorization token) {
        ArrayList<TLRPC.TL_auth_authorization> tokens = getSavedLogInTokens(account);
        if (tokens == null) {
            tokens = new ArrayList<>();
        }
        tokens.add(0, token);
        saveLogInTokens(account, tokens);
    }

    private static void saveLogInTokens(int account, ArrayList<TLRPC.TL_auth_authorization> tokens) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("saved_tokens_login_" + SafeLinkServers.account(account).id, Context.MODE_PRIVATE);
        ArrayList<TLRPC.TL_auth_authorization> activeTokens = new ArrayList<>();
        preferences.edit().clear().apply();
        for (int i = 0; i < Math.min(20, tokens.size()); i++) {
            activeTokens.add(tokens.get(i));
        }
        if (activeTokens.size() > 0) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putInt("count", activeTokens.size());
            for (int i = 0; i < activeTokens.size(); i++) {
                SerializedData data = new SerializedData(activeTokens.get(i).getObjectSize());
                activeTokens.get(i).serializeToStream(data);
                editor.putString("log_in_token_" + i, Utilities.bytesToHex(data.toByteArray()));
            }
            editor.apply();
            BackupAgent.requestBackup();
        }
    }

    public static void addLogOutToken(String serverId, TLRPC.TL_auth_loggedOut response) {
        if (response.future_auth_token == null || response.future_auth_token.length == 0) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("saved_tokens_" + serverId, Context.MODE_PRIVATE);
        int count = Math.min(19, Math.max(0, preferences.getInt("count", 0)));
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = count; i > 0; i--) {
            editor.putString("log_out_token_" + i, preferences.getString("log_out_token_" + (i - 1), ""));
        }
        SerializedData data = new SerializedData(response.getObjectSize());
        response.serializeToStream(data);
        // The logout flow returns to the login screen immediately. Persist this
        // token before that screen can issue the next auth.sendCode request.
        if (!editor.putString("log_out_token_0", Utilities.bytesToHex(data.toByteArray())).putInt("count", count + 1).commit()) {
            FileLog.e("Failed to persist future auth token");
        }
        BackupAgent.requestBackup();
    }

    public static void clearLogInTokens() {
        for (SafeLinkServer server : SafeLinkServers.all()) {
            ApplicationLoader.applicationContext.getSharedPreferences("saved_tokens_login_" + server.id, Context.MODE_PRIVATE).edit().clear().apply();
            ApplicationLoader.applicationContext.getSharedPreferences("saved_tokens_" + server.id, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }
}
