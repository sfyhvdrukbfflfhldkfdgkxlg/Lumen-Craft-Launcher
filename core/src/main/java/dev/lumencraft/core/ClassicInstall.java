package dev.lumencraft.core;
import java.io.File;
import org.json.JSONObject;

/** The RubyDung adapter only consumes the original game jar and its embedded textures. */
public final class ClassicInstall {
    public static final String VERSION = "rd-132211";
    private static final String[][] BUILDS = {
        {VERSION,"d090f5d3766a28425316473d9ab6c37234d48b02","393e8d4b4d708587e2accd7c5221db65365e1075"},
        {"rd-132328","4ec49ff663f96e78a5cf0d9538adb9d1358fc485","12dace5a458617d3f90337a7ebde86c0593a6899"},
        {"rd-160052","0cac2ceab812568826c6e5aeb4cf980397550479","b100be8097195b6c9112046dc6a80d326c8df839"},
        {"rd-161348","f22a3882d124ef4468f6eb50b12836c53286e18a","6323bd14ed7f83852e17ebc8ec418e55c97ddfe4"},
        {"rd-20090515","a3165080e2b0bf20519eac5f55ee841f3491e277","6323bd14ed7f83852e17ebc8ec418e55c97ddfe4"}
    };
    public static java.util.List<String> versions() {
        java.util.List<String> ids=new java.util.ArrayList<>();
        for(String[] build:BUILDS) ids.add(build[0]);
        return ids;
    }
    private ClassicInstall() {}
    public static synchronized File install(File root, VanillaInstaller.Progress progress) throws Exception {
        return install(root, VERSION, VERSION+"-mobile", progress);
    }
    public static synchronized File install(File root, String id, String name, VanillaInstaller.Progress progress) throws Exception {
        String[] selected=null;
        for(String[] build:BUILDS) if(build[0].equals(id)) selected=build;
        if(selected==null) throw new java.io.IOException("缺少此版本的运行适配");
        String metadataHash=selected[1], gameHash=selected[2];
        String jsonPath = "shared/assets/objects/"+metadataHash.substring(0,2)+"/"+metadataHash;
        String jarPath = "shared/assets/objects/"+gameHash.substring(0,2)+"/"+gameHash;
        VerifiedDownloads transport = new VerifiedDownloads();
        progress.update("校验官方版本信息",0,2);
        File meta = transport.fetch("https://piston-meta.mojang.com/v1/packages/"+metadataHash+"/"+id+".json",new File(root,jsonPath),metadataHash,-1);
        JSONObject descriptor = VanillaInstaller.readJson(meta);
        JSONObject artifact = descriptor.getJSONObject("downloads").getJSONObject("client");
        if (!gameHash.equals(artifact.getString("sha1")) || !id.equals(descriptor.getString("id")))
            throw new java.io.IOException("游戏与适配模块不匹配");
        progress.update("下载并校验游戏",1,2);
        transport.fetch(artifact.getString("url"),new File(root,jarPath),gameHash,artifact.getLong("size"));
        File instances = new File(root,"instances").getCanonicalFile();
        File instance = new File(instances,name).getCanonicalFile();
        if (!instances.equals(instance.getParentFile())) throw new java.io.IOException("实例目录无效");
        VerifiedDownloads.directory(instance);
        VanillaInstaller.writeJson(new File(instance,"install.json"), new JSONObject().put("format",1)
            .put("kind","client").put("id",id).put("javaMajor",8).put("metadata",jsonPath)
            .put("metadataSha1",metadataHash).put("jar",jarPath).put("adapter","rubydung"));
        progress.update("原版文件已准备",2,2);
        return instance;
    }
}
