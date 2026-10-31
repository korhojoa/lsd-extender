/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.vehicleprobe;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.dsi.ifc.base.DSIListener;
import org.korho.lsdextender.DsiSubscribe;
import org.korho.lsdextender.EventClock;
import org.korho.lsdextender.LsdExtender;

/** Passive owned subscriptions and a bounded change-log worker. */
final class VehicleReads implements Runnable, ReadValues.Sink {
    private static final String OBJECT_STRING = "toString";
    private static final String OBJECT_HASH = "hashCode";
    private static final String ASYNC_ERROR = "asyncException";
    private static final String PREFIX = "vehicle-probe-read";
    private static final String[] SERVICES = new String[]{
        "org.dsi.ifc.generalvehiclestates.DSIGeneralVehicleStates",
        "org.dsi.ifc.carkombi.DSICarKombi", "org.dsi.ifc.carlight.DSICarLight"};
    private static final int[][] ATTRIBUTES = new int[][]{new int[]{20, 22, 28}, new int[]{65, 8, 10}, new int[]{13, 14}};
    private static final int[] FIRST = new int[]{0, 3, 6};
    private static final int[] END = new int[]{3, 6, 8};
    private final ReadValues values = new ReadValues(this);
    private volatile boolean closed;
    private Thread worker;

    synchronized void start() {
        if (closed || worker != null) { return; }
        for (int i = 0; i < SERVICES.length; i++) {
            final int service = i;
            try {
                Class type = Class.forName(SERVICES[i] + "Listener");
                DSIListener listener = (DSIListener)Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, new Listener(i));
                DsiSubscribe.whenRegistered(PREFIX, SERVICES[i], ATTRIBUTES[i], listener, new Runnable() {
                    public void run() { values.unavailable(FIRST[service], END[service]); }
                });
            } catch (Throwable unavailable) { // Optional reads must not interrupt stock startup.
                values.unavailable(FIRST[i], END[i]);
            }
        }
        worker = new Thread(this, "lsd-extender-vehicle-reads");
        worker.setDaemon(true);
        worker.start();
    }

    public void run() {
        log("op=reader-start fields=8 interval_ms=1000");
        while (!closed) {
            values.flush(EventClock.millis());
            try { Thread.sleep(100); }
            catch (InterruptedException stopped) { break; }
        }
    }

    void close() {
        Thread thread;
        synchronized (this) { closed = true; values.close(); thread = worker; }
        if (thread == null) { return; }
        thread.interrupt();
        try { thread.join(1000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (thread.isAlive()) { throw new IllegalStateException("vehicle read worker stop not proven"); }
        log("op=reader-stop joined=true");
    }

    public void log(String detail) { LsdExtender.trace(" vehicle-probe " + EventClock.stamp() + " " + detail); }

    private final class Listener implements InvocationHandler {
        private final int service;
        Listener(int service) { this.service = service; }

        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (OBJECT_STRING.equals(name)) { return PREFIX; }
            if (OBJECT_HASH.equals(name)) { return new Integer(System.identityHashCode(proxy)); }
            if ("equals".equals(name)) { return Boolean.valueOf(proxy == args[0]); } // NOPMD CompareObjectsWithEquals - proxy identity
            if (ASYNC_ERROR.equals(name)) { values.unavailable(FIRST[service], END[service]); }
            else { ReadCallbacks.accept(values, name, args); }
            return null;
        }
    }
}
