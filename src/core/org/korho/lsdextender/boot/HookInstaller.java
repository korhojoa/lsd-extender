/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.boot;

import org.korho.lsdextender.RuntimeCore;
import org.korho.lsdextender.Slog;
import java.util.Vector;
import org.osgi.framework.Bundle;

/**
 * Starts owned core and independent bundle/event registrations from StartupHook.
 *
 * Every slog line carries boot_ms (milliseconds since MIBMain constructed
 * StartupHook, System.currentTimeMillis, the clock every wait in this class
 * uses) and wall_ms. No method here throws.
 *
 * Only the bounded owned readiness worker starts before admission. The feed,
 * listeners, configuration and features remain inactive on unavailable ASL.
 */
public final class HookInstaller {
    /** Bundle states reported by our independent listener. */
    public static final int STARTING = 8;
    public static final int ACTIVE = 32;

    private static long bootMs = -1L;
    private static boolean booted;
    private static final Vector listeners = new Vector();

    private HookInstaller() {
    }

    /** True once StartupHook ran: the launcher has the -D flag. */
    public static synchronized boolean startedBySpi() {
        return booted;
    }

    /** One slog line with the boot clock. Never throws. */
    public static void log(String msg) {
        try {
            long now = System.currentTimeMillis();
            long since = bootMs < 0 ? -1L : now - bootMs;
            Slog.write(" hooks: " + msg + " boot_ms=" + since + " wall_ms=" + now);
        } catch (Throwable ignored) {
            // logging is best effort
        }
    }

    /** From the StartupHook constructor on MIBMain's main thread: return fast. */
    static void boot() {
        synchronized (HookInstaller.class) {
            if (booted) {
                return;
            }
            booted = true;
            bootMs = System.currentTimeMillis();
        }
        org.korho.lsdextender.SiHook.start();
    }

    /** Called only by the owned worker after native ASL admission. */
    public static void activated() {
        log("StartupHook admitted [" + RuntimeCore.banner() + "]");
        BundleLifecycle.start();
    }

    /** Stock autostart proceeds immediately; no early listener or I/O. */
    static void beforeAutostart() {
        // Readiness admission owns all extension lifecycle activation.
    }

    /** Compatibility query: we never occupy the firmware monitor field. */
    public static boolean monitorInPlace() { return false; }

    /** A hook that waits for a bundle registers here; called for every later state change. */
    public static void addBundleListener(BundleStateListener l) {
        synchronized (listeners) {
            listeners.addElement(l);
        }
    }

    public static void removeBundleListener(BundleStateListener l) {
        synchronized (listeners) {
            listeners.removeElement(l);
        }
    }

    /** From our registered BundleListener. Client exceptions are contained. */
    static void onBundleState(int state, Bundle bundle) {
        Object[] ls;
        synchronized (listeners) {
            ls = new Object[listeners.size()];
            listeners.copyInto(ls);
        }
        for (int i = 0; i < ls.length; i++) {
            try {
                ((BundleStateListener) ls[i]).bundleStateChanged(state, bundle);
            } catch (Throwable t) {
                log("bundle listener " + ls[i].getClass().getName() + " failed: " + t);
            }
        }
    }
}
