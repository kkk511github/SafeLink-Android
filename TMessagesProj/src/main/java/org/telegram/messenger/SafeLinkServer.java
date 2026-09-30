package org.telegram.messenger;

import android.util.Base64;
import org.json.JSONObject;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

public final class SafeLinkServer {
    public final String id, name, host, publicKey, fingerprint;
    public final int port, dc;
    public static final SafeLinkServer PRIMARY = parse("{\"version\":1,\"server_id\":\"5cc5b7bffe3c42758a7d4a44c168feb59039b8099ae6a759740d90d5e0393507\",\"name\":\"SafeLink\",\"host\":\"212.189.31.87\",\"port\":2398,\"dc_id\":2,\"rsa_public_key\":\"-----BEGIN RSA PUBLIC KEY-----\\nMIIBCgKCAQEAzmgJTNhh+Rfz1sBBb2htmPUtIJULMB2YRFElh59UbNl7tHe0h73m\\n4wDxMNWd5R/0TInVrXP1XEGwztIdZ56/xKUsm+VvioP+Ohk4vsYK73eArzx4afs4\\nUs1eZhLfEdO6ouAjeuE2oMyoyk9BfDI8vhYU6flAZcHHlAfmFbflkdXvHEqm+PHW\\n76CSmQDJ9yhNoy41cVPvCLw5UKbgu8c/xdIpIIGEk01BJjtCNbiLJKRLjUIFVIlv\\nnsSnrnQwou4I2p90PWjqAQODKiRMscrgYRXj4GO8W9zVibf1ZPzWznmRZVWERWm9\\nQ0XxobndWXPc8Ei4Y2LAp7uA8/iL94nN/wIDAQAB\\n-----END RSA PUBLIC KEY-----\\n\",\"rsa_fingerprint\":\"4be27a5bb0fc10c4\"}");

    public SafeLinkServer(JSONObject json) throws Exception {
        if (json.getInt("version") != 1) throw new IllegalArgumentException("不支持的服务器配置版本");
        id = json.getString("server_id");
        name = json.getString("name");
        host = json.getString("host");
        port = json.getInt("port");
        dc = json.getInt("dc_id");
        publicKey = json.getString("rsa_public_key").trim();
        fingerprint = json.getString("rsa_fingerprint");
        if (name.isEmpty() || name.length() > 80 || name.matches("(?s).*\\p{Cntrl}.*")
                || port < 1 || port > 65535 || dc < 1 || dc > 1000
                || !(host.matches("[0-9.]+") || host.matches("[0-9a-fA-F:]+") && host.contains(":"))) {
            throw new IllegalArgumentException("服务器地址配置无效");
        }
        InetAddress address = InetAddress.getByName(host);
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) throw new IllegalArgumentException("服务器 IP 无效");
        if (publicKey.length() > 4096 || !publicKey.startsWith("-----BEGIN RSA PUBLIC KEY-----\n")
                || !publicKey.endsWith("-----END RSA PUBLIC KEY-----")) throw new IllegalArgumentException("RSA 公钥格式无效");
        byte[] der = Base64.decode(publicKey.replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", ""), Base64.DEFAULT);
        byte[] prefix = {0x30, (byte) 0x82, 1, 10, 2, (byte) 0x82, 1, 1, 0};
        if (der.length != 270 || !Arrays.equals(prefix, Arrays.copyOf(der, 9))
                || !Arrays.equals(new byte[]{2, 3, 1, 0, 1}, Arrays.copyOfRange(der, 265, 270))) throw new IllegalArgumentException("仅支持 RSA-2048/65537 公钥");
        BigInteger modulus = new BigInteger(1, Arrays.copyOfRange(der, 9, 265));
        if (modulus.bitLength() != 2048) throw new IllegalArgumentException("RSA 公钥位数无效");
        KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)));
        if (!hex(MessageDigest.getInstance("SHA-256").digest(der)).equals(id)) throw new IllegalArgumentException("服务器身份校验失败");
        byte[] tl = new byte[264];
        tl[0] = (byte) 254; tl[2] = 1;
        System.arraycopy(der, 9, tl, 4, 256);
        tl[260] = 3; tl[261] = 1; tl[263] = 1;
        byte[] sha = MessageDigest.getInstance("SHA-1").digest(tl);
        byte[] reversed = new byte[8];
        for (int i = 0; i < 8; i++) reversed[i] = sha[19 - i];
        if (!hex(reversed).equals(fingerprint)) throw new IllegalArgumentException("RSA 指纹校验失败");
    }

    public static SafeLinkServer parse(String value) {
        try { return new SafeLinkServer(new JSONObject(value)); }
        catch (Exception e) { throw new IllegalArgumentException("服务器配置无效", e); }
    }

    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    public String json() {
        try {
            return new JSONObject().put("version", 1).put("server_id", id).put("name", name)
                    .put("host", host).put("port", port).put("dc_id", dc)
                    .put("rsa_public_key", publicKey).put("rsa_fingerprint", fingerprint).toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public boolean sameEndpoint(SafeLinkServer other) {
        return id.equals(other.id) && host.equals(other.host) && port == other.port && dc == other.dc;
    }

    public static URI discoveryURI(String input) throws Exception {
        String text = input.trim();
        URI uri = new URI(text.contains("://") ? text : "https://" + text);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("请输入 IP 或 HTTPS 地址");
        return new URI("https", null, uri.getHost(), uri.getPort(), "/.well-known/safelink-client.json", null, null);
    }
}

