package dev.lumencraft.core;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Computes hardware-aware JVM memory, renderer backend, resolution scale, and safe config rollback. */
public final class DeviceProfile {
    public static final class Recommendation {
        public final int heapMiB, maxFps;
        public final float resolutionScale;
        public final String rendererBackend, reason;

        Recommendation(int heapMiB, int maxFps, float resolutionScale, String rendererBackend, String reason) {
            this.heapMiB = heapMiB;
            this.maxFps = maxFps;
            this.resolutionScale = resolutionScale;
            this.rendererBackend = rendererBackend;
            this.reason = reason;
        }
    }

    private DeviceProfile() {}

    public static Recommendation recommend(long totalMemBytes, long availMemBytes, boolean lowRam,
                                           boolean powerSave, String probeReport, boolean modernClient) {
        long totalMiB = totalMemBytes / (1024L * 1024L);
        long availMiB = availMemBytes / (1024L * 1024L);
        int heap;
        if (!modernClient) {
            heap = (lowRam || totalMiB < 2048) ? 128 : 256;
        } else {
            long target = Math.min(availMiB / 2, totalMiB / 3);
            heap = (int) Math.max(384, Math.min(2048, target));
            if (lowRam) heap = Math.min(heap, 512);
        }

        int fps = (lowRam || powerSave) ? 15 : (modernClient ? 60 : 30);
        float scale = (lowRam || totalMiB < 3072) ? 0.75f : 1.0f;

        String rep = probeReport == null ? "" : probeReport.toLowerCase(Locale.ROOT);
        String backend;
        String reason;
        if (!modernClient) {
            backend = "OSMesa (软件固定管线)";
            reason = "远古 RubyDung 客户端使用独立 OSMesa 软件管线以确保确定性兼容";
        } else if (rep.contains("1px render/readback: pass") && rep.contains("opengl es 3.")) {
            if (rep.contains("vulkan: 1.") && !rep.contains("vulkan: unavailable")) {
                backend = "GLES 3.0 兼容层（设备支持 Vulkan 探测）";
                reason = "检测到 GLES 3.x 与 Vulkan 驱动，默认启用低开销 GLES 3.0 兼容层并支持回退";
            } else {
                backend = "GLES 3.0 兼容层";
                reason = "检测到可用 OpenGL ES 3.x 驱动，启用 GLES 3.0 着色器转换与状态兼容层";
            }
        } else if (rep.contains("1px render/readback: pass")) {
            backend = "GLES 2.0 兼容层";
            reason = "设备仅报告 OpenGL ES 2.0，自动启用 GLES 2.0 固定管线兼容模式";
        } else {
            backend = "软件渲染回退";
            reason = "未确认硬件 EGL 渲染回读，自动降级为软件兼容方案";
        }
        return new Recommendation(heap, fps, scale, backend, reason);
    }

    /** Saves a snapshot of launcher runtime preferences so users can safely roll back bad tuning. */
    public static void saveSnapshot(File file, JSONObject config) throws Exception {
        VerifiedDownloads.directory(file.getParentFile());
        VanillaInstaller.writeJson(file, config);
    }

    public static JSONObject loadSnapshot(File file) throws Exception {
        if (!file.isFile()) throw new IOException("尚未保存可回滚的配置快照");
        return VanillaInstaller.readJson(file);
    }
}
