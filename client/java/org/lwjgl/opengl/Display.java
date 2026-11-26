package org.lwjgl.opengl;
import dev.lumencraft.bridge.Session;
import org.lwjgl.LWJGLException;
public final class Display {
    private static DisplayMode mode;
    public static void setFullscreen(boolean enabled) throws LWJGLException {
        if (!enabled) throw new LWJGLException("The mobile surface supports fullscreen presentation only");
        mode = new DisplayMode(Session.WIDTH, Session.HEIGHT);
    }
    public static void setDisplayMode(DisplayMode requested) {
        if (requested.getWidth() <= 0 || requested.getHeight() <= 0) throw new IllegalArgumentException("Invalid display dimensions");
        mode = new DisplayMode(Session.WIDTH, Session.HEIGHT);
    }
    public static DisplayMode getDisplayMode() { return mode; }
    public static void create() throws LWJGLException {
        if (mode == null || !Session.createBuffer(mode.getWidth(), mode.getHeight()))
            throw new LWJGLException("OSMesa could not create a 320 x 240 RGBA context");
    }
    public static boolean isCloseRequested() { return Session.closed; }
    public static void update() { Session.present(); }
    public static void destroy() { Session.destroyBuffer(); Session.close(); }
}
