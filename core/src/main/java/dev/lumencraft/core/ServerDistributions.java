package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.jar.*;

/** Verified isolated Fabric and Purpur server installations. install.json is published last. */
public final class ServerDistributions {
    private final File root;
    private final VerifiedDownloads transport = new VerifiedDownloads();
    public ServerDistributions(File root) { this.root = root.getAbsoluteFile(); }
    private static final Object INSTALL = new Object();
    private static final String META = "https://meta.fabricmc.net/v2/versions/loader/";

    public File installFabric(String game, VanillaInstaller.Progress progress) throws Exception {
        synchronized (INSTALL) {
            File dir = destination("fabric",game);
            File previous = new File(dir,"loader.json");
            String pinned = previous.isFile() ? VanillaInstaller.readJson(previous).getString("version") : null;
            update(progress,"核对 Fabric 服务端版本",0,1);
            String endpoint = META + game;
            String loader = FabricInstaller.choose(new JSONArray(text(endpoint)),"fabric",game,pinned);
            String url = endpoint + "/" + loader + "/server/json";
            JSONObject profile = new JSONObject(text(url));
            if (!game.equals(profile.getString("inheritsFrom")) || !("fabric-loader-"+loader+"-"+game).equals(profile.getString("id")))
                throw new IOException("Fabric 服务端版本不匹配");
            String target = profile.getString("mainClass");
            if (!target.matches("net\\.fabricmc\\.[A-Za-z0-9_.]+")) throw new IOException("Fabric 启动类无效");
            JSONObject receipt = base(game);
            JSONObject vanilla = receipt.getJSONObject("mojangServer");
            VerifiedDownloads.directory(dir);
            transport.fetch(vanilla.getString("url"),new File(dir,"vanilla.jar"),vanilla.getString("sha1"),vanilla.getLong("size"));
            JSONArray libraries = profile.getJSONArray("libraries");
            StringBuilder paths = new StringBuilder();
            String main = null;
            for (int i=0;i<libraries.length();i++) {
                JSONObject library = libraries.getJSONObject(i);
                String coordinate = library.getString("name");
                String path = FabricInstaller.mavenPath(coordinate);
                if (!"https://maven.fabricmc.net/".equals(library.getString("url"))) throw new IOException("不支持的 Fabric 服务端依赖源");
                String address = "https://maven.fabricmc.net/" + path;
                String digest = FabricInstaller.checksum(transport,library,address);
                String relative = "libraries/" + path;
                File file = VanillaInstaller.safe(dir,relative);
                transport.fetch(address,file,digest,library.optLong("size",-1));
                if (paths.length()>0) paths.append(' ');
                paths.append(relative);
                if (coordinate.equals("net.fabricmc:fabric-loader:"+loader)) {
                    try (JarFile contents = new JarFile(file)) {
                        Manifest manifest = contents.getManifest();
                        if (manifest != null) main = manifest.getMainAttributes().getValue("Main-Class");
                    }
                }
                update(progress,"校验 Fabric 服务端运行库",i+1,libraries.length());
            }
            if (main == null || !main.matches("net\\.fabricmc\\.[A-Za-z0-9_.]+")) throw new IOException("Fabric Loader 缺少启动类");
            File settings = new File(dir,"fabric-server-launcher.properties");
            if (!settings.exists()) {
                File temp = File.createTempFile("fabric-",".tmp",dir);
                try (FileOutputStream out = new FileOutputStream(temp)) { out.write("serverJar=vanilla.jar\n".getBytes(StandardCharsets.UTF_8)); }
                VerifiedDownloads.replace(temp,settings);
            } else {
                Properties values = new Properties();
                try (InputStream in = new FileInputStream(settings)) { values.load(in); }
                if (!"vanilla.jar".equals(values.getProperty("serverJar"))) throw new IOException("已有 Fabric 配置指定了其他服务端 JAR，请检查实例");
            }
            File launcher = File.createTempFile("fabric-",".jar",dir);
            try {
                Manifest manifest = new Manifest();
                Attributes attrs = manifest.getMainAttributes();
                attrs.putValue("Manifest-Version","1.0");
                attrs.putValue("Main-Class",main);
                attrs.putValue("Class-Path",paths.toString());
                try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(launcher),manifest)) {
                    jar.putNextEntry(new JarEntry("fabric-server-launch.properties"));
                    jar.write(("launch.mainClass="+target+"\n").getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                }
                VerifiedDownloads.replace(launcher,new File(dir,"server.jar"));
            } finally { if (launcher.exists()) launcher.delete(); }
            finish(dir,receipt,"fabric",loader);
            return dir;
        }
    }

    public static boolean supportsPurpur(String game) {
        return "1.20.1".equals(game) || "1.21.1".equals(game);
    }

    public File installPurpur(String game, VanillaInstaller.Progress progress) throws Exception {
        synchronized (INSTALL) {
            if (!supportsPurpur(game)) throw new IOException("此版本尚无已校验的 Purpur 发行文件");
            File dir = destination("purpur",game);
            boolean older = "1.20.1".equals(game);
            String build = older ? "2062" : "2329";
            String digest = older ? "238e64b33e5c7c9b87506cf1a23f239e6ce7fb71edd9d31837f229c94f4dfa1a" : "30403cf54f981f16e1403f172645e82d3e4a59ad6c9f1d8e98df99edb1f8ae4c";
            long size = older ? 46903164L : 53071332L;
            String md5 = older ? "59bbff70cc394c053a71baccc428063b" : "dc0026462cac76c869335feb43388a76";
            String api = "https://api.purpurmc.org/v2/purpur/"+game+"/"+build;
            update(progress,"核对 Purpur 官方构建",0,1);
            JSONObject meta = new JSONObject(text(api));
            if (!game.equals(meta.getString("version")) || !"purpur".equals(meta.getString("project")) ||
                !build.equals(meta.getString("build")) || !"SUCCESS".equals(meta.getString("result")) || !md5.equals(meta.getString("md5")))
                throw new IOException("Purpur 构建元数据不匹配");
            JSONObject receipt = base(game);
            transport.fetch(api+"/download",new File(dir,"server.jar"),digest,size);
            receipt.put("serverSha256",digest);
            finish(dir,receipt,"purpur",build);
            update(progress,"Purpur 服务端已校验",1,1);
            return dir;
        }
    }

    private JSONObject base(String game) throws Exception {
        VanillaInstaller.Version version = null;
        for (VanillaInstaller.Version entry : new VanillaInstaller(root).versions()) if (game.equals(entry.id)) { version=entry;break; }
        if (version == null) throw new IOException("官方清单缺少 "+game);
        String local = "shared/assets/objects/"+version.sha1.substring(0,2)+"/"+version.sha1;
        File metadata = transport.fetch(version.url,new File(root,local),version.sha1,-1);
        JSONObject descriptor = VanillaInstaller.readJson(metadata);
        if (!game.equals(descriptor.getString("id"))) throw new IOException("Mojang 元数据版本不匹配");
        return new JSONObject().put("format",1).put("id",game).put("kind","server").put("metadata",local)
                .put("javaMajor",descriptor.getJSONObject("javaVersion").getInt("majorVersion")).put("metadataSha1",version.sha1)
                .put("mojangServer",descriptor.getJSONObject("downloads").getJSONObject("server"));
    }
    private File destination(String distribution,String game) throws Exception {
        if (!game.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,100}") || game.contains(".."))
            throw new IOException("无效的游戏版本");
        File dir = VanillaInstaller.safe(new File(root,"servers"),distribution+"-"+game);
        File marker = new File(dir,"install.json");
        if (marker.exists()) {
            JSONObject existing = VanillaInstaller.readJson(marker);
            if (!distribution.equals(existing.optString("distribution")) || !game.equals(existing.optString("id")))
                throw new IOException("已有其他实例占用此目录");
        }
        return dir;
    }
    private void finish(File dir,JSONObject receipt,String distribution,String version) throws Exception {
        receipt.put("distribution",distribution).put("jar","servers/"+dir.getName()+"/server.jar");
        VanillaInstaller.writeJson(new File(dir,"loader.json"),new JSONObject().put("loader",distribution).put("version",version));
        VanillaInstaller.writeJson(new File(dir,"install.json"),receipt);
    }
    private String text(String url) throws IOException {
        return new String(transport.metadata(url,8*1024*1024),StandardCharsets.UTF_8);
    }
    private static void update(VanillaInstaller.Progress progress,String label,int done,int total) {
        if (progress!=null) progress.update(label,done,total);
    }
}

