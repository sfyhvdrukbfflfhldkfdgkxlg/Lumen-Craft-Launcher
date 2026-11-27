package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verified installers for NeoForge, Forge, LiteLoader, Cleanroom, and local OptiFine archives. */
public final class ModLoaderInstaller {
    private static final String NEOFORGE_META = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String NEOFORGE_REPO = "https://maven.neoforged.net/releases/";
    private static final String FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String FORGE_REPO = "https://maven.minecraftforge.net/";
    private static final String LITELOADER_VERSIONS = "https://dl.liteloader.com/versions/versions.json";
    private static final String CLEANROOM_RELEASES = "https://api.github.com/repos/CleanroomMC/Cleanroom/releases?per_page=10";
    private static final Pattern XML_VERSION = Pattern.compile("<version>([^<]+)</version>");

    private ModLoaderInstaller() {}

    public static String install(File root, File instance, String game, String loader, String pinned,
                                 VanillaInstaller.Progress progress) throws Exception {
        if ("fabric".equals(loader) || "quilt".equals(loader)) {
            return FabricInstaller.install(root, instance, game, loader, pinned, progress);
        }
        return install(root, instance, game, loader, pinned, progress, new VerifiedDownloads());
    }

    static String install(File root, File instance, String game, String loader, String pinned,
                          VanillaInstaller.Progress progress, VerifiedDownloads transport) throws Exception {
        if ("fabric".equals(loader) || "quilt".equals(loader)) {
            return FabricInstaller.install(root, instance, game, loader, pinned, progress, transport);
        }
        verifyTargetInstance(root, instance, game);
        if (progress == null) progress = (l, d, t) -> {};
        switch (loader) {
            case "neoforge": return installNeoForge(root, instance, game, pinned, progress, transport);
            case "forge": return installForge(root, instance, game, pinned, progress, transport);
            case "liteloader": return installLiteLoader(root, instance, game, pinned, progress, transport);
            case "cleanroom": return installCleanroom(root, instance, game, pinned, progress, transport);
            default: throw new IOException("不支持的加载器 " + loader);
        }
    }

    /** Imports a user-supplied official OptiFine installer JAR into a verified client instance. */
    public static String installLocalOptiFine(File root, File instance, InputStream jarStream,
                                              VanillaInstaller.Progress progress) throws Exception {
        if (progress == null) progress = (l, d, t) -> {};
        File canonicalInstance = instance.getCanonicalFile();
        File instances = new File(root, "instances").getCanonicalFile();
        if (!instances.equals(canonicalInstance.getParentFile())) {
            throw new IOException("OptiFine 只能安装到独立游戏实例");
        }
        JSONObject base = VanillaInstaller.readJson(new File(canonicalInstance, "install.json"));
        String game = base.getString("id");
        if (!"client".equals(base.getString("kind"))) throw new IOException("请选择客户端实例");
        progress.update("校验 OptiFine 安装包", 0, 2);

        File stage = File.createTempFile("optifine-", ".jar.tmp", canonicalInstance);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long bytes;
            try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(stage)), sha)) {
                bytes = VerifiedDownloads.transfer(jarStream, out, 64L << 20);
            }
            if (bytes < 1024) throw new IOException("OptiFine JAR 文件不完整");
            String version = null;
            boolean hasTweaker = false;
            try (ZipFile zip = new ZipFile(stage)) {
                if (zip.size() > 20000) throw new IOException("OptiFine 归档条目过多");
                hasTweaker = zip.getEntry("optifine/OptiFineTweaker.class") != null
                        || zip.getEntry("optifine/OptiFineForgeTweaker.class") != null
                        || zip.getEntry("optifine/OptiFineClassTransformer.class") != null;
                if (!hasTweaker) throw new IOException("未在 JAR 中识别到官方 OptiFine 组件");
                ZipEntry changelog = zip.getEntry("changelog.txt");
                if (changelog != null && changelog.getSize() > 0 && changelog.getSize() < 1024 * 1024) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(zip.getInputStream(changelog), StandardCharsets.UTF_8))) {
                        String first = br.readLine();
                        if (first != null && !first.trim().isEmpty()) version = first.trim();
                    }
                }
            }
            if (version == null) version = "local-" + game;
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            String hash = hex.toString();
            String relative = "shared/loaders/" + hash.substring(0, 2) + "/" + hash + ".jar";
            File stored = new File(root, relative);
            VerifiedDownloads.directory(stored.getParentFile());
            if (!stored.isFile()) VerifiedDownloads.replace(stage, stored);

            File existingLoader = new File(canonicalInstance, "loader.json");
            if (existingLoader.isFile()) {
                JSONObject prev = VanillaInstaller.readJson(existingLoader);
                String prevLoader = prev.optString("loader");
                if ("forge".equals(prevLoader) || "neoforge".equals(prevLoader)) {
                    File modsDir = new File(canonicalInstance, "mods");
                    VerifiedDownloads.directory(modsDir);
                    File modDst = new File(modsDir, "OptiFine-" + hash.substring(0, 12) + ".jar");
                    copyFile(stored, modDst);
                    progress.update("OptiFine 已添加为模组", 2, 2);
                    return version;
                }
            }
            JSONObject receipt = new JSONObject()
                    .put("format", 1)
                    .put("loader", "optifine")
                    .put("version", version)
                    .put("game", game)
                    .put("mainClass", "net.minecraft.launchwrapper.Launch")
                    .put("tweakClass", "optifine.OptiFineTweaker")
                    .put("sha256", hash)
                    .put("classpath", new JSONArray().put(relative));
            VanillaInstaller.writeJson(existingLoader, receipt);
            progress.update("OptiFine 已安装", 2, 2);
            return version;
        } finally {
            if (stage.exists()) stage.delete();
        }
    }

    static String neoForgePrefix(String game) throws IOException {
        // Minecraft 1.20.2+ maps to NeoForge 20.2.x; 1.21 -> 21.0.x; 1.21.1 -> 21.1.x.
        String[] parts = game.split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) {
            throw new IOException("NeoForge 不支持该 Minecraft 版本格式 " + game);
        }
        String minor = parts[1];
        String patch = parts.length >= 3 ? parts[2] : "0";
        if (!minor.matches("[0-9]+") || !patch.matches("[0-9]+")) {
            throw new IOException("NeoForge 仅支持正式版本号 " + game);
        }
        return minor + "." + patch + ".";
    }

    static String chooseNeoForgeVersion(String xml, String game, String pinned) throws IOException {
        String prefix = neoForgePrefix(game);
        Matcher m = XML_VERSION.matcher(xml);
        String bestStable = null;
        String bestAny = null;
        while (m.find()) {
            String v = m.group(1).trim();
            if (!v.matches("[A-Za-z0-9._+-]{1,128}") || v.contains("..")) continue;
            if (pinned != null) {
                if (pinned.equals(v)) return v;
                continue;
            }
            if (!v.startsWith(prefix)) continue;
            if (bestAny == null || compareNumericPrefix(v, bestAny) > 0) bestAny = v;
            if (!v.contains("-")) {
                if (bestStable == null || compareNumericPrefix(v, bestStable) > 0) bestStable = v;
            }
        }
        String chosen = bestStable != null ? bestStable : bestAny;
        if (chosen == null) throw new IOException("NeoForge 官方仓库没有适配 " + game + " 的版本");
        return chosen;
    }

    private static String installNeoForge(File root, File instance, String game, String pinned,
                                          VanillaInstaller.Progress progress, VerifiedDownloads transport) throws Exception {
        progress.update("查询 NeoForge 版本", 0, 1);
        String xml = new String(transport.metadata(NEOFORGE_META, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        String ver = chooseNeoForgeVersion(xml, game, pinned);
        String installerUrl = NEOFORGE_REPO + "net/neoforged/neoforge/" + ver + "/neoforge-" + ver + "-installer.jar";
        String digest = mavenChecksum(transport, installerUrl);
        String installerRel = "shared/loaders/" + digest.substring(0, 2) + "/" + digest + ".jar";
        progress.update("下载并校验 NeoForge 安装器", 0, 1);
        File installerJar = transport.fetch(installerUrl, new File(root, installerRel), digest, -1);
        JSONObject receipt = extractInstallerProfile(root, installerJar, "neoforge", ver, game, installerRel, NEOFORGE_REPO, transport, progress);
        VanillaInstaller.writeJson(new File(instance, "loader.json"), receipt);
        return ver;
    }

    static String chooseForgeVersion(JSONObject promosJson, String game, String pinned) throws IOException {
        if (pinned != null) {
            if (!pinned.matches("[A-Za-z0-9._+-]{1,128}") || pinned.contains("..")) {
                throw new IOException("Forge 版本号无效");
            }
            return pinned;
        }
        JSONObject promos = promosJson.optJSONObject("promos");
        if (promos == null) throw new IOException("Forge 版本清单格式无效");
        String rec = promos.optString(game + "-recommended", "");
        if (!rec.isEmpty()) return rec;
        String latest = promos.optString(game + "-latest", "");
        if (!latest.isEmpty()) return latest;
        throw new IOException("Forge 官方清单没有适配 " + game + " 的版本");
    }

    private static String installForge(File root, File instance, String game, String pinned,
                                       VanillaInstaller.Progress progress, VerifiedDownloads transport) throws Exception {
        progress.update("查询 Forge 版本", 0, 1);
        JSONObject promos = new JSONObject(new String(transport.metadata(FORGE_PROMOS, 4 * 1024 * 1024), StandardCharsets.UTF_8));
        String ver = chooseForgeVersion(promos, game, pinned);
        String full = game + "-" + ver;
        String installerUrl = FORGE_REPO + "net/minecraftforge/forge/" + full + "/forge-" + full + "-installer.jar";
        String digest = mavenChecksum(transport, installerUrl);
        String installerRel = "shared/loaders/" + digest.substring(0, 2) + "/" + digest + ".jar";
        progress.update("下载并校验 Forge 安装器", 0, 1);
        File installerJar = transport.fetch(installerUrl, new File(root, installerRel), digest, -1);
        JSONObject receipt = extractInstallerProfile(root, installerJar, "forge", ver, game, installerRel, FORGE_REPO, transport, progress);
        VanillaInstaller.writeJson(new File(instance, "loader.json"), receipt);
        return ver;
    }

    private static JSONObject extractInstallerProfile(File root, File installerJar, String loader, String version,
                                                      String game, String installerRel, String defaultRepo,
                                                      VerifiedDownloads transport, VanillaInstaller.Progress progress) throws Exception {
        JSONObject versionJson = null;
        JSONObject installProfile = null;
        try (ZipFile zip = new ZipFile(installerJar)) {
            ZipEntry ve = zip.getEntry("version.json");
            if (ve != null && ve.getSize() > 0 && ve.getSize() <= 4L * 1024 * 1024) {
                versionJson = new JSONObject(readZipText(zip, ve));
            }
            ZipEntry pe = zip.getEntry("install_profile.json");
            if (pe != null && pe.getSize() > 0 && pe.getSize() <= 4L * 1024 * 1024) {
                installProfile = new JSONObject(readZipText(zip, pe));
            }
        }
        if (versionJson == null && installProfile != null && installProfile.has("versionInfo")) {
            versionJson = installProfile.getJSONObject("versionInfo");
        }
        if (versionJson == null) throw new IOException(loader + " 安装器缺少 version.json");
        JSONArray libs = versionJson.optJSONArray("libraries");
        if (libs == null) libs = new JSONArray();
        JSONArray classpath = new JSONArray();
        classpath.put(installerRel);
        progress.update("校验 " + loader + " 运行库", 0, libs.length());
        for (int i = 0; i < libs.length(); i++) {
            JSONObject lib = libs.getJSONObject(i);
            String coord = lib.getString("name");
            JSONObject downloads = lib.optJSONObject("downloads");
            JSONObject artifact = downloads == null ? null : downloads.optJSONObject("artifact");
            String url = null;
            String sha = null;
            long size = -1;
            if (artifact != null) {
                url = artifact.optString("url", "");
                sha = artifact.optString("sha1", "");
                size = artifact.optLong("size", -1L);
            }
            if ((url == null || url.isEmpty()) && lib.has("url")) {
                String base = lib.getString("url");
                String path = FabricInstaller.mavenPath(coord);
                url = (base.endsWith("/") ? base : base + "/") + path;
            }
            if (url != null && url.startsWith("https://")) {
                if (sha == null || sha.isEmpty()) {
                    try { sha = mavenChecksum(transport, url); }
                    catch (IOException ignored) { sha = ""; }
                }
                if (sha.matches("(?i)([a-f0-9]{40}|[a-f0-9]{64})")) {
                    String local = "shared/loaders/" + sha.toLowerCase(Locale.ROOT).substring(0, 2) + "/" + sha.toLowerCase(Locale.ROOT) + ".jar";
                    transport.fetch(url, new File(root, local), sha, size);
                    classpath.put(local);
                }
            }
            progress.update("校验 " + loader + " 运行库", i + 1, libs.length());
        }
        JSONObject receipt = new JSONObject()
                .put("format", 1)
                .put("loader", loader)
                .put("version", version)
                .put("game", game)
                .put("installerJar", installerRel)
                .put("mainClass", versionJson.optString("mainClass", ""))
                .put("profile", versionJson)
                .put("classpath", classpath);
        if (installProfile != null && installProfile.has("processors")) {
            receipt.put("hasProcessors", true);
        }
        return receipt;
    }

    private static String installLiteLoader(File root, File instance, String game, String pinned,
                                            VanillaInstaller.Progress progress, VerifiedDownloads transport) throws Exception {
        progress.update("查询 LiteLoader 版本", 0, 1);
        JSONObject all = new JSONObject(new String(transport.metadata(LITELOADER_VERSIONS, 4 * 1024 * 1024), StandardCharsets.UTF_8));
        JSONObject versions = all.optJSONObject("versions");
        if (versions == null || !versions.has(game)) throw new IOException("LiteLoader 官方清单不支持 " + game);
        JSONObject gameObj = versions.getJSONObject(game);
        JSONObject repoObj = gameObj.optJSONObject("repo");
        String repoUrl = repoObj != null ? repoObj.optString("url", "https://dl.liteloader.com/versions/") : "https://dl.liteloader.com/versions/";
        if (repoUrl.startsWith("http://")) repoUrl = "https://" + repoUrl.substring(7);
        JSONObject artefacts = gameObj.optJSONObject("artefacts");
        JSONObject ll = artefacts == null ? null : artefacts.optJSONObject("com.mumfrey:liteloader");
        if (ll == null) throw new IOException("LiteLoader 缺少该版本的构建信息");
        JSONObject chosen = ll.optJSONObject(pinned != null ? pinned : "latest");
        if (chosen == null) throw new IOException("未找到指定的 LiteLoader 版本");
        String ver = chosen.getString("version");
        String tweak = chosen.optString("tweakClass", "com.mumfrey.liteloader.launch.LiteLoaderTweaker");
        String file = chosen.getString("file");
        String url = (repoUrl.endsWith("/") ? repoUrl : repoUrl + "/") + "com/mumfrey/liteloader/" + game + "/" + file;
        String digest = mavenChecksum(transport, url);
        String rel = "shared/loaders/" + digest.substring(0, 2) + "/" + digest + ".jar";
        transport.fetch(url, new File(root, rel), digest, -1);
        JSONObject receipt = new JSONObject()
                .put("format", 1)
                .put("loader", "liteloader")
                .put("version", ver)
                .put("game", game)
                .put("mainClass", "net.minecraft.launchwrapper.Launch")
                .put("tweakClass", tweak)
                .put("classpath", new JSONArray().put(rel));
        VanillaInstaller.writeJson(new File(instance, "loader.json"), receipt);
        progress.update("LiteLoader 已校验", 1, 1);
        return ver;
    }

    private static String installCleanroom(File root, File instance, String game, String pinned,
                                           VanillaInstaller.Progress progress, VerifiedDownloads transport) throws Exception {
        if (!"1.12.2".equals(game)) throw new IOException("Cleanroom 仅适配 Minecraft 1.12.2");
        progress.update("查询 Cleanroom 版本", 0, 1);
        JSONArray releases = new JSONArray(new String(transport.metadata(CLEANROOM_RELEASES, 4 * 1024 * 1024), StandardCharsets.UTF_8));
        JSONObject targetRelease = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject rel = releases.getJSONObject(i);
            String tag = rel.optString("tag_name", "");
            if (pinned != null ? pinned.equals(tag) : !rel.optBoolean("draft", false)) {
                targetRelease = rel;
                break;
            }
        }
        if (targetRelease == null) throw new IOException("未找到可用的 Cleanroom 发布版本");
        String ver = targetRelease.getString("tag_name");
        JSONArray assets = targetRelease.optJSONArray("assets");
        String assetUrl = null;
        long assetSize = -1;
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "").toLowerCase(Locale.ROOT);
                if (name.contains("installer") && name.endsWith(".jar")) {
                    assetUrl = a.getString("browser_download_url");
                    assetSize = a.optLong("size", -1L);
                    break;
                }
            }
        }
        if (assetUrl == null) throw new IOException("Cleanroom 发布缺少 installer JAR");
        // Verify digest if GitHub exposes asset digest or require explicit metadata validation.
        String sha = "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (assetUrl.equals(a.optString("browser_download_url"))) {
                String d = a.optString("digest", "");
                if (d.startsWith("sha256:")) sha = d.substring(7);
            }
        }
        if (!sha.matches("(?i)[a-f0-9]{64}")) {
            throw new IOException("Cleanroom 发布资产缺少 SHA-256 校验值");
        }
        String relPath = "shared/loaders/" + sha.substring(0, 2) + "/" + sha + ".jar";
        File installerJar = transport.fetch(assetUrl, new File(root, relPath), sha, assetSize);
        JSONObject receipt = extractInstallerProfile(root, installerJar, "cleanroom", ver, game, relPath, FORGE_REPO, transport, progress);
        VanillaInstaller.writeJson(new File(instance, "loader.json"), receipt);
        return ver;
    }

    private static void verifyTargetInstance(File root, File instance, String game) throws Exception {
        File instances = new File(root, "instances").getCanonicalFile();
        if (!instances.equals(instance.getCanonicalFile().getParentFile())) {
            throw new IOException("加载器安装目录必须是独立游戏实例");
        }
        JSONObject base = VanillaInstaller.readJson(new File(instance, "install.json"));
        if (!game.equals(base.getString("id")) || !"client".equals(base.getString("kind"))) {
            throw new IOException("加载器需要已校验的对应版本客户端");
        }
    }

    static String mavenChecksum(VerifiedDownloads transport, String url) throws IOException {
        String digest;
        try {
            digest = new String(transport.metadata(url + ".sha256", 1024), StandardCharsets.US_ASCII).trim();
        } catch (IOException unavailable) {
            VerifiedDownloads.interrupted();
            digest = new String(transport.metadata(url + ".sha1", 1024), StandardCharsets.US_ASCII).trim();
        }
        digest = digest.split("\\s+", 2)[0];
        if (!digest.matches("(?i)([a-f0-9]{64}|[a-f0-9]{40})")) {
            throw new IOException("Maven 文件缺少有效的 SHA-1/SHA-256 摘要");
        }
        return digest.toLowerCase(Locale.ROOT);
    }

    private static String readZipText(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            VerifiedDownloads.transfer(in, out, 4L * 1024 * 1024);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File tmp = File.createTempFile("copy-", ".tmp", dst.getParentFile());
        try {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
                VerifiedDownloads.transfer(in, out, src.length());
            }
            VerifiedDownloads.replace(tmp, dst);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static int compareNumericPrefix(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            BigInteger xi = (i < x.length && !x[i].isEmpty()) ? new BigInteger(x[i]) : BigInteger.ZERO;
            BigInteger yi = (i < y.length && !y[i].isEmpty()) ? new BigInteger(y[i]) : BigInteger.ZERO;
            int cmp = xi.compareTo(yi);
            if (cmp != 0) return cmp;
        }
        return a.compareTo(b);
    }
}
