/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.ProjectUpdates;

/** Read-only initialized-agent status health, isolated from the page's current update job. */
final class UpdaterBacking implements SettingsMenuEntry.Gate, Runnable {
    private static final long HEALTH_INTERVAL_MS = 30000L;
    private final Runnable changed;
    private final ProjectUpdates.BackendProbe probe = new ProjectUpdates.BackendProbe();
    private volatile boolean available, stopped, hostReady;
    private Thread worker;
    UpdaterBacking(Runnable changed) { this.changed = changed; }
    public String title() { return "Project updates"; }
    public String unavailable() { return "hu-agent status unavailable; initialize via enrollment"; }
    void hosted() { hostReady = true; }
    public synchronized boolean live() { return !stopped && available && worker != null && worker.isAlive(); }
    synchronized void start() {
        if (worker != null || stopped) { return; }
        worker = new Thread(this, "lsd-updater-entry-backing"); worker.setDaemon(true); worker.start();
    }
    public void run() {
        try {
            while (!stopped) {
                boolean next = ProjectUpdates.configurationProblem() == null;
                if (next) { int result = probe.check(); next = result < 0 ? available : result > 0; }
                if (next != available || !hostReady) { available = next; changed.run(); }
                // Entry admission needs a bounded health check, not a continuous
                // journal scan. The open page renders its own changed job state.
                synchronized (this) { if (!stopped) { wait(HEALTH_INTERVAL_MS); } }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally {
            available = false;
            if (!stopped) { changed.run(); }
        }
    }
    void stop() {
        Thread running;
        synchronized (this) { stopped = true; probe.stop(); running = worker; notifyAll(); }
        if (running != null && running != Thread.currentThread()) {
            try { running.join(6000L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (running.isAlive()) { throw new IllegalStateException("Updater backing worker did not retire"); }
        }
    }
}
