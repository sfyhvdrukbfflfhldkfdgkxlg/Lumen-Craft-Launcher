package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Blocking Modrinth v2 client. Callers choose an isolated instance and its installed loader. */
public final class Modrinth {
    private Modrinth() {}
    private static final String API = "https://api.modrinth.com/v2";
    private static final int PAGE = 20;
    static final Source LIVE = new LiveSource();

    public static final class Project {
        public final String id, title, description, slug, type;
        Project(JSONObject hit) throws JSONException {
            id = hit.getString("project_id"); title = hit.getString("title");
            description = hit.optString("description", ""); slug = hit.getString("slug");
            type = hit.getString("project_type");
        }
    }

    public static List<Project> search(String query, String type) throws Exception {
        if (!Arrays.asList("mod","modpack","resourcepack","shader","datapack").contains(type))
            throw new IOException("未知资源类型");
        String facets = new JSONArray().put(new JSONArray().put("project_type:"+type)).toString();
        JSONObject result = new JSONObject(LIVE.json("/search?limit="+PAGE+"&query="+enc(query.trim())+"&facets="+enc(facets)));
        JSONArray hits = result.getJSONArray("hits");
        List<Project> projects = new ArrayList<>();
        for (int i = 0; i < hits.length(); i++) projects.add(new Project(hits.getJSONObject(i)));
        return projects;
    }

    public static List<File> install(String projectId, String type, String game, String loader, File instanceDir) throws Exception {
        return ModrinthFiles.install(LIVE, id(projectId), type, game, loader, instanceDir);
    }

    /** Downloads the newest release (or newest available build) into caller-owned temporary storage. */
    public static File downloadPack(String projectId, File tempDir) throws Exception {
        String project = id(projectId);
        JSONObject info = new JSONObject(LIVE.json("/project/"+project));
        if (!project.equals(info.getString("id")) || !"modpack".equals(info.getString("project_type")))
            throw new IOException("所选项目不是 Modrinth 整合包");
        JSONArray available = new JSONArray(LIVE.json("/project/"+project+"/version"));
        JSONObject selected = null;
        for (int i=0; i<available.length(); i++) {
            JSONObject v=available.getJSONObject(i);
            if (!project.equals(v.getString("project_id"))) throw new IOException("整合包版本归属不符");
            if (selected == null) selected=v;
            if ("release".equals(v.optString("version_type"))) { selected=v; break; }
        }
        if (selected==null) throw new IOException("整合包没有可下载版本");
        JSONObject archive = primary(selected);
        if (!archive.getString("filename").toLowerCase(Locale.US).endsWith(".mrpack"))
            throw new IOException("不支持的整合包归档格式");
        VerifiedDownloads.directory(tempDir);
        File local = File.createTempFile("modrinth-pack-", ".mrpack", tempDir);
        try {
            PackImport.verifiedFile(LIVE, local, archive.getJSONObject("hashes"),
                archive.getLong("size"),new JSONArray().put(archive.getString("url")),PackImport.ARCHIVE_LIMIT);
            return local;
        } catch (Exception error) { local.delete(); throw error; }
    }

    static JSONObject primary(JSONObject version) throws Exception {
        JSONArray files=version.getJSONArray("files");
        JSONObject primary=null;
        for (int i=0; i<files.length(); i++) {
            JSONObject f=files.getJSONObject(i);
            if (files.length()==1 || f.optBoolean("primary",false)) {
                if (primary!=null) throw new IOException("多个主文件，无法自动选择");
                primary=f;
            }
        }
        if (primary==null) throw new IOException("缺少明确的主文件");
        return primary;
    }

    interface Source {
        String json(String path) throws IOException;
        InputStream file(String url) throws IOException;
    }

    static String read(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) {
                if (bytes.size() + n > limit) throw new IOException("元数据过大");
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String id(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("资源标识无效");
        return value;
    }
    static String enc(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }

    private static final class LiveSource implements Source {
        public String json(String path) throws IOException { return read(open(API+path, true), 32*1024*1024); }
        public InputStream file(String url) throws IOException { return open(url, false); }
        private InputStream open(String raw, boolean metadata) throws IOException {
            URL url = new URL(raw);
            for (int n=0; n<4; n++) {
                String host = url.getHost().toLowerCase(Locale.US);
                boolean allowed = metadata ? host.equals("api.modrinth.com") : Arrays.asList("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com").contains(host) || (n>0 && host.equals("release-assets.githubusercontent.com"));
                if (!allowed || !url.getProtocol().equals("https") || url.getUserInfo() != null ||
                    (url.getPort() != -1 && url.getPort() != 443)) throw new IOException("下载地址不在整合包支持的 HTTPS 来源内");
                final HttpURLConnection http = (HttpURLConnection)url.openConnection();
                http.setInstanceFollowRedirects(false); http.setConnectTimeout(10000); http.setReadTimeout(25000);
                http.setRequestProperty("User-Agent", "LumenCraftAndroid/Alpha1.2.0 (Android Minecraft launcher)");
                try {
                    int status = http.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = http.getHeaderField("Location");
                        if (location == null) throw new IOException("重定向缺少地址");
                        url = new URL(url, location); http.disconnect(); continue;
                    }
                    if (status != 200) throw new IOException("Modrinth HTTP "+status);
                    return new FilterInputStream(http.getInputStream()) {
                        public void close() throws IOException {
                            try { super.close(); } finally { http.disconnect(); }
                        }
                    };
                } catch (IOException e) { http.disconnect(); throw e; }
            }
            throw new IOException("下载地址重定向次数过多");
        }
    }

    static String folder(String type) throws IOException {
        if ("mod".equals(type)) return "mods";
        if ("resourcepack".equals(type)) return "resourcepacks";
        if ("shader".equals(type)) return "shaderpacks";
        throw new IOException("当前安装器无法安装此资源类型");
    }

    static final class Entry {
        final String project, version, path, hash, url;
        final long size;
        final JSONArray dependencies;
        Entry(String p, String v, String t, JSONObject f, JSONArray deps) throws Exception {
            project = id(p); version = id(v); dependencies = deps;
            String name = f.getString("filename");
            if (!name.matches("[^\\p{Cntrl}\\\\/:*?\"<>|]{1,240}") || name.startsWith(".") ||
                !name.toLowerCase(Locale.US).endsWith("mod".equals(t) ? ".jar" : ".zip"))
                throw new IOException("无法识别安装文件名 "+name);
            path = folder(t)+"/"+name;
            hash = f.getJSONObject("hashes").getString("sha512").toLowerCase(Locale.US);
            size = f.getLong("size"); url = f.getString("url");
            if (!hash.matches("[a-f0-9]{128}") || size <= 0 || size > 1024L*1024*1024)
                throw new IOException("文件长度或 SHA-512 无效");
        }
        JSONObject receipt() throws JSONException {
            return new JSONObject().put("version",version).put("path",path).put("hash",hash)
                .put("dependencies",dependencies);
        }
    }

    static final class Resolver {
        final Source source;
        final String game, loader;
        final JSONObject installed;
        final Map<String,Entry> entries = new LinkedHashMap<>();
        final Map<String,JSONObject> versions = new HashMap<>();
        final Map<String,String> pins;

        Resolver(Source source, String game, String loader, JSONObject installed) {
            this(source,game,loader,installed,Collections.<String,String>emptyMap());
        }

        Resolver(Source source, String game, String loader, JSONObject installed, Map<String,String> pins) {
            this.source = source; this.game = game; this.loader = loader; this.installed = installed;
            this.pins = pins;
        }

        void add(String project, String pin, String expectedType) throws Exception {
            JSONObject version = pin == null ? null : version(pin);
            if (version != null) {
                String owner = id(version.getString("project_id"));
                if (project != null && !owner.equals(project)) throw new IOException("依赖版本归属不匹配");
                project = owner;
            }
            project = id(project);
            String locked = pins.get(project);
            if (locked != null) {
                if (pin != null && !locked.equals(pin)) throw conflict(project);
                pin = locked;
                version = version(locked);
            }
            if (entries.containsKey(project)) {
                if (pin != null && !pin.equals(entries.get(project).version)) throw conflict(project);
                return;
            }
            if (entries.size() >= 96) throw new IOException("依赖数量超过限制");
            JSONObject meta = new JSONObject(source.json("/project/"+project));
            if (!project.equals(meta.getString("id"))) throw new IOException("项目标识不匹配");
            String type = meta.getString("project_type");
            if (expectedType != null && !expectedType.equals(type)) throw new IOException("项目类型不匹配");
            folder(type);
            if ("unsupported".equals(meta.optString("client_side"))) throw new IOException("仅服务端项目 "+project);
            if (type.equals("mod") && (loader == null || !loader.matches("[a-z0-9_-]+") || loader.equals("vanilla")))
                throw new IOException("请先选择实际安装的模组加载器");
            JSONObject previous = installed.optJSONObject(project);
            if (previous != null) {
                String oldId = previous.getString("version");
                if (pin != null && !oldId.equals(pin)) throw conflict(project);
                version = version(oldId);
            }
            if (version == null) version = choose(project, type);
            if (!project.equals(version.getString("project_id")) || !supports(version, type))
                throw new IOException("依赖不适用于目标游戏和加载器 "+project);
            if (serverOnly(version)) throw new IOException("仅服务端版本 "+project);
            JSONObject primary = primary(version);
            JSONArray deps = version.optJSONArray("dependencies");
            if (deps == null) deps = new JSONArray();
            Entry entry = new Entry(project, version.getString("id"), type, primary, deps);
            entries.put(project, entry);
            for (int i=0; i<deps.length(); i++) {
                JSONObject d = deps.getJSONObject(i);
                if ("required".equals(d.getString("dependency_type"))) {
                    String pid = optional(d,"project_id"), vid = optional(d,"version_id");
                    if (pid==null && vid==null) throw new IOException("外部前置依赖需要手动安装");
                    add(pid, vid, null);
                }
            }
        }
        private static IOException conflict(String p) { return new IOException("存在互不兼容的版本约束 "+p); }

        private JSONObject version(String pin) throws Exception {
            id(pin);
            if (!versions.containsKey(pin)) {
                JSONObject v = new JSONObject(source.json("/version/"+pin));
                if (!pin.equals(v.getString("id"))) throw new IOException("版本标识不匹配");
                versions.put(pin, v);
            }
            return versions.get(pin);
        }

        private JSONObject choose(String project, String type) throws Exception {
            String filter = "?game_versions="+enc(new JSONArray().put(game).toString());
            if (type.equals("mod")) filter += "&loaders="+enc(new JSONArray().put(loader).toString());
            JSONArray versions = new JSONArray(source.json("/project/"+project+"/version"+filter));
            JSONObject selected = null;
            for (int i=0; i<versions.length(); i++) {
                JSONObject v = versions.getJSONObject(i);
                if (!supports(v,type)) continue;
                // API returns newest first. Prefer published releases to beta/alpha builds.
                if (selected==null) selected=v;
                if ("release".equals(v.optString("version_type"))) return v;
            }
            if (selected==null) throw new IOException("没有匹配的版本 "+project);
            return selected;
        }

        private boolean supports(JSONObject v, String type) {
            return contains(v.optJSONArray("game_versions"),game) &&
                (!type.equals("mod") || contains(v.optJSONArray("loaders"), loader));
        }
        private static boolean contains(JSONArray a,String value) {
            if (a!=null) for (int i=0; i<a.length(); i++) if (a.optString(i).equals(value)) return true;
            return false;
        }
        private static String optional(JSONObject o,String key) { return o.isNull(key) ? null : o.optString(key); }

        void checkConflicts(JSONObject merged) throws Exception {
            Set<String> paths = new HashSet<>();
            Set<String> versionIds = new HashSet<>();
            for (Iterator<String> i=merged.keys(); i.hasNext();) {
                JSONObject r=merged.getJSONObject(i.next());
                if (!paths.add(r.getString("path").toLowerCase(Locale.US))) throw new IOException("两个项目使用相同的文件名");
                versionIds.add(r.getString("version"));
            }
            for (Iterator<String> i=merged.keys(); i.hasNext();) {
                String p=i.next();
                JSONArray deps=merged.getJSONObject(p).getJSONArray("dependencies");
                for (int j=0; j<deps.length(); j++) {
                    JSONObject d=deps.getJSONObject(j);
                    if (!"incompatible".equals(d.optString("dependency_type"))) continue;
                    String pid=optional(d,"project_id"), vid=optional(d,"version_id");
                    if (vid!=null ? versionIds.contains(vid) : pid!=null && merged.has(pid))
                        throw new IOException("已声明的不兼容依赖 "+p+" / "+(vid!=null?vid:pid));
                }
            }
        }
    }

    static boolean serverOnly(JSONObject version) {
        String environment=version.optString("environment");
        return "server_only".equals(environment) || "dedicated_server_only".equals(environment);
    }
}
