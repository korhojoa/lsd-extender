/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import org.korho.lsdextender.AndroidAutoSettings;
import org.korho.lsdextender.AndroidAutoSettingsState;
import org.korho.lsdextender.Config;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.Pps;
import org.korho.lsdextender.Slog;

/** Off-event-thread backing checks and a real HMI process publication for our tile. */
final class SmallTileBacking implements SmallTileContent.Backing, Runnable {
    private final SettingsBackend[] backends = { SettingsBackend.androidAuto(), SettingsBackend.carPlay() };
    private final Runnable changed;
    private static final int LINE_END = '\n';
    private final boolean[] available = new boolean[2];
    private final AndroidAutoSettings[] admitted = new AndroidAutoSettings[2];
    private final Thread[] admittedWorkers = new Thread[2];
    private volatile boolean stopped;
    private Thread worker;
    private Process child;
    private String publication;
    private String pid;
    private String lastFailure;
    private String skinContext;
    SmallTileBacking(final OwnedSettingsPage.Host queue, final Runnable changed) {
        this(new Runnable() { public void run() { queue.enqueue(changed); } });
    }
    SmallTileBacking(Runnable changed) { this.changed = changed; }
    synchronized void start() {
        if (worker != null || stopped) { return; }
        worker = new Thread(this, "lsd-settings-tile-backing"); worker.setDaemon(true); worker.start();
    }
    public synchronized boolean live(int profile) {
        return !stopped && available[profile] && current(backends[profile], admitted[profile], admittedWorkers[profile]);
    }
    static boolean current(SettingsBackend backend, AndroidAutoSettings admitted, Thread running) {
        if (admitted == null || running == null || !running.isAlive() || backend.get() != admitted) { return false; }
        AndroidAutoSettingsState state = admitted.snapshot();
        return state != null && state.ready && state.status != null && !state.status.startsWith("Settings unavailable:");
    }
    public void run() {
        try {
            pid = parentPid();
            File mount = new File("/ramdisk/pps");
            if (!mount.isDirectory()) { mount = new File("/pps"); }
            if (!mount.isDirectory()) { throw new IllegalStateException("PPS mount unavailable"); }
            publication = new File(mount, "lsdextender/settings_tile").getPath();
            while (!stopped) {
                boolean[] next = { probe(0), probe(1) };
                de.vw.mib.configuration.ConfigurationManager config = de.vw.mib.widgets.internal.ServiceManager.configurationManager;
                String nextContext = config == null ? "unavailable" : config.getCurrentSkinId() + "/" + config.getCurrentGuiLanguage();
                Pps.write(publication, "pid::" + pid + "\nalive::true\naa::" + next[0] + "\ncp::" + next[1] + "\n");
                boolean notify;
                synchronized (this) {
                    notify = available[0] != next[0] || available[1] != next[1] || !nextContext.equals(skinContext);
                    skinContext = nextContext;
                    available[0] = next[0]; available[1] = next[1];
                }
                if (notify) { changed.run(); }
                synchronized (this) { if (!stopped) { wait(1000L); } }
            }
        } catch (Exception failure) {
            if (!stopped) { report(failure.toString()); }
        } finally {
            synchronized (this) { available[0] = false; available[1] = false; }
            if (publication != null) {
                try { Pps.write(publication, "pid::" + pid + "\nalive::false\naa::false\ncp::false\n"); }
                catch (java.io.IOException failure) { report(failure.toString()); }
            }
            if (!stopped) { changed.run(); }
        }
    }
    private boolean probe(int profile) throws Exception {
        SettingsBackend backend = backends[profile];
        AndroidAutoSettings service = backend.get();
        if (service == null || !"org.korho.lsdextender.aasettings.ProjectionSettingsService".equals(service.getClass().getName())) { return false; }
        // Inspect only our own service. A retained ready snapshot is not proof its worker lives.
        Field workerField = service.getClass().getDeclaredField("worker"); workerField.setAccessible(true);
        Thread serviceWorker = (Thread)workerField.get(service);
        Field stopField = service.getClass().getDeclaredField("stopped"); stopField.setAccessible(true);
        if (serviceWorker == null || !serviceWorker.isAlive() || stopField.getBoolean(service)
                || !new File("/proc/" + pid).isDirectory()) { return false; }
        String directory = Config.directory();
        if (directory == null || !new File(directory, profile == 0 ? "android-auto-settings" : "carplay-settings").isFile()) { return false; }
        // Availability observes the live worker and cached answer. Only the
        // owned page requests new values; a poll must not spawn the helper.
        synchronized (this) { admitted[profile] = service; admittedWorkers[profile] = serviceWorker; }
        return current(backend, service, serviceWorker);
    }
    private String parentPid() throws Exception {
        Process process = Runtime.getRuntime().exec(new String[] { "/bin/sh", "-c", "echo \"$PPID\"" });
        synchronized (this) { child = process; if (stopped) { process.destroy(); } }
        InputStream input = process.getInputStream();
        StringBuffer value = new StringBuffer();
        long deadline = System.currentTimeMillis() + 2000L;
        try {
            while (!stopped && System.currentTimeMillis() < deadline && value.length() < 32) {
                if (input.available() > 0) {
                    int next = input.read();
                    if (next == LINE_END) {
                        String result = value.toString();
                        if (!validPid(result) || !new File("/proc/" + result).isDirectory()) { break; }
                        return result;
                    }
                    if (next < 0) { break; }
                    value.append((char)next);
                } else { Thread.sleep(10L); }
            }
            throw new IllegalStateException("HMI parent PID unavailable");
        } finally {
            input.close(); process.getErrorStream().close(); process.getOutputStream().close(); process.destroy();
            synchronized (this) { if (child == process) { child = null; } }
        }
    }
    static boolean validPid(String value) {
        if (value == null || value.length() == 0 || value.length() > 10 || value.charAt(0) == '0') { return false; }
        for (int i = 0; i < value.length(); i++) { if (value.charAt(i) < '0' || value.charAt(i) > '9') { return false; } }
        return true;
    }
    private void report(String reason) {
        if (!reason.equals(lastFailure)) {
            lastFailure = reason;
            Slog.write(" homeui tile backing refused: " + reason + " " + EventClock.stamp());
        }
    }
    void stop() {
        Thread running;
        synchronized (this) { stopped = true; if (child != null) { child.destroy(); } running = worker; notifyAll(); }
        if (running != null && running != Thread.currentThread()) {
            try { running.join(2500L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (running.isAlive()) { throw new IllegalStateException("Owned tile backing worker did not retire"); }
        }
    }
}
