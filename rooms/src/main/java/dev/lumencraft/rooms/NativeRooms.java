// SPDX-License-Identifier: AGPL-3.0-only
package dev.lumencraft.rooms;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** JNI contract defined by Terracotta v0.4.2's src/lib.rs. Native code runs only in the companion process. */
final class NativeRooms {
    static volatile RoomService service;
    static volatile boolean started;
    static volatile String versions="";
    static {
        System.setProperty("net.burningtnt.terracotta.native_location",NativeRooms.class.getName().replace('.','/'));
        if(android.os.Build.VERSION.SDK_INT==23)System.loadLibrary("roomnet23");
        System.loadLibrary("terracotta");
    }

    static synchronized void initialize(Context app)throws IOException {
        if(started)return;
        File data=new File(app.getNoBackupFilesDir(),"terracotta");
        if(!data.isDirectory()&&!data.mkdirs())throw new IOException("无法创建组网配置目录");
        // The upstream log stream may contain invite codes. Keep it out of file storage.
        int fd=ParcelFileDescriptor.open(new File("/dev/null"),ParcelFileDescriptor.MODE_WRITE_ONLY).detachFd();
        int result=start0(data.getAbsolutePath(),fd);
        if(result!=0)throw new IOException("陶瓦核心启动失败 "+result);
        started=true;
        String[] metadata=getMetadata0().split(String.valueOf((char)0),-1);
        if(metadata.length==3)versions="Terracotta "+metadata[0]+"  ·  EasyTier "+metadata[2];
    }

    @SuppressWarnings("unused")
    private static int onVpnServiceStateChanged(byte a,byte b,byte c,byte d,short prefix,String cidrs)throws Exception{
        RoomService active=service;
        if(active==null)throw new IOException("联机服务已停止");
        return active.openTunnel(new byte[]{a,b,c,d},prefix,cidrs);
    }

    private static native int start0(String base,int log);
    static native String getState0();
    static native void setWaiting0();
    static native void setScanning0(String room,String player);
    static native boolean setGuesting0(String room,String player);
    static native int verifyRoomCode0(String room);
    private static native String getMetadata0();
    // Required by JNI_OnLoad's RegisterNatives even though this app has no log-export or debug action.
    private static native long prepareExportLogs0();
    private static native void finishExportLogs0(long token);
    private static native void panic0();
}
