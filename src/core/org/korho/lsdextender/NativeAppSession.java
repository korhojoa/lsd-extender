/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** One native window and its ordered, connection-scoped input stream. */
public final class NativeAppSession {
    public interface Output {
        void send(String line);
    }

    public final String name;
    public final int displayable;
    public final int width;
    public final int height;
    private final Output output;
    private boolean connected = true;
    private boolean viewIsVisible;
    private boolean displayIsVisible;
    private boolean focused;
    private long epoch;

    NativeAppSession(String name, int displayable, int width, int height, Output output) {
        this.name = name;
        this.displayable = displayable;
        this.width = width;
        this.height = height;
        this.output = output;
    }

    public synchronized boolean isConnected() { return connected; }
    public synchronized boolean isFocused() { return connected && focused; }

    public synchronized void viewVisible(boolean visible) {
        viewIsVisible = visible;
        updateFocus();
    }

    public synchronized void displayVisible(boolean visible) {
        displayIsVisible = visible;
        updateFocus();
    }

    private void updateFocus() {
        boolean next = connected && viewIsVisible && displayIsVisible;
        if (focused != next) {
            focused = next;
            epoch++;
            output.send("app focus " + epoch + " " + (next ? 1 : 0) + " " + width + " " + height
                + " " + EventClock.millis());
        }
    }

    public synchronized void touch(int gesture, int finger, int x, int y, int p1, int p2, long sourceTime) {
        touchAt(gesture, finger, x, y, p1, p2, sourceTime, EventClock.millis());
    }

    /** Preserve the Canvas callback arrival time through geometry mapping. */
    public synchronized void touchAt(int gesture, int finger, int x, int y, int p1, int p2,
            long sourceTime, long arrivalTime) {
        if (!connected || !focused) { return; }
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        output.send("app touch " + epoch + " " + gesture + " " + finger + " " + x + " " + y
            + " " + p1 + " " + p2 + " " + sourceTime + " " + arrivalTime);
    }

    /** A catalog-defined menu shortcut, delivered only to this focused app. */
    public synchronized void key(int scancode, int modifiers) {
        if (!connected || !focused || scancode < 4 || scancode >= 512 || modifiers < 0 || modifiers > 4095) { return; }
        output.send("app key " + epoch + " " + scancode + " " + modifiers + " " + EventClock.millis());
    }

    /** Graceful SDL quit, also available after Back returns to the catalog. */
    public synchronized void quit() {
        if (connected) { output.send("app quit " + epoch + " " + EventClock.millis()); }
    }

    synchronized void disconnect() {
        connected = false;
        updateFocus();
    }
}
