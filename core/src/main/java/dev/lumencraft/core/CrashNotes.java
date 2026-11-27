package dev.lumencraft.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structured crash diagnosis for Minecraft, JVM, JNI, mod loader, and OpenGL failures. */
public final class CrashNotes {
    public enum RepairAction {
        NONE,
        REPAIR_FILES,
        LOWER_HEAP,
        SWITCH_JAVA,
        OPEN_MODS,
        CHECK_GRAPHICS,
        FALLBACK_RENDERER
    }

    public static final class Diagnosis {
        public final String category, summary, culprit, repairLabel;
        public final RepairAction action;

        Diagnosis(String category, String summary, String culprit, RepairAction action, String repairLabel) {
            this.category = category;
            this.summary = summary;
            this.culprit = culprit;
            this.action = action;
            this.repairLabel = repairLabel;
        }
    }

    private static final Pattern MISSING_CLASS = Pattern.compile("(?:NoClassDefFoundError|ClassNotFoundException):\\s*([A-Za-z0-9_/.$]+)");
    private static final Pattern MISSING_LIB = Pattern.compile("(?:dlopen failed:\\s*library\\s*\"([^\"]+)\"|no\\s+([A-Za-z0-9_.-]+)\\s+in\\s+java\\.library\\.path)");
    private static final Pattern MOD_DEP = Pattern.compile("Mod\\s+'([^']+)'\\s+(?:requires|needs)\\s+([^\\n]+)", Pattern.CASE_INSENSITIVE);

    private CrashNotes() {}

    public static Diagnosis diagnose(String report) {
        if (report == null || report.trim().isEmpty()) {
            return new Diagnosis("UNKNOWN", "", "", RepairAction.NONE, "");
        }
        String s = report.toLowerCase(Locale.ROOT);
        if (s.contains("outofmemoryerror") || s.contains("could not reserve enough space") || s.contains("failed to allocate")) {
            return new Diagnosis("OOM",
                    "Java 堆内存不足或系统拒绝分配请求的内存。可适当调低最大堆内存并关闭后台应用。",
                    "", RepairAction.LOWER_HEAP, "调低堆内存并重试");
        }
        if (s.contains("unsatisfiedlinkerror") || s.contains("dlopen failed") || s.contains("cannot load hotspot")) {
            String culprit = extract(MISSING_LIB, report);
            return new Diagnosis("NATIVE_ABI",
                    "系统动态链接器无法载入原生库" + (culprit.isEmpty() ? "" : "（" + culprit + "）") + "。请检查设备架构 ABI 与已安装的运行时。",
                    culprit, RepairAction.REPAIR_FILES, "重新校验运行环境");
        }
        if (s.contains("unsupportedclassversionerror")) {
            return new Diagnosis("JAVA_VERSION",
                    "当前 Java 版本过低，无法载入此 Minecraft 或模组编译的 class 文件。请切换到版本元数据要求的 Java 主版本。",
                    "", RepairAction.SWITCH_JAVA, "检查 Java 版本配置");
        }
        if (s.contains("noclassdeffounderror") || s.contains("classnotfoundexception")) {
            String cls = extract(MISSING_CLASS, report);
            return new Diagnosis("MISSING_CLASS",
                    "Java 类路径缺少必需类" + (cls.isEmpty() ? "" : "（" + cls + "）") + "，通常由依赖损坏或缺少前置模组引起。",
                    cls, RepairAction.REPAIR_FILES, "一键校验并修复游戏文件");
        }
        if (s.contains("incompatible mod set") || s.contains("mod resolution encountered")
                || s.contains("missing or unsupported mandatory dependencies") || s.contains("duplicate mods found")) {
            String detail = extract(MOD_DEP, report);
            return new Diagnosis("MOD_CONFLICT",
                    "模组加载器报告依赖缺失或版本冲突" + (detail.isEmpty() ? "" : "：" + detail) + "。可在模组管理中补全前置或停用冲突项。",
                    detail, RepairAction.OPEN_MODS, "打开模组诊断与前置补全");
        }
        if (s.contains("egl_bad_") || s.contains("glfw error 65542") || s.contains("shader compilation failed")
                || s.contains("gl_out_of_memory")) {
            return new Diagnosis("GRAPHICS",
                    "当前 GPU 驱动未能创建请求的图形上下文或编译着色器。可切换到兼容渲染后端或运行图形能力检测。",
                    "", RepairAction.FALLBACK_RENDERER, "切换兼容渲染模式");
        }
        if (s.contains("trust anchor") || s.contains("certpathvalidatorexception") || s.contains("sslhandshakeexception")) {
            return new Diagnosis("TLS",
                    "HTTPS 证书链验证失败。请核对设备系统时间，或在设置中测速切换可用的下载源。",
                    "", RepairAction.NONE, "");
        }
        if (s.contains("no space left on device") || s.contains("enospc")) {
            return new Diagnosis("DISK_FULL",
                    "设备可用存储空间不足，无法写入游戏文件或存档。",
                    "", RepairAction.NONE, "");
        }
        return new Diagnosis("UNKNOWN", "", "", RepairAction.CHECK_GRAPHICS, "");
    }

    public static String explain(String report) {
        return diagnose(report).summary;
    }

    private static String extract(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return "";
        for (int i = 1; i <= m.groupCount(); i++) {
            String g = m.group(i);
            if (g != null && !g.trim().isEmpty()) return g.trim();
        }
        return "";
    }
}
