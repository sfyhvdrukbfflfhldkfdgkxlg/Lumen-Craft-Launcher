package dev.lumencraft.runtime;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public final class GraphicsProbe {
    private GraphicsProbe() {}

    /** The short-lived EGL thread owns both the context and its entire cleanup. */
    public static String inspect() {
        try {
            System.loadLibrary("lumencap");
            FutureTask<String> result = new FutureTask<>(GraphicsProbe::readOnWorker);
            new Thread(result, "lumen-egl-probe").start();
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "EGL probe interrupted";
        } catch (ExecutionException | UnsatisfiedLinkError e) {
            return "EGL unavailable: " + e.getMessage();
        }
    }
    private static native String readOnWorker();
}
