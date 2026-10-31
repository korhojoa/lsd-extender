/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Publishes native cluster handover and reads the active cluster owners. */
public final class ClusterEarly {
    private static final String PREFIX = " cluster-early: ";
    private static final String AT_UPTIME = " at uptime_ms=";
    private static final long POLL_MS = 500L;
    private static final Object LOCK = new Object();
    private static boolean started;

    private ClusterEarly() { }

    /** Observe the native context switch once per JVM. */
    public static void start() {
        synchronized (LOCK) {
            if (started) { return; }
            started = true;
        }
        if (Config.getBool("lsdextender.cluster.handover", true)) { watchHandover(); }
    }

    static boolean isStarted() {
        synchronized (LOCK) { return started; }
    }

    /** Compatibility for an older runtime hook; this service sends no DSI calls. */
    public static boolean ownCall() { return false; }

    /**
     * GAL announces cluster ownership with taking::1 in
     * lsdextender.cluster.gal_pps (/ramdisk/pps/cluster/gal), back to 0 at session end.
     */
    public static boolean galTaking() {
        String path = Config.get("lsdextender.cluster.gal_pps", "/ramdisk/pps/cluster/gal");
        String text = readSmall(path);
        return text.startsWith("taking::1") || text.indexOf("\ntaking::1") >= 0;
    }

    private static String readSmall(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }

    static final int HMI_SWITCH_EVENT = 1075841828;
    static final int NAV_CTX = 70;
    private static boolean handoverPublished;

    /** Observes HMI_SWITCH_EVENT once our ASL target exists (about 20 s), on its own thread. */
    private static void watchHandover() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 240; i++) {
                    if (LsdExtender.observeAsl(HMI_SWITCH_EVENT)) {
                        log("observing the HMI's cluster context switch (ASL " + HMI_SWITCH_EVENT
                            + ") from uptime_ms=" + uptimeMs());
                        return;
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                log("no own ASL target in 120 s: the handover is not observed");
            }
        }, "lsd-extender-cluster-handover");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Read ctx from lsdextender.cluster.owner_pps, written by the current display
     * owner while it is active; return 0 when none is present. The default object
     * is /ramdisk/pps/cluster/owner. This read reports the requested context
     * and does not rewrite stock display requests.
     */
    public static int ownerContext() {
        String path = Config.get("lsdextender.cluster.owner_pps", "/ramdisk/pps/cluster/owner");
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) {
                return 0;
            }
            String text = new String(buf, 0, n, "UTF-8");
            int at = text.indexOf("\nctx::");
            int from = at >= 0 ? at + 6 : (text.startsWith("ctx::") ? 5 : -1);
            if (from < 0) {
                return 0;
            }
            int end = text.indexOf('\n', from);
            return Integer.parseInt(text.substring(from, end < 0 ? text.length() : end).trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }

    /** Observe the HMI cluster context through the owned ASL event path. */
    public static void onHmiSwitch(int ctx) {
        long now = uptimeMs();
        if (ctx != NAV_CTX) {
            log("HMI switched the cluster to context " + ctx + AT_UPTIME + now);
            return;
        }
        synchronized (LOCK) {
            if (handoverPublished) {
                return;
            }
            handoverPublished = true;
        }
        String path = Config.get("lsdextender.cluster.handover_pps", "/ramdisk/pps/cluster/handover");
        String r;
        try {
            Pps.write(path, "hmi_ctx::" + ctx + "\nhmi_ms::" + now + "\n");
            r = "published to " + path;
        } catch (Throwable t) {
            r = "PPS write to " + path + " failed: " + t;
        }
        log("HMI handed the cluster to nav (context " + ctx + ")" + AT_UPTIME + now + ": " + r);
    }

    /** The HMI timer clock (EventClock), or the wall clock until that is up. */
    public static long uptimeMs() {
        long m = EventClock.millis();
        return m >= 0 ? m : System.currentTimeMillis();
    }

    static void log(String text) { Slog.write(PREFIX + text); }
}
