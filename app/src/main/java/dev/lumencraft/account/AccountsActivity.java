package dev.lumencraft.account;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.IOException;
import java.util.List;

/** Account operations are only triggered by explicit user actions in this private Activity. */
public final class AccountsActivity extends Activity {
    private static final int PICK_SKIN = 52;
    private AccountVault vault;
    private final MicrosoftLogin api=new MicrosoftLogin();
    private final YggdrasilLogin yggdrasil=new YggdrasilLogin();
    private String skinTargetKey;
    private Thread worker;
    private AlertDialog pending;
    private int generation;
    private boolean leaving;
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(dev.lumencraft.ui.Appearance.attach(base));
    }
    @Override public void onCreate(Bundle saved) {
        dev.lumencraft.ui.Appearance.apply(this);
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        vault=new AccountVault(this);
        render();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size) {
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(size);t.setPadding(0,dp(5),0,dp(5));
        return t;
    }
    private void addButton(LinearLayout parent,String title,View.OnClickListener action) {
        Button button=new Button(this);button.setText(title);button.setAllCaps(false);
        button.setOnClickListener(action);parent.addView(button);
    }
    private LinearLayout column() {
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }
    private ScrollView scroll(View content) {
        ScrollView scroll=new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }
    private void render() {
        Configuration config=getResources().getConfiguration();
        boolean wide=config.orientation==Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp>=440;
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        int side=dp(wide?16:20), vertical=dp(wide?8:16);
        page.setPadding(side,vertical,side,vertical);
        if (android.os.Build.VERSION.SDK_INT>=35) page.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|
                    android.view.WindowInsets.Type.displayCutout());
            view.setPadding(side+bars.left,vertical+bars.top,side+bars.right,vertical+bars.bottom);
            return insets;
        });
        LinearLayout actions=column(), profiles=column();
        TextView header=text("账户",wide?24:28);
        header.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        if (wide) {
            actions.addView(header);
            actions.setPadding(0,0,dp(14),0);
            int actionWidth=dp(config.screenWidthDp>=600?200:164);
            page.addView(scroll(actions),new LinearLayout.LayoutParams(actionWidth,-1));
            page.addView(scroll(profiles),new LinearLayout.LayoutParams(0,-1,1));
        } else {
            LinearLayout all=column();
            all.addView(header);all.addView(profiles);all.addView(actions);
            page.addView(scroll(all),new LinearLayout.LayoutParams(-1,-1));
        }
        setContentView(page);
        try {
            List<AccountVault.Profile> rows=vault.list();
            if (rows.isEmpty()) profiles.addView(text("本机尚未保存账户",16));
            for (AccountVault.Profile p:rows) {
                String badge = p.online ? "微软" : p.yggdrasil ? "第三方认证" : "离线";
                addButton(profiles,p.name+"  ·  "+badge+(p.selected?"  ·  当前":""),v->manage(p));
            }
            addButton(actions,"添加离线账户",v->offline());
            addButton(actions,"添加微软账户",v->microsoft());
            addButton(actions,"添加第三方认证（Yggdrasil）",v->yggdrasilDialog());
        } catch (Exception e) {
            profiles.addView(text("账户库无法解密。请先解锁设备，再重新读取账户。",16));
            addButton(actions,"重新读取",v->render());
        }
        addButton(actions,"返回",v->finish());
    }
    private void manage(AccountVault.Profile p) {
        java.util.ArrayList<String> actions=new java.util.ArrayList<>();
        actions.add("切换到此账户");
        if (p.online) actions.add("验证授权与游戏权益");
        if (p.yggdrasil) actions.add("刷新第三方认证令牌");
        actions.add("皮肤与披风信息");
        if (!p.online && !p.yggdrasil) actions.add("导入本地皮肤 PNG");
        actions.add("移除此账户");
        new AlertDialog.Builder(this).setTitle(p.name).setItems(actions.toArray(new String[0]),(dialog,i)->{
            String chosen=actions.get(i);
            if ("切换到此账户".equals(chosen)) local(()->vault.select(p.key));
            else if ("验证授权与游戏权益".equals(chosen)) refresh(p);
            else if ("刷新第三方认证令牌".equals(chosen)) refreshYggdrasil(p);
            else if ("皮肤与披风信息".equals(chosen)) showTextures(p);
            else if ("导入本地皮肤 PNG".equals(chosen)) pickOfflineSkin(p);
            else new AlertDialog.Builder(this).setTitle("从本机移除 "+p.name+"？")
                        .setMessage(p.online || p.yggdrasil?"此操作清除本机保存的刷新凭据，可稍后重新登录。":"可用同一名称重新创建此本地身份。")
                        .setNegativeButton("取消",null).setPositiveButton("移除",(d,n)->local(()->vault.remove(p.key))).show();
        }).show();
    }
    private void showTextures(AccountVault.Profile p) {
        StringBuilder sb=new StringBuilder();
        sb.append("UUID: ").append(p.id).append('\n');
        sb.append("类型: ").append(p.online ? "Microsoft 正版" : p.yggdrasil ? "Yggdrasil 第三方认证" : "离线").append('\n');
        if (!p.apiUrl.isEmpty()) sb.append("认证地址: ").append(p.apiUrl).append('\n');
        sb.append("皮肤: ").append(p.skinUrl.isEmpty() ? "默认皮肤" : p.skinUrl).append('\n');
        sb.append("披风: ").append(p.capeUrl.isEmpty() ? "无" : p.capeUrl);
        new AlertDialog.Builder(this).setTitle(p.name + " · 外观档案").setMessage(sb.toString())
                .setPositiveButton("关闭",null).show();
    }
    private void pickOfflineSkin(AccountVault.Profile p) {
        skinTargetKey=p.key;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png");
        try { startActivityForResult(intent,PICK_SKIN); }
        catch (ActivityNotFoundException e) { Toast.makeText(this,"系统缺少文件选择器",Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onActivityResult(int req,int res,Intent data) {
        super.onActivityResult(req,res,data);
        if (req!=PICK_SKIN || res!=RESULT_OK || data==null || data.getData()==null || skinTargetKey==null) return;
        String key=skinTargetKey; skinTargetKey=null;
        local(()->{
            java.io.File skins=new java.io.File(getFilesDir(),"skins");
            if (!skins.isDirectory() && !skins.mkdirs()) throw new AccountProblem("无法创建皮肤目录");
            java.io.File dst=new java.io.File(skins,key+".png");
            try (java.io.InputStream in=getContentResolver().openInputStream(data.getData());
                 java.io.OutputStream out=new java.io.FileOutputStream(dst)) {
                if (in==null) throw new AccountProblem("无法读取皮肤文件");
                byte[] buf=new byte[8192]; int total=0,n;
                while ((n=in.read(buf))!=-1) {
                    total+=n; if (total>2*1024*1024) throw new AccountProblem("皮肤 PNG 过大（上限 2 MiB）");
                    out.write(buf,0,n);
                }
                if (total<32) throw new AccountProblem("皮肤文件不完整");
            }
            vault.setCustomSkin(key,dst.getAbsolutePath());
        });
    }
    private void yggdrasilDialog() {
        EditText apiField=field("认证服务器 HTTPS 地址（如 https://littleskin.cn/api/yggdrasil）","");
        EditText userField=field("邮箱或用户名","");
        EditText passField=field("密码","");
        passField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        showInput(new AlertDialog.Builder(this).setTitle("第三方认证（authlib-injector）")
                .setView(dialogBody(text("支持合法的 Yggdrasil 第三方认证服务，凭据仅使用本机 Keystore 加密存储。",15),apiField,userField,passField))
                .setNegativeButton("取消",null).setPositiveButton("登录",(d,w)->{
                    String url=apiField.getText().toString().trim();
                    String user=userField.getText().toString().trim();
                    String pass=passField.getText().toString();
                    start("第三方认证登录",token->{
                        YggdrasilLogin.AuthResult auth=yggdrasil.authenticate(url,user,pass);
                        ui(token,()->{ finishWork(); local(()->vault.storeYggdrasil(auth)); });
                    });
                }));
    }
    private void refreshYggdrasil(AccountVault.Profile p) {
        start("刷新 "+p.name,token->{
            AccountVault.Credential old=vault.credential(p.key);
            YggdrasilLogin.AuthResult auth=yggdrasil.refresh(old.apiUrl,old.accessToken,old.clientToken,p.id,p.name);
            ui(token,()->{
                finishWork();
                local(()->vault.storeYggdrasil(auth));
                Toast.makeText(this,"第三方认证令牌与外观已更新",Toast.LENGTH_SHORT).show();
            });
        });
    }
    private interface Work { void run() throws Exception; }
    private void local(Work work) {
        try { work.run();render(); } catch (Exception e) { error(e); }
    }
    private EditText field(String hint,String value) {
        EditText e=new EditText(this);e.setSingleLine();e.setHint(hint);e.setText(value);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setSaveEnabled(false);return e;
    }
    private ScrollView dialogBody(View... elements) {
        LinearLayout content=column();
        content.setPadding(dp(20),0,dp(20),dp(4));
        for (View element:elements) content.addView(element);
        return scroll(content);
    }
    private void showInput(AlertDialog.Builder builder) {
        AlertDialog dialog=builder.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }
    private void offline() {
        EditText name=field("玩家名称","");
        showInput(new AlertDialog.Builder(this).setTitle("离线玩家")
                .setView(dialogBody(text("使用本机身份游玩单机世界。",16),name))
                .setNegativeButton("取消",null).setPositiveButton("创建",(d,w)->
                        local(()->vault.addOffline(name.getText().toString().trim()))));
    }
    private void microsoft() {
        SharedPreferences prefs=getSharedPreferences("account_setup",MODE_PRIVATE);
        EditText id=field("应用程序客户端 ID",prefs.getString("client_id",""));
        showInput(new AlertDialog.Builder(this).setTitle("微软正版登录")
                .setView(dialogBody(text("请输入你已获 Minecraft Java 服务授权的公共客户端应用 ID。登录将在微软官网完成。",16),id))
                .setNegativeButton("取消",null).setPositiveButton("获取设备代码",(d,w)->{
                    try {
                        String client=MicrosoftLogin.clientId(id.getText().toString().trim());
                        prefs.edit().putString("client_id",client).apply();login(client);
                    } catch (IOException e) { error(e); }
                }));
    }
    private interface Operation { void run(int token) throws Exception; }
    private void start(String title,Operation op) {
        if (worker!=null) return;
        int token=++generation;
        pending=new AlertDialog.Builder(this).setTitle(title).setMessage("正在连接身份服务…")
                .setNegativeButton("取消",(d,w)->cancel()).setOnCancelListener(d->cancel()).show();
        worker=new Thread(()->{
            try { op.run(token); }
            catch (Exception e) { ui(token,()->{ finishWork();error(e); }); }
        },"account-auth");
        worker.start();
    }
    private void login(String client) {
        start("登录微软账户",token->{
            MicrosoftLogin.Device code=api.begin(client);
            ui(token,()->{
                if (pending!=null) pending.dismiss();
                TextView userCode=text(code.userCode,24);
                userCode.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
                userCode.setTextIsSelectable(true);
                pending=new AlertDialog.Builder(this).setTitle("微软设备代码")
                        .setView(dialogBody(userCode,text("在 "+code.page+" 输入此代码，完成授权后返回。",16)))
                        .setPositiveButton("打开微软官网",null).setNegativeButton("取消",(d,w)->cancel())
                        .setOnCancelListener(d->cancel()).create();
                pending.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                pending.show();
                pending.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                    try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(code.page))); }
                    catch (ActivityNotFoundException e) { Toast.makeText(this,"可在另一台设备打开上方网址",Toast.LENGTH_LONG).show(); }
                });
            });
            MicrosoftLogin.OAuth oauth=api.waitForUser(client,code);
            ui(token,()->{
                if (pending!=null) pending.dismiss();
                pending=new AlertDialog.Builder(this).setTitle("验证 Xbox 与 Minecraft 档案")
                        .setMessage("正在检查 Java 版游戏权益…").setNegativeButton("取消",(d,w)->cancel())
                        .setOnCancelListener(d->cancel()).show();
            });
            MicrosoftLogin.Session session=api.minecraft(oauth);
            ui(token,()->{
                finishWork();
                local(()->vault.store(oauth,session));
            });
        });
    }
    private void refresh(AccountVault.Profile p) {
        start("验证 "+p.name,token->{
            AccountVault.Credential old=vault.credential(p.key);
            MicrosoftLogin.OAuth oauth=api.renew(old.client,old.refresh);
            // Persist rotated credentials before downstream services can fail.
            vault.rotate(p.key,old.refresh,oauth.refresh);
            MicrosoftLogin.Session session=api.minecraft(oauth);
            ui(token,()->{
                finishWork();
                try {
                    vault.updateProfile(p.key,oauth,session);render();
                    new AlertDialog.Builder(this).setTitle("Java 版权益已验证").setMessage(session.name)
                            .setPositiveButton("完成",null).show();
                } catch (Exception e) { error(e); }
            });
        });
    }
    private void ui(int token,Runnable work) {
        runOnUiThread(()->{if (!leaving && token==generation && !isFinishing()) work.run();});
    }
    private void finishWork() {
        if (pending!=null) pending.dismiss();
        pending=null;worker=null;
    }
    private void cancel() {
        generation++;
        if (worker!=null) worker.interrupt();
        finishWork();
    }
    private void error(Exception e) {
        // Only messages produced by this module are eligible for display; JSON/crypto stack traces can include secrets.
        String message=e instanceof AccountProblem?e.getMessage():"请检查网络连接、应用注册信息与设备解锁状态。";
        new AlertDialog.Builder(this).setTitle("账户操作未完成").setMessage(message).setPositiveButton("关闭",null).show();
    }
    @Override protected void onDestroy() { leaving=true;cancel();super.onDestroy(); }
}
