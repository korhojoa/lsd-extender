/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** A bounded handoff to an HMI or ASL dispatcher, with cancellation before dispatch. */
public final class ThreadTask implements Runnable {
    public static final Runnable IDLE = new Runnable() { public void run() { } };
    private Runnable work;
    private boolean started, complete;
    private Throwable failure;

    public ThreadTask(Runnable work) { this.work = work; }

    public void run() {
        Runnable task;
        synchronized (this) {
            task = work;
            if (task == null) { return; }
            started = true;
        }
        try { task.run(); }
        catch (Throwable failed) { failure = failed; }
        finally {
            synchronized (this) { work = null; complete = true; notifyAll(); }
        }
    }

    public synchronized void await() {
        long deadline = System.currentTimeMillis() + 10000L;
        while (!complete) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 && !started) {
                work = null;
                throw new IllegalStateException("dispatcher did not accept reload work");
            }
            try { wait(left > 0 ? left : 1000L); }
            catch (InterruptedException interrupted) {
                if (!started) { work = null; }
                Thread.currentThread().interrupt();
                throw new IllegalStateException("reload interrupted");
            }
        }
        if (failure != null) { throw new IllegalStateException("reload work failed: " + failure); }
    }
}
