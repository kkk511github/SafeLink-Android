package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import javax.net.ssl.HttpsURLConnection;
import org.telegram.tgnet.ConnectionsManager;

public final class SafeLinkServers {
    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("safelink_servers_v1", Context.MODE_PRIVATE);
    }

    public static synchronized SafeLinkServer account(int account) {
        String value = preferences().getString("account_" + account, null);
        if (value != null) return SafeLinkServer.parse(value);
        if (!preferences().edit().putString("account_" + account, SafeLinkServer.PRIMARY.json()).commit()) {
            throw new IllegalStateException("无法保存账号的服务器配置");
        }
        return SafeLinkServer.PRIMARY;
    }

    public static boolean same(int a, int b) { return account(a).id.equals(account(b).id); }

    public static ArrayList<SafeLinkServer> all() {
        ArrayList<SafeLinkServer> result = new ArrayList<>();
        result.add(SafeLinkServer.PRIMARY);
        for (String key : preferences().getAll().keySet()) {
            if (!key.startsWith("server_")) continue;
            SafeLinkServer server = SafeLinkServer.parse(preferences().getString(key, ""));
            if (!server.id.equals(SafeLinkServer.PRIMARY.id)) result.add(server);
        }
        result.sort((a, b) -> a.id.equals(b.id) ? 0 : a.id.equals(SafeLinkServer.PRIMARY.id) ? -1 : b.id.equals(SafeLinkServer.PRIMARY.id) ? 1 : a.name.compareTo(b.name));
        return result;
    }

    public static synchronized void save(SafeLinkServer server) {
        ArrayList<SafeLinkServer> all = all();
        for (SafeLinkServer existing : all) {
            if (existing.id.equals(server.id)) {
                if (!existing.sameEndpoint(server)) throw new IllegalArgumentException("服务器身份与已保存的地址冲突");
                return;
            }
            if (existing.host.equals(server.host) && existing.port == server.port) throw new IllegalArgumentException("该地址的公钥已经改变");
        }
        if (all.size() >= 32) throw new IllegalArgumentException("最多保存 32 台服务器");
        if (!preferences().edit().putString("server_" + server.id, server.json()).commit()) throw new IllegalStateException("保存失败");
    }

    public static synchronized void bindForLogin(int account, SafeLinkServer server) {
        if (UserConfig.getInstance(account).isClientActivated()) throw new IllegalStateException("不能修改已登录账号的服务器");
        ConnectionsManager manager = ConnectionsManager.getInstance(account);
        save(server);
        // Login challenges belong to one server, even when an account slot is reused.
        SharedPreferences login = ApplicationLoader.applicationContext.getSharedPreferences("logininfo2_" + account, Context.MODE_PRIVATE);
        if (!login.edit().clear().commit()) throw new IllegalStateException("无法清除旧服务器的登录状态");
        if (account == UserConfig.selectedAccount && !ApplicationLoader.applicationContext.getSharedPreferences("logininfo2", Context.MODE_PRIVATE).edit().clear().commit()) {
            throw new IllegalStateException("无法清除旧服务器的登录状态");
        }
        if (!preferences().edit().putString("account_" + account, server.json()).commit()) throw new IllegalStateException("保存失败");
        UserConfig.getInstance(account).registeredForPush = false;
        manager.cleanup(true);
        manager.configureSafeLinkServer(server, true);
    }

    public static SafeLinkServer discover(String input) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) SafeLinkServer.discoveryURI(input).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalArgumentException("无法获取服务器配置，请检查 HTTPS 证书和地址");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[2048];
                int size;
                while ((size = stream.read(chunk)) != -1) {
                    if (output.size() + size > 16384) throw new IllegalArgumentException("服务器配置过大");
                    output.write(chunk, 0, size);
                }
                return SafeLinkServer.parse(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }

    public static synchronized byte[] pushKey(int account) {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("safelink_push_v1", Context.MODE_PRIVATE);
        String key = account(account).id;
        String value = prefs.getString(key, null);
        if (value != null) return Base64.decode(value, Base64.NO_WRAP);
        byte[] bytes;
        // Preserve the primary instance's existing registration during migration.
        if (key.equals(SafeLinkServer.PRIMARY.id) && SharedConfig.pushAuthKey != null && SharedConfig.pushAuthKey.length == 256) {
            bytes = SharedConfig.pushAuthKey.clone();
        } else {
            bytes = new byte[256];
            Utilities.random.nextBytes(bytes);
        }
        if (!prefs.edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) throw new IllegalStateException("无法保存推送密钥");
        return bytes;
    }
}
