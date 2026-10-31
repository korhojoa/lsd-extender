/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

import de.vw.mib.timer.Invoker;
import de.vw.mib.timer.Timer;
import de.vw.mib.timer.TimerManager;
import java.util.Vector;

/** Exact owned native timer handles, retained across uncertain queue outcomes. */
final class RestartTimers {
    interface Owner { void unavailable(); void log(String decision); }
    private final RestartCapability.Platform platform;
    private final Owner owner;
    private final Vector handles = new Vector();
    private boolean closed;
    RestartTimers(RestartCapability.Platform nativePlatform, Owner authority) { platform = nativePlatform; owner = authority; }
    void after(long delay, Runnable task) {
        Handle handle = new Handle(delay, task);
        synchronized (this) { if (closed) { return; } handles.addElement(handle); }
        handle.schedule();
    }
    synchronized boolean isEmpty() { return handles.isEmpty(); }
    private void settle(Handle handle) {
        try { handle.cancel(); synchronized (this) { handles.removeElement(handle); } }
        catch (Throwable pending) { owner.log("timer-cleanup-retained"); }
    }
    void close() {
        Object[] values;
        synchronized (this) { closed = true; values = new Object[handles.size()]; handles.copyInto(values); }
        for (int i = 0; i < values.length; i++) { settle((Handle)values[i]); }
        synchronized (this) { if (!handles.isEmpty()) { throw new IllegalStateException("owned dialog timer cleanup unresolved"); } }
    }
    private final class Handle {
        private final long delay;
        private final Runnable work;
        private Timer timer;
        private boolean cancelled, pending = true;
        Handle(long milliseconds, Runnable task) { delay = milliseconds; work = task; }
        void schedule() {
            try {
                platform.enqueue(new Runnable() { public void run() { install(); } });
            } catch (Throwable uncertain) {
                synchronized (this) { cancelled = true; }
                throw new IllegalStateException("timer enqueue unknown; exact handle retained");
            }
        }
        private void install() {
            try {
                synchronized (this) { if (cancelled) { return; } }
                TimerManager manager = platform.timers();
                Invoker invoker = platform.invoker();
                if (manager == null || invoker == null) { throw new IllegalStateException("native timer unavailable"); }
                Timer created = manager.createTimer("owned restart question", delay, false,
                    new Runnable() { public void run() { fire(); } }, invoker);
                if (created == null) { throw new IllegalStateException("native timer refused"); }
                boolean keep;
                synchronized (this) { timer = created; keep = !cancelled; }
                if (keep) { created.start(); }
                synchronized (this) { keep = !cancelled; }
                if (!keep) { cancel(); }
            } catch (Throwable failed) {
                try { cancel(); } catch (Throwable retained) { /* Registry retains exact ownership. */ }
                owner.unavailable();
            } finally {
                boolean retire;
                synchronized (this) { pending = false; retire = cancelled; }
                if (retire) { settle(this); }
            }
        }
        private void fire() {
            synchronized (this) { if (cancelled) { return; } }
            try { work.run(); }
            catch (Throwable failed) { owner.unavailable(); }
            finally {
                try { platform.enqueue(new Runnable() { public void run() { settle(Handle.this); } }); }
                catch (Throwable unknown) { owner.log("timer-retirement-enqueue-unknown"); }
            }
        }
        void cancel() {
            Timer old;
            synchronized (this) { cancelled = true; old = timer; }
            if (old != null) {
                old.stop();
                synchronized (this) { if (timer == old) { timer = null; } } // NOPMD CompareObjectsWithEquals - exact owned handle.
            }
            synchronized (this) { if (pending) { throw new IllegalStateException("owned timer installation pending"); } }
        }
    }
}
