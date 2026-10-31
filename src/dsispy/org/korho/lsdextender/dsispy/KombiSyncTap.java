/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.dsispy;

import java.lang.reflect.Method;
import org.dsi.ifc.kombisync.DSIKombiSyncListener;
import org.dsi.ifc.kombisync.KombiDisplayRequest;
import org.dsi.ifc.kombisync.KombiDisplayStatus;
import org.dsi.ifc.kombisync.MenuContext;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxy;
import de.vw.mib.asl.framework.api.dsiproxy.DSIProxyInterceptor;
import de.vw.mib.asl.framework.internal.framework.dsi.util.Utils;

/** Own additive tap. Callback-owned values are copied before native dispatch continues. */
final class KombiSyncTap implements DSIProxyInterceptor {
    private static final int CAPACITY = 16;
    // Original Slog.MAX=200, including "lsd-extender" and Output.line's leading space.
    private static final int RECORD_CHARS = 188;
    private static final String STATUS = "responseKombiDisplayStatus";
    private static final String REQUEST = "responseKombiDisplayRequest";
    private final ClusterObserver.Output output;
    private final ClusterObserver.Clock clock;
    private final String[] pending = new String[CAPACITY];
    private DSIProxy proxy;
    private int typeIndex = -1;
    private int head, count;
    private boolean attempted, stopped;
    private long sequence, dropped, discarded, captureFailures, outputFailures;
    private long reportedDropped, reportedDiscarded, reportedCaptureFailures, reportedOutputFailures;

    KombiSyncTap(ClusterObserver.Output output, ClusterObserver.Clock clock) {
        this.output = output;
        this.clock = clock;
    }

    /** Caller serializes install and detach with the existing registration lock. */
    void install(DSIProxy selected) {
        synchronized (this) { if (stopped || attempted) { return; } }
        try {
            validate(DSIKombiSyncListener.class);
            int index = Utils.getTypeIndex(DSIKombiSyncListener.class);
            if (index < 0) { throw new IllegalStateException("unknown KombiSync listener type"); }
            synchronized (this) {
                if (stopped) { return; }
                typeIndex = index;
                proxy = selected;
                attempted = true; // Keep exact cleanup if the API accepts and then throws.
            }
            selected.addListenerInterceptor(DSIKombiSyncListener.class, 0, this);
        } catch (Throwable unavailable) { failedCapture(); }
    }

    /** Validate original declarations, never inferred ordinal or similarly named interface. */
    static void validate(Class listener) throws Exception {
        Method status = listener.getMethod(STATUS, new Class[] { KombiDisplayStatus.class, Integer.TYPE });
        Method request = listener.getMethod(REQUEST, new Class[] { KombiDisplayRequest.class });
        if (status.getReturnType() != Void.TYPE || request.getReturnType() != Void.TYPE) {
            throw new IllegalStateException("unexpected KombiSync return type");
        }
        Method[] methods = listener.getMethods();
        int statuses = 0, requests = 0;
        for (int i = 0; i < methods.length; i++) {
            if (STATUS.equals(methods[i].getName())) { statuses++; }
            if (REQUEST.equals(methods[i].getName())) { requests++; }
        }
        if (statuses != 1 || requests != 1) { throw new IllegalStateException("ambiguous KombiSync callback"); }
    }

    synchronized void stopCapture() {
        stopped = true;
        discarded += count;
        for (int i = 0; i < pending.length; i++) { pending[i] = null; }
        head = 0; count = 0;
    }

    boolean detach() {
        DSIProxy selected;
        synchronized (this) {
            if (!attempted) { return true; }
            selected = proxy;
        }
        try {
            selected.removeListenerInterceptor(DSIKombiSyncListener.class, 0, this);
            synchronized (this) { attempted = false; }
            return true;
        } catch (Throwable unavailable) { return false; }
    }

    public boolean before(String type, int instance, int index, Object[] args) {
        try {
            long timer = clock.timerMillis();
            long wall = clock.millis();
            int registeredType;
            synchronized (this) {
                if (stopped || !attempted) { return true; }
                registeredType = typeIndex;
            }
            if (instance != 0 || index < 0 || !DSIKombiSyncListener.class.getName().equals(type)) { return true; }
            String name = Utils.getRuntimeListenerMethodName(registeredType, index);
            String values;
            if (STATUS.equals(name)) { values = copyStatus(args); }
            else if (REQUEST.equals(name)) { values = copyRequest(args); }
            else { return true; }
            synchronized (this) {
                if (stopped) { return true; }
                sequence = sequence == Long.MAX_VALUE ? 1L : sequence + 1L;
                if (count == CAPACITY) { dropped++; return true; }
                pending[(head + count) % CAPACITY] = "kombi-arrival seq=" + sequence
                    + " hmi_ms=" + timer + " wall_ms=" + wall + " instance=" + instance
                    + " index=" + index + " method=" + name + " " + values;
                count++;
            }
        } catch (Throwable unavailable) { failedCapture(); }
        return true;
    }

    public void after(String type, int instance, int index, Object[] args) { }

    private synchronized void failedCapture() { if (!stopped) { captureFailures++; } }

    /** Only the existing owned worker calls this; no output while holding the callback lock. */
    void drain() {
        for (int i = 0; i < CAPACITY; i++) {
            String value;
            synchronized (this) {
                if (stopped || count == 0) { break; }
                value = pending[head]; pending[head] = null;
                head = (head + 1) % CAPACITY; count--;
            }
            try {
                String[] frames = frame(value);
                for (int part = 0; part < frames.length; part++) {
                    try { output.line(frames[part]); }
                    catch (Throwable unavailable) { synchronized (this) { outputFailures++; } }
                }
            }
            catch (Throwable unavailable) { synchronized (this) { outputFailures++; } }
        }
        reportFailures(false);
    }

    /** Every physical record has the complete arrival key; field tokens are never split. */
    private static String[] frame(String arrival) {
        int methodAt = arrival.indexOf(" method=");
        int fieldsAt = arrival.indexOf(' ', methodAt + 8);
        String method = arrival.substring(methodAt + 8, fieldsAt);
        String kind = STATUS.equals(method) ? "S" : REQUEST.equals(method) ? "R" : null;
        if (kind == null) { throw new IllegalArgumentException("unknown captured callback"); }
        String header = "kombi-arrival v=1" + arrival.substring(13, methodAt) + " kind=" + kind;
        // Reserve two digits for both part and total, plus separators and a space.
        int budget = RECORD_CHARS - header.length() - 9;
        String values = arrival.substring(fieldsAt + 1);
        String[] groups = new String[32];
        int used = 0, start = 0;
        String group = "";
        while (start < values.length()) {
            int end = values.indexOf(' ', start);
            if (end < 0) { end = values.length(); }
            String token = values.substring(start, end);
            if (token.length() > budget) { throw new IllegalArgumentException("captured field exceeds record budget"); }
            if (group.length() > 0 && group.length() + 1 + token.length() > budget) {
                groups[used++] = group; group = "";
            }
            group = group.length() == 0 ? token : group + " " + token;
            start = end + 1;
        }
        if (group.length() > 0) { groups[used++] = group; }
        String[] frames = new String[used];
        for (int i = 0; i < used; i++) { frames[i] = header + " p=" + (i + 1) + "/" + used + " " + groups[i]; }
        return frames;
    }

    /** Called by the worker on exit, inside the existing bounded join contract. */
    void finishStop() { reportFailures(true); }

    synchronized boolean needsStopReport() {
        return dropped != reportedDropped || discarded != reportedDiscarded
            || captureFailures != reportedCaptureFailures || outputFailures != reportedOutputFailures;
    }

    private void reportFailures(boolean ending) {
        long lost, discardedPending, capture, write;
        synchronized (this) {
            if (stopped && !ending) { return; }
            lost = dropped; discardedPending = discarded; capture = captureFailures; write = outputFailures;
            if (!ending && lost == reportedDropped && discardedPending == reportedDiscarded
                && capture == reportedCaptureFailures && write == reportedOutputFailures) { return; }
        }
        try {
            output.line("kombi-capture hmi_ms=" + clock.timerMillis() + " wall_ms=" + clock.millis()
                + " stopped=" + ending + " dropped=" + lost + " pending_discarded=" + discardedPending
                + " capture_failures=" + capture + " output_failures=" + write);
            synchronized (this) {
                reportedDropped = lost; reportedDiscarded = discardedPending;
                reportedCaptureFailures = capture; reportedOutputFailures = write;
            }
        } catch (Throwable unavailable) { synchronized (this) { outputFailures++; } }
    }

    private static String copyStatus(Object[] args) {
        if (args == null || args.length != 2 || !(args[1] instanceof Integer)
            || (args[0] != null && !(args[0] instanceof KombiDisplayStatus))) {
            throw new IllegalArgumentException("unexpected KombiSync status arguments");
        }
        KombiDisplayStatus value = (KombiDisplayStatus)args[0];
        if (value == null) { return "value=null raw_arg1=" + ((Integer)args[1]).intValue(); }
        return "value=present tid=" + value.tid + " focus=" + value.focus + " screenFormat=" + value.screenFormat
            + " mainContext=" + value.mainContext + menu(value.menuContext) + " popupContext=" + value.popupContext
            + " animationScreenType=" + value.animationScreenType + " animationScreenSubType=" + value.animationScreenSubType
            + " animationLeadTime=" + value.animationLeadTime + " animationAddInfo=" + value.animationAddInfo
            + " animationSpeed=" + value.animationSpeed + " raw_arg1=" + ((Integer)args[1]).intValue();
    }

    private static String copyRequest(Object[] args) {
        if (args == null || args.length != 1 || (args[0] != null && !(args[0] instanceof KombiDisplayRequest))) {
            throw new IllegalArgumentException("unexpected KombiSync request arguments");
        }
        KombiDisplayRequest value = (KombiDisplayRequest)args[0];
        if (value == null) { return "value=null"; }
        return "value=present tid=" + value.tid + " focus=" + value.focus + " screenFormat=" + value.screenFormat
            + " mainContext=" + value.mainContext + menu(value.menuContext) + " popupContext=" + value.popupContext
            + " animationScreenType=" + value.animationScreenType + " animationScreenSubType=" + value.animationScreenSubType
            + " animationLeadTime=" + value.animationLeadTime + " animationAddInfo=" + value.animationAddInfo
            + " lastmoderequest=" + value.lastmoderequest + " lvdslock=" + value.lvdslock;
    }

    private static String menu(MenuContext value) {
        if (value == null) { return " menuContext=null"; }
        return " menuContext=present leftSideMenuOpen=" + value.leftSideMenuOpen
            + " rightSideMenuOpen=" + value.rightSideMenuOpen + " partialPopupOpen=" + value.partialPopupOpen
            + " secondStatusLine=" + value.secondStatusLine;
    }
}
