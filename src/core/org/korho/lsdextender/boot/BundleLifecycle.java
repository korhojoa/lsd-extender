/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.boot;

import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import java.util.Hashtable;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;

/** Our lifecycle registration, alongside the firmware's unchanged monitor. */
public final class BundleLifecycle implements BundleListener {
    private static final long WAIT_MS = 30000L;
    private static boolean started;
    private static boolean stopped;
    private static BundleLifecycle installed;
    private final BundleContext context;
    private boolean closed;
    private final Hashtable active = new Hashtable();

    private BundleLifecycle(BundleContext context) { this.context = context; }

    static synchronized void start() {
        if (started) { return; }
        started = true;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                long until = System.currentTimeMillis() + WAIT_MS;
                try {
                    for (int attempt = 0; attempt < 300 && System.currentTimeMillis() < until; attempt++) {
                        synchronized (BundleLifecycle.class) { if (stopped) { return; } }
                        BundleContext context = ServiceManager.bundleContext;
                        if (context != null) { attach(context); return; }
                        Thread.sleep(100L);
                    }
                    HookInstaller.log("own bundle listener unavailable: ASL context deadline");
                } catch (Throwable failed) { HookInstaller.log("own bundle listener failed: " + failed); }
            }
        }, "lsd-extender-bundle-listener");
        worker.setDaemon(true);
        worker.start();
    }

    static synchronized void attach(BundleContext context) {
        if (installed != null || stopped) { return; }
        BundleLifecycle listener = new BundleLifecycle(context);
        context.addBundleListener(listener);
        installed = listener;
        try {
            Bundle[] bundles = context.getBundles();
            for (int i = 0; bundles != null && i < bundles.length; i++) {
                if (bundles[i] != null && bundles[i].getState() == (32)) { listener.deliver(bundles[i], true); }
            }
            HookInstaller.log("own bundle listener registered; ACTIVE sweep complete (no STARTING hook)");
        } catch (Throwable failed) {
            context.removeBundleListener(listener);
            installed = null;
            throw new IllegalStateException("own bundle ACTIVE sweep failed: " + failed);
        }
    }

    public void bundleChanged(BundleEvent event) {
        try {
            if (event.getType() == (2)) { deliver(event.getBundle(), true); }
            else if (event.getType() == (4)) { deliver(event.getBundle(), false); }
        } catch (Throwable failed) { HookInstaller.log("own bundle notification failed: " + failed); }
    }

    private void deliver(Bundle bundle, boolean running) {
        synchronized (this) {
            if (closed) { return; }
            Long id = new Long(bundle.getBundleId());
            if (running) {
                if (active.containsKey(id)) { return; }
                active.put(id, Boolean.TRUE);
            } else {
                if (active.remove(id) == null) { return; }
            }
        }
        HookInstaller.onBundleState(running ? (32) : (4), bundle);
    }

    /** Remove only our registration; the stock monitor and listeners are untouched. */
    public static synchronized void shutdown() {
        stopped = true;
        if (installed != null) {
            BundleLifecycle listener = installed;
            synchronized (listener) { listener.closed = true; }
            listener.context.removeBundleListener(listener);
            installed = null;
        }
    }
}
