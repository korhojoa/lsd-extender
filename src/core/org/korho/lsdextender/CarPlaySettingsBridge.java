/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** CarPlay backend slot, reusing the existing typed numeric settings contract. */
public final class CarPlaySettingsBridge {
    private static AndroidAutoSettings backend;
    private static long backendGeneration;
    private CarPlaySettingsBridge() { }
    public static synchronized void register(AndroidAutoSettings value) { backend = value; backendGeneration++; }
    public static synchronized void unregister(AndroidAutoSettings value) {
        if (backend == value) { backend = null; backendGeneration++; }
    }
    public static synchronized AndroidAutoSettings get() { return backend; }
    public static synchronized long generation() { return backendGeneration; }
}
