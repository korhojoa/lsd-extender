/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import org.korho.lsdextender.LsdExtender;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.genericevents.Target;
import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.powermanagement.DSIPowerManagement;

/** Explicit user activation action. Construction and polling never restart. */
final class NativeRestartAction {
    interface Action { void restart(); }
    interface Queue { void enqueue(Runnable work); }
    private final Action action;
    private final Queue queue;
    private boolean requested, started, complete;
    private Throwable outcome;
    NativeRestartAction(Action action) {
        this(action, new Queue() { public void enqueue(Runnable work) { work.run(); } });
    }
    NativeRestartAction(Action action, Queue queue) {
        if (action == null || queue == null) { throw new IllegalArgumentException("restart action and queue required"); }
        this.action = action; this.queue = queue;
    }
    static NativeRestartAction resolve() {
        return resolve(new Action() {
            public void restart() { nativeRestart(); }
        });
    }
    /** Uses the production ASL queue with an explicitly supplied terminal action. */
    static NativeRestartAction resolve(Action terminal) {
        return new NativeRestartAction(terminal, new Queue() {
            public void enqueue(Runnable work) {
                if (de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch == null) {
                    throw new IllegalStateException("native ASL dispatcher unavailable");
                }
                de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch.enqueue(work);
            }
        });
    }
    private static void nativeRestart() {
        Target target = LsdExtender.siTarget();
        if (target == null) { throw new IllegalStateException("initialized native target unavailable"); }
        DSIProxy proxy = DSIProxyFactory.getDSIProxyAPI().getDSIProxy();
        if (proxy == null) { throw new IllegalStateException("native DSI proxy unavailable"); }
        DSIBase service = proxy.getService(target, DSIPowerManagement.class);
        if (!(service instanceof DSIPowerManagement)) { throw new IllegalStateException("native restart service unavailable"); }
        ((DSIPowerManagement)service).rebootSystem();
    }
    synchronized void userNow() {
        if (requested) { return; }
        requested = true;
        try {
            queue.enqueue(new Runnable() {
                public void run() {
                    synchronized (NativeRestartAction.this) {
                        if (complete || started) { return; }
                        started = true;
                    }
                    Throwable value = null;
                    try { action.restart(); }
                    catch (Throwable failed) { value = failed; }
                    completed(value);
                }
            });
        } catch (RuntimeException failed) { completed(failed); }
        catch (LinkageError failed) { completed(failed); }
    }
    private synchronized void completed(Throwable value) {
        if (complete) { return; }
        outcome = value; complete = true;
    }
    synchronized boolean isComplete() { return complete; }
    synchronized Throwable failure() { return outcome; }
}
