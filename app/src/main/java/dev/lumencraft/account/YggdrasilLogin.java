package dev.lumencraft.account;

import android.util.Base64;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Legal third-party Yggdrasil (authlib-injector) authentication and skin/cape metadata reader. */
final class YggdrasilLogin {
    interface Transport {
        Response send(String url, String body, String bearer) throws IOException;
        String discoverApiLocation(String url) throws IOException;
    }

    static final class Response {
        final int status;
        final JSONObject body;
        Response(int status, JSONObject body) { this.status = status; this.body = body; }
    }

    static final class AuthResult {
        final String apiUrl, accessToken, clientToken, profileId, profileName, skinUrl, capeUrl;
        AuthResult(String apiUrl, String accessToken, String clientToken,
                   String profileId, String profileName, String skinUrl, String capeUrl) {
            this.apiUrl = apiUrl;
            this.accessToken = accessToken;
            this.clientToken = clientToken;
            this.profileId = profileId;
            this.profileName = profileName;
            this.skinUrl = skinUrl == null ? "" : skinUrl;
            this.capeUrl = capeUrl == null ? "" : capeUrl;
        }
    }

    private final Transport http;
    YggdrasilLogin() { this(new LiveTransport()); }
    YggdrasilLogin(Transport http) { this.http = http; }

    static String normalizeApiRoot(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) throw new AccountProblem("请输入第三方认证服务器地址");
        String value = raw.trim();
        if (!value.startsWith("https://")) value = "https://" + value;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new AccountProblem("认证服务器地址必须为有效的 HTTPS 网址");
        } catch (AccountProblem e) {
            throw e;
        } catch (Exception e) {
            throw new AccountProblem("认证服务器地址格式无效");
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** Resolves authlib-injector ALI header if present, then authenticates against /authserver/authenticate. */
    AuthResult authenticate(String serverInput, String username, String password) throws Exception {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new AccountProblem("请输入账号与密码");
        }
        String root = normalizeApiRoot(serverInput);
        String discovered = http.discoverApiLocation(root);
        if (discovered != null && !discovered.isEmpty()) root = normalizeApiRoot(discovered);

        String clientToken = UUID.randomUUID().toString().replace("-", "");
        JSONObject req = new JSONObject()
                .put("agent", new JSONObject().put("name", "Minecraft").put("version", 1))
                .put("username", username.trim())
                .put("password", password)
                .put("clientToken", clientToken)
                .put("requestUser", false);
        Response res = http.send(root + "/authserver/authenticate", req.toString(), null);
        if (res.status < 200 || res.status >= 300) {
            String msg = res.body.optString("errorMessage", "认证服务器拒绝登录（HTTP " + res.status + "）");
            throw new AccountProblem(msg);
        }
        String access = res.body.optString("accessToken", "");
        String returnedClient = res.body.optString("clientToken", clientToken);
        JSONObject selected = res.body.optJSONObject("selectedProfile");
        if (selected == null) {
            JSONArray avail = res.body.optJSONArray("availableProfiles");
            if (avail != null && avail.length() > 0) selected = avail.optJSONObject(0);
        }
        if (access.isEmpty() || selected == null) {
            throw new AccountProblem("认证响应缺少可用角色或访问令牌");
        }
        String id = selected.getString("id").toLowerCase(Locale.ROOT);
        String name = selected.getString("name");
        String[] textures = fetchTextures(root, id);
        return new AuthResult(root, access, returnedClient, id, name, textures[0], textures[1]);
    }

    AuthResult refresh(String apiRoot, String accessToken, String clientToken, String profileId, String profileName) throws Exception {
        String root = normalizeApiRoot(apiRoot);
        JSONObject req = new JSONObject().put("accessToken", accessToken).put("clientToken", clientToken);
        Response res = http.send(root + "/authserver/refresh", req.toString(), null);
        if (res.status < 200 || res.status >= 300) {
            throw new AccountProblem("第三方认证令牌已过期，请重新登录");
        }
        String nextAccess = res.body.optString("accessToken", "");
        JSONObject sel = res.body.optJSONObject("selectedProfile");
        String id = sel != null ? sel.optString("id", profileId) : profileId;
        String name = sel != null ? sel.optString("name", profileName) : profileName;
        String[] textures = fetchTextures(root, id);
        return new AuthResult(root, nextAccess.isEmpty() ? accessToken : nextAccess, clientToken, id, name, textures[0], textures[1]);
    }

    String[] fetchTextures(String apiRoot, String uuid) {
        try {
            Response r = http.send(apiRoot + "/sessionserver/session/minecraft/profile/" + uuid, null, null);
            if (r.status != 200) return new String[]{"", ""};
            JSONArray props = r.body.optJSONArray("properties");
            if (props == null) return new String[]{"", ""};
            for (int i = 0; i < props.length(); i++) {
                JSONObject p = props.optJSONObject(i);
                if (p != null && "textures".equals(p.optString("name"))) {
                    byte[] decoded = Base64.decode(p.getString("value"), Base64.DEFAULT);
                    JSONObject tex = new JSONObject(new String(decoded, StandardCharsets.UTF_8)).optJSONObject("textures");
                    if (tex == null) return new String[]{"", ""};
                    String skin = tex.optJSONObject("SKIN") != null ? tex.optJSONObject("SKIN").optString("url", "") : "";
                    String cape = tex.optJSONObject("CAPE") != null ? tex.optJSONObject("CAPE").optString("url", "") : "";
                    return new String[]{skin, cape};
                }
            }
        } catch (Exception ignored) {
        }
        return new String[]{"", ""};
    }

    private static final class LiveTransport implements Transport {
        @Override
        public String discoverApiLocation(String address) throws IOException {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "LumenCraftAndroid/Alpha1.2.0");
            try {
                conn.getResponseCode();
                String ali = conn.getHeaderField("X-Authlib-Injector-API-Location");
                if (ali == null || ali.trim().isEmpty()) return null;
                return new URL(new URL(address + "/"), ali.trim()).toExternalForm();
            } finally {
                conn.disconnect();
            }
        }

        @Override
        public Response send(String address, String body, String bearer) throws IOException {
            HttpsURLConnection conn = (HttpsURLConnection) new URL(address).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "LumenCraftAndroid/Alpha1.2.0");
            try {
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
                if (body != null) {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
                }
                int status = conn.getResponseCode();
                try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in != null) {
                        byte[] buf = new byte[8192];
                        for (int n; (n = in.read(buf)) >= 0; ) {
                            if (out.size() + n > 512 * 1024) throw new AccountProblem("第三方认证响应过大");
                            out.write(buf, 0, n);
                        }
                    }
                    try { return new Response(status, new JSONObject(out.toString("UTF-8"))); }
                    catch (JSONException e) { return new Response(status, new JSONObject()); }
                }
            } finally {
                conn.disconnect();
            }
        }
    }
}
