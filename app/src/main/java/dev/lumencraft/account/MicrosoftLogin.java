package dev.lumencraft.account;

import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Consumer OAuth and Minecraft Java entitlement checks. Secrets never appear in public exceptions. */
final class MicrosoftLogin {
    static final String OAUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
    private static final String XBL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String GAME = "https://api.minecraftservices.com";
    private static final String SCOPE = "XboxLive.signin offline_access";

    interface Transport { Response send(String url, String contentType, String body, String bearer) throws IOException; }
    static final class Response {
        final int status;
        final JSONObject body;
        Response(int status, JSONObject body) { this.status=status; this.body=body; }
        JSONObject success(String service) throws IOException {
            if (status < 200 || status >= 300) throw new AccountProblem(service + " 返回 HTTP " + status);
            return body;
        }
    }
    static final class Device {
        final String code, userCode, page;
        final long expires;
        final int interval;
        Device(JSONObject body) throws IOException {
            code=required(body,"device_code");
            userCode=required(body,"user_code");
            page=required(body,"verification_uri");
            try {
                URI uri = new URI(page);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getPort()!=-1 ||
                    !(host.equals("microsoft.com") || host.equals("www.microsoft.com") ||
                      host.equals("login.microsoftonline.com") || host.equals("microsoft.com.cn")))
                    throw new AccountProblem("微软返回的授权页地址无效");
            } catch (URISyntaxException e) { throw new AccountProblem("微软返回的授权页地址无效"); }
            int duration = body.optInt("expires_in",0);
            if (duration < 1 || duration > 3600 || userCode.length() > 64) throw new AccountProblem("设备代码格式无效");
            expires=System.nanoTime()+duration*1_000_000_000L;
            interval=Math.max(1,Math.min(60,body.optInt("interval",5)));
        }
    }
    static final class OAuth {
        final String client, access, refresh;
        OAuth(String client, JSONObject json, String oldRefresh) throws IOException {
            this.client=client;
            access=required(json,"access_token");
            refresh=json.optString("refresh_token",oldRefresh);
            if (refresh==null || refresh.isEmpty()) throw new AccountProblem("授权未返回刷新令牌，请重新授权 offline_access");
        }
    }
    static final class Session {
        final String id,name,access;
        Session(String id,String name,String token) { this.id=id; this.name=name; access=token; }
    }
    private final Transport http;
    MicrosoftLogin() { this(MicrosoftLogin::connect); }
    MicrosoftLogin(Transport http) { this.http=http; }

    static String clientId(String value) throws IOException {
        if (value==null || !value.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new AccountProblem("请输入已获批的应用程序客户端 ID（UUID）");
        return value.toLowerCase(Locale.ROOT);
    }
    Device begin(String client) throws Exception {
        return new Device(form("devicecode","client_id",clientId(client),"scope",SCOPE).success("微软设备代码服务"));
    }
    OAuth waitForUser(String client,Device device) throws Exception {
        int seconds=device.interval;
        while (device.expires-System.nanoTime()>0) {
            Thread.sleep(seconds*1000L);
            if (device.expires-System.nanoTime()<=0) break;
            Response result=form("token","client_id",client,"device_code",device.code,
                    "grant_type","urn:ietf:params:oauth:grant-type:device_code");
            if (result.status==200) return new OAuth(client,result.body,null);
            String reason=result.body.optString("error");
            if ("authorization_pending".equals(reason)) continue;
            if ("slow_down".equals(reason) || result.status==429) { seconds=Math.min(seconds+5,120); continue; }
            if ("authorization_declined".equals(reason) || "access_denied".equals(reason))
                throw new AccountProblem("已拒绝微软授权");
            if ("expired_token".equals(reason)) break;
            throw new AccountProblem("微软未完成授权，请检查应用注册和公共客户端设置（HTTP " + result.status + "）");
        }
        throw new AccountProblem("设备代码已过期，请重新登录");
    }
    OAuth renew(String client,String refresh) throws Exception {
        Response response=form("token","client_id",clientId(client),"grant_type","refresh_token","refresh_token",refresh,"scope",SCOPE);
        if ("invalid_grant".equals(response.body.optString("error"))) throw new AccountProblem("微软授权已失效，请重新登录");
        return new OAuth(client,response.success("微软授权服务"),refresh);
    }
    Session minecraft(OAuth oauth) throws Exception {
        JSONObject props=new JSONObject().put("AuthMethod","RPS").put("SiteName","user.auth.xboxlive.com").put("RpsTicket","d="+oauth.access);
        JSONObject user=post(XBL,tokenRequest(props,"http://auth.xboxlive.com")).success("Xbox 用户认证");
        JSONObject sandbox=new JSONObject().put("SandboxId","RETAIL").put("UserTokens",new JSONArray().put(required(user,"Token")));
        Response xsts=post(XSTS,tokenRequest(sandbox,"rp://api.minecraftservices.com/"));
        if (xsts.status==401) {
            long reason=xsts.body.optLong("XErr");
            if (reason==2148916233L) throw new AccountProblem("请先在 Xbox 官方网站创建玩家档案");
            if (reason==2148916238L) throw new AccountProblem("Xbox 家庭设置限制了此账户的授权");
            throw new AccountProblem("Xbox 安全令牌服务拒绝认证");
        }
        JSONObject security=xsts.success("Xbox 安全令牌服务");
        JSONObject claims=security.optJSONObject("DisplayClaims");
        JSONArray users=claims==null?null:claims.optJSONArray("xui");
        if (users==null || users.length()!=1 || users.optJSONObject(0)==null) throw new AccountProblem("Xbox 身份声明无效");
        String identity="XBL3.0 x="+required(users.optJSONObject(0),"uhs")+";"+required(security,"Token");
        JSONObject login=post(GAME+"/authentication/login_with_xbox",new JSONObject().put("identityToken",identity)).success("Minecraft 登录服务");
        String bearer=required(login,"access_token");
        JSONArray items=http.send(GAME+"/entitlements/mcstore",null,null,bearer).success("Minecraft 游戏权益服务").optJSONArray("items");
        boolean licensed=false;
        if (items!=null) for (int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);
            String name=item==null?"":item.optString("name");
            if ("game_minecraft".equals(name) || "product_minecraft".equals(name)) licensed=true;
        }
        if (!licensed) throw new AccountProblem("服务未返回可验证的 Java 版游戏权益");
        JSONObject profile=http.send(GAME+"/minecraft/profile",null,null,bearer).success("Minecraft 玩家档案");
        String id=required(profile,"id"), name=required(profile,"name");
        if (!id.matches("(?i)[0-9a-f]{32}") || !name.matches("[a-zA-Z0-9_]{1,16}"))
            throw new AccountProblem("Minecraft 返回的玩家档案格式无效");
        return new Session(id.toLowerCase(Locale.ROOT),name,bearer);
    }
    private static JSONObject tokenRequest(JSONObject properties,String relyingParty) throws JSONException {
        return new JSONObject().put("Properties",properties).put("RelyingParty",relyingParty).put("TokenType","JWT");
    }
    private Response post(String url,JSONObject json) throws IOException {
        return http.send(url,"application/json",json.toString(),null);
    }
    private Response form(String route,String... params) throws IOException {
        StringBuilder body=new StringBuilder();
        for (int i=0;i<params.length;i+=2) {
            if (i>0) body.append('&');
            body.append(URLEncoder.encode(params[i],"UTF-8")).append('=').append(URLEncoder.encode(params[i+1],"UTF-8"));
        }
        return http.send(OAUTH+route,"application/x-www-form-urlencoded",body.toString(),null);
    }
    private static String required(JSONObject object,String name) throws IOException {
        Object value=object.opt(name);
        if (!(value instanceof String) || ((String)value).isEmpty() || ((String)value).length()>131072)
            throw new AccountProblem("身份服务返回的数据不完整");
        return (String)value;
    }
    private static Response connect(String address,String contentType,String body,String bearer) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("登录已取消");
        HttpsURLConnection conn=(HttpsURLConnection)new URL(address).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        conn.setConnectTimeout(12000); conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("Cache-Control","no-store");
        conn.setRequestProperty("User-Agent","LumenCraftAndroid/Alpha1.0.0");
        if (address.equals(XBL) || address.equals(XSTS)) conn.setRequestProperty("x-xbl-contract-version","1");
        try {
            if (bearer!=null) conn.setRequestProperty("Authorization","Bearer "+bearer);
            if (body!=null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type",contentType);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out=conn.getOutputStream()) { out.write(bytes); }
            }
            int status=conn.getResponseCode();
            try (InputStream in=status>=400?conn.getErrorStream():conn.getInputStream();
                 ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                if (in!=null) {
                    byte[] block=new byte[8192];
                    for (int n;(n=in.read(block))>=0;) {
                        if (out.size()+n>1024*1024) throw new AccountProblem("身份服务响应超过限制");
                        out.write(block,0,n);
                    }
                }
                try { return new Response(status,new JSONObject(out.toString("UTF-8"))); }
                catch (JSONException e) {
                    if (status>=300) return new Response(status,new JSONObject());
                    throw new AccountProblem("身份服务响应格式无效");
                }
            }
        } finally { conn.disconnect(); }
    }
}
