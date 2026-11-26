package dev.lumencraft.bridge;
import java.util.ArrayDeque;
import java.util.Arrays;

public final class InputQueue {
    private final boolean[] down;
    private final ArrayDeque<Integer> events = new ArrayDeque<>();
    private int current;
    private boolean connected;
    public InputQueue(int keys) { down = new boolean[keys]; }
    public synchronized void connect(boolean enabled) {
        events.clear();
        Arrays.fill(down, false);
        current = 0;
        connected = enabled;
    }
    public synchronized void change(int code, boolean state) {
        if (!connected || code < 0 || code >= down.length || down[code] == state) return;
        down[code] = state;
        if (events.size() == 256) events.removeFirst();
        events.addLast((code << 1) | (state ? 1 : 0));
    }
    public synchronized boolean next() {
        if (events.isEmpty()) return false;
        current = events.removeFirst();
        return true;
    }
    public synchronized int code() { return current >>> 1; }
    public synchronized boolean state() { return (current & 1) != 0; }
    public synchronized boolean down(int code) { return code >= 0 && code < down.length && down[code]; }
}
