package dev.lumencraft;
public final class LegacyGraphics {
    static { System.loadLibrary("lumengl"); }
    private LegacyGraphics() {}
    public static native String check();
}
