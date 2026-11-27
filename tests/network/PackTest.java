package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Imports real ZIP bytes with an isolated fixture content source and records exact component requests. */
public final class PackTest {
    private static int passed;
    private static void check(String s, boolean b){ if(!b)throw new AssertionError(s); passed++;System.out.println("PASS "+s); }
    private static void rejects(String name, NetworkTest.Action a)throws Exception{
        try{a.run();}catch(IOException expected){check(name,true);return;}throw new AssertionError(name);
    }
    private static String hex(String alg,byte[] b)throws Exception{
        StringBuilder h=new StringBuilder();for(byte v:MessageDigest.getInstance(alg).digest(b))h.append(String.format("%02x",v));
        return h.toString();
    }
    private static final byte[] BODY="fixture contents".getBytes(StandardCharsets.UTF_8);
    private static JSONObject entry(String path,String side)throws Exception{
        JSONObject h=new JSONObject().put("sha1",hex("SHA-1",BODY)).put("sha512",hex("SHA-512",BODY));
        return new JSONObject().put("path",path).put("fileSize",BODY.length).put("hashes",h)
            .put("downloads",new JSONArray().put("bad-mirror").put("artifact"))
            .put("env",new JSONObject().put("client",side).put("server","optional"));
    }
    private static JSONObject model()throws Exception{
        return new JSONObject().put("formatVersion",1).put("game","minecraft").put("versionId","r1").put("name","测试包")
            .put("dependencies",new JSONObject().put("minecraft","1.20.1").put("fabric-loader","0.15.11"))
            .put("files",new JSONArray().put(entry("mods/Test.jar","required")).put(entry("mods/Optional.jar","optional"))
                .put(entry("mods/Server.jar","unsupported")));
    }
    private static byte[] pack(JSONObject index,String extra)throws Exception{
        ByteArrayOutputStream buf=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(buf)){
            add(out,"modrinth.index.json",index.toString());
            add(out,"server-overrides/config/server.cfg","dedicated");
            add(out,"client-overrides/config/settings.cfg","client");
            add(out,"overrides/config/settings.cfg","common");
            add(out,"overrides/options.txt","fullscreen:true");
            if(extra!=null)add(out,extra,"untrusted");
        }return buf.toByteArray();
    }
    private static void add(ZipOutputStream z,String n,String body)throws Exception{
        z.putNextEntry(new ZipEntry(n));z.write(body.getBytes(StandardCharsets.UTF_8));z.closeEntry();
    }
    private static final class Fixture implements Modrinth.Source,PackImport.Components{
        int calls,downloads;String loader,pin,game;
        public String json(String p)throws IOException{throw new IOException(p);}
        public InputStream file(String u)throws IOException{
            if(!u.equals("artifact"))throw new IOException("unavailable");downloads++;return new ByteArrayInputStream(BODY);
        }
        public void install(File root, File stage,String game,String loader,String pin,VanillaInstaller.Progress p)throws Exception{
            this.game=game;this.pin=pin;this.loader=loader;calls++;
            Files.write(new File(stage,"install.json").toPath(),new JSONObject().put("id",game).put("kind","client").toString().getBytes(StandardCharsets.UTF_8));
            if(loader!=null) VanillaInstaller.writeJson(new File(stage,"loader.json"),new JSONObject().put("loader",loader).put("version",pin));
        }
        File run(File root,byte[] archive)throws Exception{return PackImport.install(root,new ByteArrayInputStream(archive),null,this,this);}
        File server(File root,byte[] archive,String game,String digest)throws Exception{
            return PackImport.installServer(root,new ByteArrayInputStream(archive),null,this,this,"play.example:25565",game,digest);
        }
    }
    private static void serverPacks(File root) throws Exception {
        byte[] archive=pack(model(),null);
        String hash=hex("SHA-256",archive), address="play.example:25565";
        List<VanillaInstaller.Version> known=new ArrayList<>();
        for(String id:Arrays.asList("1.20","1.20.1"))
            known.add(new VanillaInstaller.Version(new JSONObject().put("id",id).put("type","release").put("sha1","unused").put("url","https://example.invalid")));
        JSONObject listing=new JSONObject().put("schema",1).put("port",25565).put("minecraft","1.20.1")
            .put("modrinthPack",new JSONObject().put("name","测试包").put("url","https://play.example/p.mrpack")
            .put("sha256",hash.toUpperCase(Locale.ROOT)).put("size",archive.length));
        ServerPack manifest=new ServerPack(address,listing,25565,"Velocity multi-version",known);
        check("explicit known manifest resolves ambiguous status",manifest.game.equals("1.20.1") && manifest.sha256.equals(hash));
        rejects("reject exact status conflict",()->new ServerPack(address,listing,25565,"1.20",known));
        rejects("reject wrong game port",()->new ServerPack(address,listing,25564,"1.20.1",known));
        rejects("reject fractional schema",()->new ServerPack(address,new JSONObject(listing.toString()).put("schema",1.1),25565,"1.20.1",known));
        rejects("reject unknown manifest game",()->new ServerPack(address,new JSONObject(listing.toString()).put("minecraft","future"),25565,"proxy",known));
        JSONObject insecure=new JSONObject(listing.toString());
        insecure.getJSONObject("modrinthPack").put("url","http://play.example/p.mrpack");
        rejects("require HTTPS pack URL",()->new ServerPack(address,insecure,25565,"1.20.1",known));
        Fixture f=new Fixture();
        rejects("pin archive digest at handoff",()->f.server(root,archive,manifest.game,hex("SHA-256",new byte[]{0})));
        rejects("pin inner Minecraft version before installing",()->f.server(root,archive,"1.20",hash));
        check("rejected manifests downloaded no components",f.calls==0 && f.downloads==0);
        File installed=f.server(root,archive,manifest.game,manifest.sha256);
        check("pin loader and store address",f.pin.equals("0.15.11") &&
              VanillaInstaller.readJson(new File(installed,"pack.json")).getString("serverAddress").equals(address));
        File user=new File(installed,"options.txt");
        Files.write(user.toPath(),"custom".getBytes(StandardCharsets.UTF_8));
        check("repeat exact server install reuses committed instance",f.server(root,archive,manifest.game,hash).equals(installed)
               && f.calls==1 && f.downloads==1 && user.length()==6);
        JSONObject broken=model().put("versionId","broken-update");
        broken.getJSONArray("files").getJSONObject(0).getJSONObject("hashes").put("sha1",hex("SHA-1",new byte[]{1}));
        byte[] bad=pack(broken,null);
        rejects("failed update is never published",()->f.server(root,bad,manifest.game,hex("SHA-256",bad)));
        check("failed server update retains old world and removes staging",new File(root,"instances").list().length==1 && user.length()==6);
        byte[] updated=pack(model().put("versionId","r2"),null);
        File two=f.server(root,updated,manifest.game,hex("SHA-256",updated));
        check("changed pack creates isolated revision",!two.equals(installed) && new File(root,"instances").list().length==2 && user.length()==6);
        VanillaInstaller.writeJson(new File(installed,"loader.json"),new JSONObject().put("loader","fabric").put("version","other"));
        rejects("do not reuse wrong loader version",()->f.server(root,archive,manifest.game,hash));
        VanillaInstaller.writeJson(new File(installed,"loader.json"),new JSONObject().put("loader","fabric").put("version","0.15.11"));
        VanillaInstaller.writeJson(new File(installed,"pack.json"),new JSONObject().put("archiveSha256",hash).put("serverAddress","other.example"));
        rejects("do not reuse wrong server address",()->f.server(root,archive,manifest.game,hash));
        File parallelRoot=new File(root,"parallel");
        Fixture parallel=new Fixture();
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<File> same=()->{ start.await(); return parallel.server(parallelRoot,archive,manifest.game,hash); };
            java.util.concurrent.Future<File> a=pool.submit(same), b=pool.submit(same);
            start.countDown();
            check("parallel callers commit one copy",a.get().equals(b.get()) && parallel.calls==1 && parallel.downloads==1 &&
                  new File(parallelRoot,"instances").list().length==1);
        } finally { pool.shutdownNow(); }
    }
    private static void multiFormatAndAutoModpack(File root) throws Exception {
        Fixture f=new Fixture();
        // CurseForge ZIP
        ByteArrayOutputStream cfBuf=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(cfBuf)){
            add(z,"manifest.json","{\"name\":\"CFPack\",\"minecraft\":{\"version\":\"1.20.1\",\"modLoaders\":[{\"id\":\"forge-47.3.0\"}]},\"overrides\":\"overrides\",\"files\":[{\"projectID\":1,\"fileID\":2}]}");
            add(z,"overrides/config/cf.toml","ok=true");
        }
        InstanceMigration.PackSummary cf=InstanceMigration.importAnyPack(root,new ByteArrayInputStream(cfBuf.toByteArray()),null,f);
        check("CurseForge pack format import","curseforge".equals(cf.format) && "forge".equals(cf.loader) && cf.pendingExternalMods==1 && new File(cf.instanceDir,"config/cf.toml").isFile());

        // MultiMC ZIP
        ByteArrayOutputStream mmcBuf=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(mmcBuf)){
            add(z,"Demo/mmc-pack.json","{\"components\":[{\"uid\":\"net.minecraft\",\"version\":\"1.21.1\"},{\"uid\":\"net.neoforged\",\"version\":\"21.1.85\"}]}");
            add(z,"Demo/instance.cfg","name=PrismPack\n");
            add(z,"Demo/.minecraft/options.txt","fov:0.5\n");
        }
        InstanceMigration.PackSummary mmc=InstanceMigration.importAnyPack(root,new ByteArrayInputStream(mmcBuf.toByteArray()),null,f);
        check("MultiMC/Prism pack format import","multimc".equals(mmc.format) && "neoforge".equals(mmc.loader) && new File(mmc.instanceDir,"options.txt").isFile());

        // HMCL ZIP
        ByteArrayOutputStream hmclBuf=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(hmclBuf)){
            add(z,"mcbbs.packmeta","{\"name\":\"HMCLPack\",\"addons\":[{\"id\":\"game\",\"version\":\"1.20.1\"},{\"id\":\"fabric\",\"version\":\"0.15.11\"}]}");
            add(z,"overrides/config/hmcl.cfg","a=1");
        }
        InstanceMigration.PackSummary hmcl=InstanceMigration.importAnyPack(root,new ByteArrayInputStream(hmclBuf.toByteArray()),null,f);
        check("HMCL pack format import","hmcl".equals(hmcl.format) && "fabric".equals(hmcl.loader) && new File(hmcl.instanceDir,"config/hmcl.cfg").isFile());

        // Directory migration (FCL/PCL)
        File srcDir=new File(root,"legacy-pcl");
        new File(srcDir,"saves/World1").mkdirs();
        Files.write(new File(srcDir,"saves/World1/level.dat").toPath(),new byte[]{7,8,9});
        int migrated=InstanceMigration.migrateDirectory(srcDir,hmcl.instanceDir);
        check("FCL/PCL directory migration",migrated==1 && new File(hmcl.instanceDir,"saves/World1/level.dat").isFile());

        // AutoModpack sync protocol
        byte[] modBytes="amp-client-mod".getBytes(StandardCharsets.UTF_8);
        String modSha=hex("SHA-1",modBytes);
        JSONObject ampJson=new JSONObject().put("modpackName","AMP-Server").put("mcVersion","1.20.1")
                .put("loader","fabric").put("loaderVersion","0.15.11")
                .put("list",new JSONArray()
                        .put(new JSONObject().put("file","/mods/client.jar").put("sha1",modSha).put("size",modBytes.length).put("type","mod"))
                        .put(new JSONObject().put("file","/mods/onlyserver.jar").put("sha1",modSha).put("size",modBytes.length).put("type","server")));
        final Map<String,byte[]> httpMap=new HashMap<>();
        httpMap.put("/amp/content.json",ampJson.toString().getBytes(StandardCharsets.UTF_8));
        httpMap.put("/amp/mods/client.jar",modBytes);
        VerifiedDownloads ampTransport=new VerifiedDownloads(url -> new java.net.HttpURLConnection(url) {
            public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
            public int getResponseCode() { return httpMap.containsKey(url.getPath()) ? 200 : 404; }
            public InputStream getInputStream() { return new ByteArrayInputStream(httpMap.get(url.getPath())); }
        });
        AutoModpackSync.SyncManifest manifest=AutoModpackSync.fetchManifest("https://srv.example/amp/content.json",ampTransport);
        AutoModpackSync.SyncResult sync1=AutoModpackSync.sync(root,"srv.example:25565",manifest,null,ampTransport,f);
        check("AutoModpack client sync & server-only skip",sync1.downloaded==1 && sync1.skippedServerOnly==1 && new File(sync1.instanceDir,"mods/client.jar").isFile() && !new File(sync1.instanceDir,"mods/onlyserver.jar").exists());
        AutoModpackSync.SyncResult sync2=AutoModpackSync.sync(root,"srv.example:25565",manifest,null,ampTransport,f);
        check("AutoModpack idempotent re-sync",sync2.downloaded==0 && sync2.reused==1);
    }
    public static void main(String[] args)throws Exception{
        Path tmp=Files.createTempDirectory("lumen-pack-integration");
        try{
            File root=tmp.toFile();Fixture f=new Fixture();byte[] archive=pack(model(),null);
            File instance=f.run(root,archive);
            check("publish isolated pack instance",instance.isDirectory()&&instance.getName().startsWith("pack-测试包-"));
            check("exact game and Fabric loader pins",f.game.equals("1.20.1")&&f.loader.equals("fabric")&&f.pin.equals("0.15.11"));
            check("mirror fallback and dual digest",Arrays.equals(BODY,Files.readAllBytes(new File(instance,"mods/Test.jar").toPath()))&&f.downloads==1);
            check("skip optional and dedicated files",!new File(instance,"mods/Optional.jar").exists()&&!new File(instance,"mods/Server.jar").exists());
            check("client overrides take precedence",new String(Files.readAllBytes(new File(instance,"config/settings.cfg").toPath()),StandardCharsets.UTF_8).equals("client"));
            check("no dedicated overrides",!new File(instance,"config/server.cfg").exists());
            check("record pack source digest",new File(instance,"pack.json").isFile()&&!new File(instance,".pack-source").exists());
            rejects("existing instance protected",()->f.run(root,archive));
            JSONObject forge=model();forge.getJSONObject("dependencies").put("forge","1");
            rejects("unsupported loader rejected",()->f.run(root,pack(forge,null)));
            rejects("ZIP traversal rejected",()->f.run(root,pack(model(),"overrides/../leave.txt")));
            rejects("protect launcher metadata",()->f.run(root,pack(model(),"overrides/loader.json")));
            JSONObject hostile=model();hostile.getJSONArray("files").getJSONObject(0).put("path","/escape.jar");
            rejects("absolute manifest path rejected",()->f.run(root,pack(hostile,null)));
            JSONObject corrupt=model();corrupt.getJSONArray("files").getJSONObject(0).getJSONObject("hashes")
                .put("sha512",hex("SHA-512",new byte[]{9}));
            rejects("valid SHA1 cannot mask bad SHA512",()->f.run(root,pack(corrupt,null)));
            check("failed imports leave only complete instance",new File(root,"instances").list().length==1);
            serverPacks(new File(root,"server-fixture"));
            multiFormatAndAutoModpack(new File(root,"extended-fixture"));
            if(Arrays.asList(args).contains("--live")){
                File actual=Modrinth.downloadPack("1KVo5zza",root);
                try(ZipFile zip=new ZipFile(actual)){
                    check("live Fabulously Optimized mrpack and index",zip.getEntry("modrinth.index.json")!=null);
                    JSONObject index=new JSONObject(Modrinth.read(zip.getInputStream(zip.getEntry("modrinth.index.json")),4<<20));
                    System.out.println("Live pack "+index.getString("name")+" "+index.getString("versionId")+" / "+actual.length()+" bytes / "+index.getJSONArray("files").length()+" files");
                }
            }
        }finally{try(java.util.stream.Stream<Path> tree=Files.walk(tmp)){
            for(Path p:tree.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);
        }}
        System.out.println("Passed "+passed+" pack checks");
    }
}
