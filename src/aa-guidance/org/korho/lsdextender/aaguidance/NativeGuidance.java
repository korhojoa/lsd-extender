/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.aaguidance;

import de.vw.mib.asl.api.navigation.ASLNavigationFactory;
import de.vw.mib.asl.api.navigation.NavigationServiceException;
import de.vw.mib.asl.api.navigation.guidance.NavigationGuidanceListener;
import de.vw.mib.asl.api.navigation.guidance.NavigationGuidanceService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Own native guidance service, freed before a runtime generation is discarded. */
public final class NativeGuidance implements InvocationHandler {
    private static NavigationGuidanceService service;
    private static boolean closing;
    private static boolean releaseQueued;

    private NativeGuidance() { }

    /** Called from the deferred ASL startup, never from a socket command. */
    static synchronized boolean open() throws NavigationServiceException {
        if (closing) { return false; }
        if (service != null) {
            return true;
        }
        NativeGuidance listener = new NativeGuidance();
        NavigationGuidanceListener proxy = (NavigationGuidanceListener)Proxy.newProxyInstance(
            NavigationGuidanceListener.class.getClassLoader(),
            new Class[]{NavigationGuidanceListener.class}, listener);
        NavigationGuidanceService created = ASLNavigationFactory.getNavigationApi().getNavigationGuidanceService(proxy);
        if (created == null) {
            return false;
        }
        service = created;
        return true;
    }

    /** Missing native state is not permission to overwrite native navigation data. */
    static synchronized boolean blocked() {
        if (closing || service == null) {
            return true;
        }
        try {
            return service.isRgActive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    static synchronized void close() {
        if (service == null || releaseQueued) { return; }
        closing = true;
        final NavigationGuidanceService old = service;
        if (de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch == null) {
            throw new IllegalStateException("native guidance cleanup dispatcher unavailable");
        }
        releaseQueued = true;
        try {
            de.vw.mib.asl.framework.internal.framework.ServiceManager.aslThreadSwitch.enqueue(new Runnable() {
                public void run() {
                    try {
                        old.freeService();
                        synchronized (NativeGuidance.class) {
                            if (service == old) { service = null; closing = false; } // NOPMD CompareObjectsWithEquals - exact owned service.
                        }
                    } catch (Throwable failed) {
                        org.korho.lsdextender.LsdExtender.trace(" navaa: native guidance cleanup failed; handle retained: " + failed);
                    } finally {
                        synchronized (NativeGuidance.class) { releaseQueued = false; }
                    }
                }
            });
        } catch (Throwable failed) {
            releaseQueued = false;
            throw new IllegalStateException("native guidance cleanup queue failed: " + failed);
        }
    }

    public Object invoke(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if (name.equals("hashCode")) {
            return new Integer(System.identityHashCode(proxy));
        }
        if (name.equals("equals")) {
            return new Boolean(args != null && args.length == 1 && proxy == args[0]); // NOPMD CompareObjectsWithEquals - Proxy identity avoids equals recursion.
        }
        if (name.equals("toString")) {
            return "lsd-extender owned native guidance listener";
        }
        // Native callbacks belong to this consumer only. Read state via isRgActive;
        // never issue route commands or alter any other consumer's registration.
        return null;
    }
}
