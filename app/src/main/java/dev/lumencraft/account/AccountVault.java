package dev.lumencraft.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;

/** A single authenticated snapshot holds the selected identity and its accompanying refresh credentials. */
final class AccountVault {
    private static final Object LOCK = new Object();
    private static final String ALIAS="dev.lumencraft.account.v1", FIELD="encrypted_v1";
    private static final byte[] AAD=ALIAS.getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;
    AccountVault(Context c) { prefs=c.getSharedPreferences("accounts",Context.MODE_PRIVATE); }

    static final class Profile {
        final String key,name,id;
        final boolean online,selected;
        Profile(String key,JSONObject p,String current) throws JSONException {
            this.key=key; name=p.getString("name"); id=p.getString("id");
            online="microsoft".equals(p.getString("type"));
            selected=key.equals(current);
        }
    }
    static final class Credential {
        final String client,refresh;
        Credential(JSONObject p) throws JSONException { client=p.getString("client");refresh=p.getString("refresh"); }
    }
    private static SecretKey key(boolean create) throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        SecretKey exists=(SecretKey)keys.getKey(ALIAS,null);
        if (exists!=null) return exists;
        if (!create) throw new AccountProblem("设备密钥已不可用");
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    private JSONObject read() throws Exception {
        String stored=prefs.getString(FIELD,null);
        if (stored==null) return new JSONObject().put("profiles",new JSONObject()).put("current","");
        byte[] blob=Base64.decode(stored,Base64.NO_WRAP);
        if (blob.length<28) throw new AccountProblem("账户存储损坏");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,blob,0,12));
        cipher.updateAAD(AAD);
        byte[] plain=cipher.doFinal(blob,12,blob.length-12);
        try { return new JSONObject(new String(plain,StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plain,(byte)0); }
    }
    private void write(JSONObject state) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key(true));
        cipher.updateAAD(AAD);
        byte[] iv=cipher.getIV(), plaintext=state.toString().getBytes(StandardCharsets.UTF_8);
        byte[] sealed;
        try { sealed=cipher.doFinal(plaintext); }
        finally { Arrays.fill(plaintext,(byte)0); }
        if (iv.length!=12) throw new AccountProblem("设备密钥服务返回的 IV 长度无效");
        byte[] blob=Arrays.copyOf(iv,iv.length+sealed.length);
        System.arraycopy(sealed,0,blob,iv.length,sealed.length);
        if (!prefs.edit().putString(FIELD,Base64.encodeToString(blob,Base64.NO_WRAP)).commit())
            throw new AccountProblem("无法保存账户更改");
    }
    List<Profile> list() throws Exception {
        synchronized (LOCK) {
            JSONObject state=read(), profiles=state.getJSONObject("profiles");
            List<Profile> result=new ArrayList<>();
            for (Iterator<?> i=profiles.keys();i.hasNext();) {
                String key=(String)i.next();
                result.add(new Profile(key,profiles.getJSONObject(key),state.optString("current")));
            }
            Collections.sort(result,(a,b)->a.selected==b.selected?a.name.compareToIgnoreCase(b.name):a.selected?-1:1);
            return result;
        }
    }
    void addOffline(String name) throws Exception {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) throw new AccountProblem("名称需为 3 至 16 个英文字母、数字或下划线");
        String id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
        synchronized (LOCK) {
            JSONObject state=read(), profiles=state.getJSONObject("profiles");
            String key="local-"+id;
            if (profiles.has(key)) throw new AccountProblem("此离线账户已存在");
            profiles.put(key,new JSONObject().put("type","offline").put("name",name).put("id",id));
            write(state.put("current",key));
        }
    }
    void store(MicrosoftLogin.OAuth oauth,MicrosoftLogin.Session game) throws Exception {
        synchronized (LOCK) {
            JSONObject state=read();
            String key="msa-"+game.id;
            state.getJSONObject("profiles").put(key,new JSONObject().put("type","microsoft").put("name",game.name)
                    .put("id",game.id).put("client",oauth.client).put("refresh",oauth.refresh));
            write(state.put("current",key));
        }
    }
    Credential credential(String key) throws Exception {
        synchronized (LOCK) { return new Credential(read().getJSONObject("profiles").getJSONObject(key)); }
    }
    void rotate(String key,String old,String updated) throws Exception {
        if (old.equals(updated)) return;
        synchronized (LOCK) {
            JSONObject state=read(), p=state.getJSONObject("profiles").optJSONObject(key);
            if (p==null || !old.equals(p.optString("refresh"))) throw new AccountProblem("账户已移除或重新登录");
            p.put("refresh",updated);
            write(state);
        }
    }
    void updateProfile(String key,MicrosoftLogin.OAuth oauth,MicrosoftLogin.Session game) throws Exception {
        synchronized (LOCK) {
            JSONObject state=read(), p=state.getJSONObject("profiles").optJSONObject(key);
            if (p==null || !oauth.refresh.equals(p.optString("refresh")) || !game.id.equals(p.getString("id")))
                throw new AccountProblem("刷新后的账户身份不一致");
            p.put("name",game.name);
            write(state);
        }
    }
    void select(String key) throws Exception {
        synchronized (LOCK) {
            JSONObject state=read();
            if (!state.getJSONObject("profiles").has(key)) throw new AccountProblem("账户已移除");
            write(state.put("current",key));
        }
    }
    void remove(String key) throws Exception {
        synchronized (LOCK) {
            JSONObject state=read(), profiles=state.getJSONObject("profiles");
            profiles.remove(key);
            if (key.equals(state.optString("current"))) {
                Iterator<?> remaining=profiles.keys();
                state.put("current",remaining.hasNext()?remaining.next():"");
            }
            write(state);
        }
    }
}
