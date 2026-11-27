package dev.lumencraft.core;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Download source selection, legal mirror rewriting (with strict official digest checks), and live speed benchmarking. */
public final class MirrorBench {
    public enum SourceId {
        OFFICIAL("官方源 (Mojang)", "https://piston-meta.mojang.com"),
        BMCLAPI("BMCLAPI 镜像", "https://bmclapi2.bangbang93.com"),
        CUSTOM("自定义镜像源", "");

        public final String label;
        public final String probeBase;

        SourceId(String label, String probeBase) {
            this.label = label;
            this.probeBase = probeBase;
        }
    }

    public static final class Result {
        public final SourceId id;
        public final String baseUrl;
        public final boolean reachable;
        public final long latencyMs;
        public final String error;

        Result(SourceId id, String baseUrl, boolean reachable, long latencyMs, String error) {
            this.id = id;
            this.baseUrl = baseUrl;
            this.reachable = reachable;
            this.latencyMs = latencyMs;
            this.error = error;
        }
    }

    private static volatile SourceId activeSource = SourceId.OFFICIAL;
    private static volatile String customBaseUrl = "";

    private MirrorBench() {}

    public static void configure(SourceId id, String customUrl) throws IOException {
        if (id == SourceId.CUSTOM) {
            String normalized = normalizeCustomBase(customUrl);
            customBaseUrl = normalized;
        }
        activeSource = id == null ? SourceId.OFFICIAL : id;
    }

    public static SourceId activeSource() { return activeSource; }
    public static String customBaseUrl() { return customBaseUrl; }

    public static String normalizeCustomBase(String url) throws IOException {
        if (url == null || url.trim().isEmpty()) throw new IOException("请输入自定义 HTTPS 下载源地址");
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        try {
            URI u = new URI(trimmed);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) {
                throw new IOException("自定义下载源必须使用有效的 HTTPS 地址");
            }
            return trimmed;
        } catch (Exception e) {
            throw new IOException("自定义下载源地址格式无效", e);
        }
    }

    /** Rewrites Mojang download URLs to the selected mirror. Caller always falls back to originalUrl on failure. */
    public static String rewrite(String originalUrl) {
        SourceId src = activeSource;
        if (src == SourceId.OFFICIAL || originalUrl == null) return originalUrl;
        String base = src == SourceId.BMCLAPI ? "https://bmclapi2.bangbang93.com" : customBaseUrl;
        if (base == null || base.isEmpty()) return originalUrl;
        if (originalUrl.startsWith("https://piston-meta.mojang.com/")) {
            return base + "/" + originalUrl.substring("https://piston-meta.mojang.com/".length());
        }
        if (originalUrl.startsWith("https://piston-data.mojang.com/")) {
            return base + "/" + originalUrl.substring("https://piston-data.mojang.com/".length());
        }
        if (originalUrl.startsWith("https://launchermeta.mojang.com/")) {
            return base + "/" + originalUrl.substring("https://launchermeta.mojang.com/".length());
        }
        if (originalUrl.startsWith("https://launcher.mojang.com/")) {
            return base + "/" + originalUrl.substring("https://launcher.mojang.com/".length());
        }
        if (originalUrl.startsWith("https://libraries.minecraft.net/")) {
            return base + "/maven/" + originalUrl.substring("https://libraries.minecraft.net/".length());
        }
        if (originalUrl.startsWith("https://resources.download.minecraft.net/")) {
            return base + "/assets/" + originalUrl.substring("https://resources.download.minecraft.net/".length());
        }
        return originalUrl;
    }

    /** Measures actual HTTPS latency to Official, BMCLAPI, and optional Custom mirror. */
    public static List<Result> benchmark(String optionalCustom) {
        List<Result> results = new ArrayList<>();
        results.add(probe(SourceId.OFFICIAL, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
        results.add(probe(SourceId.BMCLAPI, "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"));
        if (optionalCustom != null && !optionalCustom.trim().isEmpty()) {
            try {
                String base = normalizeCustomBase(optionalCustom);
                results.add(probe(SourceId.CUSTOM, base + "/mc/game/version_manifest_v2.json"));
            } catch (IOException e) {
                results.add(new Result(SourceId.CUSTOM, optionalCustom, false, Long.MAX_VALUE, e.getMessage()));
            }
        }
        results.sort((a, b) -> {
            if (a.reachable != b.reachable) return a.reachable ? -1 : 1;
            return Long.compare(a.latencyMs, b.latencyMs);
        });
        return results;
    }

    private static Result probe(SourceId id, String url) {
        long start = System.nanoTime();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "LumenCraftAndroid/Alpha1.2.0");
            int code = conn.getResponseCode();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (code >= 200 && code < 400) return new Result(id, url, true, ms, "");
            return new Result(id, url, false, Long.MAX_VALUE, "HTTP " + code);
        } catch (IOException e) {
            return new Result(id, url, false, Long.MAX_VALUE, e.getMessage() == null ? "连接失败" : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
