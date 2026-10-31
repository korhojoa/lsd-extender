/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.genericevents.Target;
import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.powermanagement.DSIPowerManagement;

/** Explicit shell restart requests, dispatched through the original ASL queue. */
final class RestartCommand implements CommandHandler {
    static final String NAME = "restart";
    interface Queue { void enqueue(Runnable work); }
    interface Action { void restart(); }
    interface Log { void write(String line); }
    private static long sequence;
    private final Queue queue;
    private final Action action;
    private final Log log;

    RestartCommand() {
        this(new Queue() {
            public void enqueue(Runnable work) {
                if (ServiceManager.aslThreadSwitch == null) {
                    throw new IllegalStateException("native ASL dispatcher unavailable");
                }
                ServiceManager.aslThreadSwitch.enqueue(work);
            }
        }, new Action() {
            public void restart() { nativeRestart(); }
        }, new Log() {
            public void write(String line) { Slog.write(line); }
        });
    }

    RestartCommand(Queue queue, Action action, Log log) {
        if (queue == null || action == null || log == null) {
            throw new IllegalArgumentException("restart queue, action and log required");
        }
        this.queue = queue; this.action = action; this.log = log;
    }

    public String command(String line) {
        if (!NAME.equals(line)) { return null; }
        Request request = new Request(nextRequest());
        try {
            queue.enqueue(request);
            return "ok restart queued request=" + request.id;
        } catch (Throwable failed) {
            request.finish(failed);
            return "err restart request=" + request.id + ": " + failure(failed);
        }
    }

    private static synchronized long nextRequest() { return ++sequence; }

    private static String failure(Throwable failed) {
        String text = failed.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private static void nativeRestart() {
        Target target = LsdExtender.nativeTarget();
        if (target == null) { throw new IllegalStateException("initialized native target unavailable"); }
        DSIProxy proxy = DSIProxyFactory.getDSIProxyAPI().getDSIProxy();
        if (proxy == null) { throw new IllegalStateException("native DSI proxy unavailable"); }
        DSIBase service = proxy.getService(target, DSIPowerManagement.class);
        if (!(service instanceof DSIPowerManagement)) {
            throw new IllegalStateException("native restart service unavailable");
        }
        ((DSIPowerManagement) service).rebootSystem();
    }

    private final class Request implements Runnable {
        private final long id;
        private boolean started;
        private boolean complete;
        Request(long id) { this.id = id; }
        public void run() {
            synchronized (this) {
                if (started || complete) { return; }
                started = true;
            }
            Throwable failure = null;
            try { action.restart(); }
            catch (Throwable failed) { failure = failed; }
            finish(failure);
        }
        synchronized void finish(Throwable failed) {
            if (complete) { return; }
            complete = true;
            log.write(" cmd restart request=" + id + (failed == null
                ? " dispatched via native power management" : " failed: " + failure(failed)));
        }
    }
}
