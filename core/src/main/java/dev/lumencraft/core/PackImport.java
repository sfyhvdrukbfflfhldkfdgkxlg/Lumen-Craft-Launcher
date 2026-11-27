package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.zip.*;

/** Modrinth v1 client pack importer. The instance becomes visible after the whole import succeeds. */
public final class PackImport {
    static final long ARCHIVE_LIMIT = 256L << 20;
    private static final long FILE_LIMIT = 1L << 30;
    private static final long TOTAL_LIMIT = 4L << 30;
    private static final int ENTRY_LIMIT = 20000;
    private static final String PREFIX = "pack-staging-";
    private static final Set<String> CONTENT_DIRS = new HashSet<>(Arrays.asList(
        "mods","config","defaultconfigs","resourcepacks","shaderpacks","datapacks","saves","kubejs",
        "scripts","global_packs","openloader","patchouli_books","fancymenu","schematics","configurable",
        "resources","paxi","journeymap","xaerominimap","xaeroworldmap"));
    private static final Set<String> ROOT_FILES = new HashSet<>(Arrays.asList(
        "options.txt","optionsof.txt","optionsshaders.txt","servers.dat","icon.png"));
    private PackImport() {}

    interface Components {
        void install(File root, File stage, String game, String loader, String pin, VanillaInstaller.Progress progress) throws Exception;
    }

    public static File install(File root, InputStream zip, VanillaInstaller.Progress progress) throws Exception {
        return installServer(root,zip,progress,null,null,null);
    }
    static File installServer(File root, InputStream zip, VanillaInstaller.Progress progress,String address,String expectedGame,String expectedDigest) throws Exception {
        return installServer(root,zip,progress,Modrinth.LIVE,(base,stage,game,loader,pin,report)->{
            new VanillaInstaller(base).install(game,stage.getName(),false,report);
            if (loader!=null) FabricInstaller.install(base,stage,game,loader,pin,report);
        },address,expectedGame,expectedDigest);
    }

    static synchronized File install(File root, InputStream input, VanillaInstaller.Progress progress,
                                      Modrinth.Source source, Components components) throws Exception {
        return installServer(root,input,progress,source,components,null,null,null);
    }
    static synchronized File installServer(File root, InputStream input, VanillaInstaller.Progress progress,
                                      Modrinth.Source source, Components components,String address,String expectedGame,String expectedDigest) throws Exception {
        return installBundle(root,input,progress,source,components,address,expectedGame,expectedDigest,false);
    }
    static synchronized File installRoom(File root, InputStream input, VanillaInstaller.Progress progress,
                                      Modrinth.Source source, Components components,String game) throws Exception {
        return installBundle(root,input,progress,source,components,null,game,null,true);
    }
    private static File installBundle(File root, InputStream input, VanillaInstaller.Progress progress,
                                      Modrinth.Source source, Components components,String address,String expectedGame,String expectedDigest,
                                      boolean room) throws Exception {
        File instances = new File(root.getCanonicalFile(),"instances");
        VerifiedDownloads.directory(instances);
        VanillaInstaller.Progress report = progress == null ? (s,d,t)->{} : progress;
        File lock = new File(root,".pack-import.lock");
        try (RandomAccessFile gate = new RandomAccessFile(lock,"rw"); FileLock ignored=gate.getChannel().lock()) {
            File[] stale=instances.listFiles();
            if (stale==null) throw new IOException("无法读取实例目录");
            for (File f:stale) if (f.getName().matches("pack-staging-[0-9a-f-]{36}") &&
                f.getCanonicalFile().equals(f.getAbsoluteFile()) && new File(f,".pack-stage").isFile()) discard(f);

            File stage=new File(instances,PREFIX+UUID.randomUUID());
            if (!stage.mkdir()) throw new IOException("无法创建整合包暂存目录");
            try {
                if (!new File(stage,".pack-stage").createNewFile()) throw new IOException("无法标记暂存目录");
                File archive=new File(stage,".pack-source");
                report.update("读取整合包",0,1);
                try (OutputStream out=new BufferedOutputStream(new FileOutputStream(archive))) {
                    VerifiedDownloads.transfer(input,out,ARCHIVE_LIMIT);
                }
                String digest=VerifiedDownloads.digest(archive,"SHA-256");
                if(address!=null && !digest.equals(expectedDigest)) throw new IOException("整合包内容与服务器 SHA-256 不匹配");
                File target;
                try (ZipFile zip=new ZipFile(archive)) {
                    Map<String,ZipEntry> entries=entries(zip);
                    ZipEntry metadata=entries.get("modrinth.index.json");
                    if (metadata==null || metadata.getSize()>4L<<20) throw new IOException("缺少有效的 modrinth.index.json");
                    JSONObject index=new JSONObject(Modrinth.read(zip.getInputStream(metadata),4<<20));
                    Plan plan = new Plan(index);
                    if(expectedGame!=null && !expectedGame.equals(plan.game)) throw new IOException("整合包内部版本与服务器清单不一致");
                    String display=plan.name.replaceAll("[^\\p{L}\\p{N}._-]","-");
                    if (display.codePointCount(0,display.length())>48) display=display.substring(0,display.offsetByCodePoints(0,48));
                    target=new File(instances,"pack-"+display+"-"+digest.substring(0,12));
                    if(address!=null || room) {
                        String prefix=room ? "room" : "online-"+UUID.nameUUIDFromBytes(address.getBytes(StandardCharsets.UTF_8));
                        target=new File(instances,prefix+"-"+digest.substring(0,room?24:12));
                        if(target.isDirectory()) {
                            if (!target.equals(target.getCanonicalFile())) throw new IOException("服务器实例目录无效");
                            JSONObject receipt=VanillaInstaller.readJson(new File(target,"pack.json"));
                            JSONObject gameReceipt=VanillaInstaller.readJson(new File(target,"install.json"));
                            if (!digest.equals(receipt.optString("archiveSha256")) || (address!=null && !address.equals(receipt.optString("serverAddress")))
                                    || !plan.game.equals(gameReceipt.optString("id")) || !"client".equals(gameReceipt.optString("kind")))
                                throw new IOException("已有服务器实例记录与请求不一致");
                            File loaderFile=new File(target,"loader.json");
                            if (plan.loader!=null) {
                                JSONObject loader=VanillaInstaller.readJson(loaderFile);
                                if(!plan.loader.equals(loader.optString("loader")) || !plan.pin.equals(loader.optString("version")))
                                    throw new IOException("服务器实例的加载器与锁定版本不符");
                            } else if(loaderFile.exists()) throw new IOException("服务器实例的加载器与整合包不符");
                            if (room) verifyRoomFiles(target,plan);
                            return target;
                        }
                    }
                    if (target.exists()) throw new IOException("此整合包实例已存在 "+target.getName());

                    // Resolve override precedence before opening output files. Ignore server-only overrides.
                    LinkedHashMap<String,ZipEntry> overrides=new LinkedHashMap<>();
                    for (String prefix:new String[]{"overrides/","client-overrides/"}) {
                        for (Map.Entry<String,ZipEntry> e:entries.entrySet()) {
                            if (!e.getKey().startsWith(prefix) || e.getValue().isDirectory()) continue;
                            String path=e.getKey().substring(prefix.length());
                            contentPath(path); overrides.put(path,e.getValue());
                        }
                    }
                    Set<String> outputNames=new HashSet<>();
                    long total=0;
                    for (Map.Entry<String,JSONObject> item:plan.files.entrySet()) {
                        String path=item.getKey();
                        if (overrides.containsKey(path)) continue;
                        total+=size(item.getValue().getLong("fileSize"),FILE_LIMIT);
                        if (!outputNames.add(path.toLowerCase(Locale.US))) throw new IOException("重复文件 "+path);
                    }
                    for (Map.Entry<String,ZipEntry> item:overrides.entrySet()) {
                        total+=size(item.getValue().getSize(),FILE_LIMIT);
                        if (!outputNames.add(item.getKey().toLowerCase(Locale.US))) throw new IOException("重复覆盖文件");
                    }
                    if (total>TOTAL_LIMIT || total>stage.getUsableSpace()) throw new IOException("整合包过大或可用空间不足");
                    int done=0;
                    components.install(root,stage,plan.game,plan.loader,plan.pin,report);
                    for (Map.Entry<String,JSONObject> item:plan.files.entrySet()) {
                        String path=item.getKey();
                        if (overrides.containsKey(path)) continue;
                        JSONObject f=item.getValue();
                        report.update("校验整合包文件",done++,outputNames.size());
                        File dst=fresh(stage,path);
                        verifiedFile(source,dst,f.getJSONObject("hashes"),f.getLong("fileSize"),
                            f.getJSONArray("downloads"),FILE_LIMIT);
                    }
                    for (Map.Entry<String,ZipEntry> item:overrides.entrySet()) {
                        ZipEntry e=item.getValue();
                        report.update("安装整合包覆盖文件",done++,outputNames.size());
                        CRC32 crc=new CRC32();
                        try (InputStream in=new CheckedInputStream(zip.getInputStream(e),crc);
                             OutputStream out=new BufferedOutputStream(new FileOutputStream(fresh(stage,item.getKey())))) {
                            if (VerifiedDownloads.transfer(in,out,e.getSize())!=e.getSize() || crc.getValue()!=e.getCrc())
                                throw new IOException("覆盖文件校验失败 "+item.getKey());
                        }
                    }
                    JSONObject receipt=new JSONObject().put("name",plan.name).put("versionId",index.getString("versionId"))
                        .put("archiveSha256",digest).put("requiredFiles",plan.files.size()).put("overrides",overrides.size());
                    if(address!=null) receipt.put("serverAddress",address);
                    try (FileOutputStream out=new FileOutputStream(new File(stage,"pack.json"))) {
                        out.write(receipt.toString(2).getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
                    }
                }
                if (!archive.delete()) throw new IOException("无法清理整合包归档");
                if (target.exists() || !stage.renameTo(target)) throw new IOException("无法提交整合包实例");
                new File(target,".pack-stage").delete();
                report.update("整合包已导入",1,1);
                return target;
            } finally { if (stage.exists()) discard(stage); }
        }
    }

    private static void verifyRoomFiles(File instance,Plan plan) throws Exception {
        for (Map.Entry<String,JSONObject> item:plan.files.entrySet()) {
            File file=VanillaInstaller.safe(instance,item.getKey());
            JSONObject expected=item.getValue();
            if (!file.isFile() || file.length()!=expected.getLong("fileSize") ||
                    !VerifiedDownloads.digest(file,"SHA-512").equals(expected.getJSONObject("hashes").getString("sha512")))
                throw new IOException("房间实例的资源已变更，请恢复文件或将实例移出列表后重试："+file.getName());
        }
        File mods=new File(instance,"mods");
        File[] active=mods.listFiles((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (mods.exists() && active==null) throw new IOException("无法校验房间实例的模组目录");
        if (active!=null) for (File file:active) if (!plan.files.containsKey("mods/"+file.getName()))
            throw new IOException("房间实例含有额外启用的模组："+file.getName());
    }

    private static File fresh(File stage,String relative) throws IOException {
        File dst=VanillaInstaller.safe(stage,relative);
        VerifiedDownloads.directory(dst.getParentFile());
        if (!dst.createNewFile()) throw new IOException("整合包与已有组件文件冲突 "+relative);
        return dst;
    }

    static void verifiedFile(Modrinth.Source source,File dest,JSONObject hashes,long length,JSONArray urls,long limit) throws Exception {
        size(length,limit);
        String sha512=hashes.optString("sha512","").toLowerCase(Locale.US);
        String sha1=hashes.optString("sha1","").toLowerCase(Locale.US);
        if ((sha1.isEmpty() && sha512.isEmpty()) || (!sha1.isEmpty() && !sha1.matches("[a-f0-9]{40}")) ||
            (!sha512.isEmpty() && !sha512.matches("[a-f0-9]{128}"))) throw new IOException("缺少有效的整合包文件校验值");
        IOException last=new IOException("整合包文件缺少下载地址");
        for (int i=0;i<Math.min(urls.length(),8);i++) {
            VerifiedDownloads.interrupted();
            try (InputStream in=source.file(urls.getString(i)); OutputStream out=new FileOutputStream(dest)) {
                if (VerifiedDownloads.transfer(in,out,length)!=length) throw new IOException("整合包文件长度不符");
            } catch (IOException e) { last=e; continue; }
            if ((sha512.isEmpty() || VerifiedDownloads.digest(dest,"SHA-512").equals(sha512)) &&
                (sha1.isEmpty() || VerifiedDownloads.digest(dest,"SHA-1").equals(sha1))) return;
            last=new IOException("整合包文件校验失败 "+dest.getName());
        }
        throw last;
    }

    private static final class Plan {
        final String game,name;
        String loader,pin;
        final Map<String,JSONObject> files=new LinkedHashMap<>();
        Plan(JSONObject index) throws Exception {
            if (index.getInt("formatVersion")!=1 || !"minecraft".equals(index.getString("game")))
                throw new IOException("不支持的整合包格式");
            name=index.getString("name").trim();
            if (name.isEmpty() || index.getString("versionId").isEmpty()) throw new IOException("整合包名称或版本为空");
            JSONObject deps=index.getJSONObject("dependencies"); game=deps.getString("minecraft");
            for (Iterator<?> keys=deps.keys();keys.hasNext();) {
                String key=(String)keys.next();
                if ("minecraft".equals(key)) continue;
                if ((!key.equals("fabric-loader") && !key.equals("quilt-loader")) || loader!=null)
                    throw new IOException("尚不支持的整合包组件 "+key);
                loader=key.substring(0,key.indexOf('-')); pin=deps.getString(key);
                if (!pin.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}")) throw new IOException("加载器版本无效");
            }
            JSONArray entries=index.getJSONArray("files");
            if (entries.length()>ENTRY_LIMIT) throw new IOException("整合包文件过多");
            Set<String> seen=new HashSet<>();
            for (int i=0;i<entries.length();i++) {
                JSONObject f=entries.getJSONObject(i);
                String path=f.getString("path"); validatePath(path);
                JSONObject env=f.has("env") ? f.getJSONObject("env") : null;
                String side=env==null ? "required" : env.getString("client");
                if ("optional".equals(side) || "unsupported".equals(side)) continue;
                if (!"required".equals(side)) throw new IOException("无法识别客户端文件要求 "+side);
                contentPath(path);
                if (!seen.add(path.toLowerCase(Locale.US))) throw new IOException("整合包列出重名文件 "+path);
                files.put(path,f);
            }
        }
    }

    private static Map<String,ZipEntry> entries(ZipFile zip) throws IOException {
        if (zip.size()>ENTRY_LIMIT) throw new IOException("整合包 ZIP 条目过多");
        Map<String,ZipEntry> result=new LinkedHashMap<>(); Set<String> names=new HashSet<>();
        Enumeration<? extends ZipEntry> all=zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry e=all.nextElement();String raw=e.getName();
            String path=e.isDirectory()?raw.substring(0,raw.length()-1):raw; validatePath(path);
            if (!names.add(path.toLowerCase(Locale.US))) throw new IOException("整合包 ZIP 包含重名条目");
            result.put(raw,e);
        }
        return result;
    }
    private static void validatePath(String path) throws IOException {
        String[] parts=path.split("/",-1);
        if (parts.length>48 || path.length()>900) throw new IOException("整合包路径过长");
        for (String p:parts) {
            if (p.isEmpty() || p.length()>240 || p.endsWith(".") || p.endsWith(" ") ||
                p.matches(".*[\\p{Cntrl}\\\\:*?\"<>|].*") ||
                p.matches("(?i)(CON|NUL|PRN|AUX|COM[1-9]|LPT[1-9])(\\..*)?"))
                throw new IOException("整合包路径无效");
        }
    }
    private static void contentPath(String path) throws IOException {
        validatePath(path); int slash=path.indexOf('/');
        boolean ok=slash<0 ? ROOT_FILES.contains(path.toLowerCase(Locale.US)) : CONTENT_DIRS.contains(path.substring(0,slash));
        if (!ok) throw new IOException("整合包文件不属于受支持的游戏内容目录 "+path);
    }
    private static long size(long value,long limit) throws IOException {
        if (value<0 || value>limit) throw new IOException("整合包文件大小无效");
        return value;
    }
    private static void discard(File f) throws IOException {
        // Never follow symlinks when removing our own staging directory.
        if (f.getCanonicalFile().equals(f.getAbsoluteFile()) && f.isDirectory()) {
            File[] children=f.listFiles();
            if (children==null) throw new IOException("无法清理暂存目录");
            for (File child:children) discard(child);
        }
        if (!f.delete() && f.exists()) throw new IOException("无法清理暂存文件 "+f.getName());
    }
}
