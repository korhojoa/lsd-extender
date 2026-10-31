/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.genericevents.Target;
import java.util.Vector;
import org.dsi.ifc.smartphoneintegration.DSISmartphoneIntegration;
import org.dsi.ifc.smartphoneintegration.DSISmartphoneIntegrationListener;
import org.dsi.ifc.smartphoneintegration.Device;
import org.korho.lsdextender.LsdExtender;

/** Inert until explicitly opened. No command, UI, static startup or reconnect. */
public final class DisconnectServiceLease implements DSIServiceStateListener {
    interface Dispatcher { void enqueue(Runnable task); }
    interface Clock { long now(); }

    private static final Class SERVICE = DSISmartphoneIntegration.class;
    private static final Class LISTENER = DSISmartphoneIntegrationListener.class;
    private static final long LOG_ORIGIN = System.currentTimeMillis();
    private static final int INSTANCE = 0;
    private static final int[] ATTRS = new int[] {
        (1),
        (2)
    };
    private final DSIProxy proxy;
    private final Target target;
    private final Dispatcher dispatcher;
    private final Clock clock;
    private final DisconnectRequestState state = new DisconnectRequestState();
    private final Vector bindings = new Vector();
    private boolean closed, registrationAttempted, registrationRemoved, cleanupQueued;
    private long epoch;
    private Binding current;

    /** Explicit candidate-only factory. Normal runtime startup never calls it. */
    public static DisconnectServiceLease open() {
        Target target = LsdExtender.nativeTarget();
        if (target == null || ServiceManager.aslThreadSwitch == null) {
            throw new IllegalStateException("owned ASL target/dispatcher unavailable");
        }
        DisconnectServiceLease lease = new DisconnectServiceLease(
            DSIProxyFactory.getDSIProxyAPI().getDSIProxy(), target,
            new Dispatcher() {
                public void enqueue(Runnable task) { ServiceManager.aslThreadSwitch.enqueue(task); }
            },
            new Clock() { public long now() { return System.currentTimeMillis(); } });
        lease.begin();
        return lease;
    }

    DisconnectServiceLease(DSIProxy proxy, Target target, Dispatcher dispatcher, Clock clock) {
        this.proxy = proxy; this.target = target; this.dispatcher = dispatcher; this.clock = clock;
    }

    void begin() {
        try { start(); }
        catch (Throwable failed) {
            close();
            trace("start scheduling failed; closed lease retains cleanup authority: " + failed);
        }
    }

    void start() {
        dispatcher.enqueue(new Runnable() {
            public void run() {
                synchronized (DisconnectServiceLease.this) {
                    if (closed || registrationAttempted) { return; }
                    // add can call registered immediately, including before it returns.
                    registrationAttempted = true;
                    try { proxy.addServiceStateListener(SERVICE, INSTANCE, DisconnectServiceLease.this); }
                    catch (Throwable failed) {
                        closed = true; state.close();
                        trace("service registration failed; exact cleanup retained: " + failed);
                        queueCleanup();
                    }
                }
            }
        });
    }

    public synchronized void registered(String name, int instance) {
        if (closed || instance != INSTANCE) { return; }
        epoch = state.registering();
        current = null;
        final long expectedEpoch = epoch;
        try {
            dispatcher.enqueue(new Runnable() {
                public void run() { bind(expectedEpoch); }
            });
        } catch (Throwable failed) {
            epoch = state.registering(); state.unavailable(epoch); current = null;
            closed = true; state.close();
            trace("bind enqueue failed: " + failed);
            queueCleanup();
        }
    }

    private synchronized void bind(long expectedEpoch) {
        if (closed || expectedEpoch != epoch) { return; }
        cleanupBindings();
        try {
            DSISmartphoneIntegration service = (DSISmartphoneIntegration)proxy.getService(target, SERVICE, INSTANCE);
            if (service == null) { throw new IllegalStateException("service unavailable"); }
            Binding binding = new Binding(expectedEpoch, service);
            bindings.addElement(binding);
            current = binding;
            state.bound(expectedEpoch);
            binding.responseAttempted = true;
            proxy.addResponseListener(target, LISTENER, INSTANCE, binding.listener);
            binding.notificationsAttempted = true;
            service.setNotification((int[])ATTRS.clone(), binding.listener);
            trace("owned service observation bound; no request issued");
        } catch (Throwable failed) {
            current = null; state.unavailable(expectedEpoch);
            trace("subscription failed; exact binding cleanup retained: " + failed);
            cleanupBindings();
        }
    }

    public synchronized void unregistered(String name, int instance) {
        if (closed || instance != INSTANCE) { return; }
        // Cancel a previously queued bind even when getService can still return a proxy.
        epoch = state.registering(); state.unavailable(epoch); current = null;
        try {
            dispatcher.enqueue(new Runnable() {
                public void run() { synchronized (DisconnectServiceLease.this) { cleanupBindings(); } }
            });
        } catch (Throwable failed) { trace("unregister cleanup retained: " + failed); }
    }

    public DisconnectRequestState.Snapshot snapshot() { return state.snapshot(); }
    public String outcome() { return state.outcome(clock.now()); }
    public String detail() { return state.detail(); }

    /** Only queues an owned request; its result is never native completion. */
    public synchronized String request(DisconnectRequestState.Snapshot snapshot, long timeoutMillis) {
        if (closed || current == null) { return "LOCAL_REFUSED: no live owned binding"; }
        final DisconnectRequestState.Ticket ticket = state.reserve(snapshot, clock.now(), timeoutMillis);
        if (ticket == null) { return "LOCAL_REFUSED: stale, duplicate or spent observation epoch"; }
        final Binding binding = current;
        try {
            dispatcher.enqueue(new Runnable() {
                public void run() {
                    synchronized (DisconnectServiceLease.this) {
                        if (closed || current != binding || !state.issue(ticket, clock.now())) { return; } // NOPMD CompareObjectsWithEquals - Queued work belongs only to this exact live binding.
                        try {
                            binding.service.disconnectDevice(ticket.snapshot.deviceId());
                            trace("typed Disconnect invoked; delivery/acceptance/completion unproved");
                        } catch (Throwable failed) {
                            state.dispatchFailed(ticket);
                            trace("typed invocation failed; delivery unknown: " + failed);
                        }
                    }
                }
            });
            return DisconnectRequestState.QUEUED;
        } catch (Throwable failed) {
            state.dispatchFailed(ticket);
            trace("request enqueue failed: " + failed);
            return state.outcome(clock.now());
        }
    }

    /** Invalidates immediately; only this lease's exact cleanup is queued/retried. */
    public synchronized void close() {
        closed = true; state.close(); current = null;
        queueCleanup();
    }

    public synchronized boolean cleanupPending() {
        return registrationAttempted && !registrationRemoved || !bindings.isEmpty() || cleanupQueued;
    }

    private void queueCleanup() {
        if (cleanupQueued) { return; }
        cleanupQueued = true;
        try {
            dispatcher.enqueue(new Runnable() {
                public void run() {
                    synchronized (DisconnectServiceLease.this) {
                        try {
                            if (registrationAttempted && !registrationRemoved) {
                                try {
                                    proxy.removeServiceStateListener(SERVICE, INSTANCE, DisconnectServiceLease.this);
                                    registrationRemoved = true;
                                } catch (Throwable failed) { trace("state listener cleanup retained: " + failed); }
                            }
                            cleanupBindings();
                        } finally { cleanupQueued = false; }
                    }
                }
            });
        } catch (Throwable failed) {
            cleanupQueued = false;
            trace("cleanup enqueue failed; retry close: " + failed);
        }
    }

    private void cleanupBindings() {
        for (int i = bindings.size() - 1; i >= 0; i--) {
            Binding binding = (Binding)bindings.elementAt(i);
            if (binding == current && !closed) { continue; } // NOPMD CompareObjectsWithEquals - Preserve the exact current subscription, not an equivalent proxy.
            boolean clean = true;
            if (binding.notificationsAttempted) {
                try {
                    binding.service.clearNotification((int[])ATTRS.clone(), binding.listener);
                    binding.notificationsAttempted = false;
                } catch (Throwable failed) { clean = false; trace("attribute cleanup retained: " + failed); }
            }
            if (binding.responseAttempted) {
                try {
                    proxy.removeResponseListener(target, LISTENER, INSTANCE, binding.listener);
                    binding.responseAttempted = false;
                } catch (Throwable failed) { clean = false; trace("response cleanup retained: " + failed); }
            }
            if (clean) { bindings.removeElementAt(i); }
        }
    }

    private static void trace(String message) {
        LsdExtender.trace("[" + (System.currentTimeMillis() - LOG_ORIGIN) + "ms] si-disconnect: " + message);
    }

    private final class Binding {
        final long observedEpoch;
        final DSISmartphoneIntegration service;
        boolean responseAttempted, notificationsAttempted;
        final DSISmartphoneIntegrationListener listener;
        Binding(long observedEpoch, DSISmartphoneIntegration service) {
            this.observedEpoch = observedEpoch; this.service = service;
            listener = new DSISmartphoneIntegrationListener() {
                public void updateDiscoveredDevices(Device[] devices, int validity) {
                    state.tick(clock.now());
                    DisconnectRequestState.DeviceInfo[] copy = null;
                    if (devices != null) {
                        copy = new DisconnectRequestState.DeviceInfo[devices.length];
                        for (int i = 0; i < devices.length; i++) {
                            Device device = devices[i];
                            if (device != null) {
                                copy[i] = new DisconnectRequestState.DeviceInfo(device.deviceID,
                                    device.deviceAddress, device.connectionType, device.connectionMethod);
                            }
                        }
                    }
                    state.devices(Binding.this.observedEpoch, copy, validity);
                }
                public void updateDeviceConnectionState(int id, int value, int method, int validity) {
                    state.tick(clock.now());
                    state.state(Binding.this.observedEpoch, id, value, method, validity);
                }
                public void asyncException(int error, String text, int requestType) {
                    state.tick(clock.now());
                    state.error(Binding.this.observedEpoch, error, requestType);
                }
                public void responseFactorySettings(int id, boolean result) { }
                public void updateSWaPStatus(int value, int validity) { }
                public void updateUSBResetActive(boolean value, int validity) { }
                public void updateAppConnectContextRequested(boolean value, int validity) { }
            };
        }
    }
}
