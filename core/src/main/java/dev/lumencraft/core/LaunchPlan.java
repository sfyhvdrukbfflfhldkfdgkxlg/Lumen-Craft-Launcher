package dev.lumencraft.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Assembles verified launch plans (classpath, JVM args, game args) from Mojang + loader metadata. */
public final class LaunchPlan {
    public enum Readiness {
        READY_CLASSIC,
        NEEDS_LWJGL2_BRIDGE,
        NEEDS_LWJGL3_NATIVE
    }

    public static final class AccountSpec {
        public final String username, uuid, accessToken, userType, authlibApiUrl;
        public AccountSpec(String username, String uuid, String accessToken, String userType, String authlibApiUrl) {
            this.username = username == null || username.isEmpty() ? "Player" : username;
            this.uuid = uuid == null || uuid.isEmpty() ? "00000000000000000000000000000000" : uuid;
            this.accessToken = accessToken == null ? "0" : accessToken;
            this.userType = userType == null || userType.isEmpty() ? "legacy" : userType;
            this.authlibApiUrl = authlibApiUrl;
        }
    }

    public final String gameId, loader, mainClass, assetIndex;
    public final int javaMajor, heapMiB;
    public final File instanceDir, assetsDir;
    public final List<File> classpath;
    public final List<String> jvmArgs, gameArgs;
    public final Readiness readiness;
    public final String readinessNote;

    private LaunchPlan(String gameId, String loader, String mainClass, String assetIndex,
                       int javaMajor, int heapMiB, File instanceDir, File assetsDir,
                       List<File> classpath, List<String> jvmArgs, List<String> gameArgs,
                       Readiness readiness, String readinessNote) {
        this.gameId = gameId;
        this.loader = loader;
        this.mainClass = mainClass;
        this.assetIndex = assetIndex;
        this.javaMajor = javaMajor;
        this.heapMiB = heapMiB;
        this.instanceDir = instanceDir;
        this.assetsDir = assetsDir;
        this.classpath = Collections.unmodifiableList(classpath);
        this.jvmArgs = Collections.unmodifiableList(jvmArgs);
        this.gameArgs = Collections.unmodifiableList(gameArgs);
        this.readiness = readiness;
        this.readinessNote = readinessNote;
    }

    public static LaunchPlan build(File root, File instanceDir, AccountSpec account,
                                   int heapMiB, int width, int height) throws Exception {
        File canonRoot = root.getCanonicalFile();
        File canonInstance = instanceDir.getCanonicalFile();
        JSONObject install = VanillaInstaller.readJson(new File(canonInstance, "install.json"));
        if (!"client".equals(install.getString("kind"))) throw new IOException("只能为客户端实例构建启动计划");
        String gameId = install.getString("id");
        int javaMajor = install.getInt("javaMajor");

        File loaderFile = new File(canonInstance, "loader.json");
        JSONObject loaderJson = loaderFile.isFile() ? VanillaInstaller.readJson(loaderFile) : null;
        String loader = loaderJson != null ? loaderJson.optString("loader", "vanilla") : "vanilla";

        if ("rubydung".equals(install.optString("adapter")) && "vanilla".equals(loader)) {
            File jar = new File(canonRoot, install.getString("jar")).getCanonicalFile();
            if (!jar.isFile()) throw new IOException("缺少游戏主文件 " + jar.getName());
            return new LaunchPlan(gameId, "vanilla", "dev.lumencraft.bridge.Session", "",
                    8, Math.max(128, Math.min(512, heapMiB)), canonInstance, new File(canonRoot, "shared/assets"),
                    Collections.singletonList(jar),
                    Arrays.asList("-Dminecraft.launcher.brand=LumenCraft"),
                    Collections.emptyList(),
                    Readiness.READY_CLASSIC,
                    "RubyDung 远古版适配模块已就绪");
        }

        File metaFile = new File(canonRoot, install.getString("metadata")).getCanonicalFile();
        JSONObject versionMeta = VanillaInstaller.readJson(metaFile);
        JSONObject loaderProfile = loaderJson != null ? loaderJson.optJSONObject("profile") : null;

        String mainClass = install.optString("mainClass", versionMeta.optString("mainClass", ""));
        if (loaderJson != null && !loaderJson.optString("mainClass", "").isEmpty()) {
            mainClass = loaderJson.getString("mainClass");
        }
        if (mainClass.isEmpty()) throw new IOException("版本元数据缺少主启动类");

        // Assemble deduplicated classpath: loader libraries first, then vanilla libraries & game jar.
        LinkedHashSet<File> cpSet = new LinkedHashSet<>();
        if (loaderJson != null && loaderJson.has("classpath")) {
            JSONArray lcp = loaderJson.getJSONArray("classpath");
            for (int i = 0; i < lcp.length(); i++) {
                File f = new File(canonRoot, lcp.getString(i)).getCanonicalFile();
                if (!f.isFile()) throw new IOException("缺少加载器运行库 " + f.getName());
                cpSet.add(f);
            }
        }
        JSONArray baseCp = install.optJSONArray("classpath");
        if (baseCp != null) {
            for (int i = 0; i < baseCp.length(); i++) {
                File f = new File(canonRoot, baseCp.getString(i)).getCanonicalFile();
                if (!f.isFile()) throw new IOException("缺少游戏运行库 " + f.getName());
                cpSet.add(f);
            }
        } else {
            File jar = new File(canonRoot, install.getString("jar")).getCanonicalFile();
            if (!jar.isFile()) throw new IOException("缺少游戏主文件 " + jar.getName());
            cpSet.add(jar);
        }

        File assetsDir = new File(canonRoot, install.optString("assetRoot", "shared/assets")).getCanonicalFile();
        String assetIndex = install.optString("assetIndex", "");
        AccountSpec acc = account != null ? account : new AccountSpec("Player", null, "0", "legacy", null);

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", acc.username);
        vars.put("version_name", gameId);
        vars.put("game_directory", canonInstance.getAbsolutePath());
        vars.put("assets_root", assetsDir.getAbsolutePath());
        vars.put("game_assets", new File(assetsDir, "virtual/" + assetIndex).getAbsolutePath());
        vars.put("assets_index_name", assetIndex);
        vars.put("auth_uuid", acc.uuid);
        vars.put("auth_access_token", acc.accessToken);
        vars.put("auth_session", acc.accessToken);
        vars.put("user_type", acc.userType);
        vars.put("user_properties", "{}");
        vars.put("version_type", "LumenCraft");
        vars.put("launcher_name", "LumenCraft");
        vars.put("launcher_version", "Alpha1.2.0");
        vars.put("natives_directory", new File(canonInstance, "natives").getAbsolutePath());
        vars.put("library_directory", new File(canonRoot, "shared/loaders").getAbsolutePath());
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("resolution_width", Integer.toString(Math.max(320, width)));
        vars.put("resolution_height", Integer.toString(Math.max(240, height)));

        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Dminecraft.launcher.brand=LumenCraft");
        jvmArgs.add("-Dminecraft.launcher.version=Alpha1.2.0");
        if (acc.authlibApiUrl != null && !acc.authlibApiUrl.isEmpty()) {
            jvmArgs.add("-Dlumen.authlib.api=" + acc.authlibApiUrl);
        }
        collectArguments(versionMeta.optJSONObject("arguments"), "jvm", vars, jvmArgs);
        if (loaderProfile != null) {
            collectArguments(loaderProfile.optJSONObject("arguments"), "jvm", vars, jvmArgs);
        }

        List<String> gameArgs = new ArrayList<>();
        if (versionMeta.has("arguments")) {
            collectArguments(versionMeta.optJSONObject("arguments"), "game", vars, gameArgs);
        } else if (versionMeta.has("minecraftArguments")) {
            for (String token : versionMeta.getString("minecraftArguments").trim().split("\\s+")) {
                if (!token.isEmpty()) gameArgs.add(substitute(token, vars));
            }
        }
        if (loaderProfile != null) {
            collectArguments(loaderProfile.optJSONObject("arguments"), "game", vars, gameArgs);
            if (loaderProfile.has("minecraftArguments") && gameArgs.isEmpty()) {
                for (String token : loaderProfile.getString("minecraftArguments").trim().split("\\s+")) {
                    if (!token.isEmpty()) gameArgs.add(substitute(token, vars));
                }
            }
        }
        if (loaderJson != null && !loaderJson.optString("tweakClass", "").isEmpty()) {
            gameArgs.add("--tweakClass");
            gameArgs.add(loaderJson.getString("tweakClass"));
        }

        boolean usesLwjgl3 = false;
        JSONArray libs = versionMeta.optJSONArray("libraries");
        if (libs != null) {
            for (int i = 0; i < libs.length(); i++) {
                String name = libs.getJSONObject(i).optString("name", "");
                if (name.startsWith("org.lwjgl:lwjgl:") || name.contains("lwjgl-glfw")) {
                    usesLwjgl3 = true;
                    break;
                }
            }
        }
        Readiness readiness = usesLwjgl3 ? Readiness.NEEDS_LWJGL3_NATIVE : Readiness.NEEDS_LWJGL2_BRIDGE;
        String note = usesLwjgl3
                ? "此版本（Java " + javaMajor + "）使用 LWJGL 3（GLFW/OpenAL/桌面 GL 3.2+）。启动参数、依赖和类路径已校验就绪（" + cpSet.size() + " 个 JAR），Android 端原生 LWJGL 3/GLFW 窗口与渲染后端仍待实机适配。"
                : "此版本（Java " + javaMajor + "）使用 LWJGL 2。启动参数、资源与类路径已校验就绪（" + cpSet.size() + " 个 JAR），除五个已验收 RubyDung 版本外，其余 LWJGL 2 客户端窗口与音频桥仍待逐版本验收。";

        return new LaunchPlan(gameId, loader, mainClass, assetIndex, javaMajor,
                Math.max(256, Math.min(4096, heapMiB)), canonInstance, assetsDir,
                new ArrayList<>(cpSet), jvmArgs, gameArgs, readiness, note);
    }

    private static void collectArguments(JSONObject argsObj, String section, Map<String, String> vars, List<String> out) throws Exception {
        if (argsObj == null || !argsObj.has(section)) return;
        JSONArray arr = argsObj.getJSONArray(section);
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.get(i);
            if (item instanceof String) {
                String resolved = substitute((String) item, vars);
                // Avoid passing literal -cp ${classpath} into extra JVM args file since headless.cpp sets -Djava.class.path.
                if (!"-cp".equals(resolved) && !"${classpath}".equals(item)) out.add(resolved);
            } else if (item instanceof JSONObject) {
                JSONObject obj = (JSONObject) item;
                if (!evalArgRules(obj.optJSONArray("rules"))) continue;
                Object val = obj.opt("value");
                if (val instanceof String) {
                    out.add(substitute((String) val, vars));
                } else if (val instanceof JSONArray) {
                    JSONArray vals = (JSONArray) val;
                    for (int j = 0; j < vals.length(); j++) out.add(substitute(vals.getString(j), vars));
                }
            }
        }
    }

    private static boolean evalArgRules(JSONArray rules) {
        if (rules == null) return true;
        boolean allow = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            JSONObject features = rule.optJSONObject("features");
            if (features != null) {
                // Demo/quick-play/custom-resolution flags stay off by default unless explicitly requested.
                if (features.optBoolean("is_demo_user", false)
                        || features.optBoolean("has_custom_resolution", false)
                        || features.optBoolean("has_quick_plays_support", false)
                        || features.optBoolean("is_quick_play_singleplayer", false)
                        || features.optBoolean("is_quick_play_multiplayer", false)
                        || features.optBoolean("is_quick_play_realms", false)) {
                    continue;
                }
            }
            JSONObject os = rule.optJSONObject("os");
            if (os != null && !os.optString("name", "linux").equals("linux")) continue;
            allow = "allow".equals(rule.optString("action"));
        }
        return allow;
    }

    private static String substitute(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /** Writes verified JVM options for liblumenjvm.so (LUMEN_JVM_ARGS_FILE). */
    public File writeJvmArgsFile(File dest) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String arg : jvmArgs) {
            if (!arg.isEmpty() && arg.indexOf('\n') < 0 && arg.indexOf('\r') < 0) {
                sb.append(arg).append('\n');
            }
        }
        File tmp = File.createTempFile("jvm-args-", ".tmp", dest.getParentFile());
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            VerifiedDownloads.replace(tmp, dest);
            return dest;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }
}
