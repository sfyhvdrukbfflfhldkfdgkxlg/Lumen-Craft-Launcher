package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Private room credentials travel in the invitation only. The installed pack contains public metadata. */
public final class RoomInvite {
    private static final String PREFIX = "LumenCraft/1 ";
    private static final int MOD_LIMIT = 96;
    public static final int MAX_LENGTH = 32768;
    public final String roomCode, game, loader, loaderVersion;
    private final SortedMap<String,String> versions = new TreeMap<>();
    private final Map<String,String> hashes = new HashMap<>();

    private RoomInvite(String code, String game, String loader, String loaderVersion) throws IOException {
        if (code.isEmpty() || code.length()>512 || code.matches("(?s).*\\p{Cntrl}.*"))
            throw new IOException("陶瓦联机码无效");
        this.roomCode=code; this.game=game; this.loader=loader; this.loaderVersion=loaderVersion;
    }

    public boolean hasEnvironment() { return game!=null; }
    public int modCount() { return versions.size(); }

    private static String token(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+ -]{0,127}") || value.contains(".."))
            throw new IOException("邀请中的版本标识无效");
        return value;
    }

    public static RoomInvite parse(String input) throws Exception {
        if (input==null || input.length()>MAX_LENGTH) throw new IOException("房间邀请为空或过长");
        String value=input.trim();
        if (!value.startsWith(PREFIX)) return new RoomInvite(value,null,null,null);
        JSONObject spec=new JSONObject(value.substring(PREFIX.length()));
        String game=token(spec.getString("game"));
        String loader=spec.getString("loader");
        if (!Arrays.asList("vanilla","fabric","quilt").contains(loader)) throw new IOException("尚不支持邀请中的加载器");
        String pin=loader.equals("vanilla") ? null : token(spec.getString("loaderVersion"));
        if (pin==null && spec.has("loaderVersion")) throw new IOException("原版邀请含有加载器版本");
        RoomInvite invite=new RoomInvite(spec.getString("room"),game,loader,pin);
        JSONArray mods=spec.getJSONArray("mods");
        if (mods.length()>MOD_LIMIT || (pin==null && mods.length()>0)) throw new IOException("模组数量或加载器不匹配");
        for (int n=0;n<mods.length();n++) {
            JSONArray mod=mods.getJSONArray(n);
            if (mod.length()!=3) throw new IOException("邀请缺少模组版本或摘要");
            String id=Modrinth.id(mod.getString(0)), version=Modrinth.id(mod.getString(1)), hash=mod.getString(2);
            if (!hash.matches("[a-f0-9]{128}") || invite.versions.put(id,version)!=null)
                throw new IOException("重复项目或无效的文件摘要");
            invite.hashes.put(id,hash);
        }
        return invite;
    }

    public static String share(String roomCode, File clientInstance) throws Exception {
        return share(roomCode,clientInstance,Modrinth.LIVE);
    }

    static String share(String code, File instance, Modrinth.Source source) throws Exception {
        instance=instance.getCanonicalFile();
        JSONObject install=VanillaInstaller.readJson(new File(instance,"install.json"));
        if (!"client".equals(install.getString("kind"))) throw new IOException("请选择客户端实例");
        JSONObject wire=new JSONObject().put("room",code).put("game",install.getString("id")).put("loader","vanilla");
        File loaderFile=new File(instance,"loader.json");
        if (loaderFile.exists()) {
            JSONObject loader=VanillaInstaller.readJson(loaderFile);
            if (!install.getString("id").equals(loader.getString("game"))) throw new IOException("实例的加载器与游戏版本不符");
            wire.put("loader",loader.getString("loader")).put("loaderVersion",loader.getString("version"));
        }
        JSONArray list=new JSONArray();
        File mods=new File(instance,"mods");
        if (mods.exists()) {
            if (!mods.getCanonicalFile().equals(mods)) throw new IOException("模组目录不能是符号链接");
            File[] jars=mods.listFiles((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if (jars==null || jars.length>MOD_LIMIT) throw new IOException("无法读取模组目录或模组超过 96 个");
            Arrays.sort(jars);
            for (File jar:jars) {
                VerifiedDownloads.interrupted();
                if (!jar.isFile() || !jar.getCanonicalFile().equals(jar.getAbsoluteFile()) || jar.length()>(1L<<30))
                    throw new IOException("无效的模组文件："+jar.getName());
                String hash=VerifiedDownloads.digest(jar,"SHA-512");
                JSONObject version;
                try { version=new JSONObject(source.json("/version_file/"+hash+"?algorithm=sha512")); }
                catch (IOException e) { throw new IOException("无法在 Modrinth 确认模组 "+jar.getName(),e); }
                if (Modrinth.serverOnly(version)) continue;
                String project=Modrinth.id(version.getString("project_id"));
                JSONObject meta=new JSONObject(source.json("/project/"+project));
                if (!project.equals(meta.getString("id")) || !"mod".equals(meta.getString("project_type")))
                    throw new IOException("文件所属模组项目无效："+jar.getName());
                if ("unsupported".equals(meta.optString("client_side"))) continue;
                JSONObject file=Modrinth.primary(version);
                if (!hash.equals(file.getJSONObject("hashes").getString("sha512")) || jar.length()!=file.getLong("size"))
                    throw new IOException("该模组不是可精确复现的发布主文件："+jar.getName());
                list.put(new JSONArray().put(project).put(version.getString("id")).put(hash));
            }
        }
        String invitation=PREFIX+wire.put("mods",list);
        parse(invitation);
        return invitation;
    }

    public File install(File root, VanillaInstaller.Progress progress) throws Exception {
        return install(root,progress,Modrinth.LIVE,(base,stage,id,engine,pin,report)->{
            if (ClassicInstall.versions().contains(id) && engine==null) ClassicInstall.install(base,id,stage.getName(),report);
            else new VanillaInstaller(base).install(id,stage.getName(),false,report);
            if (engine!=null) FabricInstaller.install(base,stage,id,engine,pin,report);
        });
    }

    File install(File root, VanillaInstaller.Progress progress, Modrinth.Source source, PackImport.Components components) throws Exception {
        if (!hasEnvironment()) throw new IOException("此邀请只包含联机码");
        VanillaInstaller.Progress report=progress==null ? (s,d,t)->{} : progress;
        Modrinth.Resolver resolver=new Modrinth.Resolver(source,game,loader,new JSONObject(),versions);
        int done=0;
        for (String project:versions.keySet()) {
            VerifiedDownloads.interrupted();
            report.update("解析房间模组及前置依赖",done++,versions.size());
            resolver.add(project,versions.get(project),"mod");
        }
        JSONObject receipts=new JSONObject(), deps=new JSONObject().put("minecraft",game);
        if (loaderVersion!=null) deps.put(loader+"-loader",loaderVersion);
        JSONArray files=new JSONArray();
        for (Modrinth.Entry entry:new TreeMap<>(resolver.entries).values()) {
            if (hashes.containsKey(entry.project) && !hashes.get(entry.project).equals(entry.hash))
                throw new IOException("模组发布文件与房主分享的摘要不符："+entry.project);
            receipts.put(entry.project,entry.receipt());
            files.put(new JSONObject().put("path",entry.path).put("fileSize",entry.size)
                .put("downloads",new JSONArray().put(entry.url)).put("hashes",new JSONObject().put("sha512",entry.hash)));
        }
        resolver.checkConflicts(receipts);
        JSONObject index=new JSONObject().put("formatVersion",1).put("game","minecraft").put("name","房间 · "+game)
            .put("versionId","1").put("dependencies",deps).put("files",files);
        ByteArrayOutputStream archive=new ByteArrayOutputStream();
        try (ZipOutputStream out=new ZipOutputStream(archive)) {
            ZipEntry item=new ZipEntry("modrinth.index.json");
            // Fixed DOS timestamp keeps repeated imports of the same manifest idempotent on this device.
            Calendar calendar=new GregorianCalendar(2000,Calendar.JANUARY,1,0,0,0);
            item.setTime(calendar.getTimeInMillis());
            out.putNextEntry(item);out.write(index.toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();
        }
        return PackImport.installRoom(root,new ByteArrayInputStream(archive.toByteArray()),report,source,components,game);
    }
}
