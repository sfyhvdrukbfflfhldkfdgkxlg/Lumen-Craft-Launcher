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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A single supervised child JVM with a bounded tail, separate from Android's ART process. */
public final class ServerService extends Service {
    static final String JAR = "jar";
    static final String JAVA = "java";
    static final String HEAP = "heap";
    private static final String CHANNEL = "server";
    private static final StringBuilder tail = new StringBuilder();
    private static final ExecutorService commands = Executors.newSingleThreadExecutor();
    private static volatile Process child;
    private static volatile String activePath;
    private static volatile String lastPath;
    private static volatile int revision;
    private volatile boolean destroyed;

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
    private static synchronized void append(String text) {
        tail.append(text);
        if (tail.length() > 24000) tail.delete(0, tail.length() - 24000);
        revision++;
    }
    static void command(String path, String command) {
        if (command.trim().isEmpty() || command.length() > 2048 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return;
        Process proc = child;
        if (!controls(path)) return;
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
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
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
            revision++;
        }
        new Thread(() -> runServer(path, major, heap), "lumen-server").start();
        return START_NOT_STICKY;
    }
    private void runServer(String path, int major, int heap) {
        Process proc = null;
        try {
            File jar = checkedJar(this, path);
            if (!eula(jar)) throw new IOException("需要先阅读并同意 Minecraft EULA");
            if (heap < 128 || heap > 8192) throw new IOException("内存设置无效");
            proc = RuntimeStore.start(this, RuntimeStore.runtime(this, major), major, heap, jar);
            child = proc;
            if (destroyed) proc.destroy();
            try (Reader reader = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[1024];
                for (int n; (n = reader.read(buf)) != -1;) append(new String(buf, 0, n));
            }
            append("\n进程退出，状态码 " + proc.waitFor() + "\n");
        } catch (IOException | RuntimeException e) { append("\n服务端错误 " + e.getMessage() + "\n"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            if (proc != null) proc.destroy();
            child = null;
            // Serialize publishing idle and stopping the current service with new start intents.
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
        Process proc = child;
        if (proc != null) proc.destroy();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
