/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.smartphoneintegration.target.HsmTarget;
import de.vw.mib.genericevents.EventGeneric;
import de.vw.mib.genericevents.GenericEvents;
import de.vw.mib.genericevents.Target;
import java.util.Vector;

/** An independently registered receiver; never substitutes the SI target. */
final class OwnedAslTarget implements Target {
    private static final int REGISTERED_EVENT = 8;
    static final class CleanupFailure extends Exception {
        private static final long serialVersionUID = 1L;
        CleanupFailure(String message) { super(message); }
    }
    private static final Object INSTALL_LOCK = new Object();
    private static OwnedAslTarget installed;
    private static boolean installing;
    private static final int MFL_EVENT = mflEvent();

    private static int mflEvent() {
        try {
            return Class.forName("generated.de.vw.mib.asl.internal.smartphoneintegration.ASLSmartPhoneIntegrationServiceConstants")
                .getField("MFL_EVENT").getInt(null);
        } catch (Throwable absent) { return -1; }
    }
    private final GenericEvents main;
    private final HsmTarget smartphone;
    private final Vector subscriptions = new Vector();
    private int targetId;
    private boolean closed;
    private boolean enabled;

    private OwnedAslTarget(GenericEvents main, HsmTarget smartphone) {
        this.main = main; this.smartphone = smartphone;
    }

    static boolean install(HsmTarget smartphone) throws Exception {
        synchronized (INSTALL_LOCK) {
            if (installed != null) { return true; }
            if (installing) { throw new IllegalStateException("own ASL registration in progress"); }
            installing = true;
        }
        OwnedAslTarget ours = null;
        try {
            GenericEvents main = smartphone.getMainObject();
            String task = main.getEventDispatcher().getTaskId(smartphone.getTargetId());
            if (task == null) { throw new IllegalStateException("native SI task unavailable"); }
            ours = new OwnedAslTarget(main, smartphone);
            main.getEventDispatcher().registerTarget(ours, task);
            if (MFL_EVENT != -1) { ours.subscribe(MFL_EVENT); }
            synchronized (INSTALL_LOCK) { installed = ours; }
            return true;
        } catch (Throwable failed) {
            if (ours != null) {
                try { ours.close(); }
                catch (Throwable cleanup) {
                    synchronized (INSTALL_LOCK) { installed = ours; }
                    throw new CleanupFailure("own ASL registration cleanup unproved: " + cleanup);
                }
            }
            throw new IllegalStateException("own ASL registration failed: " + failed);
        } finally {
            synchronized (INSTALL_LOCK) { installing = false; }
        }
    }

    static Target target() {
        synchronized (INSTALL_LOCK) { return installed; }
    }

    static boolean observe(final int event) {
        final OwnedAslTarget target;
        synchronized (INSTALL_LOCK) { target = installed; }
        if (target == null) { return false; }
        try {
            if (ServiceManager.aslThreadSwitch == null) { return false; }
            ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
                public void run() {
                    try { target.subscribe(event); }
                    catch (Throwable failed) { Slog.write(" own ASL subscription failed: " + failed); }
                }
            });
            return true;
        } catch (Throwable failed) {
            Slog.write(" own ASL subscription queue failed: " + failed);
            return false;
        }
    }

    private void subscribe(int event) {
        Integer id = new Integer(event);
        synchronized (this) {
            if (closed || subscriptions.contains(id)) { return; }
            // Record the intent first: native addObserver may throw after registration.
            subscriptions.addElement(id);
        }
        main.getServiceRegister().addObserver(event, targetId);
        synchronized (this) {
            if (!closed) { return; }
        }
        main.getServiceRegister().removeObserver(event, targetId);
    }

    static void shutdown() throws Exception {
        final OwnedAslTarget target;
        synchronized (INSTALL_LOCK) { target = installed; }
        if (target == null) { return; }
        target.close();
        synchronized (INSTALL_LOCK) {
            if (installed == target) { installed = null; } // NOPMD CompareObjectsWithEquals - exact owned registration.
        }
        LsdExtender.shutdownDeferred();
    }

    private void close() throws Exception {
        Object[] ids;
        synchronized (this) {
            closed = true;
            ids = new Object[subscriptions.size()];
            subscriptions.copyInto(ids);
        }
        if (main.getEventDispatcher().getTarget(targetId) != this) { // NOPMD CompareObjectsWithEquals - exact registration ownership.
            if (ids.length != 0) { throw new CleanupFailure("our ASL target is no longer registered"); }
            return;
        }
        Throwable failure = null;
        for (int i = 0; i < ids.length; i++) {
            int event = ((Integer) ids[i]).intValue();
            try { main.getServiceRegister().removeObserver(event, targetId); }
            catch (Throwable failed) { failure = failed; }
        }
        try { main.getEventDispatcher().unregisterTarget(targetId); }
        catch (Throwable failed) { failure = failed; }
        if (failure != null) { throw new CleanupFailure("own ASL cleanup unproved: " + failure); }
        synchronized (this) { subscriptions.removeAllElements(); }
    }

    static void enable() {
        final OwnedAslTarget target;
        synchronized (INSTALL_LOCK) { target = installed; }
        if (target != null) {
            synchronized (target) { if (!target.closed) { target.enabled = true; } }
        }
    }

    public GenericEvents getMainObject() { return main; }
    public int getTargetId() { return targetId; }
    public void setTargetId(int id) { targetId = id; }
    public void gotEvent(EventGeneric event) {
        if (event.getReceiverEventId() == REGISTERED_EVENT) { return; }
        synchronized (this) { if (closed || !enabled) { return; } }
        try {
            LsdExtender.onEvent(smartphone, event);
            if (MFL_EVENT != -1 && event.getReceiverEventId() == MFL_EVENT) {
                LsdExtender.onMflEvent(smartphone, event.getInt(0));
            }
        }
        catch (Throwable failed) { Slog.write(" own ASL event failed: " + failed); }
    }
}
