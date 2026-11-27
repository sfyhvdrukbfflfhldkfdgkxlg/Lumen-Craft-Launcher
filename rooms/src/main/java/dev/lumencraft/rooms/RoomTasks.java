// SPDX-License-Identifier: AGPL-3.0-only
package dev.lumencraft.rooms;

import java.util.concurrent.Callable;

/** Retained across configuration changes. No Activity, View or room credentials are persisted. */
final class RoomTasks {
    String pendingAction, pendingCode, typed = "", port = "", shareCode;
    int selectedPort;
    private Thread worker;
    private Object result;
    private String operation, error;
    volatile String progress = "";

    synchronized boolean busy() { return operation != null; }

    synchronized void begin(String name, String label, Callable<?> task) {
        if (busy()) return;
        operation = name;
        progress = label;
        worker = new Thread(() -> {
            Object value = null;
            String failure = null;
            try { value = task.call(); }
            catch (LinkageError e) { failure = "设备不能加载陶瓦原生库"; }
            catch (Exception e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            synchronized (this) {
                if (worker != Thread.currentThread()) return;
                result = value; error = failure; worker = null;
            }
        }, "lumen-room-" + name);
        worker.start();
    }

    synchronized Result take() {
        if (operation == null || worker != null) return null;
        Result completed = new Result(operation, result, error);
        operation = null; result = null; error = null; progress = "";
        return completed;
    }

    synchronized void cancel() {
        if (worker != null) worker.interrupt();
        worker = null; operation = null; result = null;
        pendingCode = null; typed = ""; shareCode = null;
    }

    static final class Result {
        final String operation, error;
        final Object value;
        Result(String operation, Object value, String error) {
            this.operation = operation; this.value = value; this.error = error;
        }
    }
}
