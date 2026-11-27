package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Verified AutoModpack public manifest client for automatic server mod/config synchronization. */
public final class AutoModpackSync {
    private static final Set<String> ALLOWED_PREFIXES = new HashSet<>(Arrays.asList(
            "mods/", "config/", "defaultconfigs/", "resourcepacks/", "shaderpacks/", "datapacks/", "kubejs/", "scripts/"));

    public static final class RemoteFile {
        public final String path, sha1, type;
        public final long size;
        public final boolean editable;

        RemoteFile(String path, String sha1, String type, long size, boolean editable) {
            this.path = path;
            this.sha1 = sha1;
            this.type = type;
            this.size = size;
            this.editable = editable;
        }
    }

    public static final class SyncManifest {
        public final String modpackName, mcVersion, loader, loaderVersion, baseUrl;
        public final List<RemoteFile> files;

        SyncManifest(String modpackName, String mcVersion, String loader, String loaderVersion,
                     String baseUrl, List<RemoteFile> files) {
            this.modpackName = modpackName;
            this.mcVersion = mcVersion;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.baseUrl = baseUrl;
            this.files = Collections.unmodifiableList(files);
        }
    }

    public static final class SyncResult {
        public final File instanceDir;
        public final int downloaded, reused, skippedServerOnly;

        SyncResult(File instanceDir, int downloaded, int reused, int skippedServerOnly) {
            this.instanceDir = instanceDir;
            this.downloaded = downloaded;
            this.reused = reused;
            this.skippedServerOnly = skippedServerOnly;
        }
    }

    private AutoModpackSync() {}

    public static SyncManifest fetchManifest(String manifestUrl) throws Exception {
        return fetchManifest(manifestUrl, new VerifiedDownloads());
    }

    static SyncManifest fetchManifest(String manifestUrl, VerifiedDownloads transport) throws Exception {
        URI uri = new URI(manifestUrl.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("AutoModpack 清单地址必须为有效的 HTTPS 链接");
        }
        String raw = new String(transport.metadata(manifestUrl.trim(), 8 * 1024 * 1024), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(raw);
        String name = json.optString("modpackName", uri.getHost()).trim();
        if (name.isEmpty()) name = uri.getHost();
        String mcVersion = json.getString("mcVersion").trim();
        String loader = json.optString("loader", "vanilla").toLowerCase(Locale.ROOT);
        String loaderVersion = json.optString("loaderVersion", "");
        if (!Arrays.asList("vanilla", "fabric", "quilt", "neoforge", "forge").contains(loader)) {
            throw new IOException("不支持的 AutoModpack 加载器 " + loader);
        }
        JSONArray list = json.getJSONArray("list");
        if (list.length() > 10000) throw new IOException("AutoModpack 文件数量超出限制");

        String path = uri.getRawPath();
        int lastSlash = path == null ? -1 : path.lastIndexOf('/');
        String base = uri.getScheme() + "://" + uri.getRawAuthority() + (lastSlash >= 0 ? path.substring(0, lastSlash + 1) : "/");

        List<RemoteFile> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String file = normalizeRelative(item.getString("file"));
            String sha1 = item.getString("sha1").toLowerCase(Locale.ROOT);
            long size = item.getLong("size");
            String type = item.optString("type", "other").toLowerCase(Locale.ROOT);
            boolean editable = item.optBoolean("editable", false);
            if (!sha1.matches("[0-9a-f]{40}") || size < 0 || size > (512L << 20)) {
                throw new IOException("AutoModpack 文件校验信息无效 " + file);
            }
            if (!isAllowedClientPath(file)) continue;
            if (!seen.add(file.toLowerCase(Locale.ROOT))) throw new IOException("AutoModpack 清单存在重名文件 " + file);
            files.add(new RemoteFile(file, sha1, type, size, editable));
        }
        return new SyncManifest(name, mcVersion, loader, loaderVersion, base, files);
    }

    public static SyncResult sync(File root, String serverAddress, SyncManifest manifest,
                                  VanillaInstaller.Progress progress) throws Exception {
        return sync(root, serverAddress, manifest, progress, new VerifiedDownloads(),
                (baseRoot, stage, game, loader, pin, rep) -> {
                    new VanillaInstaller(baseRoot).install(game, stage.getName(), false, rep);
                    if (loader != null && !"vanilla".equals(loader)) {
                        ModLoaderInstaller.install(baseRoot, stage, game, loader, pin.isEmpty() ? null : pin, rep);
                    }
                });
    }

    static synchronized SyncResult sync(File root, String serverAddress, SyncManifest manifest,
                                        VanillaInstaller.Progress progress, VerifiedDownloads transport,
                                        PackImport.Components components) throws Exception {
        VanillaInstaller.Progress report = progress == null ? (l, d, t) -> {} : progress;
        File instances = new File(root.getCanonicalFile(), "instances");
        VerifiedDownloads.directory(instances);
        String key = UUID.nameUUIDFromBytes(("automodpack:" + serverAddress + ":" + manifest.mcVersion).getBytes(StandardCharsets.UTF_8)).toString();
        File instance = VanillaInstaller.safe(instances, "server-amp-" + key.substring(0, 12));
        VerifiedDownloads.directory(instance);

        if (!new File(instance, "install.json").isFile()) {
            components.install(root, instance, manifest.mcVersion,
                    "vanilla".equals(manifest.loader) ? null : manifest.loader,
                    manifest.loaderVersion, report);
        }

        int downloaded = 0, reused = 0, skippedServer = 0;
        int total = manifest.files.size();
        for (int i = 0; i < total; i++) {
            RemoteFile rf = manifest.files.get(i);
            report.update("同步 AutoModpack 客户端文件", i, total);
            if ("server".equals(rf.type) || "server_only".equals(rf.type)) {
                skippedServer++;
                continue;
            }
            File dst = VanillaInstaller.safe(instance, rf.path);
            if (dst.isFile()) {
                if (rf.editable) {
                    reused++;
                    continue;
                }
                if (dst.length() == rf.size && VerifiedDownloads.digest(dst, "SHA-1").equals(rf.sha1)) {
                    reused++;
                    continue;
                }
            }
            String fileUrl = manifest.baseUrl + rf.path;
            transport.fetch(fileUrl, dst, rf.sha1, rf.size);
            downloaded++;
        }
        JSONObject receipt = new JSONObject()
                .put("protocol", "automodpack")
                .put("name", manifest.modpackName)
                .put("serverAddress", serverAddress)
                .put("mcVersion", manifest.mcVersion)
                .put("loader", manifest.loader)
                .put("loaderVersion", manifest.loaderVersion)
                .put("manifestUrl", manifest.baseUrl)
                .put("syncedFiles", downloaded + reused);
        VanillaInstaller.writeJson(new File(instance, "pack.json"), receipt);
        report.update("AutoModpack 同步完成", total, total);
        return new SyncResult(instance, downloaded, reused, skippedServer);
    }

    private static String normalizeRelative(String raw) throws IOException {
        String s = raw.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty() || s.contains("..") || s.contains(":")) throw new IOException("AutoModpack 路径非法 " + raw);
        return s;
    }

    private static boolean isAllowedClientPath(String rel) {
        if ("options.txt".equals(rel) || "servers.dat".equals(rel)) return true;
        for (String prefix : ALLOWED_PREFIXES) {
            if (rel.startsWith(prefix)) return true;
        }
        return false;
    }
}
