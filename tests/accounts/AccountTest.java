package dev.lumencraft.account;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed under a separate test-only Android UID. No request goes to a remote account service. */
public class AccountTest extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    static void ok(boolean test,String name) { if (!test) throw new AssertionError(name); }
    interface Action { void run() throws Exception; }
    static void rejected(Action op,String label) throws Exception {
        try { op.run(); } catch (Exception expected) { return; }
        throw new AssertionError(label);
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context c=getTargetContext();AccountVault vault=new AccountVault(c);
            SharedPreferences prefs=c.getSharedPreferences("accounts",Context.MODE_PRIVATE);
            prefs.edit().clear().commit();
            vault.addOffline("TestPlayer");
            vault.addOffline("OtherPlayer");
            List<AccountVault.Profile> rows=vault.list();
            ok(rows.size()==2 && rows.get(0).selected && rows.get(0).name.equals("OtherPlayer"),"offline selection");
            String first=rows.get(1).key;
            ok(first.equals("local-"+UUID.nameUUIDFromBytes("OfflinePlayer:TestPlayer".getBytes("UTF-8")).toString().replace("-","")),"offline UUID");
            vault.select(first);
            ok(new AccountVault(c).list().get(0).key.equals(first),"selection saved");
            rejected(()->vault.addOffline("TestPlayer"),"duplicate accepted");
            rejected(()->vault.addOffline("bad player"),"invalid name");
            String client="d360aa15-979d-47c0-a854-b700d83f33d3";
            AtomicInteger polls=new AtomicInteger();
            List<String> calls=new ArrayList<>();
            MicrosoftLogin.Transport fake=(url,type,body,bearer)->{
                try {
                    calls.add(url);
                    JSONObject answer=new JSONObject();
                    if (url.endsWith("/devicecode")) {
                        ok(body.contains("scope=XboxLive.signin+offline_access"),"requested scope");
                        answer.put("device_code","fixture-device-only").put("user_code","TEST_CODE")
                              .put("verification_uri","https://microsoft.com/devicelogin").put("expires_in",45).put("interval",1);
                    } else if (url.endsWith("/token")) {
                        if (body.contains("fixture-device-only") && polls.getAndIncrement()==0)
                            return new MicrosoftLogin.Response(400,new JSONObject().put("error","authorization_pending"));
                        answer.put("access_token","fixture-msa").put("refresh_token","fixture-rotating-refresh");
                    } else if (url.contains("user.auth.xboxlive.com")) {
                        ok(new JSONObject(body).getJSONObject("Properties").getString("RpsTicket").equals("d=fixture-msa"),"RPS");
                        answer.put("Token","fixture-user");
                    } else if (url.contains("xsts.auth.xboxlive.com")) {
                        ok(new JSONObject(body).getString("RelyingParty").equals("rp://api.minecraftservices.com/"),"XSTS audience");
                        answer.put("Token","fixture-xsts").put("DisplayClaims",new JSONObject().put("xui",
                                new JSONArray().put(new JSONObject().put("uhs","fixture-hash"))));
                    } else if (url.endsWith("/authentication/login_with_xbox")) {
                        ok(new JSONObject(body).getString("identityToken").equals("XBL3.0 x=fixture-hash;fixture-xsts"),"Xbox identity");
                        answer.put("access_token","fixture-game");
                    } else if (url.endsWith("/entitlements/mcstore")) {
                        ok("fixture-game".equals(bearer),"entitlement bearer");
                        answer.put("items",new JSONArray().put(new JSONObject().put("name","product_minecraft")));
                    } else if (url.endsWith("/minecraft/profile")) {
                        ok("fixture-game".equals(bearer),"profile bearer");
                        answer.put("id","1234567890abcdef1234567890abcdef").put("name","TestOnline");
                    } else throw new AssertionError("unexpected URL");
                    return new MicrosoftLogin.Response(200,answer);
                } catch (JSONException e) { throw new IOException("bad fixture"); }
            };
            MicrosoftLogin api=new MicrosoftLogin(fake);
            MicrosoftLogin.OAuth oauth=api.waitForUser(client,api.begin(client));
            MicrosoftLogin.Session session=api.minecraft(oauth);
            ok(polls.get()==2 && calls.size()==8 && session.name.equals("TestOnline"),"OAuth chain");
            vault.store(oauth,session);
            String online="msa-"+session.id;
            ok(vault.list().get(0).online && vault.list().get(0).key.equals(online),"separate Microsoft ID");
            String ciphertext=prefs.getString("encrypted_v1",null);
            byte[] bytes=Base64.decode(ciphertext,Base64.NO_WRAP);
            String asText=new String(bytes,"ISO-8859-1");
            ok(!asText.contains("fixture-rotating-refresh")&&!asText.contains("TestOnline")&&!asText.contains("TestPlayer"),"ciphertext privacy");
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            ok(keys.getKey("dev.lumencraft.account.v1",null).getEncoded()==null,"non-exportable key");
            ok(vault.credential(online).refresh.equals(oauth.refresh),"secret round-trip");
            vault.rotate(online,oauth.refresh,"fixture-rotated");
            ok(vault.credential(online).refresh.equals("fixture-rotated"),"rotation");
            rejected(()->vault.rotate(online,oauth.refresh,"fixture-stale"),"stale overwrite");
            api.renew(client,"fixture-rotated");
            MicrosoftLogin missing=new MicrosoftLogin((url,type,body,bearer)->
                    url.endsWith("/entitlements/mcstore")?new MicrosoftLogin.Response(200,new JSONObject()):fake.send(url,type,body,bearer));
            rejected(()->missing.minecraft(oauth),"missing entitlement");
            rejected(()->new MicrosoftLogin.Device(new JSONObject().put("device_code","x").put("user_code","y")
                    .put("verification_uri","https://example.org/").put("expires_in",40)),"untrusted verification URI");
            rejected(()->MicrosoftLogin.clientId("not-a-client"),"invalid app id");
            vault.remove(online);ok(vault.list().size()==2,"remove");
            ok(vault.list().get(0).selected,"fallback selection");
            byte[] damaged=Base64.decode(prefs.getString("encrypted_v1",""),Base64.NO_WRAP);
            damaged[damaged.length-1]^=1;
            prefs.edit().putString("encrypted_v1",Base64.encodeToString(damaged,Base64.NO_WRAP)).commit();
            rejected(vault::list,"tamper acceptance");
            result.putString("result","PASS offline UUID, switch/reopen/remove, non-exportable Keystore AES/GCM, token rotation, ciphertext tamper rejection, Microsoft/Xbox/Minecraft protocol fixtures and rejection paths");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable e) {
            result.putString("result","FAIL "+e.getClass().getSimpleName()+" "+e.getMessage());
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
