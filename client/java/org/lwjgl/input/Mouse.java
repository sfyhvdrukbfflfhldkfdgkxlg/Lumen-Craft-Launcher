package org.lwjgl.input;
import dev.lumencraft.bridge.InputQueue;
public final class Mouse {
    private static final InputQueue events = new InputQueue(8);
    private static int dx, dy;
    private static boolean grabbed;
    public static void create() { events.connect(true); }
    public static void destroy() { events.connect(false); setGrabbed(false); }
    public static void change(int button, boolean down) { events.change(button, down); }
    public static boolean next() { return events.next(); }
    public static int getEventButton() { return events.code(); }
    public static boolean getEventButtonState() { return events.state(); }
    public static synchronized void setGrabbed(boolean value) { grabbed = value; dx = dy = 0; }
    public static synchronized void move(int x, int y) { if (grabbed) { dx += x; dy += y; } }
    public static synchronized int getDX() { int value = dx; dx = 0; return value; }
    public static synchronized int getDY() { int value = dy; dy = 0; return value; }
}
