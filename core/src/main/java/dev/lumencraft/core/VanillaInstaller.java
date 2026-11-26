package dev.lumencraft.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Installs the official, platform-independent vanilla distribution files into app-private storage. */
public final class VanillaInstaller {
    public static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final int JSON_LIMIT = 16 * 1024 * 1024;
    private static final Object[] INSTALL_LOCKS = new Object[16];
    static { for (int i = 0; i < INSTALL_LOCKS.length; i++) INSTALL_LOCKS[i] = new Object(); }
    private final File root;
    private final VerifiedDownloads downloads;
    private final String manifest;
    private List<Version> catalog;
    private long refreshed;

    public interface Progress { void update(String label, int done, int total); }

    public static final class Version {
        public final String id, type, url, sha1;
        Version(JSONObject entry) throws Exception {
            id = entry.getString("id");
            type = entry.getString("type");
            url = entry.getString("url");
            sha1 = entry.getString("sha1");
        }
        @Override public String toString() { return id; }
    }

    public VanillaInstaller(File root) { this(root, new VerifiedDownloads(), MANIFEST); }
    VanillaInstaller(File root, VerifiedDownloads downloads, String manifest) {
        this.root = root.getAbsoluteFile();
        this.downloads = downloads;
        this.manifest = manifest;
    }

    public synchronized List<Version> versions() throws Exception {
        if (catalog != null && System.nanoTime() - refreshed < 600_000_000_000L) return catalog;
        String json = new String(downloads.metadata(manifest, JSON_LIMIT), StandardCharsets.UTF_8);
        JSONArray entries = new JSONObject(json).getJSONArray("versions");
        List<Version> result = new ArrayList<>(entries.length());
        for (int i = 0; i < entries.length(); i++) result.add(new Version(entries.getJSONObject(i)));
        catalog = Collections.unmodifiableList(result);
        refreshed = System.nanoTime();
        return catalog;
    }

    public File install(String id, boolean server, Progress progress) throws Exception {
        return install(id, id, server, progress);
    }

    /** A caller may assign a separate directory to a server-specific game instance. */
    public File install(String id, String instanceName, boolean server, Progress progress) throws Exception {
        segment(id);
        if (instanceName == null || !instanceName.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}._ +\\-]{0,127}")
                || instanceName.endsWith(".") || instanceName.endsWith(" ")
                || instanceName.getBytes(StandardCharsets.UTF_8).length > 255
                || instanceName.matches("(?i)(CON|NUL|PRN|AUX|COM[1-9]|LPT[1-9])(\\..*)?"))
            throw new IOException("实例名称无效");
        File instance = safe(new File(root, server ? "servers" : "instances"), instanceName);
        synchronized (INSTALL_LOCKS[instance.hashCode() & (INSTALL_LOCKS.length - 1)]) {
            Progress report = progress == null ? (label, done, total) -> {} : progress;
            report.update("获取官方版本清单", 0, 1);
            Version entry = null;
            for (Version v : versions()) if (v.id.equals(id)) { entry = v; break; }
            if (entry == null) throw new IOException("官方清单没有此版本 " + id);
            File descriptor = fetch(entry.url, entry.sha1, -1);
            JSONObject version = readJson(descriptor);
            if (!id.equals(version.getString("id")) || version.has("inheritsFrom"))
                throw new IOException("版本元数据不匹配或需要继承解析");
            JSONObject java = version.optJSONObject("javaVersion");
            if (java == null || java.getInt("majorVersion") < 1)
                throw new IOException("官方元数据没有指定 Java 版本");
            JSONObject artifact = version.getJSONObject("downloads").optJSONObject(server ? "server" : "client");
            if (artifact == null) throw new IOException("此版本没有官方" + (server ? "服务端" : "客户端") + "文件");

            File marker = new File(instance, "install.json");
            if (marker.isFile() && !id.equals(readJson(marker).getString("id")))
                throw new IOException("此实例名称已用于其他版本");
            VerifiedDownloads.directory(instance);
            report.update(server ? "下载并验证服务端" : "下载并验证客户端", 0, 1);
            File jar = fetch(artifact);
            JSONObject receipt = new JSONObject();
            receipt.put("format", 1).put("id", id).put("kind", server ? "server" : "client");
            receipt.put("javaMajor", java.getInt("majorVersion"));
            receipt.put("metadataSha1", entry.sha1);
            receipt.put("metadata", relative(descriptor));
            if (server) {
                File target = new File(instance, "server.jar");
                materialize(jar, target, artifact.getString("sha1"));
                receipt.put("jar", relative(target));
            } else {
                receipt.put("jar", relative(jar));
                installClient(version, instance, receipt, report);
            }
            writeJson(marker, receipt);
            report.update("文件安装完成", 1, 1);
            return instance;
        }
    }

    private void installClient(JSONObject version, File instance, JSONObject receipt, Progress report) throws Exception {
        JSONArray libraries = version.getJSONArray("libraries");
        List<JSONObject> jars = new ArrayList<>();
        JSONArray classpath = new JSONArray();
        for (int i = 0; i < libraries.length(); i++) {
            JSONObject lib = libraries.getJSONObject(i);
            if (!allowed(lib.optJSONArray("rules"))) continue;
            String coordinate = lib.getString("name");
            if (coordinate.matches(".*:natives-[^:]+$")) continue;
            JSONObject parts = lib.getJSONObject("downloads");
            JSONObject item = parts.optJSONObject("artifact");
            if (item == null && lib.has("natives")) continue;
            if (item == null) throw new IOException("库缺少已校验的文件 " + coordinate);
            jars.add(item);
            classpath.put(relative(object(item.getString("sha1"))));
        }
        batch("验证 Java 库", jars.size(), report, i -> fetch(jars.get(i)));
        classpath.put(receipt.getString("jar"));
        receipt.put("classpath", classpath);
        JSONObject logging = version.optJSONObject("logging");
        if (logging != null && logging.has("client")) {
            JSONObject log = logging.getJSONObject("client").getJSONObject("file");
            receipt.put("logConfig", relative(fetch(log)));
        }

        JSONObject indexInfo = version.getJSONObject("assetIndex");
        String assetId = segment(indexInfo.getString("id"));
        File pinnedIndex = fetch(indexInfo);
        File assetIndex = safe(new File(root, "shared/assets/indexes"), assetId + ".json");
        materialize(pinnedIndex, assetIndex, indexInfo.getString("sha1"));
        JSONObject index = readJson(pinnedIndex);
        JSONObject objects = index.getJSONObject("objects");
        List<String> keys = new ArrayList<>();
        for (Iterator<?> it = objects.keys(); it.hasNext();) keys.add((String) it.next());
        boolean virtual = index.optBoolean("virtual", false);
        boolean resources = index.optBoolean("map_to_resources", false);
        batch("验证游戏资源", keys.size(), report, i -> {
            String key = keys.get(i);
            JSONObject value = objects.getJSONObject(key);
            String hash = value.getString("hash");
            File dst = object(hash);
            String path = hash.substring(0, 2) + "/" + hash;
            downloads.fetch("https://resources.download.minecraft.net/" + path, dst, hash, value.getLong("size"));
            if (virtual) materialize(dst, safe(new File(root, "shared/assets/virtual/" + assetId), key), hash);
            if (resources) materialize(dst, safe(new File(instance, "resources"), key), hash);
        });
        receipt.put("assetIndex", assetId).put("assetRoot", "shared/assets");
        receipt.put("mainClass", version.getString("mainClass"));
    }

    // Evaluate desktop Linux Java-library rules. Desktop native classifiers are never used on Android.
    static boolean allowed(JSONArray rules) throws Exception {
        if (rules == null) return true;
        boolean allow = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            JSONObject os = rule.optJSONObject("os");
            if (os != null) {
                if (!os.optString("name", "linux").equals("linux")) continue;
                if (os.has("arch") && !System.getProperty("os.arch", "").matches(os.getString("arch"))) continue;
                if (os.has("version") && !System.getProperty("os.version", "").matches(os.getString("version"))) continue;
            }
            if (rule.has("features")) throw new IOException("遇到未支持的游戏库条件");
            String action = rule.getString("action");
            if (!action.equals("allow") && !action.equals("disallow")) throw new IOException("未知的游戏库规则");
            allow = action.equals("allow");
        }
        return allow;
    }

    private interface Work { void perform(int n) throws Exception; }
    private static void batch(String label, int total, Progress progress, Work work) throws Exception {
        progress.update(label, 0, total);
        if (total == 0) return;
        int workers = Math.min(4, total);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CompletionService<Void> result = new ExecutorCompletionService<>(pool);
        AtomicInteger index = new AtomicInteger(), done = new AtomicInteger();
        try {
            for (int j = 0; j < workers; j++) result.submit(() -> {
                for (int n = index.getAndIncrement(); n < total; n = index.getAndIncrement()) {
                    VerifiedDownloads.interrupted();
                    work.perform(n);
                    synchronized (done) {
                        int count = done.incrementAndGet();
                        if (count == total || count % Math.max(1, total / 100) == 0)
                            progress.update(label, count, total);
                    }
                }
                return null;
            });
            for (int j = 0; j < workers; j++) result.take().get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("安装已取消");
        } finally { pool.shutdownNow(); }
    }

    private File fetch(JSONObject artifact) throws Exception {
        long size = artifact.getLong("size");
        if (size < 0) throw new IOException("文件大小无效");
        return fetch(artifact.getString("url"), artifact.getString("sha1"), size);
    }
    private File fetch(String url, String hash, long size) throws IOException {
        return downloads.fetch(url, object(hash), hash, size);
    }
    private File object(String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{40}")) throw new IOException("官方 SHA-1 格式无效");
        return new File(root, "shared/assets/objects/" + hash.substring(0, 2) + "/" + hash);
    }
    private String relative(File f) throws IOException {
        String base = root.getCanonicalPath() + File.separator;
        String path = f.getCanonicalPath();
        if (!path.startsWith(base)) throw new IOException("内容地址超出根目录");
        return path.substring(base.length()).replace(File.separatorChar, '/');
    }
    private static String segment(String s) throws IOException {
        if (s == null || !s.matches("[a-zA-Z0-9][a-zA-Z0-9._ +\\-]{0,127}"))
            throw new IOException("版本或实例名称无效");
        return s;
    }
    static File safe(File base, String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":"))
            throw new IOException("文件路径无效");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("文件路径无效");
        }
        File child = new File(base, path).getCanonicalFile();
        if (!child.getPath().startsWith(base.getCanonicalPath() + File.separator))
            throw new IOException("文件路径超出实例目录");
        return child;
    }
    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            VerifiedDownloads.transfer(in, out, JSON_LIMIT);
            return out.toByteArray();
        }
    }
    static JSONObject readJson(File f) throws Exception {
        return new JSONObject(new String(read(f), StandardCharsets.UTF_8));
    }
    static void writeJson(File file, JSONObject value) throws Exception {
        byte[] body = value.toString(2).getBytes(StandardCharsets.UTF_8);
        if (file.isFile() && Arrays.equals(read(file), body)) return;
        File tmp = File.createTempFile("receipt-", ".json.tmp", file.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
                out.getFD().sync();
            }
            VerifiedDownloads.replace(tmp, file);
        } finally { if (tmp.exists()) tmp.delete(); }
    }
    private static void materialize(File from, File to, String sha1) throws IOException {
        if (to.isFile() && to.length() == from.length() && VerifiedDownloads.digest(to, "SHA-1").equals(sha1)) return;
        VerifiedDownloads.directory(to.getParentFile());
        File tmp = File.createTempFile("file-", ".tmp", to.getParentFile());
        try {
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
                VerifiedDownloads.transfer(in, out, from.length());
            }
            VerifiedDownloads.replace(tmp, to);
        } finally { if (tmp.exists()) tmp.delete(); }
    }
}
