package dev.lumencraft.bridge;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public final class Session {
    public static final int WIDTH = 320, HEIGHT = 240;
    public static volatile boolean closed;
    private static final Object visibility = new Object();
    private static boolean paused;
    private static final byte[] pixels = new byte[WIDTH * HEIGHT * 4];
    private static Socket socket;
    private static DataOutputStream output;
    private static long lastFrame;
    private static final long FRAME_PERIOD = "1".equals(System.getenv("LUMEN_LOW_POWER"))
            ? 66_666_667L : 33_333_333L;
    public static native boolean createBuffer(int w, int h);
    public static native void destroyBuffer();
    private static native void readBuffer(byte[] pixels);

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || args[1].length() < 24) throw new IllegalArgumentException("Missing local session credentials");
        String[] path = System.getProperty("java.class.path").split(File.pathSeparator);
        if (path.length != 2) throw new IOException("Expected only the launcher bridge and the official game");
        File file = new File(path[1]);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1;) digest.update(buf, 0, n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(Integer.toHexString((b & 255) | 256).substring(1));
        String entry;
        switch (hash.toString()) {
            case "393e8d4b4d708587e2accd7c5221db65365e1075":  // rd-132211
            case "12dace5a458617d3f90337a7ebde86c0593a6899":  // rd-132328
            case "b100be8097195b6c9112046dc6a80d326c8df839":  // rd-160052
                entry = "com.mojang.rubydung.RubyDung";
                break;
            case "6323bd14ed7f83852e17ebc8ec418e55c97ddfe4":  // rd-161348 and rd-20090515
                entry = "com.mojang.minecraft.RubyDung";
                break;
            default: throw new IOException("Unsupported game jar");
        }
        Class<?> game = Class.forName(entry, false, Session.class.getClassLoader());
        System.load(new File(args[2],"liblumenclassic.so").getAbsolutePath());
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[0])),10000);
        socket.setTcpNoDelay(true);
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 32768));
        output.writeUTF(args[1]);
        output.writeInt(WIDTH);
        output.writeInt(HEIGHT);
        output.flush();
        Thread input = new Thread(Session::input, "lumen-input");
        input.setDaemon(true);
        input.start();
        game.getMethod("main", String[].class).invoke(null, (Object)new String[0]);
    }
    private static void input() {
        try {
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!closed) {
                int kind = input.readInt(), a = input.readInt(), b = input.readInt();
                switch (kind) {
                    case 1: Keyboard.change(a, b != 0); break;
                    case 2: Mouse.move(a,b); break;
                    case 3: Mouse.change(a,b != 0); break;
                    case 4: closed = true; setPaused(false); break;
                    case 5: setPaused(a != 0); break;
                    default: throw new IOException("Unknown input event");
                }
            }
        } catch (IOException e) { closed = true; setPaused(false); }
    }
    private static void setPaused(boolean value) {
        synchronized(visibility) { paused=value; visibility.notifyAll(); }
    }
    public static void present() {
        synchronized(visibility) {
            while (paused && !closed) {
                try { visibility.wait(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); closed=true; break; }
            }
        }
        if (closed) return;
        long due = lastFrame + FRAME_PERIOD - System.nanoTime();
        if (due > 0) LockSupport.parkNanos(due);
        lastFrame = System.nanoTime();
        readBuffer(pixels);
        // Desktop RGB windows composite as opaque. The game's clear alpha is zero.
        for (int i = 3; i < pixels.length; i += 4) pixels[i] = (byte)255;
        try {
            output.writeInt(pixels.length);
            output.write(pixels);
            output.flush();
        } catch (IOException e) { closed = true; }
    }
    public static void close() {
        closed = true;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
