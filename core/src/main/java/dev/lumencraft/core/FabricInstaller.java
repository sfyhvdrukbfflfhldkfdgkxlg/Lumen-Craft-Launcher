package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Official Fabric and Quilt client profile installer. All library bytes are verified before commit. */
public final class FabricInstaller {
    private FabricInstaller() {}

    public static String install(File root, File instance, String game, VanillaInstaller.Progress progress) throws Exception {
        return install(root, instance, game, "fabric", null, progress);
    }
    public static String install(File root, File instance, String game, String loader, VanillaInstaller.Progress progress) throws Exception {
        return install(root, instance, game, loader, null, progress);
    }
    /** A non-null loaderVersion requires an exact upstream match, including pre-release suffixes. */
    public static String install(File root, File instance, String game, String loader, String loaderVersion,
                                 VanillaInstaller.Progress progress) throws Exception {
        return install(root, instance, game, loader, loaderVersion, progress, new VerifiedDownloads());
    }
    static String install(File root, File instance, String game, String loader, String pinned,
                          VanillaInstaller.Progress progress, VerifiedDownloads transport) throws Exception {
        String meta;
        if ("fabric".equals(loader)) meta = "https://meta.fabricmc.net/v2/versions/loader/";
        else if ("quilt".equals(loader)) meta = "https://meta.quiltmc.org/v3/versions/loader/";
        else throw new IOException("不支持的加载器 " + loader);
        token(game);
        if (pinned != null) token(pinned);
        File instances = new File(root, "instances").getCanonicalFile();
        if (!instances.equals(instance.getCanonicalFile().getParentFile()))
            throw new IOException("加载器安装目录必须是独立游戏实例");
        JSONObject base = VanillaInstaller.readJson(new File(instance, "install.json"));
        if (!game.equals(base.getString("id")) || !"client".equals(base.getString("kind")))
            throw new IOException("加载器需要已校验的对应版本客户端");
        if (progress == null) progress = (label, done, total) -> {};
        progress.update("查询 " + loader + " 版本", 0, 1);
        String api = meta + game;
        JSONArray versions = new JSONArray(json(transport, api));
        String version = choose(versions, loader, game, pinned);
        JSONObject profile = new JSONObject(json(transport, api + "/" + version + "/profile/json"));
        if (!game.equals(profile.getString("inheritsFrom"))
                || !(loader + "-loader-" + version + "-" + game).equals(profile.getString("id")))
            throw new IOException("加载器配置与所选游戏不匹配");
        JSONArray libraries = profile.getJSONArray("libraries");
        if (libraries.length() == 0) throw new IOException("加载器没有提供库清单");
        JSONArray classpath = new JSONArray();
        String expectedLoader = ("quilt".equals(loader) ? "org.quiltmc:quilt-loader:" : "net.fabricmc:fabric-loader:") + version;
        boolean hasLoader = false;
        progress.update("安装 " + loader + " 库", 0, libraries.length());
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject item = libraries.getJSONObject(i);
            String coordinate = item.getString("name");
            String path = mavenPath(coordinate);
            String repo = item.getString("url");
            URL url = new URL(repo.endsWith("/") ? repo + path : repo + "/" + path);
            String hash = checksum(transport, item, url.toExternalForm());
            String local = "shared/loaders/" + hash.substring(0,2) + "/" + hash + ".jar";
            transport.fetch(url.toExternalForm(), new File(root, local), hash, item.optLong("size", -1L));
            classpath.put(local);
            if (expectedLoader.equals(coordinate)) hasLoader = true;
            progress.update("安装 " + loader + " 库", i + 1, libraries.length());
        }
        if (!hasLoader) throw new IOException("库清单缺少所选加载器");
        JSONObject receipt = new JSONObject().put("format",1).put("loader",loader).put("version",version)
                .put("game",game).put("mainClass",profile.getString("mainClass"))
                .put("profile",profile).put("classpath",classpath);
        VanillaInstaller.writeJson(new File(instance, "loader.json"), receipt);
        return version;
    }

    private static String json(VerifiedDownloads d, String url) throws IOException {
        return new String(d.metadata(url, 8 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    static String choose(JSONArray list, String loader, String game, String pinned) throws Exception {
        String selected = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.getJSONObject(i);
            if (!game.equals(entry.getJSONObject("intermediary").getString("version"))) continue;
            JSONObject descriptor = entry.getJSONObject("loader");
            String version = token(descriptor.getString("version"));
            if (pinned != null) {
                if (pinned.equals(version)) return version;
                continue;
            }
            if (!version.matches("[0-9]+(\\.[0-9]+){1,3}")) continue;
            if ("fabric".equals(loader) && !descriptor.optBoolean("stable", false)) continue;
            if (selected == null || compareRelease(version, selected) > 0) selected = version;
        }
        if (selected == null) throw new IOException("官方没有与 " + game + " 匹配的 " + loader +
                (pinned == null ? " 稳定版" : " " + pinned));
        return selected;
    }
    private static int compareRelease(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            BigInteger n = i < x.length ? new BigInteger(x[i]) : BigInteger.ZERO;
            BigInteger m = i < y.length ? new BigInteger(y[i]) : BigInteger.ZERO;
            int c = n.compareTo(m);
            if (c != 0) return c;
        }
        return 0;
    }
    private static String token(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}") || value.contains(".."))
            throw new IOException("加载器元数据包含无效名称");
        return value;
    }
    static String mavenPath(String coordinate) throws IOException {
        String[] pieces = coordinate.split(":", -1);
        if (pieces.length < 3 || pieces.length > 4) throw new IOException("不支持的 Maven 坐标");
        for (String piece : pieces) token(piece);
        return pieces[0].replace('.', '/') + "/" + pieces[1] + "/" + pieces[2] + "/" +
                pieces[1] + "-" + pieces[2] + (pieces.length == 4 ? "-" + pieces[3] : "") + ".jar";
    }
    static String checksum(VerifiedDownloads transport, JSONObject library, String url) throws IOException {
        String digest = library.optString("sha256", library.optString("sha1", ""));
        if (digest.isEmpty()) {
            try {
                digest = new String(transport.metadata(url + ".sha256", 1024), StandardCharsets.US_ASCII).trim();
            } catch (IOException unavailable) {
                VerifiedDownloads.interrupted();
                digest = new String(transport.metadata(url + ".sha1", 1024), StandardCharsets.US_ASCII).trim();
            }
            digest = digest.split("\\s+", 2)[0];
        }
        if (!digest.matches("(?i)([a-f0-9]{64}|[a-f0-9]{40})"))
            throw new IOException("Maven 文件缺少有效的完整性摘要");
        return digest.toLowerCase(java.util.Locale.ROOT);
    }
}

