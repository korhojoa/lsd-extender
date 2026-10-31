/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.nativedestination;

import de.vw.mib.asl.api.navigation.ASLNavigationFactory;
import de.vw.mib.asl.api.navigation.ASLNavigationServices;
import de.vw.mib.asl.api.navigation.GuidanceInfo;
import de.vw.mib.asl.api.navigation.NavAddress;
import de.vw.mib.asl.api.navigation.guidance.NavigationGuidanceListener;
import de.vw.mib.asl.api.navigation.guidance.NavigationGuidanceService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;

/** All methods are executed on the ASL queue; only this instance's observer is freed. */
final class NativeRouteService {
    interface Access {
        boolean available();
        boolean active();
        boolean idle();
        String identity();
        int start(String address);
        void close();
    }

    interface Current { boolean current(); }
    interface Dispatcher { void enqueue(Runnable task); }

    static boolean enqueue(Dispatcher dispatcher, Runnable task, DestinationState state) {
        try { dispatcher.enqueue(task); return true; }
        catch (Throwable uncertain) { state.unknown(); return false; }
    }

    private static final String ATTEMPT = "org.korho.lsdextender.nativeDestination.attempt";
    private static final int MAX_IDENTITY_BYTES = 512;
    private Access access;
    private boolean closed;
    private String observed;

    NativeRouteService() { }
    NativeRouteService(Access supplied) { access = supplied; }

    synchronized String step(DestinationState state, String transaction, Current current) {
        if (closed || !current.current()) { return "RETIRED"; }
        try {
            if (access == null) { access = new PublicAccess(); }
            boolean available = access.available();
            boolean active = access.active();
            boolean idle = access.idle();
            state.nativeSnapshot(true, available, active, !idle, null);
            observed = available && active ? access.identity() : null;
            if (!available || active || !idle) {
                if (!DestinationState.ACCEPTED.equals(state.state())) { state.retire(); }
                return active ? (DestinationState.ACCEPTED.equals(state.state())
                        ? "NATIVE_ACTIVE_IDENTITY_UNATTRIBUTED" : "EXISTING_NATIVE_ROUTE") : "NATIVE_STATE_BLOCKED";
            }
            if (!current.current() || !state.takeAttempt()) { return state.state(); }
            // Persist before invoking native work. Reload must not retry an uncertain call.
            if (!claimAttempt(transaction, state)) { return "ALREADY_ATTEMPTED_OR_JOURNAL_INVALID"; }
            if (!current.current()) { state.retire(); return "RETIRED_BEFORE_CALL"; }
            int result = access.start(state.addressText());
            state.externalStartResult(result);
            return result == 1 ? DestinationState.ACCEPTED : "PUBLIC_START_REJECTED";
        } catch (Throwable failed) {
            state.unknown();
            return "UNKNOWN_NO_RETRY";
        }
    }

    private static boolean claimAttempt(String transaction, DestinationState state) {
        Properties properties = System.getProperties();
        synchronized (properties) {
            Object previous = properties.get(ATTEMPT);
            if (previous != null && !(previous instanceof String)) { state.unknown(); return false; }
            if (transaction.equals(previous)) { state.unknown(); return false; }
            properties.put(ATTEMPT, transaction);
            return true;
        }
    }

    synchronized String observedIdentity() { return observed; }

    synchronized void close() {
        closed = true;
        if (access != null) { access.close(); access = null; }
    }

    private static final class PublicAccess implements Access, InvocationHandler {
        private final ASLNavigationServices route;
        private NavigationGuidanceService observer;

        PublicAccess() throws Exception {
            route = ASLNavigationFactory.getNavigationApi().getASLNavigationServices(null);
            if (route == null) { throw new IllegalStateException("route service unavailable"); }
            NavigationGuidanceListener listener = (NavigationGuidanceListener)Proxy.newProxyInstance(
                    NavigationGuidanceListener.class.getClassLoader(),
                    new Class[]{NavigationGuidanceListener.class}, this);
            // The factory can synchronously call our inert listener before this assignment.
            observer = ASLNavigationFactory.getNavigationApi().getNavigationGuidanceService(listener);
            if (observer == null) { throw new IllegalStateException("guidance observer unavailable"); }
        }

        public boolean available() { return observer != null && route.isServiceAvailable(); }
        public boolean active() { return observer.isRgActive(); }
        public boolean idle() {
            if (observer.getGuidanceState() != (0)) { return false; }
            int[] states = observer.getCalculationStates();
            if (states == null || states.length == 0) { return false; }
            for (int i = 0; i < states.length; i++) {
                if (states[i] != (0)) { return false; }
            }
            return true;
        }

        public int start(String address) {
            NavAddress target = new NavAddress();
            target.setStreet(address);
            // Empty public house field causes the stock target to append a separator space.
            return route.startGuidance(new NavAddress[]{target}, false,
                    (6));
        }

        public String identity() {
            GuidanceInfo[] info = route.getGuidanceInfo();
            if (info == null || info.length < 2 || info[1] == null) { return null; }
            NavAddress address = info[1].getAddress();
            if (address == null) { return null; }
            // Copy values now; no mutable stock NavAddress escapes the ASL queue.
            return "country=" + encode(address.getCountry()) + ";state=" + encode(address.getState())
                    + ";city=" + encode(address.getCity()) + ";zip=" + encode(address.getZip())
                    + ";street=" + encode(address.getStreet()) + ";house=" + encode(address.getHousenumber())
                    + ";lat=" + address.getLatitude() + ";lon=" + address.getLongitude();
        }

        private static String encode(String value) {
            if (value == null) { return "-"; }
            try {
                byte[] bytes = value.getBytes("UTF-8");
                if (bytes.length > MAX_IDENTITY_BYTES) { throw new IllegalStateException("native identity field too long"); }
                StringBuffer result = new StringBuffer();
                for (int i = 0; i < bytes.length; i++) {
                    int b = bytes[i] & 255;
                    result.append("0123456789abcdef".charAt(b >> 4));
                    result.append("0123456789abcdef".charAt(b & 15));
                }
                return result.toString();
            } catch (java.io.UnsupportedEncodingException failed) { throw new IllegalStateException("UTF-8 unavailable"); }
        }

        public void close() {
            if (observer != null) { observer.freeService(); observer = null; }
        }

        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("hashCode")) { return new Integer(System.identityHashCode(proxy)); }
            if (name.equals("equals")) {
                return new Boolean(args != null && args.length == 1 && proxy == args[0]); // NOPMD CompareObjectsWithEquals - exact owned proxy identity.
            }
            if (name.equals("toString")) { return "owned native destination observer"; }
            return null;
        }
    }
}
