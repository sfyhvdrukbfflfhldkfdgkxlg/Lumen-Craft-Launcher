package dev.lumencraft.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.AtomicFile;
import dev.lumencraft.core.LocalFiles;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Supervised child JVM with live telemetry, scheduled backups, and bounded crash recovery. */
public final class ServerService extends Service {
    static final String JAR = "jar";
    static final String JAVA = "java";
    static final String HEAP = "heap";
    static final String AUTO_RESTART = "autoRestart";
    static final String BACKUP_MINUTES = "backupMinutes";
    private static final String CHANNEL = "server";
    private static final StringBuilder tail = new StringBuilder();
    private static final ExecutorService commands = Executors.newSingleThreadExecutor();
    private static final Pattern LAG_PATTERN = Pattern.compile("Running\\s+(\\d+)ms\\s+or\\s+(\\d+)\\s+ticks\\s+behind");
    private static final Pattern TPS_PATTERN = Pattern.compile("TPS[^0-9]*([0-9]+\\.[0-9]+)");
    private static volatile Process child;
    private static volatile String activePath;
    private static volatile String lastPath;
    private static volatile int revision;
    private static volatile boolean stopRequested;
    private static volatile int onlineEstimate;
    private static volatile String lastTickNote = "";
    private static volatile int crashRestarts;
    private volatile boolean destroyed;
    private ScheduledExecutorService backupTimer;

    static boolean busy() { return activePath != null; }

    /** Port of the supervised JVM for local room discovery. Read on the room worker. */
    public static int activePort() throws IOException {
        String path = activePath;
        if (path == null || child == null) return 0;
        File config = new File(new File(path).getParentFile(), "server.properties");
        Properties properties = new Properties();
        if (config.isFile()) {
            try (InputStream in = new FileInputStream(config)) { properties.load(in); }
        }
        try {
            int port = Integer.parseInt(properties.getProperty("server-port", "25565").trim());
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {}
        throw new IOException("当前服务端的 server-port 无效");
    }

    static boolean controls(String path) { return path.equals(activePath) && child != null; }
    static int revision() { return revision; }
    static synchronized String output(String path) { return path.equals(lastPath) ? tail.toString() : ""; }

    /** Reads real child JVM VmRSS (MiB) from /proc/<pid>/status when running, plus parsed player/tick metrics. */
    static String metrics(String path) {
        if (!controls(path)) return "";
        long rssMiB = readChildRssMiB(child);
        StringBuilder sb = new StringBuilder();
        if (rssMiB > 0) sb.append("内存 ").append(rssMiB).append(" MiB");
        if (onlineEstimate >= 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("在线≈").append(onlineEstimate);
        }
        if (!lastTickNote.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(lastTickNote);
        }
        if (crashRestarts > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("自动重启 ").append(crashRestarts);
        }
        return sb.toString();
    }

    private static long readChildRssMiB(Process proc) {
        if (proc == null) return -1;
        try {
            Field f = proc.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            int pid = f.getInt(proc);
            File status = new File("/proc/" + pid + "/status");
            if (!status.isFile()) return -1;
            try (BufferedReader br = new BufferedReader(new FileReader(status))) {
                for (String line; (line = br.readLine()) != null; ) {
                    if (line.startsWith("VmRSS:")) {
                        String digits = line.replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) return Long.parseLong(digits) / 1024L;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static synchronized void append(String text) {
        tail.append(text);
        if (tail.length() > 24000) tail.delete(0, tail.length() - 24000);
        parseTelemetry(text);
        revision++;
    }

    private static void parseTelemetry(String chunk) {
        for (String line : chunk.split("\n")) {
            if (line.contains("joined the game")) onlineEstimate++;
            else if (line.contains("left the game")) onlineEstimate = Math.max(0, onlineEstimate - 1);
            Matcher lag = LAG_PATTERN.matcher(line);
            if (lag.find()) {
                lastTickNote = "过载 " + lag.group(1) + "ms";
            } else {
                Matcher tps = TPS_PATTERN.matcher(line);
                if (tps.find()) lastTickNote = "TPS " + tps.group(1);
            }
        }
    }

    static void command(String path, String command) {
        if (command.trim().isEmpty() || command.length() > 2048 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return;
        Process proc = child;
        if (!controls(path)) return;
        if ("stop".equalsIgnoreCase(command.trim())) stopRequested = true;
        commands.execute(() -> {
            try {
                if (proc != child || proc == null) return;
                OutputStream out = proc.getOutputStream();
                out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                append("> " + command + "\n");
            } catch (IOException e) { append("控制台写入失败 " + e.getMessage() + "\n"); }
        });
    }

    /** Creates a rotating ZIP backup of the server's world directory inside server-backups/. */
    static File backupWorld(File jar) throws IOException {
        File serverDir = jar.getParentFile();
        File world = new File(serverDir, "world");
        if (!world.isDirectory()) throw new IOException("尚未生成 world 世界目录");
        File backups = new File(serverDir, "server-backups");
        if (!backups.isDirectory() && !backups.mkdirs()) throw new IOException("无法创建备份目录");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(backups, "world-" + stamp + ".zip");
        File tmp = new File(backups, ".world-" + stamp + ".tmp");
        try {
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp))) {
                LocalFiles.backup(world, os);
            }
            if (!tmp.renameTo(out)) throw new IOException("无法提交世界备份");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        File[] all = backups.listFiles((d, n) -> n.startsWith("world-") && n.endsWith(".zip"));
        if (all != null && all.length > 5) {
            Arrays.sort(all, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < all.length - 5; i++) all[i].delete();
        }
        return out;
    }

    static File checkedJar(Context c, String path) throws IOException {
        if (path == null) throw new IOException("缺少服务端路径");
        File jar = new File(path).getCanonicalFile();
        if (!jar.getPath().startsWith(c.getFilesDir().getCanonicalPath() + File.separator) || !jar.isFile()
            || !jar.getName().endsWith(".jar")) throw new IOException("服务端 JAR 无效");
        return jar;
    }

    static boolean eula(File jar) {
        try (InputStream in = new FileInputStream(new File(jar.getParentFile(), "eula.txt"))) {
            Properties properties = new Properties();
            properties.load(in);
            return "true".equalsIgnoreCase(properties.getProperty("eula"));
        } catch (IOException | IllegalArgumentException e) { return false; }
    }

    static void accept(File jar) throws IOException {
        AtomicFile file = new AtomicFile(new File(jar.getParentFile(), "eula.txt"));
        FileOutputStream stream = file.startWrite();
        try {
            stream.write("eula=true\n".getBytes(StandardCharsets.US_ASCII));
            file.finishWrite(stream);
        } catch (IOException e) { file.failWrite(stream); throw e; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (busy()) return START_NOT_STICKY;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String path = intent.getStringExtra(JAR);
        int major = intent.getIntExtra(JAVA, 0);
        final int heap = intent.getIntExtra(HEAP, 384);
        final boolean autoRestart = intent.getBooleanExtra(AUTO_RESTART, false);
        final int backupMinutes = intent.getIntExtra(BACKUP_MINUTES, 0);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder note;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "游戏服务端", NotificationManager.IMPORTANCE_LOW));
            note = new Notification.Builder(this, CHANNEL);
        } else { note = new Notification.Builder(this); }
        Intent console = new Intent(this, ServerConsoleActivity.class).putExtra(JAR, path).putExtra(JAVA, major);
        note.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Minecraft 服务端")
            .setContentText("打开控制台").setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, console, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        startForeground(17, note.build());
        synchronized (ServerService.class) {
            tail.setLength(0);
            lastPath = path;
            activePath = path;
            stopRequested = false;
            onlineEstimate = 0;
            lastTickNote = "";
            crashRestarts = 0;
            revision++;
        }
        new Thread(() -> runServer(path, major, heap, autoRestart, backupMinutes), "lumen-server").start();
        return START_NOT_STICKY;
    }

    private void runServer(String path, int major, int heap, boolean autoRestart, int backupMinutes) {
        Process proc = null;
        try {
            File jar = checkedJar(this, path);
            if (!eula(jar)) throw new IOException("需要先阅读并同意 Minecraft EULA");
            if (heap < 128 || heap > 8192) throw new IOException("内存设置无效");
            if (backupMinutes >= 10) {
                backupTimer = Executors.newSingleThreadScheduledExecutor();
                backupTimer.scheduleAtFixedRate(() -> {
                    if (!controls(path)) return;
                    try {
                        command(path, "save-all");
                        Thread.sleep(2000);
                        File zip = backupWorld(jar);
                        append("\n[定时备份] 已保存 " + zip.getName() + "\n");
                    } catch (Exception e) {
                        append("\n[定时备份] 跳过：" + e.getMessage() + "\n");
                    }
                }, backupMinutes, backupMinutes, TimeUnit.MINUTES);
            }

            while (!destroyed && !stopRequested) {
                onlineEstimate = 0;
                lastTickNote = "";
                proc = RuntimeStore.start(this, RuntimeStore.runtime(this, major), major, heap, jar);
                child = proc;
                if (destroyed) proc.destroy();
                try (Reader reader = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[1024];
                    for (int n; (n = reader.read(buf)) != -1; ) append(new String(buf, 0, n));
                }
                int code = proc.waitFor();
                append("\n进程退出，状态码 " + code + "\n");
                if (code == 0 || !autoRestart || stopRequested || destroyed || crashRestarts >= 3) break;
                crashRestarts++;
                long waitMs = 3000L << (crashRestarts - 1);
                append("[崩溃恢复] " + (waitMs / 1000) + " 秒后第 " + crashRestarts + " 次自动重启…\n");
                Thread.sleep(waitMs);
            }
        } catch (IOException | RuntimeException e) { append("\n服务端错误 " + e.getMessage() + "\n"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            if (backupTimer != null) { backupTimer.shutdownNow(); backupTimer = null; }
            if (proc != null) proc.destroy();
            child = null;
            new android.os.Handler(getMainLooper()).post(() -> {
                activePath = null;
                revision++;
                stopForeground(true);
                stopSelf();
            });
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopRequested = true;
        if (backupTimer != null) { backupTimer.shutdownNow(); backupTimer = null; }
        Process proc = child;
        if (proc != null) proc.destroy();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
