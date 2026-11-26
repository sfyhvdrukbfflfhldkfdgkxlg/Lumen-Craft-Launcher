package org.lwjgl.input;
import dev.lumencraft.bridge.InputQueue;
public final class Keyboard {
    private static final InputQueue events = new InputQueue(256);
    public static void create() { events.connect(true); }
    public static void destroy() { events.connect(false); }
    public static void change(int key, boolean down) { events.change(key, down); }
    public static boolean next() { return events.next(); }
    public static int getEventKey() { return events.code(); }
    public static boolean getEventKeyState() { return events.state(); }
    public static boolean isKeyDown(int key) { return events.down(key); }
}
