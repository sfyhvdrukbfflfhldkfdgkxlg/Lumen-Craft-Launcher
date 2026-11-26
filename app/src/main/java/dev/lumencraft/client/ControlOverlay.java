package dev.lumencraft.client;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.util.AtomicFile;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Multitouch input and in-game editor. All input ownership is maintained on the main thread. */
public final class ControlOverlay extends FrameLayout {
    public interface Sink { void event(int kind,int a,int b); }
    private static final int OPEN=8110, SAVE=8111;
    private final Activity activity;
    private final Sink sink;
    private final File instance;
    private final AtomicFile layout;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<Integer,Integer> counts=new HashMap<>();
    private final Map<Long,Integer> physicalKeys=new HashMap<>();
    private final Map<Integer,Touch> touches=new LinkedHashMap<>();
    private final Pad pad;
    private final TextView menu;
    private ControlProfile profile;
    private boolean editing,grabbed=true;
    private String beforeEdit;
    private String lastTap="";
    private long lastTime;
    private int mouseButtons;
    private boolean hasHover;
    private float hoverX,hoverY;

    public ControlOverlay(Activity activity,File instance,Sink sink){
        super(activity);this.activity=activity;this.instance=instance;this.sink=sink;
        layout=new AtomicFile(new File(instance,"controls-v21.json"));
        try{
            profile=ControlProfile.defaults();
            if(layout.getBaseFile().exists()||new File(layout.getBaseFile()+".bak").exists()){
                try(InputStream in=layout.openRead()){profile=read(in);}
            }
        }catch(Exception e){message("布局读取失败，已使用默认布局。"+e.getMessage());}
        pad=new Pad();addView(pad,new LayoutParams(-1,-1));
        menu=new TextView(activity);menu.setText("按键");menu.setTextColor(-1);menu.setTextSize(13);menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("打开按键布局与编辑菜单");menu.setOnClickListener(v->toolbar());
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xa1233336);bg.setCornerRadius(dp(12));menu.setBackground(bg);
        LayoutParams p=new LayoutParams(dp(58),dp(36),Gravity.TOP|Gravity.RIGHT);p.setMargins(0,dp(4),dp(5),0);addView(menu,p);
    }

    public void setGrabbed(boolean value){
        if(Looper.myLooper()!=Looper.getMainLooper()){post(()->setGrabbed(value));return;}
        if(grabbed!=value){releaseAll();grabbed=value;pad.invalidate();}
    }

    private int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private void message(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    private static ControlProfile read(InputStream in)throws Exception{
        if(in==null)throw new IOException("无法读取文档");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];
        for(int n;(n=in.read(b))!=-1;){if(out.size()+n>2<<20)throw new IOException("布局文件过大");out.write(b,0,n);}
        return new ControlProfile(new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)));
    }
    private void save(ControlProfile value)throws Exception{
        byte[] data=value.document.toString(2).getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream=layout.startWrite();
        try{stream.write(data);layout.finishWrite(stream);}
        catch(IOException e){layout.failWrite(stream);throw e;}
    }
    private boolean visible(ControlProfile.Button b){
        return editing||(!"IN_GAME".equals(b.visibility)&&!"MENU".equals(b.visibility))
            || ("IN_GAME".equals(b.visibility)==grabbed);
    }
    private RectF bounds(ControlProfile.Button b){
        int w=getWidth(),h=getHeight();
        float density=getResources().getDisplayMetrics().density;
        int width=Math.min(w,b.dim(true,w,h,density)),height=Math.min(h,b.dim(false,w,h,density));
        float left=(w-width)*b.x()/1000f,top=(h-height)*b.y()/1000f;
        return new RectF(left,top,left+width,top+height);
    }
    private void set(int fcl,boolean down){
        int code=ControlProfile.toLwjgl(fcl);if(code==0)return;
        Integer previous=counts.get(code);int old=previous==null?0:previous;
        if(!down&&old==0)return;
        int next=old+(down?1:-1);
        if(next==0)counts.remove(code);else counts.put(code,next);
        if((old==0)==(next==0))return;
        if(code>=1000)sink.event(3,code==1000?0:code==1002?1:2,down?1:0);
        else sink.event(1,code,down?1:0);
    }
    public boolean key(KeyEvent e){
        if(editing)return false;
        int code=TextKeys.physical(e.getKeyCode());if(code==0)return false;
        long id=((long)e.getDeviceId()<<32)|(e.getKeyCode()&0xffffffffL);
        if(e.getAction()==KeyEvent.ACTION_DOWN){
            if(!physicalKeys.containsKey(id)){physicalKeys.put(id,code);set(code,true);}
        }else if(e.getAction()==KeyEvent.ACTION_UP){Integer old=physicalKeys.remove(id);if(old!=null)set(old,false);}
        return true;
    }
    public void releaseAll(){
        handler.removeCallbacksAndMessages(null);touches.clear();physicalKeys.clear();lastTap="";
        for(int code:new ArrayList<>(counts.keySet())){
            if(code>=1000)sink.event(3,code==1000?0:code==1002?1:2,0);
            else sink.event(1,code,0);
        }
        counts.clear();hasHover=false;mouseButtons=0;
        if(pad!=null)pad.invalidate();
    }
    @Override protected void onDetachedFromWindow(){releaseAll();super.onDetachedFromWindow();}
    private final class Touch{
        final int pointer;final ControlProfile.Button b;
        final Map<Integer,Integer> owned=new HashMap<>();final Set<Integer> stick=new HashSet<>();
        final List<Runnable> timers=new ArrayList<>();
        final float startX,startY,offsetX,offsetY;
        float x,y,remX,remY;boolean moved,longPress;
        Touch(int id,ControlProfile.Button b,float x,float y){pointer=id;this.b=b;this.x=x;this.y=y;startX=x;startY=y;
            RectF r=b==null?new RectF():bounds(b);offsetX=x-r.left;offsetY=y-r.top;}
        void own(int c,boolean down){Integer prev=owned.get(c);int n=prev==null?0:prev;
            if(!down&&n==0)return;
            if(!down&&n==1)owned.remove(c);else owned.put(c,n+(down?1:-1));
            set(c,down);}
        void chord(int[] keys,boolean down){for(int i=0;i<keys.length;i++)own(keys[down?i:keys.length-1-i],down);}
        void tap(int[] keys){chord(keys,true);chord(keys,false);}
        void later(Runnable r,long delay){timers.add(r);handler.postDelayed(r,delay);}
        void end(){for(Runnable r:timers)handler.removeCallbacks(r);
            for(int c:new ArrayList<>(owned.keySet()))while(owned.containsKey(c))own(c,false);}
        void direction(float xx,float yy){
            RectF r=bounds(b);float dx=(xx-r.centerX())/(r.width()/2),dy=(yy-r.centerY())/(r.height()/2);
            Set<Integer> next=new HashSet<>();boolean[] on={dy<-.29f,dy>.29f,dx<-.29f,dx>.29f};
            for(int i=0;i<4;i++)if(on[i])for(int k:b.directions[i])next.add(k);
            for(int old:stick)if(!next.contains(old))own(old,false);
            for(int k:next)if(!stick.contains(k))own(k,true);
            stick.clear();stick.addAll(next);
        }
        void relative(float dx,float dy){remX+=dx;remY+=dy;int a=(int)remX,b=(int)remY;
            remX-=a;remY-=b;if(a!=0||b!=0)sink.event(2,a,b);}
    }

    private class Pad extends View{
        final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
        Pad(){super(activity);setFocusable(true);setContentDescription("游戏触控区域");}
        protected void onDraw(Canvas c){
            super.onDraw(c);if(profile==null)return;
            for(ControlProfile.Button b:profile.buttons){
                if(!visible(b))continue;RectF r=bounds(b);boolean pressed=false;
                for(Touch t:touches.values())if(t.b==b){pressed=true;break;}
                pen.setStyle(Paint.Style.FILL);pen.setColor(pressed?0xc347897d:0x8c23383c);
                float radius=Math.min(dp(12),r.height()/3);
                c.drawRoundRect(r,radius,radius,pen);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(dp(1));
                pen.setColor(editing?0xffd9b575:0x9ed0e2e1);c.drawRoundRect(r,radius,radius,pen);
                pen.setStyle(Paint.Style.FILL);pen.setColor(0xffeff6f4);pen.setTextAlign(Paint.Align.CENTER);
                pen.setTextSize(Math.max(dp(9),Math.min(dp(16),r.height()*.29f)));
                if(b.directions!=null){
                    c.drawText("▲",r.centerX(),r.top+r.height()*.25f,pen);c.drawText("▼",r.centerX(),r.top+r.height()*.85f,pen);
                    c.drawText("◀",r.left+r.width()*.18f,r.centerY()+dp(4),pen);c.drawText("▶",r.left+r.width()*.82f,r.centerY()+dp(4),pen);
                }else{
                    String label=b.text;if(label.length()>24)label=label.substring(0,24);
                    float measured=pen.measureText(label);
                    if(measured>r.width()*.9f)pen.setTextSize(pen.getTextSize()*r.width()*.9f/measured);
                    c.drawText(label,r.centerX(),r.centerY()-(pen.ascent()+pen.descent())/2,pen);
                }
            }
        }
        private ControlProfile.Button hit(float x,float y){
            for(int i=profile.buttons.size()-1;i>=0;i--){ControlProfile.Button b=profile.buttons.get(i);
                if(visible(b)&&bounds(b).contains(x,y))return b;}return null;
        }
        private void down(int id,float x,float y){
            ControlProfile.Button b=hit(x,y);Touch t=new Touch(id,b,x,y);touches.put(id,t);
            if(editing||b==null)return;
            if(b.directions!=null){t.direction(x,y);return;}
            if(b.repeat){t.tap(b.press);Runnable repeat=new Runnable(){public void run(){
                if(touches.get(id)==t){t.tap(b.press);handler.postDelayed(this,90);}
            }};t.later(repeat,350);}else t.chord(b.press,true);
            if(b.hold.length>0)t.later(()->{if(touches.get(id)==t&&!t.moved){t.longPress=true;t.chord(b.hold,true);}},ViewConfiguration.getLongPressTimeout());
        }
        private void move(Touch t,float x,float y){
            if(Math.hypot(x-t.startX,y-t.startY)>ViewConfiguration.get(getContext()).getScaledTouchSlop())t.moved=true;
            if(editing){
                if(t.b!=null){RectF r=bounds(t.b);
                    try{t.b.base.put("xPosition",ControlProfile.clamp(Math.round((x-t.offsetX)*1000/Math.max(1,getWidth()-r.width())),0,1000));
                        t.b.base.put("yPosition",ControlProfile.clamp(Math.round((y-t.offsetY)*1000/Math.max(1,getHeight()-r.height())),0,1000));}
                    catch(JSONException e){throw new IllegalStateException(e);}
                }
            }else if(t.b!=null&&t.b.directions!=null)t.direction(x,y);
            else if(t.b==null||t.b.follows)t.relative(x-t.x,t.y-y);
            t.x=x;t.y=y;
        }
        private void up(int id){
            Touch t=touches.remove(id);if(t==null)return;
            t.end();
            if(!editing&&!t.moved&&!t.longPress){
                if(t.b==null){set(1000,true);set(1000,false);}
                else if(t.b.directions==null){long now=SystemClock.uptimeMillis();
                    if(lastTap.equals(t.b.id)&&now-lastTime<ViewConfiguration.getDoubleTapTimeout()&&t.b.twice.length>0){
                        t.tap(t.b.twice);lastTap="";
                    }else{t.tap(t.b.click);lastTap=t.b.id;lastTime=now;}
                }
            }
        }
        private boolean externalMouse(MotionEvent e){
            if(!e.isFromSource(InputDevice.SOURCE_MOUSE)||editing)return false;
            int state=e.getButtonState(),action=e.getActionMasked();
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)state=0;
            int[] bits={MotionEvent.BUTTON_PRIMARY,MotionEvent.BUTTON_SECONDARY,MotionEvent.BUTTON_TERTIARY}, codes={1000,1002,1001};
            for(int i=0;i<3;i++)if(((state^mouseButtons)&bits[i])!=0)set(codes[i],(state&bits[i])!=0);
            mouseButtons=state;
            if(hasHover&&(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_HOVER_MOVE)){
                int dx=Math.round(e.getX()-hoverX),dy=Math.round(hoverY-e.getY());if(dx!=0||dy!=0)sink.event(2,dx,dy);}
            hoverX=e.getX();hoverY=e.getY();hasHover=action!=MotionEvent.ACTION_HOVER_EXIT;return true;
        }
        public boolean onGenericMotionEvent(MotionEvent e){return externalMouse(e)||super.onGenericMotionEvent(e);}
        public boolean onHoverEvent(MotionEvent e){return externalMouse(e)||super.onHoverEvent(e);}
        public boolean onTouchEvent(MotionEvent e){
            if(profile==null)return false;
            if(externalMouse(e))return true;
            int a=e.getActionMasked(),n=e.getActionIndex();
            if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN)down(e.getPointerId(n),e.getX(n),e.getY(n));
            else if(a==MotionEvent.ACTION_MOVE){for(int i=0;i<e.getPointerCount();i++){Touch t=touches.get(e.getPointerId(i));if(t!=null)move(t,e.getX(i),e.getY(i));}}
            else if(a==MotionEvent.ACTION_POINTER_UP||a==MotionEvent.ACTION_UP)up(e.getPointerId(n));
            else if(a==MotionEvent.ACTION_CANCEL)releaseAll();
            invalidate();return true;
        }
    }

    public void toolbar(){
        releaseAll();
        if(editing){
            new AlertDialog.Builder(activity).setTitle("编辑按键").setItems(new String[]{"保存并返回游戏","继续拖动","取消此次编辑"},(dialog,n)->{
                try{
                    if(n==1)return;if(n==0)save(profile);else profile=new ControlProfile(new JSONObject(beforeEdit));
                    editing=false;menu.setText("按键");pad.invalidate();
                }catch(Exception e){message("保存失败。"+e.getMessage());}
            }).show();return;
        }
        new AlertDialog.Builder(activity).setTitle("实例按键").setItems(new String[]{"拖动调整布局","从 options.txt 添加按键","导入 FCL 布局","导出 FCL 布局"},(dialog,n)->{
            if(n==0){beforeEdit=profile.document.toString();editing=true;menu.setText("完成");pad.invalidate();message("拖动按键或方向盘，点「完成」保存");}
            else if(n==1)bindingsDialog();else document(n==2);
        }).show();
    }

    private void bindingsDialog(){
        try{
            Map<String,Integer> map=ControlProfile.bindings(new File(instance,"options.txt"));
            if(map.isEmpty()){message("该实例尚无可识别的 options.txt 按键记录");return;}
            List<String> keys=new ArrayList<>(map.keySet());Collections.sort(keys);
            Map<Integer,Integer> freq=new HashMap<>();for(int code:map.values())freq.put(code,(freq.containsKey(code)?freq.get(code):0)+1);
            String[] labels=new String[keys.size()];boolean[] selected=new boolean[keys.size()];
            for(int i=0;i<labels.length;i++){int key=map.get(keys.get(i));labels[i]=keys.get(i)+(freq.get(key)>1?"  ·  共用键位":"");}
            new AlertDialog.Builder(activity).setTitle("选择要添加的绑定").setMultiChoiceItems(labels,selected,(d,w,c)->selected[w]=c)
                .setNegativeButton("取消",null).setPositiveButton("添加",(d,w)->{
                    try{
                        ControlProfile original=profile;profile=new ControlProfile(new JSONObject(original.document.toString()));int added=0,full=0;
                        try{for(int i=0;i<keys.size();i++)if(selected[i]){
                            String k=keys.get(i);int[] pos=freePosition();if(pos==null){full++;continue;}
                            if(profile.addBinding(k,map.get(k),pos[0],pos[1]))added++;}
                            save(profile);
                        }catch(Exception e){profile=original;throw e;}
                        message("新增 "+added+" 个按键"+(full>0?"，屏幕空间不足 "+full+" 个":""));pad.invalidate();
                    }catch(Exception e){message(e.getMessage());}
                }).show();
        }catch(Exception e){message(e.getMessage());}
    }
    private int[] freePosition(){
        float w=getHeight()*.147f,h=getHeight()*.128f;
        for(int yy=170;yy<=1000;yy+=145)for(int xx=10;xx<=990;xx+=85){
            float left=(getWidth()-w)*xx/1000f,top=(getHeight()-h)*yy/1000f;
            RectF r=new RectF(left,top,left+w,top+h);
            boolean free=true;
            for(ControlProfile.Button b:profile.buttons)if(RectF.intersects(r,bounds(b))){free=false;break;}
            if(free)return new int[]{xx,yy};
        }
        return null;
    }
    private void document(boolean open){
        Intent i=new Intent(open?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(open?"*/*":"application/json");
        if(!open)i.putExtra(Intent.EXTRA_TITLE,"lumen-controls.json");
        try{activity.startActivityForResult(i,open?OPEN:SAVE);}catch(ActivityNotFoundException e){message("系统没有可用的文件选择器");}
    }
    public boolean onDocumentResult(int request,int result,Intent data){
        if(request!=OPEN&&request!=SAVE)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        try{
            if(request==OPEN){
                ControlProfile imported;
                try(InputStream in=activity.getContentResolver().openInputStream(data.getData())){imported=read(in);}
                new AlertDialog.Builder(activity).setTitle("应用导入布局").setMessage("将替换本实例的按键布局。可用控件 "+imported.buttons.size()+" 个，跳过 "+imported.skipped+" 个无可执行按键的控件。")
                    .setNegativeButton("取消",null).setPositiveButton("应用",(dialog,which)->{
                        try{save(imported);releaseAll();profile=imported;pad.invalidate();}catch(Exception e){message(e.getMessage());}
                    }).show();
            }else{
                try(OutputStream out=activity.getContentResolver().openOutputStream(data.getData(),"wt")){
                    if(out==null)throw new IOException("无法写入文档");out.write(profile.document.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                message("布局已导出");
            }
        }catch(Exception e){message("布局操作失败。"+e.getMessage());}
        return true;
    }
}
