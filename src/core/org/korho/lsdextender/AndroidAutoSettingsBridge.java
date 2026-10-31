/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Core-owned reference cleared by the runtime feature's shutdown method. */
public final class AndroidAutoSettingsBridge {
    private static volatile AndroidAutoSettings backend;
    private static volatile long backendGeneration;
    private AndroidAutoSettingsBridge() { }
    public static AndroidAutoSettings get() { return backend; }
    public static long generation() { return backendGeneration; }
    public static synchronized void register(AndroidAutoSettings value) { backendGeneration++; backend = value; }
    public static synchronized void unregister(AndroidAutoSettings value) {
        if (backend == value) { backend = null; }
    }
}
