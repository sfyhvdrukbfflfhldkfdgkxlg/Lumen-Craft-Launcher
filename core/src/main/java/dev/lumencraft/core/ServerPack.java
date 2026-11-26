package dev.lumencraft.core;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional static HTTPS manifest published by a server operator. */
public final class ServerPack {
    public final String address, game, url, sha256, name;
    public final long size;
    ServerPack(String address, JSONObject data, int port,String advertised,List<VanillaInstaller.Version> official) throws Exception {
        if(integer(data,"schema")!=1 || integer(data,"port")!=port) throw new IOException("清单协议或游戏端口不匹配");
        this.address=address;
        game=data.getString("minecraft");
        JSONObject pack=data.getJSONObject("modrinthPack");
        name=pack.getString("name").trim(); url=pack.getString("url"); sha256=pack.getString("sha256").toLowerCase(Locale.ROOT);size=integer(pack,"size");
        URI uri=new URI(url);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || name.isEmpty() || name.length()>160 ||
            size<=0 || size>PackImport.ARCHIVE_LIMIT || !sha256.matches("[0-9a-f]{64}")) throw new IOException("服务器清单缺少有效的 HTTPS 地址、文件大小或 SHA-256");
        validateVersions(game,advertised,official);
    }
    private static long integer(JSONObject json,String name) throws IOException {
        Object value=json.opt(name);
        if(!(value instanceof Number) || ((Number)value).doubleValue()!=((Number)value).longValue())
            throw new IOException("服务器清单需要整数字段 "+name);
        return ((Number)value).longValue();
    }
    public static ServerPack discover(String address, String advertised, List<VanillaInstaller.Version> official) throws Exception {
        ServerProbe.Endpoint endpoint=ServerProbe.parse(address);
        String path="/.well-known/lumencraft/"+endpoint.port+".json";
        String url=new URI("https",null,endpoint.host,-1,path,null,null).toASCIIString();
        byte[] json=new VerifiedDownloads().metadata(url,65536);
        return new ServerPack(address,new JSONObject(new String(json,StandardCharsets.UTF_8)),endpoint.port,advertised,official);
    }
    static void validateVersions(String expected,String advertised,List<VanillaInstaller.Version> official) throws IOException {
        boolean known=false;
        for(VanillaInstaller.Version v:official){
            if(v.id.equals(expected)) known=true;
            if(v.id.equals(advertised) && !expected.equals(advertised))throw new IOException("整合包与服务器报告的 Minecraft 版本冲突");
        }
        if(!known)throw new IOException("整合包声明的 Minecraft 版本未进入官方清单");
    }
    public File install(File root,VanillaInstaller.Progress report) throws Exception {
        if(report!=null) report.update("下载服务器整合包",0,1);
        File cache=new File(root,"shared/packs/"+sha256+".mrpack");
        new VerifiedDownloads().fetch(url,cache,sha256,size);
        try(InputStream input=new FileInputStream(cache)) {
            return PackImport.installServer(root,input,report,address,game,sha256);
        }
    }
}
