package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Imports CurseForge, MultiMC/Prism, and HMCL modpacks, and migrates local FCL/PCL/.minecraft instances. */
public final class InstanceMigration {
    private static final Set<String> SAFE_CONTENT = new HashSet<>(Arrays.asList(
            "mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks", "datapacks",
            "saves", "kubejs", "scripts", "resources", "options.txt", "optionsof.txt", "servers.dat"));
    private static final long MAX_ARCHIVE = 512L << 20;
    private static final long MAX_TOTAL = 4L << 30;
    private static final int MAX_ENTRIES = 30000;

    public static final class PackSummary {
        public final String format, name, game, loader, loaderVersion;
        public final File instanceDir;
        public final int copiedFiles, pendingExternalMods;

        PackSummary(String format, String name, String game, String loader, String loaderVersion,
                    File instanceDir, int copiedFiles, int pendingExternalMods) {
            this.format = format;
            this.name = name;
            this.game = game;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
            this.instanceDir = instanceDir;
            this.copiedFiles = copiedFiles;
            this.pendingExternalMods = pendingExternalMods;
        }
    }

    private InstanceMigration() {}

    /** Detects and imports Modrinth (.mrpack), CurseForge (manifest.json), MultiMC (mmc-pack.json), or HMCL (mcbbs.packmeta) ZIP packs. */
    public static PackSummary importAnyPack(File root, InputStream zipStream, VanillaInstaller.Progress progress) throws Exception {
        return importAnyPack(root, zipStream, progress, (base, stage, game, loader, pin, rep) -> {
            new VanillaInstaller(base).install(game, stage.getName(), false, rep);
            if (loader != null && !"vanilla".equals(loader)) {
                ModLoaderInstaller.install(base, stage, game, loader, pin, rep);
            }
        });
    }

    static synchronized PackSummary importAnyPack(File root, InputStream zipStream, VanillaInstaller.Progress progress,
                                                  PackImport.Components components) throws Exception {
        VanillaInstaller.Progress report = progress == null ? (l, d, t) -> {} : progress;
        File instances = new File(root.getCanonicalFile(), "instances");
        VerifiedDownloads.directory(instances);
        File tempZip = File.createTempFile("lumen-pack-", ".zip", root.getCanonicalFile());
        try {
            report.update("读取整合包归档", 0, 1);
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tempZip))) {
                VerifiedDownloads.transfer(zipStream, out, MAX_ARCHIVE);
            }
            try (ZipFile zip = new ZipFile(tempZip)) {
                if (zip.size() > MAX_ENTRIES) throw new IOException("整合包条目过多");
                if (zip.getEntry("modrinth.index.json") != null) {
                    try (InputStream in = new FileInputStream(tempZip)) {
                        File dir = PackImport.install(root, in, report);
                        JSONObject inst = VanillaInstaller.readJson(new File(dir, "install.json"));
                        JSONObject pack = VanillaInstaller.readJson(new File(dir, "pack.json"));
                        String loader = new File(dir, "loader.json").isFile()
                                ? VanillaInstaller.readJson(new File(dir, "loader.json")).optString("loader", "vanilla") : "vanilla";
                        return new PackSummary("modrinth", pack.optString("name", dir.getName()),
                                inst.getString("id"), loader, "", dir, pack.optInt("requiredFiles", 0) + pack.optInt("overrides", 0), 0);
                    }
                }
                ZipEntry cf = zip.getEntry("manifest.json");
                ZipEntry mmc = findEntrySuffix(zip, "mmc-pack.json");
                ZipEntry hmcl = zip.getEntry("mcbbs.packmeta");
                if (cf != null) return importCurseForge(root, instances, zip, cf, report, components);
                if (mmc != null) return importMultiMc(root, instances, zip, mmc, report, components);
                if (hmcl != null) return importHmcl(root, instances, zip, hmcl, report, components);
                throw new IOException("未识别的整合包格式（支持 Modrinth、CurseForge、MultiMC、HMCL）");
            }
        } finally {
            if (tempZip.exists()) tempZip.delete();
        }
    }

    private static PackSummary importCurseForge(File root, File instances, ZipFile zip, ZipEntry manifestEntry,
                                                VanillaInstaller.Progress report, PackImport.Components components) throws Exception {
        JSONObject manifest = new JSONObject(readZip(zip, manifestEntry));
        JSONObject mc = manifest.getJSONObject("minecraft");
        String game = mc.getString("version");
        String name = manifest.optString("name", "CurseForge-" + game).trim();
        String overridesPrefix = manifest.optString("overrides", "overrides");
        if (!overridesPrefix.endsWith("/")) overridesPrefix += "/";
        String loader = null, loaderVer = null;
        JSONArray modLoaders = mc.optJSONArray("modLoaders");
        if (modLoaders != null) {
            for (int i = 0; i < modLoaders.length(); i++) {
                String id = modLoaders.getJSONObject(i).optString("id", "");
                int dash = id.indexOf('-');
                if (dash > 0) {
                    String prefix = id.substring(0, dash).toLowerCase(Locale.ROOT);
                    if (Arrays.asList("fabric", "quilt", "forge", "neoforge").contains(prefix)) {
                        loader = prefix;
                        loaderVer = id.substring(dash + 1);
                        break;
                    }
                }
            }
        }
        JSONArray files = manifest.optJSONArray("files");
        int externalMods = files == null ? 0 : files.length();
        return commitExtractedPack(root, instances, zip, "curseforge", name, game, loader, loaderVer,
                overridesPrefix, externalMods, manifest, report, components);
    }

    private static PackSummary importMultiMc(File root, File instances, ZipFile zip, ZipEntry mmcEntry,
                                             VanillaInstaller.Progress report, PackImport.Components components) throws Exception {
        String prefix = mmcEntry.getName().substring(0, mmcEntry.getName().length() - "mmc-pack.json".length());
        JSONObject mmc = new JSONObject(readZip(zip, mmcEntry));
        JSONArray comps = mmc.getJSONArray("components");
        String game = null, loader = null, loaderVer = null;
        for (int i = 0; i < comps.length(); i++) {
            JSONObject c = comps.getJSONObject(i);
            String uid = c.optString("uid", "");
            String ver = c.optString("version", "");
            if ("net.minecraft".equals(uid)) game = ver;
            else if ("net.fabricmc.fabric-loader".equals(uid)) { loader = "fabric"; loaderVer = ver; }
            else if ("org.quiltmc.quilt-loader".equals(uid)) { loader = "quilt"; loaderVer = ver; }
            else if ("net.minecraftforge".equals(uid)) { loader = "forge"; loaderVer = ver; }
            else if ("net.neoforged".equals(uid)) { loader = "neoforge"; loaderVer = ver; }
        }
        if (game == null || game.isEmpty()) throw new IOException("MultiMC 整合包缺少 net.minecraft 版本");
        String name = "MultiMC-" + game;
        ZipEntry cfg = zip.getEntry(prefix + "instance.cfg");
        if (cfg != null) {
            for (String line : readZip(zip, cfg).split("\n")) {
                if (line.startsWith("name=")) {
                    String v = line.substring(5).trim();
                    if (!v.isEmpty()) name = v;
                }
            }
        }
        String dotMc = prefix + ".minecraft/";
        boolean hasDotMc = false;
        Enumeration<? extends ZipEntry> scan = zip.entries();
        while (scan.hasMoreElements()) {
            if (scan.nextElement().getName().startsWith(dotMc)) { hasDotMc = true; break; }
        }
        String mcPrefix = hasDotMc ? dotMc : prefix + "minecraft/";
        return commitExtractedPack(root, instances, zip, "multimc", name, game, loader, loaderVer,
                mcPrefix, 0, mmc, report, components);
    }

    private static PackSummary importHmcl(File root, File instances, ZipFile zip, ZipEntry metaEntry,
                                          VanillaInstaller.Progress report, PackImport.Components components) throws Exception {
        JSONObject meta = new JSONObject(readZip(zip, metaEntry));
        String name = meta.optString("name", "HMCL-Pack").trim();
        JSONArray addons = meta.optJSONArray("addons");
        String game = null, loader = null, loaderVer = null;
        if (addons != null) {
            for (int i = 0; i < addons.length(); i++) {
                JSONObject a = addons.getJSONObject(i);
                String id = a.optString("id", "");
                String ver = a.optString("version", "");
                if ("game".equals(id)) game = ver;
                else if (Arrays.asList("fabric", "quilt", "forge", "neoforge").contains(id)) {
                    loader = id; loaderVer = ver;
                }
            }
        }
        if (game == null || game.isEmpty()) throw new IOException("HMCL 整合包缺少游戏版本声明");
        return commitExtractedPack(root, instances, zip, "hmcl", name, game, loader, loaderVer,
                "overrides/", 0, meta, report, components);
    }

    private static PackSummary commitExtractedPack(File root, File instances, ZipFile zip, String format, String name,
                                                   String game, String loader, String loaderVer, String contentPrefix,
                                                   int pendingExternal, JSONObject metaJson, VanillaInstaller.Progress report,
                                                   PackImport.Components components) throws Exception {
        File stage = new File(instances, "pack-staging-" + UUID.randomUUID());
        if (!stage.mkdir()) throw new IOException("无法创建暂存目录");
        try {
            components.install(root, stage, game, loader, loaderVer, report);
            int copied = 0;
            long totalBytes = 0;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(contentPrefix)) continue;
                String rel = e.getName().substring(contentPrefix.length());
                if (!isSafeContentPath(rel)) continue;
                File dst = VanillaInstaller.safe(stage, rel);
                VerifiedDownloads.directory(dst.getParentFile());
                try (InputStream in = zip.getInputStream(e); OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
                    totalBytes += VerifiedDownloads.transfer(in, out, MAX_TOTAL - totalBytes);
                }
                copied++;
            }
            JSONObject packReceipt = new JSONObject()
                    .put("name", name)
                    .put("format", format)
                    .put("game", game)
                    .put("loader", loader == null ? "vanilla" : loader)
                    .put("copiedFiles", copied)
                    .put("pendingExternalMods", pendingExternal);
            if ("curseforge".equals(format) && metaJson.has("files")) {
                packReceipt.put("curseforgeManifestFiles", metaJson.getJSONArray("files"));
            }
            VanillaInstaller.writeJson(new File(stage, "pack.json"), packReceipt);
            String slug = name.replaceAll("[^\\p{L}\\p{N}._-]", "-");
            if (slug.length() > 36) slug = slug.substring(0, 36);
            File target = new File(instances, format + "-" + slug + "-" + UUID.randomUUID().toString().substring(0, 8));
            if (!stage.renameTo(target)) throw new IOException("无法提交导入的整合包实例");
            report.update("整合包已导入", 1, 1);
            return new PackSummary(format, name, game, loader == null ? "vanilla" : loader,
                    loaderVer == null ? "" : loaderVer, target, copied, pendingExternal);
        } finally {
            if (stage.exists()) deleteTree(stage);
        }
    }

    /** Migrates saves, mods, config, packs, and options.txt from an existing FCL/PCL/.minecraft directory. */
    public static int migrateDirectory(File sourceDir, File targetInstanceDir) throws IOException {
        File src = sourceDir.getCanonicalFile();
        File dst = targetInstanceDir.getCanonicalFile();
        if (!src.isDirectory() || !new File(dst, "install.json").isFile()) {
            throw new IOException("请指定有效的源游戏目录和已安装的微光实例");
        }
        int copied = 0;
        for (String item : SAFE_CONTENT) {
            File from = new File(src, item);
            if (!from.exists()) continue;
            copied += copyTree(from, new File(dst, item));
        }
        return copied;
    }

    private static int copyTree(File from, File to) throws IOException {
        if (!from.getAbsoluteFile().equals(from.getCanonicalFile())) throw new IOException("迁移目录不能包含符号链接");
        if (from.isDirectory()) {
            VerifiedDownloads.directory(to);
            File[] children = from.listFiles();
            int count = 0;
            if (children != null) {
                for (File child : children) count += copyTree(child, new File(to, child.getName()));
            }
            return count;
        }
        VerifiedDownloads.directory(to.getParentFile());
        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            VerifiedDownloads.transfer(in, out, from.length());
        }
        return 1;
    }

    private static ZipEntry findEntrySuffix(ZipFile zip, String suffix) {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory() && (e.getName().equals(suffix) || e.getName().endsWith("/" + suffix))) return e;
        }
        return null;
    }

    private static boolean isSafeContentPath(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains("..") || rel.contains(":")) return false;
        int slash = rel.indexOf('/');
        String top = slash < 0 ? rel : rel.substring(0, slash);
        return SAFE_CONTENT.contains(top);
    }

    private static String readZip(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > 4 * 1024 * 1024) throw new IOException("整合包清单过大");
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            VerifiedDownloads.transfer(in, out, 4 * 1024 * 1024);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}
