package dev.lumencraft.runtime;

import android.content.Context;
import android.net.Uri;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import org.json.JSONObject;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RuntimeStore {
    private RuntimeStore() {}
    public interface Progress { void update(String phase, long done, long total); }
    static final class Reporter {
        private final Progress callback;
        private String lastPhase = "";
        private long lastTick;
        Reporter(Progress callback) { this.callback=callback; }
        void update(String phase,long done,long total) {
            if (callback==null) return;
            long now=android.os.SystemClock.elapsedRealtime();
            if (phase.equals(lastPhase) && done!=total && now-lastTick<500) return;
            lastPhase=phase; lastTick=now;
            callback.update(phase,done,total);
        }
    }
    private static File root(Context c) { return new File(c.getFilesDir(), "openjdk"); }
    static File runtime(Context c, int major) { return new File(root(c), Integer.toString(major)); }
    public static File glibcDir(Context c) { return new File(c.getFilesDir(), "runtime/glibc"); }
    private static File host(Context c) { return new File(c.getApplicationInfo().nativeLibraryDir, "liblumenjvm.so"); }

    public static boolean hasGlibcCompat(Context c) {
        File dir = glibcDir(c);
        if (!dir.isDirectory()) return false;
        File[] libs = dir.listFiles((d, name) -> name.startsWith("libc.so") || name.startsWith("ld-linux"));
        return libs != null && libs.length > 0;
    }

    /** Imports a user-supplied glibc compatibility ZIP into app-private isolated runtime/glibc storage. */
    public static synchronized int importGlibcCompat(Context c, Uri archive) throws IOException {
        File dst = glibcDir(c);
        File stage = new File(c.getFilesDir(), "runtime/.glibc.partial");
        remove(stage);
        mkdir(stage);
        int count = 0;
        try {
            try (InputStream in = c.getContentResolver().openInputStream(archive)) {
                if (in == null) throw new IOException("无法读取 glibc 兼容包");
                unpack(in, stage, new Reporter(null));
            }
            File[] found = stage.listFiles();
            if (found == null || found.length == 0) throw new IOException("glibc 兼容包为空");
            for (File f : found) count++;
            remove(dst);
            mkdir(dst.getParentFile());
            if (!stage.renameTo(dst)) throw new IOException("无法提交 glibc 兼容目录");
            return count;
        } finally { remove(stage); }
    }

    static JSONObject downloadEntry(Context c, int major) throws IOException {
        int[] hostElf = elf(host(c));
        String arch;
        switch (hostElf[1]) {
            case 183: arch = "arm64"; break;
            case 40: arch = "arm"; break;
            case 62: arch = "x86_64"; break;
            case 3: arch = "x86"; break;
            default: return null;
        }
        return RuntimeCatalog.entry(c,major,arch);
    }
    // A null archive requests the pinned online release for the installed executable's ABI.
    static void install(Context c, Uri archive, int major) throws IOException { install(c,archive,major,null); }
    static synchronized void install(Context c, Uri archive, int major, Progress progress) throws IOException {
        Reporter report=new Reporter(progress);
        report.update("准备 Java "+major,0,0);
        if (major < 8 || major > 99) throw new IOException("Java 版本号无效");
        File dst = runtime(c, major);
        if (dst.exists()) throw new IOException("此 Java 版本已经安装");
        File stage = new File(root(c), "." + major + ".partial");
        remove(stage);
        mkdir(stage);
        try {
            if (archive == null) {
                JSONObject entry = downloadEntry(c,major);
                if (entry == null) throw new IOException("此 Java 与 ABI 组合缺少已验证的下载源");
                File packed = new File(stage, ".download.tar.xz");
                RuntimeCatalog.fetch(c,entry,packed,report);
                RuntimeCatalog.extractVerified(packed,stage,report);
                if (!packed.delete()) throw new IOException("无法清理运行时下载");
            } else try (InputStream input = c.getContentResolver().openInputStream(archive)) {
                if (input == null) throw new IOException("无法读取文件");
                unpack(input, stage,report);
            }
            File jvm = jvm(stage);
            if (!Arrays.equals(elf(jvm), elf(host(c)))) throw new IOException("JRE ABI 与此应用进程不符");
            report.update("验证 JVM",0,0);
            probe(c, stage, major);
            if (!stage.renameTo(dst)) throw new IOException("无法保存运行时");
        } finally { remove(stage); }
    }

    static void unpack(InputStream input, File stage, Reporter report) throws IOException {
        int count = 0;
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        String prefix = stage.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (++count > 16000 || name.length() > 1024 || name.startsWith("/") || name.contains("\\")
                    || Arrays.asList(name.split("/")).contains("..")) throw new IOException("运行时 ZIP 条目无效");
                File target = new File(stage, name);
                if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("运行时 ZIP 路径无效");
                if (entry.isDirectory()) { mkdir(target); continue; }
                mkdir(target.getParentFile());
                if (!target.createNewFile()) throw new IOException("重复的 ZIP 条目");
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        total += n;
                        if (total > 768L * 1024 * 1024) throw new IOException("运行时超过 768 MiB 解压限制");
                        output.write(buffer, 0, n);
                        report.update("解压 Java",total,-1);
                    }
                }
            }
        }
    }

    private static int[] elf(File file) throws IOException {
        byte[] header = new byte[20];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) { in.readFully(header); }
        if (header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F' || header[5] != 1)
            throw new IOException("文件不是小端 ELF Android 原生库");
        return new int[] {header[4], (header[18] & 255) | ((header[19] & 255) << 8)};
    }

    private static File jvm(File home) throws IOException {
        for (String arch : new String[] {"", "aarch64/", "arm/", "amd64/", "i386/", "i586/"}) {
            for (String vm : new String[] {"server", "client"}) {
                File jvm = new File(home, "lib/" + arch + vm + "/libjvm.so");
                if (jvm.isFile()) return jvm;
            }
        }
        throw new IOException("缺少 lib/server/libjvm.so 或 Java 8 对应 ABI 的虚拟机库。ZIP 根目录应为 Java home。");
    }

    /** Blocking entry point. Acquires the pinned matching JRE when absent. */
    public static Process startClient(Context c, File instance, File bridgeJar, File gameJar, int port, String token)
            throws IOException {
        return startClient(c,instance,bridgeJar,gameJar,port,token,null);
    }
    public static Process startClient(Context c, File instance, File bridgeJar, File gameJar, int port, String token,
                                      Progress progress) throws IOException {
        if (port < 1 || port > 65535 || token == null || token.isEmpty()) throw new IOException("无效的本地窗口连接");
        File home = runtime(c,8);
        synchronized (RuntimeStore.class) { if (!home.isDirectory()) install(c,null,8,progress); }
        String classpath = bridgeJar.getCanonicalPath() + ":" + gameJar.getCanonicalPath();
        android.app.ActivityManager memory=(android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        android.os.PowerManager power=(android.os.PowerManager)c.getSystemService(Context.POWER_SERVICE);
        boolean lowMemory=memory.isLowRamDevice();
        boolean lowPower=lowMemory || power.isPowerSaveMode() || c.getSharedPreferences("library",Context.MODE_PRIVATE).getBoolean("low-power",false);
        ProcessBuilder pb = builder(c,home,8,lowMemory?128:256,classpath,instance.getCanonicalFile());
        pb.environment().put("LUMEN_LOW_POWER",lowPower?"1":"0");
        pb.command().addAll(Arrays.asList("dev.lumencraft.bridge.Session",Integer.toString(port),
                token,c.getApplicationInfo().nativeLibraryDir));
        return pb.start();
    }

    static Process start(Context c, File home, int major, int heap, File jar) throws IOException {
        ProcessBuilder command = builder(c, home, major, heap, jar.getAbsolutePath(), jar.getParentFile());
        ServerLogging.configure(c,jar,command);
        return command.start();
    }

    private static ProcessBuilder builder(Context c, File home, int major, int heap, String entry, File work) throws IOException {
        File vm = jvm(home);
        File arch = vm.getParentFile().getParentFile();
        List<String> argv = new ArrayList<>(Arrays.asList(host(c).getAbsolutePath(), vm.getAbsolutePath(),
            home.getAbsolutePath(), Integer.toString(major), Integer.toString(heap), entry));
        ProcessBuilder pb = new ProcessBuilder(argv).directory(work).redirectErrorStream(true);
        String ldPath = vm.getParent() + ":" + arch + ":" + new File(arch, "jli")
                + ":" + new File(home, "lib") + ":" + c.getApplicationInfo().nativeLibraryDir;
        if (hasGlibcCompat(c)) {
            File glibc = glibcDir(c);
            ldPath = ldPath + ":" + glibc.getAbsolutePath();
            pb.environment().put("LUMEN_GLIBC_LIB", glibc.getAbsolutePath());
        }
        pb.environment().put("LD_LIBRARY_PATH", ldPath);
        pb.environment().put("JAVA_HOME", home.getAbsolutePath());
        pb.environment().put("HOME", work.getAbsolutePath());
        pb.environment().put("TMPDIR", c.getCacheDir().getAbsolutePath());
        return pb;
    }

    private static void probe(Context c, File home, int major) throws IOException {
        Process p = builder(c, home, major, 64, "--probe", c.getCacheDir()).start();
        ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = clock.schedule(() -> p.destroy(), 45, TimeUnit.SECONDS);
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream saved = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            for (int n; (n = in.read(buf)) != -1;) saved.write(buf, 0, Math.min(n, 8192 - saved.size()));
            if (p.waitFor() != 0) throw new IOException("JVM 自检失败\n" + saved.toString("UTF-8"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("JVM 检测中断", e);
        } finally { timeout.cancel(false); clock.shutdownNow(); p.destroy(); }
    }

    private static void mkdir(File dir) throws IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建 " + dir.getName());
    }
    private static void remove(File f) throws IOException {
        boolean link;
        try { link = OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode); }
        catch (ErrnoException e) { if (e.errno == OsConstants.ENOENT) return; throw new IOException(e); }
        File[] children = link ? null : f.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (!f.delete()) throw new IOException("无法清理临时文件 " + f.getName());
    }
}

