/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.dsispy;

import java.lang.reflect.Method;
import java.io.FileInputStream;
import org.korho.lsdextender.LsdExtender;
import org.korho.lsdextender.EventClock;
import org.dsi.ifc.kombisync2.DisplayStatus;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor;
import de.vw.mib.asl.framework.internal.framework.dsi.util.Utils;

/** Read-only tap on existing dynamic callbacks and existing PPS snapshots. */
final class ClusterObserver implements Runnable {
    private static final int UNIQUE_DECLARATION_COUNT = 1, SNAPSHOT_CAPACITY = 16;
    private static final long SNAPSHOT_INTERVAL_MS = 10000L;
    interface Reader { String read(String path); }
    interface Output { void line(String summary); void detail(String text); }
    interface Clock { long millis(); long timerMillis(); }
    private static final String[] OBJECTS = { "/ramdisk/pps/gal/cluster",
        "/ramdisk/pps/gal/cluster_status", "/ramdisk/pps/cluster/gal" };
    private final ClusterObservation state = new ClusterObservation();
    private final Reader reader;
    private final Output output;
    private final Clock clock;
    private final KombiSyncTap kombiSync;
    private final long origin;
    private boolean stopped;
    private Thread worker;
    private final Tap[] taps = new Tap[3];
    private final Object registrationLock = new Object();
    private boolean setupAttempted;
    private final String[][] pending = new String[SNAPSHOT_CAPACITY][];
    private int head, count;
    private long lastSnapshot = Long.MIN_VALUE, dropped, discarded, outputFailures, reportedDropped, reportedDiscarded, reportedOutputFailures;

    ClusterObserver() {
        this(new Reader() {
            public String read(String path) { return readSnapshot(path); }
        }, new Output() {
            public void line(String summary) { LsdExtender.trace(" " + summary); }
            public void detail(String text) { LsdExtender.feed(" " + text); }
        }, new Clock() {
            public long millis() { return System.currentTimeMillis(); }
            public long timerMillis() { return EventClock.millis(); }
        });
    }

    ClusterObserver(Reader reader, Output output, Clock clock) {
        this.reader = reader; this.output = output; this.clock = clock; origin = clock.millis();
        kombiSync = new KombiSyncTap(output, clock);
    }

    /** A single non-wait PPS snapshot read, never a delta or a second blocking read. */
    private static String readSnapshot(String path) {
        FileInputStream input = null;
        try {
            input = new FileInputStream(path);
            byte[] bytes = new byte[8192];
            int count = input.read(bytes);
            return count <= 0 || count == bytes.length ? null : new String(bytes, 0, count, "UTF-8");
        } catch (Exception unavailable) { return null; }
        finally { if (input != null) { try { input.close(); } catch (Exception ignored) { /* read-only */ } } }
    }

    synchronized void start() {
        if (worker != null || stopped) { return; }
        worker = new Thread(this, "lsd-cluster-observer"); worker.setDaemon(true); worker.start();
    }

    /** Separate owned passive interceptors; no native query or notification request. */
    void install(DSIProxy proxy) {
        synchronized (registrationLock) { installLocked(proxy); }
    }

    private void installLocked(DSIProxy proxy) {
        synchronized (this) { if (stopped || setupAttempted) { return; } setupAttempted = true; }
        String[] services = { "org.dsi.ifc.kombisync2.DSIKombiSync",
            "org.dsi.ifc.komoview.DSIKOMOView", "org.dsi.ifc.displaymanagement.DSIDisplayManagement" };
        String[] labels = { "kombi_tap", "komo_tap", "dm_tap" };
        for (int i = 0; i < services.length; i++) {
            synchronized (this) { if (stopped) { return; } }
            try {
                Tap tap = new Tap(proxy, services[i]);
                taps[i] = tap;
                tap.attempted = true; // Retain exact cleanup even if add accepts then throws.
                proxy.addListenerInterceptor(tap.listener, tap);
                state.nativeValue(labels[i], "interceptor_registered");
            } catch (Throwable unavailable) { state.nativeValue(labels[i], null); }
        }
        kombiSync.install(proxy);
    }

    boolean stop() {
        Thread ending;
        synchronized (state) {
            synchronized (this) { stopped = true; ending = worker; notifyAll(); }
            discarded += count;
            for (int i = 0; i < pending.length; i++) { pending[i] = null; }
            head = 0; count = 0;
        }
        kombiSync.stopCapture();
        boolean detached = detach();
        if (ending == null && (kombiSync.needsStopReport() || snapshotLosses())) {
            // No output on the lifecycle caller, including a stop before normal start.
            // The existing run/finally path emits only diagnostics because stopped is set.
            try {
                synchronized (this) {
                    if (worker == null) {
                        Thread finalOutput = new Thread(this, "lsd-cluster-observer-stop");
                        finalOutput.setDaemon(true);
                        finalOutput.start();
                        worker = finalOutput;
                    }
                    ending = worker;
                }
            } catch (Throwable unavailable) { return false; }
        }
        if (ending != null) {
            ending.interrupt();
            try { ending.join(1000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
        return detached && (ending == null || !ending.isAlive());
    }

    private boolean detach() {
        synchronized (registrationLock) { return detachLocked(); }
    }

    private boolean detachLocked() {
        boolean clean = kombiSync.detach();
        for (int i = 0; i < taps.length; i++) {
            Tap tap = taps[i];
            if (tap == null || !tap.attempted) { continue; }
            try { tap.proxy.removeListenerInterceptor(tap.listener, tap); tap.attempted = false; }
            catch (Throwable failed) { clean = false; }
        }
        return clean;
    }

    public void run() {
        try {
            while (true) {
                synchronized (this) { if (stopped) { return; } }
                sample();
                synchronized (this) {
                    if (stopped) { return; }
                    try { wait(1000L); } catch (InterruptedException ending) { return; }
                }
            }
        } finally { kombiSync.finishStop(); reportSnapshotLosses(true); }
    }

    /** The worker owns file I/O and output; callbacks share only bounded model updates. */
    void sample() {
        kombiSync.drain();
        String[] snapshots = new String[OBJECTS.length];
        for (int i = 0; i < OBJECTS.length; i++) {
            synchronized (this) { if (stopped) { return; } }
            snapshots[i] = reader.read(OBJECTS[i]);
        }
        long now = clock.millis();
        long timer = timerMillis();
        synchronized (state) {
            synchronized (this) { if (stopped) { return; } }
            for (int i = 0; i < OBJECTS.length; i++) { state.pps(i, snapshots[i]); }
            long tick = timer < 0 ? now : timer;
            boolean due = lastSnapshot == Long.MIN_VALUE || tick < lastSnapshot
                || tick - lastSnapshot >= SNAPSHOT_INTERVAL_MS;
            queue(state.snapshot(now, now - origin, timer, due), tick);
        }
        drainSnapshots();
        reportSnapshotLosses(false);
    }

    private long timerMillis() {
        try { return clock.timerMillis(); } catch (Throwable unavailable) { return -1L; }
    }

    /** Caller holds state; every queued record is immutable before callback return. */
    private void queue(String[] pair, long tick) {
        if (pair == null) { return; }
        if (count == SNAPSHOT_CAPACITY) { dropped++; return; }
        pending[(head + count) % SNAPSHOT_CAPACITY] = pair;
        count++; lastSnapshot = tick;
    }

    private void drainSnapshots() {
        for (int i = 0; i < SNAPSHOT_CAPACITY; i++) {
            String[] pair;
            synchronized (state) {
                synchronized (this) { if (stopped) { return; } }
                if (count == 0) { return; }
                pair = pending[head]; pending[head] = null;
                head = (head + 1) % SNAPSHOT_CAPACITY; count--;
            }
            try { output.line(pair[0]); }
            catch (Throwable unavailable) { failedSnapshotOutput(); }
            try { output.detail(pair[1]); }
            catch (Throwable unavailable) { /* Native values below do not depend on the optional feed. */ }
            for (int j = 2; j < pair.length; j++) {
                try { output.line(pair[j]); }
                catch (Throwable unavailable) { failedSnapshotOutput(); }
            }
        }
    }

    private void failedSnapshotOutput() { synchronized (state) { outputFailures++; } }

    private boolean snapshotLosses() {
        synchronized (state) { return dropped != reportedDropped || discarded != reportedDiscarded || outputFailures != reportedOutputFailures; }
    }

    private void reportSnapshotLosses(boolean ending) {
        long lost, retired, failed;
        synchronized (state) {
            if (!snapshotLosses()) { return; }
            lost = dropped; retired = discarded; failed = outputFailures;
        }
        try {
            output.line("cluster-capture stopped=" + ending + " dropped=" + lost + " pending_discarded=" + retired + " output_failures=" + failed);
            synchronized (state) { reportedDropped = lost; reportedDiscarded = retired; reportedOutputFailures = failed; }
        } catch (Throwable unavailable) { /* Retain counters for a later worker attempt. */ }
    }

    /** Method is from the actual dynamic listener, never a guessed interceptor ordinal. */
    void observe(String service, Method method, Object[] args) {
        synchronized (state) {
            synchronized (this) { if (stopped) { return; } }
            if (!observeLocked(service, method, args)) { return; }
            long now = clock.millis();
            long timer = timerMillis();
            queue(state.changedSample(now, now - origin, timer), timer < 0 ? now : timer);
        }
    }

    private boolean observeLocked(String service, Method method, Object[] args) {
        if (args == null) { return false; }
        if (!method.getDeclaringClass().getName().equals(service + "Listener")) { return false; }
        String name = method.getName();
        if (service.equals("org.dsi.ifc.kombisync2.DSIKombiSync")
            && name.equals("responseKombiDisplayStatus") && args.length == 1) {
            kombi(args[0]);
        } else if (service.equals("org.dsi.ifc.komoview.DSIKOMOView") && args.length == 2) {
            if (!komo(name, args)) { return false; }
        } else if (service.equals("org.dsi.ifc.displaymanagement.DSIDisplayManagement")
            && name.equals("activeContext") && args.length == 3) {
            if (!new Integer(4).equals(args[1])) { return false; }
            state.nativeValue("dm_context", String.valueOf(args[0]));
            state.nativeValue("dm_display", String.valueOf(args[1]));
            state.nativeValue("dm_raw_arg2", String.valueOf(args[2]));
        } else { return false; }
        return true;
    }

    private void kombi(Object value) {
        DisplayStatus status = value instanceof DisplayStatus ? (DisplayStatus)value : null;
        state.nativeValue("kombi_internal", status == null ? null : "" + status.internalState);
        state.nativeValue("kombi_context", status == null ? null : "" + status.mainContext);
        boolean flagsPresent = status != null && status.statusFlags != null;
        state.nativeValue("kombi_flags", flagsPresent ? "" + status.statusFlags.statusFlags : null);
        state.nativeValue("kombi_flags_validity", !flagsPresent ? null
            : (status.statusFlags.statusFlags & 32768) == 0 ? "not_marked_invalid" : "ALL_FLAGS_INVALID");
    }

    private boolean komo(String name, Object[] args) {
        String key = name.equals("updateKomoViewEnabled") ? "komo_enabled"
            : name.equals("updateVisibility") ? "komo_visible"
            : name.equals("updateCurrentKomoViewType") ? "komo_type" : null;
        if (key == null) { return false; }
        state.nativeValue(key, new Integer(1).equals(args[1]) ? String.valueOf(args[0]) : null);
        state.nativeValue(key + "_validity", String.valueOf(args[1]));
        return true;
    }

    private final class Tap implements DSIProxyInterceptor {
        final DSIProxy proxy;
        final String service;
        final Class listener;
        final int typeIndex;
        final Method[] watched;
        boolean attempted;

        Tap(DSIProxy proxy, String service) throws Exception {
            this.proxy = proxy; this.service = service;
            listener = Class.forName(service + "Listener");
            typeIndex = Utils.getTypeIndex(listener);
            if (typeIndex < 0) { throw new IllegalStateException("unknown original listener type"); }
            if (service.indexOf("kombisync2") >= 0) {
                watched = new Method[] { listener.getMethod("responseKombiDisplayStatus", new Class[] { DisplayStatus.class }) };
            } else if (service.indexOf("komoview") >= 0) {
                watched = new Method[] {
                    listener.getMethod("updateKomoViewEnabled", new Class[] { Boolean.TYPE, Integer.TYPE }),
                    listener.getMethod("updateVisibility", new Class[] { Boolean.TYPE, Integer.TYPE }),
                    listener.getMethod("updateCurrentKomoViewType", new Class[] { Integer.TYPE, Integer.TYPE }) };
            } else {
                watched = new Method[] { listener.getMethod("activeContext", new Class[] { Integer.TYPE, Integer.TYPE, Integer.TYPE }) };
            }
            Method[] all = listener.getMethods();
            for (int i = 0; i < watched.length; i++) {
                int matches = 0;
                for (int j = 0; j < all.length; j++) { if (all[j].getName().equals(watched[i].getName())) { matches++; } }
                if (matches != UNIQUE_DECLARATION_COUNT) { throw new IllegalStateException("ambiguous original callback name"); }
            }
        }

        public boolean before(String type, int instance, int index, Object[] args) {
            try {
                synchronized (ClusterObserver.this) { if (stopped) { return true; } }
                if (instance != 0 || index < 0 || !listener.getName().equals(type)) { return true; }
                String name = Utils.getRuntimeListenerMethodName(typeIndex, index);
                for (int i = 0; i < watched.length; i++) {
                    if (watched[i].getName().equals(name)) { observe(service, watched[i], args); break; }
                }
            } catch (Throwable unavailable) { /* Always preserve native dispatch; no guessed fallback. */ }
            return true;
        }

        public void after(String type, int instance, int index, Object[] args) { }
    }

}
