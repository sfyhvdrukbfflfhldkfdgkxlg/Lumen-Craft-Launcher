package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Mod metadata inspection, conflict/dependency audit, version locking, batch update, and rollback. */
public final class ModManager {
    private static final Pattern TOML_KEY = Pattern.compile("^\\s*([A-Za-z0-9_]+)\\s*=\\s*\"([^\"]*)\"");

    private ModManager() {}

    public static final class ModInfo {
        public final File file;
        public final String modId, name, version, description, environment, loaderType;
        public final boolean enabled, locked;
        public final String modrinthProject, modrinthVersion, sha512;
        public final List<String> requiredDeps;

        ModInfo(File file, String modId, String name, String version, String description,
                String environment, String loaderType, boolean enabled, boolean locked,
                String modrinthProject, String modrinthVersion, String sha512, List<String> requiredDeps) {
            this.file = file;
            this.modId = modId;
            this.name = name;
            this.version = version;
            this.description = description;
            this.environment = environment;
            this.loaderType = loaderType;
            this.enabled = enabled;
            this.locked = locked;
            this.modrinthProject = modrinthProject;
            this.modrinthVersion = modrinthVersion;
            this.sha512 = sha512;
            this.requiredDeps = Collections.unmodifiableList(requiredDeps);
        }
    }

    public static final class AuditReport {
        public final List<String> duplicates = new ArrayList<>();
        public final List<String> missingDeps = new ArrayList<>();
        public final List<String> serverOnlyOnClient = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean isClean() {
            return duplicates.isEmpty() && missingDeps.isEmpty() && serverOnlyOnClient.isEmpty();
        }

        public String summary() {
            if (isClean() && warnings.isEmpty()) return "未检测到重复模组、缺失前置或环境冲突。";
            StringBuilder sb = new StringBuilder();
            for (String s : duplicates) sb.append("• 重复模组：").append(s).append('\n');
            for (String s : missingDeps) sb.append("• 缺少前置：").append(s).append('\n');
            for (String s : serverOnlyOnClient) sb.append("• 仅服务端模组：").append(s).append('\n');
            for (String s : warnings) sb.append("• 提示：").append(s).append('\n');
            return sb.toString().trim();
        }
    }

    public static final class BatchUpdateResult {
        public final int updated, upToDate, skippedLocked;
        public final String rollbackId;
        public final List<String> details;

        BatchUpdateResult(int updated, int upToDate, int skippedLocked, String rollbackId, List<String> details) {
            this.updated = updated;
            this.upToDate = upToDate;
            this.skippedLocked = skippedLocked;
            this.rollbackId = rollbackId;
            this.details = Collections.unmodifiableList(details);
        }
    }

    public static List<ModInfo> listMods(File instanceDir) throws Exception {
        File modsDir = new File(instanceDir, "mods");
        File[] files = modsDir.listFiles((d, name) -> {
            String low = name.toLowerCase(Locale.ROOT);
            return low.endsWith(".jar") || low.endsWith(".jar.disabled");
        });
        if (files == null || files.length == 0) return Collections.emptyList();
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        JSONObject index = readIndex(instanceDir);
        JSONObject projects = index.optJSONObject("projects");
        Map<String, String[]> byPath = new HashMap<>();
        if (projects != null) {
            for (Iterator<?> it = projects.keys(); it.hasNext(); ) {
                String pid = (String) it.next();
                JSONObject rec = projects.optJSONObject(pid);
                if (rec == null) continue;
                String rel = rec.optString("path", "").toLowerCase(Locale.ROOT);
                byPath.put(rel, new String[]{pid, rec.optString("version", ""), Boolean.toString(rec.optBoolean("locked", false))});
            }
        }

        List<ModInfo> out = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            boolean enabled = f.getName().toLowerCase(Locale.ROOT).endsWith(".jar");
            String activeRel = "mods/" + (enabled ? f.getName() : f.getName().substring(0, f.getName().length() - 9)).toLowerCase(Locale.ROOT);
            String[] tracked = byPath.get(activeRel);
            out.add(inspectJar(f, enabled, tracked != null ? tracked[0] : "",
                    tracked != null ? tracked[1] : "", tracked != null && "true".equals(tracked[2])));
        }
        return out;
    }

    public static ModInfo inspectJar(File jar, boolean enabled, String mrProject, String mrVersion, boolean locked) throws Exception {
        String id = stripJarExt(jar.getName());
        String name = id;
        String version = "";
        String desc = "";
        String env = "both";
        String loader = "unknown";
        List<String> deps = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            ZipEntry quilt = zip.getEntry("quilt.mod.json");
            ZipEntry neo = zip.getEntry("META-INF/neoforge.mods.toml");
            ZipEntry forge = zip.getEntry("META-INF/mods.toml");
            ZipEntry legacy = zip.getEntry("mcmod.info");

            if (fabric != null) {
                loader = "fabric";
                JSONObject j = new JSONObject(readEntry(zip, fabric));
                id = j.optString("id", id);
                name = j.optString("name", id);
                version = j.optString("version", "");
                desc = j.optString("description", "");
                String e = j.optString("environment", "*");
                env = "client".equals(e) ? "client" : "server".equals(e) ? "server" : "both";
                JSONObject d = j.optJSONObject("depends");
                if (d != null) {
                    for (Iterator<?> it = d.keys(); it.hasNext(); ) {
                        String k = (String) it.next();
                        if (!isBuiltinModId(k)) deps.add(k);
                    }
                }
            } else if (quilt != null) {
                loader = "quilt";
                JSONObject j = new JSONObject(readEntry(zip, quilt));
                JSONObject ql = j.optJSONObject("quilt_loader");
                if (ql != null) {
                    id = ql.optString("id", id);
                    version = ql.optString("version", "");
                    JSONObject meta = ql.optJSONObject("metadata");
                    if (meta != null) {
                        name = meta.optString("name", id);
                        desc = meta.optString("description", "");
                    }
                    JSONArray depArr = ql.optJSONArray("depends");
                    if (depArr != null) {
                        for (int i = 0; i < depArr.length(); i++) {
                            Object item = depArr.get(i);
                            String depId = item instanceof String ? (String) item : ((JSONObject) item).optString("id", "");
                            boolean optional = item instanceof JSONObject && ((JSONObject) item).optBoolean("optional", false);
                            if (!optional && !depId.isEmpty() && !isBuiltinModId(depId)) deps.add(depId);
                        }
                    }
                }
            } else if (neo != null || forge != null) {
                loader = neo != null ? "neoforge" : "forge";
                String text = readEntry(zip, neo != null ? neo : forge);
                for (String line : text.split("\n")) {
                    Matcher m = TOML_KEY.matcher(line);
                    if (!m.find()) continue;
                    String k = m.group(1), v = m.group(2);
                    if ("modId".equals(k) && id.equals(stripJarExt(jar.getName()))) id = v;
                    else if ("displayName".equals(k) && name.equals(stripJarExt(jar.getName()))) name = v;
                    else if ("version".equals(k) && version.isEmpty()) version = v;
                    else if ("description".equals(k) && desc.isEmpty()) desc = v;
                }
            } else if (legacy != null) {
                loader = "forge";
                String raw = readEntry(zip, legacy).trim();
                JSONArray arr = raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw).optJSONArray("modList");
                if (arr != null && arr.length() > 0) {
                    JSONObject first = arr.getJSONObject(0);
                    id = first.optString("modid", id);
                    name = first.optString("name", id);
                    version = first.optString("version", "");
                    desc = first.optString("description", "");
                }
            }
        } catch (Exception ignored) {
            // Unreadable or non-standard mod jar falls back to filename metadata.
        }
        String sha512 = VerifiedDownloads.digest(jar, "SHA-512");
        return new ModInfo(jar, id, name, version, desc, env, loader, enabled, locked, mrProject, mrVersion, sha512, deps);
    }

    public static AuditReport auditMods(File instanceDir) throws Exception {
        AuditReport report = new AuditReport();
        List<ModInfo> mods = listMods(instanceDir);
        Map<String, List<String>> seenIds = new LinkedHashMap<>();
        Set<String> presentIds = new HashSet<>();
        for (ModInfo m : mods) {
            if (!m.enabled) continue;
            presentIds.add(m.modId.toLowerCase(Locale.ROOT));
            seenIds.computeIfAbsent(m.modId.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(m.file.getName());
            if ("server".equals(m.environment)) {
                report.serverOnlyOnClient.add(m.name + " (" + m.file.getName() + ")");
            }
        }
        for (Map.Entry<String, List<String>> e : seenIds.entrySet()) {
            if (e.getValue().size() > 1) {
                report.duplicates.add(e.getKey() + ": " + String.join(", ", e.getValue()));
            }
        }
        for (ModInfo m : mods) {
            if (!m.enabled) continue;
            for (String dep : m.requiredDeps) {
                String low = dep.toLowerCase(Locale.ROOT);
                if (!presentIds.contains(low) && !isBuiltinModId(low)) {
                    report.missingDeps.add(m.name + " 需要 " + dep);
                }
            }
        }
        return report;
    }

    public static synchronized void setLocked(File instanceDir, String projectId, boolean locked) throws Exception {
        File state = new File(instanceDir.getCanonicalFile(), ".lumen-modrinth");
        File indexFile = new File(state, "index.json");
        if (!indexFile.isFile()) throw new IOException("该模组未由 Modrinth 跟踪，无法锁定版本");
        JSONObject index = readIndex(instanceDir);
        JSONObject rec = index.getJSONObject("projects").optJSONObject(projectId);
        if (rec == null) throw new IOException("索引中找不到项目 " + projectId);
        rec.put("locked", locked);
        VanillaInstaller.writeJson(indexFile, index);
    }

    public static synchronized boolean deleteMod(File instanceDir, File modFile) throws Exception {
        File modsDir = new File(instanceDir.getCanonicalFile(), "mods");
        File canonical = modFile.getCanonicalFile();
        if (!modsDir.equals(canonical.getParentFile())) throw new IOException("模组路径不在当前实例内");
        if (!canonical.delete()) return false;
        File indexFile = new File(instanceDir.getCanonicalFile(), ".lumen-modrinth/index.json");
        if (indexFile.isFile()) {
            JSONObject index = readIndex(instanceDir);
            JSONObject projects = index.optJSONObject("projects");
            if (projects != null) {
                String targetName = canonical.getName();
                if (targetName.endsWith(".disabled")) targetName = targetName.substring(0, targetName.length() - 9);
                String rel = "mods/" + targetName;
                List<String> toRemove = new ArrayList<>();
                for (Iterator<?> it = projects.keys(); it.hasNext(); ) {
                    String pid = (String) it.next();
                    JSONObject rec = projects.optJSONObject(pid);
                    if (rec != null && rel.equalsIgnoreCase(rec.optString("path", ""))) toRemove.add(pid);
                }
                for (String pid : toRemove) projects.remove(pid);
                VanillaInstaller.writeJson(indexFile, index);
            }
        }
        return true;
    }

    /** Completes missing required dependencies for all enabled Modrinth-recognized mods in the instance. */
    public static List<File> completeDependencies(File instanceDir, String game, String loader) throws Exception {
        return completeDependencies(instanceDir, game, loader, Modrinth.LIVE);
    }

    static List<File> completeDependencies(File instanceDir, String game, String loader, Modrinth.Source source) throws Exception {
        List<ModInfo> mods = listMods(instanceDir);
        List<File> added = new ArrayList<>();
        Set<String> seenProjects = new HashSet<>();
        for (ModInfo m : mods) {
            if (!m.enabled) continue;
            JSONObject version;
            try {
                version = new JSONObject(source.json("/version_file/" + m.sha512 + "?algorithm=sha512"));
            } catch (IOException ignored) {
                continue;
            }
            JSONArray deps = version.optJSONArray("dependencies");
            if (deps == null) continue;
            for (int i = 0; i < deps.length(); i++) {
                JSONObject d = deps.getJSONObject(i);
                if (!"required".equals(d.optString("dependency_type"))) continue;
                String pid = d.isNull("project_id") ? null : d.optString("project_id", null);
                if (pid != null && seenProjects.add(pid)) {
                    added.addAll(ModrinthFiles.install(source, pid, "mod", game, loader, instanceDir));
                }
            }
        }
        return added;
    }

    /** Updates all unlocked Modrinth-tracked mods and writes a rollback snapshot before replacing files. */
    public static BatchUpdateResult updateAll(File instanceDir, String game, String loader) throws Exception {
        return updateAll(instanceDir, game, loader, Modrinth.LIVE);
    }

    static synchronized BatchUpdateResult updateAll(File instanceDir, String game, String loader, Modrinth.Source source) throws Exception {
        File root = instanceDir.getCanonicalFile();
        File state = new File(root, ".lumen-modrinth");
        File indexFile = new File(state, "index.json");
        if (!indexFile.isFile()) return new BatchUpdateResult(0, 0, 0, null, Collections.emptyList());
        JSONObject index = readIndex(root);
        JSONObject projects = index.optJSONObject("projects");
        if (projects == null || projects.length() == 0) return new BatchUpdateResult(0, 0, 0, null, Collections.emptyList());

        int skippedLocked = 0;
        int upToDate = 0;
        Map<String, JSONObject> pendingVersions = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (Iterator<?> it = projects.keys(); it.hasNext(); ) keys.add((String) it.next());
        Collections.sort(keys);

        for (String pid : keys) {
            JSONObject rec = projects.getJSONObject(pid);
            if (!rec.optString("path", "").startsWith("mods/")) continue;
            if (rec.optBoolean("locked", false)) { skippedLocked++; continue; }
            String currentVersion = rec.getString("version");
            String filter = "?game_versions=" + Modrinth.enc(new JSONArray().put(game).toString())
                    + "&loaders=" + Modrinth.enc(new JSONArray().put(loader).toString());
            JSONArray available = new JSONArray(source.json("/project/" + pid + "/version" + filter));
            JSONObject newest = null;
            for (int i = 0; i < available.length(); i++) {
                JSONObject v = available.getJSONObject(i);
                if (newest == null) newest = v;
                if ("release".equals(v.optString("version_type"))) { newest = v; break; }
            }
            if (newest == null || currentVersion.equals(newest.getString("id"))) {
                upToDate++;
                continue;
            }
            pendingVersions.put(pid, newest);
        }

        if (pendingVersions.isEmpty()) {
            return new BatchUpdateResult(0, upToDate, skippedLocked, null, Collections.emptyList());
        }

        // Create rollback snapshot before modifying any mod JAR.
        String rollbackId = "rb-" + System.currentTimeMillis();
        File rbDir = new File(state, "rollback/" + rollbackId);
        VerifiedDownloads.directory(rbDir);
        VanillaInstaller.writeJson(new File(rbDir, "index.json"), index);
        JSONArray snapshotFiles = new JSONArray();
        List<String> details = new ArrayList<>();
        int updated = 0;

        for (Map.Entry<String, JSONObject> entry : pendingVersions.entrySet()) {
            String pid = entry.getKey();
            JSONObject oldRec = projects.getJSONObject(pid);
            String oldPath = oldRec.getString("path");
            File oldFile = VanillaInstaller.safe(root, oldPath);
            if (oldFile.isFile()) {
                File bak = new File(rbDir, pid + ".jar.bak");
                copyFile(oldFile, bak);
                snapshotFiles.put(new JSONObject().put("project", pid).put("oldPath", oldPath).put("backup", bak.getName()));
            }
            JSONObject newest = entry.getValue();
            JSONObject primary = Modrinth.primary(newest);
            Modrinth.Entry nextEntry = new Modrinth.Entry(pid, newest.getString("id"), "mod", primary,
                    newest.optJSONArray("dependencies") != null ? newest.getJSONArray("dependencies") : new JSONArray());
            File newFile = VanillaInstaller.safe(root, nextEntry.path);
            File staged = File.createTempFile("upd-", ".part", rbDir);
            try {
                try (InputStream in = source.file(nextEntry.url); OutputStream out = new FileOutputStream(staged)) {
                    if (VerifiedDownloads.transfer(in, out, nextEntry.size) != nextEntry.size)
                        throw new IOException("更新文件长度不符 " + nextEntry.path);
                }
                if (!nextEntry.hash.equals(VerifiedDownloads.digest(staged, "SHA-512"))) {
                    throw new IOException("更新文件 SHA-512 校验失败 " + nextEntry.path);
                }
                if (oldFile.isFile() && !oldFile.equals(newFile)) oldFile.delete();
                VerifiedDownloads.directory(newFile.getParentFile());
                VerifiedDownloads.replace(staged, newFile);
                JSONObject newRec = nextEntry.receipt().put("locked", false);
                projects.put(pid, newRec);
                details.add(pid + " → " + newest.optString("version_number", newest.getString("id")));
                updated++;
            } finally {
                if (staged.exists()) staged.delete();
            }
        }
        VanillaInstaller.writeJson(new File(rbDir, "manifest.json"), new JSONObject().put("files", snapshotFiles));
        VanillaInstaller.writeJson(indexFile, index);
        return new BatchUpdateResult(updated, upToDate, skippedLocked, rollbackId, details);
    }

    public static List<String> listRollbacks(File instanceDir) {
        File rbRoot = new File(instanceDir, ".lumen-modrinth/rollback");
        File[] dirs = rbRoot.listFiles(f -> f.isDirectory() && new File(f, "manifest.json").isFile());
        if (dirs == null || dirs.length == 0) return Collections.emptyList();
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        List<String> ids = new ArrayList<>();
        for (File d : dirs) ids.add(d.getName());
        return ids;
    }

    public static synchronized int rollback(File instanceDir, String rollbackId) throws Exception {
        if (rollbackId == null || !rollbackId.matches("rb-[0-9]{1,24}")) throw new IOException("无效的回滚点标识");
        File root = instanceDir.getCanonicalFile();
        File state = new File(root, ".lumen-modrinth");
        File rbDir = VanillaInstaller.safe(new File(state, "rollback"), rollbackId);
        JSONObject oldIndex = VanillaInstaller.readJson(new File(rbDir, "index.json"));
        JSONObject currentIndex = readIndex(root);
        JSONObject manifest = VanillaInstaller.readJson(new File(rbDir, "manifest.json"));
        JSONArray files = manifest.getJSONArray("files");
        JSONObject currentProjects = currentIndex.optJSONObject("projects");
        int restored = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.getJSONObject(i);
            String pid = item.getString("project");
            String oldPath = item.getString("oldPath");
            File bak = VanillaInstaller.safe(rbDir, item.getString("backup"));
            if (currentProjects != null && currentProjects.has(pid)) {
                String curPath = currentProjects.getJSONObject(pid).optString("path", "");
                if (!curPath.isEmpty() && !curPath.equals(oldPath)) {
                    File curFile = VanillaInstaller.safe(root, curPath);
                    if (curFile.isFile()) curFile.delete();
                }
            }
            File dst = VanillaInstaller.safe(root, oldPath);
            VerifiedDownloads.directory(dst.getParentFile());
            copyFile(bak, dst);
            restored++;
        }
        VanillaInstaller.writeJson(new File(state, "index.json"), oldIndex);
        return restored;
    }

    private static JSONObject readIndex(File instanceDir) throws Exception {
        File idx = new File(instanceDir.getCanonicalFile(), ".lumen-modrinth/index.json");
        if (!idx.isFile()) return new JSONObject().put("projects", new JSONObject());
        return VanillaInstaller.readJson(idx);
    }

    private static boolean isBuiltinModId(String id) {
        String low = id.toLowerCase(Locale.ROOT);
        return low.equals("minecraft") || low.equals("java") || low.equals("fabricloader")
                || low.equals("fabric") || low.equals("fabric-api") || low.equals("quilt_loader")
                || low.equals("forge") || low.equals("neoforge") || low.startsWith("fabric-");
    }

    private static String stripJarExt(String name) {
        if (name.endsWith(".jar.disabled")) return name.substring(0, name.length() - 13);
        if (name.endsWith(".jar")) return name.substring(0, name.length() - 4);
        return name;
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > 2 * 1024 * 1024) throw new IOException("模组元数据过大");
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            VerifiedDownloads.transfer(in, out, 2 * 1024 * 1024);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File tmp = File.createTempFile("mod-", ".tmp", dst.getParentFile());
        try {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
                VerifiedDownloads.transfer(in, out, src.length());
            }
            VerifiedDownloads.replace(tmp, dst);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }
}
