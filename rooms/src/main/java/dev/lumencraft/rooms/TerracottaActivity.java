// SPDX-License-Identifier: AGPL-3.0-only
package dev.lumencraft.rooms;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A standalone companion. Entry intents carry no credentials. Room codes are only copied on user request. */
public final class TerracottaActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status,versions;
    private EditText invite,port;
    private Button create,join,stop,copy;
    private String pending,code;
    private int selectedPort;
    private final Runnable tick=new Runnable(){public void run(){render();ui.postDelayed(this,500);}};
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        boolean dark=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        boolean wide=getResources().getConfiguration().screenWidthDp>=560;
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        content.setPadding(dp(wide?20:16),dp(10),dp(wide?20:16),dp(10));
        LinearLayout summary=column(),actions=column();
        if(wide){
            setContentView(content);
            ScrollView left=scroll(summary),right=scroll(actions);
            content.addView(left,new LinearLayout.LayoutParams(0,-1,0.87f));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-1,1.13f);
            rp.setMarginStart(dp(22));content.addView(right,rp);
        }else{
            content.addView(summary);content.addView(actions);setContentView(scroll(content));
        }
        TextView title=new TextView(this);title.setText("陶瓦联机");title.setTextSize(26);
        summary.addView(title);
        status=new TextView(this);status.setPadding(0,dp(8),0,dp(8));status.setTextSize(16);summary.addView(status);
        versions=new TextView(this);versions.setTextSize(13);summary.addView(versions);
        port=new EditText(this);port.setSingleLine(true);port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(5)});
        port.setHint("本机服务端端口（局域网世界可留空）");actions.addView(port);
        create=button(actions,"创建私人房间",()->prepare(RoomService.HOST));
        invite=new EditText(this);invite.setSingleLine(true);invite.setSaveEnabled(false);
        invite.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(512)});
        invite.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if(Build.VERSION.SDK_INT>=26)invite.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        invite.setHint("好友联机码");actions.addView(invite);
        join=button(actions,"加入房间",()->prepare(RoomService.JOIN));
        copy=button(actions,"复制联机码",this::copy);
        stop=button(actions,"结束联机",()->startService(new Intent(this,RoomService.class).setAction(RoomService.STOP)));
        button(wide?summary:actions,"开源许可",this::licenses);
    }
    private LinearLayout column(){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setFocusableInTouchMode(true);return panel;
    }
    private ScrollView scroll(View body){
        ScrollView panel=new ScrollView(this);panel.setFillViewport(true);
        panel.addView(body,new ScrollView.LayoutParams(-1,-2));return panel;
    }
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private Button button(LinearLayout parent,String label,Runnable op){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->op.run());parent.addView(b);return b;}
    private void prepare(String action){
        if(!RoomService.state.equals("waiting"))return;
        selectedPort=0;code=null;
        if(action.equals(RoomService.HOST)&&port.length()>0){
            try{selectedPort=Integer.parseInt(port.getText().toString());if(selectedPort<1||selectedPort>65535)throw new NumberFormatException();}
            catch(NumberFormatException ex){port.setError("端口范围 1 至 65535");return;}
        }
        if(action.equals(RoomService.JOIN)){
            code=invite.getText().toString().trim();if(code.length()<1||code.length()>512){invite.setError("请输入有效联机码");return;}
        }
        pending=action;
        try{Intent prompt=VpnService.prepare(this);if(prompt!=null)startActivityForResult(prompt,41);else startRoom();}
        catch(RuntimeException ex){pending=null;code=null;toast("系统无法提供 VPN 授权");}
    }
    private void startRoom(){
        if(pending==null)return;
        Intent request=new Intent(this,RoomService.class).setAction(pending);
        if(code!=null)request.putExtra("room",code);
        request.putExtra("port",selectedPort);pending=null;code=null;invite.getText().clear();
        if(Build.VERSION.SDK_INT>=26)startForegroundService(request);else startService(request);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==41){if(result==RESULT_OK)startRoom();else{pending=null;code=null;toast("联机需要系统 VPN 授权");}}
    }
    private void copy(){
        String value=RoomService.room.isEmpty()?RoomService.address:RoomService.room;if(value.isEmpty())return;
        ClipData clip=ClipData.newPlainText("私人房间",value);
        if(Build.VERSION.SDK_INT>=24){
            PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);
            clip.getDescription().setExtras(extras);
        }
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        toast(RoomService.room.isEmpty()?"已复制本机连接地址":"已复制私人联机码");
    }
    private void render(){
        String state=RoomService.state;boolean idle="waiting".equals(state);
        port.setVisibility(idle?View.VISIBLE:View.GONE);invite.setVisibility(idle?View.VISIBLE:View.GONE);
        join.setVisibility(idle?View.VISIBLE:View.GONE);create.setVisibility(idle?View.VISIBLE:View.GONE);
        copy.setVisibility((!RoomService.room.isEmpty()||!RoomService.address.isEmpty())?View.VISIBLE:View.GONE);
        copy.setText(RoomService.room.isEmpty()?"复制连接地址":"复制私人联机码");
        stop.setVisibility(idle?View.GONE:View.VISIBLE);stop.setEnabled(!"closing".equals(state));
        String text;
        switch(state){
            case "waiting":text=RoomService.error.isEmpty()?"创建房间，或输入好友分享的联机码。":RoomService.error;break;
            case "initializing":text="正在启动陶瓦核心";break;
            case "host-scanning":text="正在发现本机开放到局域网的 Minecraft 世界或指定服务端";break;
            case "host-starting":text="发现游戏，正在建立私人房间";break;
            case "host-ok":text="房间已就绪，可将联机码分享给好友";break;
            case "guest-connecting":case "guest-starting":text="正在通过 EasyTier 连接房主";break;
            case "guest-ok":text="已加入。游戏内连接 "+RoomService.address;break;
            case "closing":text="正在结束联机";break;
            default:text="陶瓦状态 "+state;
        }
        status.setText(text);versions.setText(RoomService.metadata);
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    private void licenses(){
        new AlertDialog.Builder(this).setTitle("开源许可").setItems(new String[]{"房间伴侣及 Terracotta · AGPLv3","EasyTier · LGPLv3"},(d,n)->{
            String path=n==0?"licenses/Terracotta-AGPL-3.0.txt":"licenses/EasyTier.txt";
            try(InputStream in=getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];for(int count;(count=in.read(b))!=-1;)out.write(b,0,count);
                new AlertDialog.Builder(this).setMessage(new String(out.toByteArray(),StandardCharsets.UTF_8)).setPositiveButton("关闭",null).show();
            }catch(IOException e){toast(e.getMessage());}
        }).show();
    }
    @Override protected void onResume(){super.onResume();ui.post(tick);}
    @Override protected void onPause(){ui.removeCallbacks(tick);super.onPause();}
}
