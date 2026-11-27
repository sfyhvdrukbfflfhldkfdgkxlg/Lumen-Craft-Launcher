// SPDX-License-Identifier: AGPL-3.0-only
package dev.lumencraft.rooms;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import dev.lumencraft.ui.Appearance;
import dev.lumencraft.core.RoomInvite;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Handles private invitations inside the launcher. Credentials stay in memory across rotations. */
public final class TerracottaActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status,versions;
    private EditText invite,port;
    private Button create,join,stop,copy,share,cancel;
    private RoomTasks tasks;
    private final Runnable tick=new Runnable(){public void run(){render();ui.postDelayed(this,500);}};
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(Appearance.attach(base));}
    @Override protected void onCreate(Bundle saved){
        Appearance.apply(this);
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        tasks=(RoomTasks)getLastNonConfigurationInstance();
        if(tasks==null)tasks=new RoomTasks();
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
        if (Build.VERSION.SDK_INT >= 35) {
            View root = findViewById(android.R.id.content);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
        TextView title=new TextView(this);title.setText("陶瓦联机");title.setTextSize(26);
        summary.addView(title);
        status=new TextView(this);status.setPadding(0,dp(8),0,dp(8));status.setTextSize(16);summary.addView(status);
        versions=new TextView(this);versions.setTextSize(13);summary.addView(versions);
        port=new EditText(this);port.setSingleLine(true);port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(5)});
        port.setHint("自动发现，或输入本机服务端端口");port.setText(tasks.port);actions.addView(port);
        create=button(actions,"创建私人房间",()->prepare(RoomService.HOST));
        invite=new EditText(this);invite.setSingleLine(true);invite.setSaveEnabled(false);
        invite.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(RoomInvite.MAX_LENGTH)});
        invite.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if(Build.VERSION.SDK_INT>=26)invite.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        invite.setHint("粘贴好友联机码或含模组的邀请");invite.setText(tasks.typed);actions.addView(invite);
        join=button(actions,"加入房间",()->prepare(RoomService.JOIN));
        share=button(actions,"分享房间邀请",this::shareOptions);
        copy=button(actions,"复制联机码",this::copy);
        cancel=button(actions,"取消当前任务",()->{tasks.cancel();render();});
        stop=button(actions,"结束联机",()->startService(new Intent(this,RoomService.class).setAction(RoomService.STOP)));
        button(wide?summary:actions,"开源许可",this::licenses);
        if(Build.VERSION.SDK_INT>=35)content.setOnApplyWindowInsetsListener((v,in)->{
            android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            int side=dp(wide?20:16);
            v.setPadding(side+bars.left,dp(10)+bars.top,side+bars.right,dp(10)+bars.bottom);return in;
        });
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
        if(!RoomService.state.equals("waiting")||tasks.busy()||tasks.pendingAction!=null)return;
        tasks.selectedPort=0;tasks.pendingCode=null;
        if(action.equals(RoomService.HOST)&&port.length()>0){
            try{tasks.selectedPort=Integer.parseInt(port.getText().toString());if(tasks.selectedPort<1||tasks.selectedPort>65535)throw new NumberFormatException();}
            catch(NumberFormatException ex){port.setError("端口范围 1 至 65535");return;}
        }
        if(action.equals(RoomService.JOIN)){
            try {
                RoomInvite invitation=RoomInvite.parse(invite.getText().toString());
                if(invitation.hasEnvironment()) {
                    new AlertDialog.Builder(this).setTitle("准备房间环境")
                        .setMessage("Minecraft "+invitation.game+" · "+invitation.loader+"\n房主分享了 "+invitation.modCount()+" 个模组。加入时会下载并校验所需文件及前置依赖。")
                        .setNegativeButton("取消",null).setPositiveButton("安装并加入",(d,w)->verifyAndPrepare(invitation)).show();
                } else verifyAndPrepare(invitation);
            } catch(Exception e) {invite.setError("邀请格式无效");}
            return;
        }
        requestVpn(action);
    }
    private void verifyAndPrepare(RoomInvite invitation){
        final File library=new File(getFilesDir(),"library");
        final RoomTasks owner=tasks;
        owner.begin("join","正在验证联机码",()->{
            if(NativeRooms.verifyRoomCode0(invitation.roomCode)<0)throw new IOException("无效或不支持的陶瓦联机码");
            if(invitation.hasEnvironment())invitation.install(library,(label,done,total)->owner.progress=label+"  "+done+" / "+total);
            return invitation.roomCode;
        });
        render();
    }
    private void requestVpn(String action){
        tasks.pendingAction=action;
        try{Intent prompt=VpnService.prepare(this);if(prompt!=null)startActivityForResult(prompt,41);else startRoom();}
        catch(RuntimeException ex){tasks.pendingAction=null;tasks.pendingCode=null;toast("系统无法提供 VPN 授权");}
    }
    private void startRoom(){
        if(tasks.pendingAction==null)return;
        Intent request=new Intent(this,RoomService.class).setAction(tasks.pendingAction);
        if(tasks.pendingCode!=null)request.putExtra("room",tasks.pendingCode);
        request.putExtra("port",tasks.selectedPort);tasks.pendingAction=null;tasks.pendingCode=null;tasks.typed="";invite.getText().clear();
        if(Build.VERSION.SDK_INT>=26)startForegroundService(request);else startService(request);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==41){if(result==RESULT_OK)startRoom();else{tasks.pendingAction=null;tasks.pendingCode=null;toast("联机需要系统 VPN 授权");}}
    }
    private void shareOptions(){
        String room=RoomService.room;
        if(room.isEmpty()||tasks.busy())return;
        File instances=new File(getFilesDir(),"library/instances");
        File[] dirs=instances.listFiles(f->!f.getName().startsWith("pack-staging-")&&new File(f,"install.json").isFile());
        if(dirs==null||dirs.length==0){sendInvitation(room);return;}
        Arrays.sort(dirs);
        String[] labels=new String[dirs.length+1];labels[0]="仅分享联机码";
        for(int i=0;i<dirs.length;i++)labels[i+1]=getSharedPreferences("library",MODE_PRIVATE)
            .getString("title/"+dirs[i].getAbsolutePath(),dirs[i].getName());
        new AlertDialog.Builder(this).setTitle("附上客户端实例").setItems(labels,(d,index)->{
            if(!room.equals(RoomService.room)){toast("房间已变更");return;}
            if(index==0){sendInvitation(room);return;}
            File selected=dirs[index-1];tasks.shareCode=room;
            tasks.begin("share","正在校验实例模组",()->RoomInvite.share(room,selected));render();
        }).show();
    }
    private void sendInvitation(String payload){
        Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,payload);
        startActivity(Intent.createChooser(intent,"分享私人房间"));
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
        RoomTasks.Result completed=tasks.take();
        if(completed!=null){
            if(completed.error!=null) new AlertDialog.Builder(this).setTitle("房间操作未完成").setMessage(completed.error)
                .setPositiveButton("关闭",null).show();
            else if("join".equals(completed.operation)&&"waiting".equals(RoomService.state)){
                tasks.pendingCode=(String)completed.value;requestVpn(RoomService.JOIN);
            } else if("share".equals(completed.operation)&&RoomService.room.equals(tasks.shareCode))sendInvitation((String)completed.value);
            tasks.shareCode=null;
        }
        String state=RoomService.state;boolean idle="waiting".equals(state);
        boolean ready=idle&&!tasks.busy()&&tasks.pendingAction==null;
        port.setVisibility(idle?View.VISIBLE:View.GONE);invite.setVisibility(idle?View.VISIBLE:View.GONE);
        port.setEnabled(ready);invite.setEnabled(ready);join.setEnabled(ready);create.setEnabled(ready);
        join.setVisibility(idle?View.VISIBLE:View.GONE);create.setVisibility(idle?View.VISIBLE:View.GONE);
        share.setVisibility(RoomService.room.isEmpty()?View.GONE:View.VISIBLE);share.setEnabled(!tasks.busy());
        cancel.setVisibility(tasks.busy()?View.VISIBLE:View.GONE);
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
            case "reconnecting":text="连接中断，正在等待第 "+RoomService.retryNumber+" 次重连";break;
            default:text="陶瓦状态 "+state;
        }
        if(tasks.busy())text=tasks.progress;
        if(!text.contentEquals(status.getText()))status.setText(text);
        if(!RoomService.metadata.contentEquals(versions.getText()))versions.setText(RoomService.metadata);
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    private void licenses(){
        new AlertDialog.Builder(this).setTitle("开源许可").setItems(new String[]{"微光及 Terracotta · AGPLv3","EasyTier · LGPLv3","查看对应源码"},(d,n)->{
            if(n==2){
                Intent page=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/sfyhvdrukbfflfhldkfdgkxlg/Lumen-Craft-Launcher"));
                try{startActivity(page);}catch(ActivityNotFoundException e){toast(page.getDataString());}return;
            }
            String path=n==0?"licenses/Terracotta-AGPL-3.0.txt":"licenses/EasyTier.txt";
            try(InputStream in=getAssets().open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];for(int count;(count=in.read(b))!=-1;)out.write(b,0,count);
                new AlertDialog.Builder(this).setMessage(new String(out.toByteArray(),StandardCharsets.UTF_8)).setPositiveButton("关闭",null).show();
            }catch(IOException e){toast(e.getMessage());}
        }).show();
    }
    @Override protected void onResume(){super.onResume();ui.post(tick);}
    @Override protected void onPause(){ui.removeCallbacks(tick);super.onPause();}
    @Override public Object onRetainNonConfigurationInstance(){
        tasks.typed=invite.getText().toString();tasks.port=port.getText().toString();return tasks;
    }
    @Override protected void onDestroy(){ui.removeCallbacks(tick);if(!isChangingConfigurations())tasks.cancel();super.onDestroy();}
}
