package dev.lumencraft.core;
import java.util.Locale;

/** Classify observed messages without claiming an unknown root cause. */
public final class CrashNotes {
    private CrashNotes() {}
    public static String explain(String report) {
        String s=report.toLowerCase(Locale.ROOT);
        if(s.contains("outofmemoryerror") || s.contains("could not reserve enough space"))
            return "Java 内存不足。若是服务端，可在控制台检查最大堆配置和系统剩余内存。";
        if(s.contains("unsatisfiedlinkerror") || s.contains("dlopen failed") || s.contains("cannot load hotspot"))
            return "系统链接器无法载入原生库。检查运行时 ABI 和下方报告的库名。";
        if(s.contains("unsupportedclassversionerror"))
            return "当前 Java 无法读取此 class 版本。检查实例收据的 Java 主版本和组件要求。";
        if(s.contains("noclassdeffounderror") || s.contains("classnotfoundexception"))
            return "Java 类路径缺少所列的类。可在游戏页校验实例文件，并检查相关组件。";
        if(s.contains("trust anchor") || s.contains("certpathvalidatorexception"))
            return "HTTPS 证书链验证失败。请检查设备时间、网络和运行时来源。";
        if(s.contains("incompatible mod set") || s.contains("mod resolution encountered"))
            return "加载器报告了模组版本或依赖冲突。请检查日志中列出的模组。";
        if(s.contains("egl_bad_") || s.contains("glfw error 65542"))
            return "驱动未能提供请求的图形上下文。可在设置页运行图形能力检测。";
        return "";
    }
}
