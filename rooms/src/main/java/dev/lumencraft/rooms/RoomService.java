// SPDX-License-Identifier: AGPL-3.0-only
package dev.lumencraft.rooms;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.*;
import dev.lumencraft.core.ServerProbe;
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

    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null||STOP.equals(i.getAction())){end("");return START_NOT_STICKY;}
        foreground();
        if(!active.compareAndSet(false,true))return START_NOT_STICKY;
        stopping=false;
        state="initializing";error="";address="";room="";
        final String action=i.getAction(),invite=i.getStringExtra("room");dedicatedPort=i.getIntExtra("port",0);
        worker.execute(()->{
            try{
                NativeRooms.service=this;NativeRooms.initialize(this);metadata=NativeRooms.versions;
                if(stopping)return;
                NativeRooms.setWaiting0();
                if(HOST.equals(action)){
                    if(dedicatedPort!=0){
                        ServerProbe.Status status=ServerProbe.probe("127.0.0.1:"+dedicatedPort);
                        if(status.protocol<0)throw new IOException("指定端口没有可用的 Minecraft Java 服务端");
                    }
                    acquireMulticast();NativeRooms.setScanning0(null,null);
                }else if(JOIN.equals(action)){
                    if(invite==null || invite.length()>512 || NativeRooms.verifyRoomCode0(invite)<0 ||
                        !NativeRooms.setGuesting0(invite,null))throw new IOException("联机码无效或该格式不受支持");
                }else throw new IOException("未知的联机操作");
                poll=worker.scheduleWithFixedDelay(this::observe,0,700,TimeUnit.MILLISECONDS);
            }catch(Exception|LinkageError ex){end(safeError(ex));}
        });
        return START_NOT_STICKY;
    }

    private static String safeError(Throwable ex){
        if(ex instanceof IOException)return ex.getMessage()==null?"网络连接失败":ex.getMessage();
        if(ex instanceof LinkageError)return "本设备无法装载陶瓦原生库。"+ex.getMessage();
        return "陶瓦核心执行失败（"+ex.getClass().getSimpleName()+"）";
    }
    private void observe(){
        if(stopping)return;
        try{
            JSONObject snapshot=new JSONObject(NativeRooms.getState0());
            String s=snapshot.getString("state");
            if("exception".equals(s)){end("陶瓦报告连接失败，类型 "+snapshot.optInt("type",-1));return;}
            if("host-scanning".equals(s)&&dedicatedPort>0)advertiseLocal();
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
        // Only the companion and the launcher should see the private room routes.
        b.addAllowedApplication(getPackageName());
        try{b.addAllowedApplication("dev.lumencraft.android");}catch(PackageManager.NameNotFoundException ignored){}
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
            error=why;state="waiting";active.set(false);
            new Handler(Looper.getMainLooper()).post(()->{stopForeground(true);stopSelf();});
        });
    }
    @Override public void onRevoke(){end("系统已撤销 VPN 授权");}
    @Override public void onDestroy(){end("");super.onDestroy();}
}
