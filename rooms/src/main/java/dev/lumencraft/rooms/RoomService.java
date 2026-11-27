// SPDX-License-Identifier: AGPL-3.0-only
package dev.lumencraft.rooms;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.*;
import dev.lumencraft.core.ServerProbe;
import dev.lumencraft.runtime.ServerService;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-started room + Android VPN lifetime. Serialize native state transitions away from the UI. */
public final class RoomService extends VpnService {
    static final String HOST="host",JOIN="join",STOP="stop";
    static volatile String state="waiting",room="",address="",error="",metadata="";
    private static final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private static final int NOTIFICATION=71;
    private volatile boolean stopping;
    private final AtomicBoolean active=new AtomicBoolean();
    private ScheduledFuture<?> poll;
    private WifiManager.MulticastLock multicast;
    private ParcelFileDescriptor tunnel;
    private int dedicatedPort;
    private String action, sessionCode;
    private int failures;
    private long retryAt, healthySince;
    static volatile int retryNumber;

    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null||STOP.equals(i.getAction())){end("");return START_NOT_STICKY;}
        foreground();
        if(!active.compareAndSet(false,true))return START_NOT_STICKY;
        stopping=false;
        state="initializing";error="";address="";room="";
        action=i.getAction();sessionCode=i.getStringExtra("room");dedicatedPort=i.getIntExtra("port",0);
        failures=0;retryNumber=0;retryAt=healthySince=0;
        worker.execute(()->{
            try{
                NativeRooms.service=this;NativeRooms.initialize(this);metadata=NativeRooms.versions;
                if(stopping)return;
                NativeRooms.setWaiting0();
                if(HOST.equals(action)){
                    if(dedicatedPort==0)dedicatedPort=ServerService.activePort();
                    if(dedicatedPort!=0){
                        ServerProbe.Status status=ServerProbe.probe("127.0.0.1:"+dedicatedPort);
                        if(status.protocol<0)throw new IOException("指定端口没有可用的 Minecraft Java 服务端");
                    }
                    acquireMulticast();
                }else if(JOIN.equals(action)){
                    if(sessionCode==null || sessionCode.length()>512 || NativeRooms.verifyRoomCode0(sessionCode)<0)
                        throw new IOException("联机码无效或该格式不受支持");
                }else throw new IOException("未知的联机操作");
                connect();
                poll=worker.scheduleWithFixedDelay(this::observe,0,700,TimeUnit.MILLISECONDS);
            }catch(Exception|LinkageError ex){end(safeError(ex));}
        });
        return START_NOT_STICKY;
    }

    private void connect() throws IOException {
        if (VpnService.prepare(this) != null) throw new IOException("未获得系统 VPN 授权");
        if (HOST.equals(action)) NativeRooms.setScanning0(sessionCode, null);
        else if (!NativeRooms.setGuesting0(sessionCode, null)) throw new IOException("陶瓦未能加入该联机码");
    }

    private void connectionFailure(int type) {
        healthySince=0;
        String cause;
        switch(type){
            case 0: case 1: cause="与房主的连接已中断"; break;
            case 2: case 3: cause="EasyTier 连接已关闭"; break;
            case 4: cause="本机游戏服务端未响应"; break;
            case 5: end("房主返回的陶瓦协议响应不受支持"); return;
            default: end("陶瓦返回未知连接错误 "+type); return;
        }
        if(failures>=5){end(cause+"，请确认网络和游戏均已就绪后再次连接");return;}
        NativeRooms.setWaiting0();
        closeTunnel();
        room="";address="";
        retryAt=SystemClock.elapsedRealtime()+(2000L<<failures);
        retryNumber=++failures;state="reconnecting";
    }

    private static String safeError(Throwable ex){
        if(ex instanceof IOException)return ex.getMessage()==null?"网络连接失败":ex.getMessage();
        if(ex instanceof LinkageError)return "本设备无法装载陶瓦原生库。"+ex.getMessage();
        return "陶瓦核心执行失败（"+ex.getClass().getSimpleName()+"）";
    }
    private void observe(){
        if(stopping)return;
        try{
            if(retryAt!=0){
                if(SystemClock.elapsedRealtime()<retryAt)return;
                retryAt=0;connect();
            }
            JSONObject snapshot=new JSONObject(NativeRooms.getState0());
            String s=snapshot.getString("state");
            if("exception".equals(s)){connectionFailure(snapshot.optInt("type",-1));return;}
            if(HOST.equals(action) && ("host-starting".equals(s)||"host-ok".equals(s)))
                sessionCode=snapshot.getString("room");
            if("host-scanning".equals(s)&&dedicatedPort>0)advertiseLocal();
            if("host-ok".equals(s)||"guest-ok".equals(s)){
                long now=SystemClock.elapsedRealtime();
                if(healthySince==0)healthySince=now;
                if(now-healthySince>=60000)failures=0;
            }else healthySince=0;
            room="host-ok".equals(s)?snapshot.optString("room",""):"";
            address="guest-ok".equals(s)?snapshot.optString("url",""):"";state=s;
        }catch(Exception|LinkageError ex){end(safeError(ex));}
    }
    private void acquireMulticast(){
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(wifi==null)return;
        multicast=wifi.createMulticastLock("Lumen.private-room");
        multicast.setReferenceCounted(false);multicast.acquire();
    }
    private void advertiseLocal()throws IOException{
        // A dedicated server does not announce an open-to-LAN world. Notify only scanner sockets on this phone.
        byte[] data=("[MOTD]Lumen Server[/MOTD][AD]"+dedicatedPort+"[/AD]").getBytes(StandardCharsets.US_ASCII);
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        try(DatagramSocket socket=new DatagramSocket()){
            while(interfaces!=null&&interfaces.hasMoreElements()){
                NetworkInterface iface=interfaces.nextElement();
                if(!iface.isUp()||iface.isLoopback())continue;
                for(InetAddress addr:Collections.list(iface.getInetAddresses()))
                    if(addr instanceof Inet4Address)socket.send(new DatagramPacket(data,data.length,addr,4445));
            }
            socket.send(new DatagramPacket(data,data.length,InetAddress.getByName("127.0.0.1"),4445));
        }
    }

    synchronized int openTunnel(byte[] ip,int prefix,String routes)throws Exception{
        if(stopping||VpnService.prepare(this)!=null)throw new IOException("未获得系统 VPN 授权");
        Builder b=new Builder().setSession("微光 · 陶瓦联机").addAddress(InetAddress.getByAddress(ip),prefix);
        // Only this launcher's Android UID (including the game and server JVMs) uses room routes.
        b.addAllowedApplication(getPackageName());
        if(!routes.isEmpty())for(String route:routes.split(String.valueOf((char)0))){
            int sep=route.lastIndexOf('/');
            if(sep<1)throw new IOException("陶瓦返回无效路由");
            int bits=Integer.parseInt(route.substring(sep+1));
            if(bits<=0 || bits>32)throw new IOException("陶瓦返回非专用 IPv4 路由");
            b.addRoute(route.substring(0,sep),bits);
        }
        ParcelFileDescriptor next=b.establish();if(next==null)throw new IOException("系统无法建立虚拟局域网");
        if(tunnel!=null)tunnel.close();tunnel=next;return tunnel.getFd();
    }
    private void foreground(){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        String channel="room";
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(channel,"私人联机房间",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,TerracottaActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
        startForeground(NOTIFICATION,b.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("陶瓦联机")
            .setContentText("打开房间状态或结束联机").setOngoing(true).setContentIntent(open).build());
    }
    private synchronized void closeTunnel(){if(tunnel!=null){try{tunnel.close();}catch(IOException ignored){}tunnel=null;}}
    private synchronized void end(String why){
        if(stopping)return;stopping=true;state="closing";room="";address="";
        worker.execute(()->{
            if(poll!=null){poll.cancel(false);poll=null;}
            try{if(NativeRooms.started)NativeRooms.setWaiting0();}catch(Exception|LinkageError ignored){}
            if(multicast!=null&&multicast.isHeld())multicast.release();
            closeTunnel();
            try{NativeRooms.service=null;}catch(LinkageError ignored){}
            sessionCode=null;retryAt=healthySince=0;
            new Handler(Looper.getMainLooper()).post(()->{
                stopForeground(true);stopSelf();error=why;state="waiting";active.set(false);
            });
        });
    }
    @Override public void onRevoke(){end("系统已撤销 VPN 授权");}
    @Override public void onDestroy(){end("");super.onDestroy();}
}
