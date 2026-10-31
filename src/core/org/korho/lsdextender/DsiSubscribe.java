/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyFactory;
import de.vw.mib.asl.framework.api.dsiproxy.DSIServiceStateListener;
import java.util.Vector;
import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.base.DSIListener;

public final class DsiSubscribe {
    /** Owned registrations, retained until their ASL cleanup completes. */
    private static final Vector subscriptions = new Vector();

    private DsiSubscribe() {
    }

    /** Resolve the service class by name, then {@link #whenRegistered(String, Class, int[], DSIListener)}. */
    public static void whenRegistered(String tag, String serviceClass, int[] attrs, DSIListener listener)
        throws ClassNotFoundException {
        whenRegistered(tag, serviceClass, attrs, listener, null);
    }

    /** As above, with a callback when instance 0 becomes unavailable. */
    public static void whenRegistered(String tag, String serviceClass, int[] attrs, DSIListener listener,
        Runnable unavailable) throws ClassNotFoundException {
        whenRegistered(tag, Class.forName(serviceClass), attrs, listener, unavailable);
    }

    /**
     * Subscribe {@code attrs} on instance 0 of {@code svc} with {@code listener} as soon
     * as the service is registered (now, if it already is) and after every
     * re-registration. Every outcome is written to slog as "<tag>: ...".
     */
    public static void whenRegistered(final String tag, final Class svc, final int[] attrs, final DSIListener listener) {
        whenRegistered(tag, svc, attrs, listener, null);
    }

    /** Keep cached consumer data invalid while the service cannot deliver updates. */
    public static void whenRegistered(final String tag, final Class svc, final int[] attrs, final DSIListener listener,
        final Runnable unavailable) {
        Lease lease = new Lease(tag, svc, attrs, listener, unavailable);
        synchronized (subscriptions) { subscriptions.addElement(lease); }
        lease.start();
    }

    private static final class Lease implements DSIServiceStateListener {
        private final String tag;
        private final Class svc;
        private final int[] attrs;
        private final DSIListener listener;
        private final Runnable unavailable;
        private final DSIProxy proxy;
        private final de.vw.mib.genericevents.Target target;
        private volatile boolean closed;
        private boolean cleanupQueued;

        Lease(String tag, Class svc, int[] attrs, DSIListener listener, Runnable unavailable) {
            this.tag = tag; this.svc = svc; this.attrs = attrs;
            this.listener = listener; this.unavailable = unavailable;
            proxy = DSIProxyFactory.getDSIProxyAPI().getDSIProxy();
            target = LsdExtender.nativeTarget();
        }

        synchronized void start() {
            if (closed) { return; }
            proxy.addServiceStateListener(svc, this);
            LsdExtender.trace(" " + tag + ": waiting for " + shortName(svc) + " registration (attrs " + join(attrs) + ")");
        }

        public synchronized void registered(String serviceName, int instance) {
            if (closed || instance != 0) { return; }
            try {
                DSIBase service = proxy.getService(target, svc);
                service.setNotification(attrs, listener);
                LsdExtender.trace(" " + tag + ": " + shortName(svc) + " registered, subscribed attrs " + join(attrs));
            } catch (Throwable failed) {
                LsdExtender.trace(" " + tag + ": subscribe failed: " + failed);
                notifyUnavailable(tag, shortName(svc), unavailable);
            }
        }

        public synchronized void unregistered(String serviceName, int instance) {
            if (!closed && instance == 0) { notifyUnavailable(tag, shortName(svc), unavailable); }
        }

        synchronized void close() {
            closed = true;
            if (cleanupQueued) { return; }
            if (de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch == null) {
                throw new IllegalStateException("DSI cleanup dispatcher unavailable");
            }
            cleanupQueued = true;
            try {
                de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
                    public void run() { cleanup(); }
                });
            } catch (Throwable failed) {
                cleanupQueued = false;
                throw new IllegalStateException("DSI cleanup queue failed: " + failed);
            }
        }

        private synchronized void cleanup() {
            try {
                proxy.removeServiceStateListener(svc, this);
                DSIBase service = proxy.getService(target, svc);
                if (service != null) { service.clearNotification(attrs, listener); }
                synchronized (subscriptions) { subscriptions.removeElement(this); }
                LsdExtender.runtimeSubscriptionRetired();
                LsdExtender.trace(" " + tag + ": " + shortName(svc) + " subscription removed (reload)");
            } catch (Throwable failed) {
                LsdExtender.trace(" " + tag + ": cleanup failed, retained for retry: " + failed);
            } finally { cleanupQueued = false; }
        }
    }

    private static void notifyUnavailable(String tag, String name, Runnable unavailable) {
        if (unavailable != null) {
            try {
                unavailable.run();
            } catch (Throwable t) {
                LsdExtender.trace(" " + tag + ": " + name + " unavailable callback failed: " + t);
            }
        }
    }

    /**
     * Take back every subscription whose listener was loaded by {@code loader} (the
     * runtime jars' loader on a reload). Returns how many were removed.
     */
    public static int unsubscribeAll(ClassLoader loader) {
        Object[] all;
        synchronized (subscriptions) {
            all = new Object[subscriptions.size()];
            subscriptions.copyInto(all);
        }
        int n = 0;
        Throwable refused = null;
        for (int i = 0; i < all.length; i++) {
            Lease lease = (Lease)all[i];
            if (loaderOf(lease.listener) != loader && !lease.closed) { continue; }
            try { lease.close(); }
            catch (Throwable failed) { refused = failed; }
            n++;
        }
        if (refused != null) { throw new IllegalStateException("DSI cleanup remains retryable: " + refused); }
        return n;
    }

    /** True until the exact loader's native cleanup has actually acknowledged. */
    static boolean hasSubscriptions(ClassLoader loader) {
        synchronized (subscriptions) {
            for (int i = 0; i < subscriptions.size(); i++) {
                if (loaderOf(((Lease) subscriptions.elementAt(i)).listener) == loader) { return true; }
            }
            return false;
        }
    }

    /**
     * The class loader that "owns" an object: for a java.lang.reflect.Proxy (the ev and
     * spy listeners) the loader of its invocation handler, since the proxy class itself
     * is defined by the interface's loader (boot), else the object's own class loader.
     */
    public static ClassLoader loaderOf(Object o) {
        if (o == null) {
            return null;
        }
        try {
            if (java.lang.reflect.Proxy.isProxyClass(o.getClass())) {
                return java.lang.reflect.Proxy.getInvocationHandler(o).getClass().getClassLoader();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return o.getClass().getClassLoader();
    }

    public static String join(int[] a) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; a != null && i < a.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(a[i]);
        }
        return b.toString();
    }

    private static String shortName(Class c) {
        String n = c.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }
}
