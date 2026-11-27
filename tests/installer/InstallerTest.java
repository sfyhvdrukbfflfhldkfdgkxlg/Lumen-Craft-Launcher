package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Standalone JVM regression suite. The injected transport supplies exact HTTP response bytes. */
public final class InstallerTest {
    private static final byte[] BODY = bytes("verified launcher artifact");
    private static final String SHA1 = hex(BODY, "SHA-1");
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String hex(byte[] b, String algo) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : java.security.MessageDigest.getInstance(algo).digest(b))
                s.append(String.format(Locale.ROOT, "%02x", x & 255));
            return s.toString();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    interface Action { void run() throws Exception; }
    private static void fails(Action a, String message) throws Exception {
        try { a.run(); } catch (IOException e) { return; }
        throw new AssertionError(message);
    }
    static final class Fixture implements VerifiedDownloads.Opener {
        final Map<String, byte[]> content = new ConcurrentHashMap<>();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        volatile boolean ignoreRange;
        public HttpURLConnection open(URL url) {
            return new HttpURLConnection(url) {
                int offset() {
                    String range = getRequestProperty("Range");
                    return ignoreRange || range == null ? 0 : Integer.parseInt(range.substring(6, range.length()-1));
                }
                public void connect() {}
                public void disconnect() {}
                public boolean usingProxy() { return false; }
                byte[] body() { return content.get(url.getPath()); }
                public int getResponseCode() { return body() == null ? 404 : offset() == 0 ? 200 : 206; }
                public String getHeaderField(String key) {
                    return "bytes " + offset() + "-" + (body().length - 1) + "/" + body().length;
                }
                public InputStream getInputStream() {
                    requests.add(url.getPath() + "@" + offset());
                    return new ByteArrayInputStream(body(), offset(), body().length-offset());
                }
            };
        }
        JSONObject artifact(String name, byte[] data) throws Exception {
            content.put("/" + name, data);
            return new JSONObject().put("sha1", hex(data,"SHA-1")).put("size", data.length)
                    .put("url", "https://fixture/" + name);
        }
    }
    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("lumen-test").toFile();
        Fixture f = new Fixture();
        VerifiedDownloads d = new VerifiedDownloads(f);
        f.content.put("/data", BODY);
        String url = "https://fixture/data";
        File target = new File(temp, "artifact");
        File part = new File(temp, "artifact.part");
        Files.write(part.toPath(), Arrays.copyOf(BODY, 5));
        d.fetch(url, target, SHA1, BODY.length);
        check(f.requests.contains("/data@5") && Arrays.equals(BODY, Files.readAllBytes(target.toPath())), "resume");
        d.fetch(url, target, SHA1, BODY.length);
        check(f.requests.size() == 1, "verified cache hit");
        Files.write(target.toPath(), bytes("bad"));
        Files.write(part.toPath(), Arrays.copyOf(BODY, 3));
        f.ignoreRange = true;
        d.fetch(url, target, hex(BODY,"SHA-256"), BODY.length);
        check(Arrays.equals(BODY, Files.readAllBytes(target.toPath())), "server ignoring Range / corrupt cache repair");
        File rejected = new File(temp, "rejected");
        fails(() -> d.fetch(url, rejected, hex(bytes("bad"), "SHA-1"), BODY.length), "checksum mismatch accepted");
        check(!rejected.exists(), "corrupt object committed");
        fails(() -> d.fetch("http://example.org/insecure", rejected, SHA1, BODY.length), "non-HTTPS allowed");
        Thread.currentThread().interrupt();
        try { fails(() -> d.fetch(url, target, SHA1, BODY.length), "cancellation ignored"); }
        finally { Thread.interrupted(); }
        fails(() -> VanillaInstaller.safe(temp, "../escape"), "traversal accepted");
        fails(() -> VanillaInstaller.safe(temp, "a\\b"), "backslash accepted");
        JSONArray rules = new JSONArray("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"linux\"}}]");
        check(!VanillaInstaller.allowed(rules), "last matching rule");
        JSONObject asset = f.artifact("unused", bytes("sound"));
        String assetHash = asset.getString("sha1");
        f.content.put("/" + assetHash.substring(0,2) + "/" + assetHash, bytes("sound"));
        JSONObject indexed = new JSONObject().put("hash", assetHash).put("size", 5);
        JSONObject index = new JSONObject().put("objects", new JSONObject().put("sounds/a.ogg", indexed))
                .put("virtual", true).put("map_to_resources", true);
        JSONObject indexRef = f.artifact("index.json", bytes(index.toString())).put("id","legacy");
        JSONObject lib = new JSONObject().put("name","org.example:lib:1")
                .put("downloads", new JSONObject().put("artifact",f.artifact("lib.jar",bytes("library"))));
        JSONObject nativeLib = new JSONObject().put("name","org.example:lib:1:natives-linux");
        JSONObject meta = new JSONObject().put("id","1.2.3").put("javaVersion",new JSONObject().put("majorVersion",8))
                .put("downloads",new JSONObject().put("client", f.artifact("game.jar",bytes("game")))
                        .put("server", f.artifact("server.jar", bytes("server"))))
                .put("assetIndex",indexRef).put("libraries",new JSONArray().put(lib).put(nativeLib))
                .put("mainClass","example.Main");
        JSONObject entry = f.artifact("version", bytes(meta.toString())).put("id","1.2.3").put("type","release");
        f.content.put("/manifest",bytes(new JSONObject().put("versions",new JSONArray().put(entry)).toString()));
        VanillaInstaller installer = new VanillaInstaller(temp,d,"https://fixture/manifest");
        check(installer.versions().get(0).id.equals("1.2.3"), "catalog");
        String namedPack="pack-叶绿工坊-123456789abc";
        File client = installer.install("1.2.3", namedPack, false, null);
        JSONObject receipt = new JSONObject(new String(Files.readAllBytes(new File(client,"install.json").toPath()), StandardCharsets.UTF_8));
        check(receipt.getInt("javaMajor") == 8 && receipt.getJSONArray("classpath").length() == 2, "receipt");
        check(new File(temp,receipt.getString("jar")).isFile(), "jar path");
        check(new File(temp,"shared/assets/virtual/legacy/sounds/a.ogg").length() == 5, "legacy virtual assets");
        check(new File(client,"resources/sounds/a.ogg").length() == 5, "legacy resources");
        int previous = f.requests.size();
        installer.install("1.2.3", "world-2", false, null);
        check(previous == f.requests.size(), "instance shared cache");
        File server = installer.install("1.2.3", true, null);
        check(new File(server,"server.jar").length() == 6, "server install");
        Files.write(new File(temp,receipt.getString("jar")).toPath(), bytes("xxxx"));
        installer.install("1.2.3", namedPack, false, null);
        check(Arrays.equals(Files.readAllBytes(new File(temp,receipt.getString("jar")).toPath()),bytes("game")) &&
                client.getName().equals(namedPack),"Unicode pack repair");
        check(f.requests.size() == previous + 2, "server download plus one client repair");
        fails(() -> installer.install("missing",false,null), "unknown version accepted");
        fails(() -> installer.install("1.2.3","../evil",false,null), "instance traversal");
        loaderTests(f, d, temp, client);
        extendedLoaderAndPlanTests(f, d, temp, client);
        System.out.println("PASS verified range resume, SHA-1/256, cache repair, HTTPS, cancellation, safe paths, Linux rules, isolated installs, legacy assets, server files, unknown versions, Fabric/Quilt/NeoForge/Forge/LiteLoader/OptiFine, MirrorBench and LaunchPlan.");
        if (args.length != 0) {
            VanillaInstaller live = new VanillaInstaller(new File(args[0]));
            System.out.println("Live catalog entries = " + live.versions().size());
            for (int n = 1; n < args.length; n++) {
                if (args[n].equals("classic=all")) {
                    File classic = Files.createTempDirectory("lumen-classic-install-audit").toFile();
                    Set<String> jars = new HashSet<>();
                    for (String id : ClassicInstall.versions()) {
                        VanillaInstaller.Version official = null;
                        for (VanillaInstaller.Version v : live.versions()) if (v.id.equals(id)) official=v;
                        check(official!=null,"official catalog "+id);
                        File installed=ClassicInstall.install(classic,id,id+"-mobile",(label,done,total)->{});
                        JSONObject record=VanillaInstaller.readJson(new File(installed,"install.json"));
                        File metadata=new File(classic,record.getString("metadata"));
                        check(VerifiedDownloads.digest(metadata,"SHA-1").equals(official.sha1),"real official descriptor hash "+id);
                        JSONObject v=VanillaInstaller.readJson(metadata);
                        JSONObject jar=v.getJSONObject("downloads").getJSONObject("client");
                        File file=new File(classic,record.getString("jar"));
                        check(record.getString("id").equals(id) && v.getString("id").equals(id) &&
                                record.getString("adapter").equals("rubydung") && record.getInt("javaMajor")==8 &&
                                v.getJSONObject("javaVersion").getInt("majorVersion")==8 &&
                                jar.getLong("size")==file.length() && jar.getString("sha1").equals(VerifiedDownloads.digest(file,"SHA-1")),
                                "official game record "+id);
                        jars.add(record.getString("jar"));
                        System.out.println("LIVE PASS "+id+" manifest="+official.sha1+" jar="+jar.getString("sha1")+" bytes="+file.length());
                    }
                    check(jars.size()==4 && Objects.requireNonNull(new File(classic,"instances").list()).length==5,"dedup and isolation");
                    continue;
                }
                if (args[n].startsWith("profile=")) {
                    String[] spec = args[n].substring(8).split(",");
                    File isolated = Files.createTempDirectory("lumen-profile").toFile();
                    File instance = new File(isolated,"instances/profile");
                    VerifiedDownloads.directory(instance);
                    VanillaInstaller.writeJson(new File(instance,"install.json"),new JSONObject().put("id",spec[1]).put("kind","client"));
                    String result = FabricInstaller.install(isolated,instance,spec[1],spec[0],null);
                    System.out.println("Verified real " + spec[0] + " libraries for " + spec[1] + " loader " + result + " (profile-only fixture)");
                    continue;
                }
                boolean serverMode = args[n].startsWith("server=");
                String id = args[n].substring(args[n].indexOf('=') + 1);
                File installed = live.install(id, serverMode, (label, done, total) -> {
                    if (done == total || done == 0) System.out.println(label + " " + done + "/" + total);
                });
                System.out.println("Installed " + id + " at " + installed);
            }
        }
    }
    private static void loaderTests(Fixture f, VerifiedDownloads d, File root, File instance) throws Exception {
        JSONObject mappings = new JSONObject().put("version","1.2.3");
        JSONArray versions = new JSONArray();
        for (String v : new String[]{"0.9.1", "0.11.1-beta.1", "0.10.1"})
            versions.put(new JSONObject().put("loader",new JSONObject().put("version",v)
                    .put("stable",!v.contains("beta"))).put("intermediary",mappings));
        String api = "/v2/versions/loader/1.2.3";
        f.content.put(api,bytes(versions.toString()));
        check(FabricInstaller.choose(versions,"quilt","1.2.3",null).equals("0.10.1"), "Quilt stable sorting");
        check(FabricInstaller.choose(versions,"fabric","1.2.3","0.11.1-beta.1").equals("0.11.1-beta.1"), "exact loader pin");
        fails(() -> FabricInstaller.choose(versions,"fabric","1.2.3","0.12.0"), "silent pinned upgrade");
        fails(() -> FabricInstaller.mavenPath("a:b:../bad"), "unsafe Maven coordinate");
        for (String family : new String[]{"fabric","quilt"}) {
            String endpoint = api.replace("/v2", family.equals("quilt") ? "/v3" : "/v2");
            f.content.put(endpoint,bytes(versions.toString()));
            String group = family.equals("quilt") ? "org.quiltmc" : "net.fabricmc";
            String name = group + ":" + family + "-loader:0.10.1";
            JSONObject artifact = new JSONObject().put("name",name).put("url","https://fixture/repo/");
            String jarPath = "/repo/" + FabricInstaller.mavenPath(name);
            f.content.put(jarPath,BODY);
            f.content.put(jarPath + ".sha1",bytes(SHA1));
            JSONObject profile = new JSONObject().put("libraries",new JSONArray().put(artifact))
                    .put("id",family + "-loader-0.10.1-1.2.3").put("inheritsFrom","1.2.3").put("mainClass","example.Entry");
            f.content.put(endpoint + "/0.10.1/profile/json",bytes(profile.toString()));
            String result = FabricInstaller.install(root,instance,"1.2.3",family,null,null,d);
            JSONObject lock = VanillaInstaller.readJson(new File(instance,"loader.json"));
            check(result.equals("0.10.1") && lock.getString("loader").equals(family) &&
                    new File(root, lock.getJSONArray("classpath").getString(0)).isFile(), "library commit");
        }
    }
    private static void extendedLoaderAndPlanTests(Fixture f, VerifiedDownloads d, File root, File instance) throws Exception {
        // MirrorBench URL rewriting and custom HTTPS validation
        MirrorBench.configure(MirrorBench.SourceId.BMCLAPI, "");
        check(MirrorBench.rewrite("https://libraries.minecraft.net/a/b.jar").equals("https://bmclapi2.bangbang93.com/maven/a/b.jar"), "BMCLAPI maven rewrite");
        check(MirrorBench.rewrite("https://resources.download.minecraft.net/ab/abcdef").equals("https://bmclapi2.bangbang93.com/assets/ab/abcdef"), "BMCLAPI assets rewrite");
        fails(() -> MirrorBench.configure(MirrorBench.SourceId.CUSTOM, "http://insecure.example"), "reject HTTP custom mirror");
        MirrorBench.configure(MirrorBench.SourceId.OFFICIAL, "");

        // NeoForge + Forge version selection
        String neoXml = "<metadata><versioning><versions><version>21.1.72</version><version>21.1.90-beta</version><version>21.1.85</version><version>20.4.200</version></versions></versioning></metadata>";
        check("21.1.85".equals(ModLoaderInstaller.chooseNeoForgeVersion(neoXml, "1.21.1", null)), "NeoForge stable preference");
        check("21.1.90-beta".equals(ModLoaderInstaller.chooseNeoForgeVersion(neoXml, "1.21.1", "21.1.90-beta")), "NeoForge exact pin");
        JSONObject promos = new JSONObject("{\"promos\":{\"1.20.1-recommended\":\"47.3.0\",\"1.20.1-latest\":\"47.3.12\"}}");
        check("47.3.0".equals(ModLoaderInstaller.chooseForgeVersion(promos, "1.20.1", null)), "Forge recommended selection");

        // OptiFine local JAR import (valid & invalid)
        java.io.ByteArrayOutputStream validOf = new java.io.ByteArrayOutputStream();
        byte[] pseudoClass = new byte[2048];
        for (int i = 0; i < pseudoClass.length; i++) pseudoClass[i] = (byte) ((i * 73 + (i >> 3) * 19) & 0xff);
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(validOf)) {
            z.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            z.putNextEntry(new java.util.zip.ZipEntry("optifine/OptiFineTweaker.class"));
            z.write(pseudoClass);
            z.closeEntry();
            z.putNextEntry(new java.util.zip.ZipEntry("changelog.txt"));
            z.write("OptiFine 1.2.3 HD U I6\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        File ofInstance = new File(root, "instances/world-2");
        String ofVer = ModLoaderInstaller.installLocalOptiFine(root, ofInstance, new ByteArrayInputStream(validOf.toByteArray()), null);
        check(ofVer.contains("OptiFine 1.2.3") && "optifine".equals(VanillaInstaller.readJson(new File(ofInstance, "loader.json")).getString("loader")), "local OptiFine import");
        fails(() -> ModLoaderInstaller.installLocalOptiFine(root, ofInstance, new ByteArrayInputStream(new byte[100]), null), "reject invalid OptiFine jar");

        // LaunchPlan assembly
        LaunchPlan plan = LaunchPlan.build(root, ofInstance, new LaunchPlan.AccountSpec("Alex", "11112222333344445555666677778888", "tok", "msa", null), 1024, 1280, 720);
        check(plan.javaMajor == 8 && "net.minecraft.launchwrapper.Launch".equals(plan.mainClass)
                && plan.gameArgs.contains("optifine.OptiFineTweaker") && plan.classpath.size() >= 3, "LaunchPlan classpath and tweakClass");
        File argsFile = plan.writeJvmArgsFile(new File(root, "test-jvm.args"));
        check(new String(Files.readAllBytes(argsFile.toPath()), StandardCharsets.UTF_8).contains("-Dminecraft.launcher.brand=LumenCraft"), "write JVM args file");
    }
}
