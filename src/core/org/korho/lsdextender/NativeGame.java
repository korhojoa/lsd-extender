/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Process identity and exit callback survive runtime class-loader replacement. */
public final class NativeGame implements Runnable {
    public final String id, title, icon, requires;
    public final String[] arguments;
    public final int menuKey, menuModifiers;
    public volatile boolean launching = true;
    private static NativeGame active;

    public NativeGame(String id, String title, String icon, String requires, String[] arguments,
            int menuKey, int menuModifiers) {
        this.id = id; this.title = title; this.icon = icon; this.requires = requires;
        this.arguments = arguments; this.menuKey = menuKey; this.menuModifiers = menuModifiers;
    }

    public static synchronized NativeGame current() { return active; }

    public static synchronized boolean begin(NativeGame value) {
        if (active != null || LsdExtender.isReloading()) { return false; }
        active = value;
        return true;
    }

    public void run() {
        synchronized (NativeGame.class) {
            if (active != this) { return; }
            NativeMediaBridge.release();
            active = null;
        }
    }
}
