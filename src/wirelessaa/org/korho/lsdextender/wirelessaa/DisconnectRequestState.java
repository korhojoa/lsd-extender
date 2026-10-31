/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

/** Owned observation policy. No state here represents a phone acknowledgment. */
public final class DisconnectRequestState {
    public static final String NOT_SUBMITTED = "NOT_SUBMITTED";
    public static final String QUEUED = "QUEUED";
    public static final String PENDING = "PENDING";
    public static final String OBSERVED_NATIVE_STATE = "OBSERVED_NATIVE_STATE";
    public static final String UNKNOWN = "UNKNOWN";

    /** A copy of the public device fields, never a borrowed firmware object. */
    public static final class DeviceInfo {
        final int id, type, methods;
        final String address;
        public DeviceInfo(int id, String address, int type, int methods) {
            this.id = id; this.address = address; this.type = type; this.methods = methods;
        }
    }

    /** Local observation capability, not a native transaction cookie. */
    public static final class Snapshot {
        final long epoch, generation;
        final DeviceInfo device;
        private Snapshot(long epoch, long generation, DeviceInfo device) {
            this.epoch = epoch; this.generation = generation; this.device = device;
        }
        public int deviceId() { return device.id; }
    }

    static final class Ticket {
        final Snapshot snapshot;
        final long deadline;
        boolean issued;
        Ticket(Snapshot snapshot, long deadline) {
            this.snapshot = snapshot; this.deadline = deadline;
        }
    }

    private long epoch, generation;
    private long lastClock = Long.MIN_VALUE;
    private boolean serviceBound, closed, devicesValid, stateValid, spent;
    private DeviceInfo[] observedDevices = new DeviceInfo[0];
    private int activeId = -1, activeMethod, nativeState = -1;
    private Snapshot current;
    private Ticket pending;
    private String lastOutcome = NOT_SUBMITTED;
    private String lastDetail = "no live service observation";

    synchronized long registering() {
        invalidate("service registration changed");
        epoch++; generation++;
        serviceBound = false; spent = false; devicesValid = false; stateValid = false;
        observedDevices = new DeviceInfo[0]; current = null;
        return epoch;
    }

    synchronized void bound(long observedEpoch) {
        if (!closed && observedEpoch == epoch) { serviceBound = true; }
    }

    synchronized void unavailable(long observedEpoch) {
        if (observedEpoch != epoch) { return; }
        invalidate("service unavailable");
        serviceBound = false; devicesValid = false; stateValid = false; current = null;
        generation++;
    }

    synchronized void close() {
        invalidate("lease closed"); closed = true; serviceBound = false; current = null;
    }

    synchronized void devices(long observedEpoch, DeviceInfo[] observed, int validity) {
        if (!accepts(observedEpoch)) { return; }
        devicesValid = validity == 1 && observed != null;
        observedDevices = devicesValid ? (DeviceInfo[])observed.clone() : new DeviceInfo[0];
        refresh();
    }

    synchronized void state(long observedEpoch, int id, int state, int method, int validity) {
        if (!accepts(observedEpoch)) { return; }
        stateValid = validity == 1;
        if (!stateValid) {
            invalidate("invalid native state observation"); current = null; generation++;
            return;
        }
        activeId = id; activeMethod = method; nativeState = state;
        if (pending != null && pending.issued && id == pending.snapshot.device.id && method == 8
            && (state == 0 || state == 3)) {
            lastOutcome = OBSERVED_NATIVE_STATE;
            lastDetail = "native state " + state + " observed; causation and phone acknowledgment unknown";
            pending = null;
        }
        refresh();
    }

    synchronized void error(long observedEpoch, int error, int requestType) {
        if (accepts(observedEpoch) && requestType == 1002 && pending != null && pending.issued) {
            lastOutcome = UNKNOWN;
            lastDetail = "native error " + error + " observed for RT1002; no operation cookie";
            pending = null;
        }
    }

    private boolean accepts(long observedEpoch) {
        return !closed && serviceBound && observedEpoch == epoch;
    }

    private static boolean same(DeviceInfo a, DeviceInfo b) {
        return a.id == b.id && a.type == b.type && a.methods == b.methods
            && (a.address == null ? b.address == null : a.address.equals(b.address));
    }

    private void refresh() {
        DeviceInfo found = null;
        boolean duplicate = false;
        if (devicesValid && stateValid) {
            for (int i = 0; i < observedDevices.length; i++) {
                DeviceInfo device = observedDevices[i];
                if (device != null && device.id == activeId) {
                    if (found != null) { duplicate = true; }
                    found = device;
                }
            }
        }
        if (duplicate || found == null || found.id < 0 || (found.methods & 8) == 0) {
            if (current != null) { invalidate("detected device no longer current"); generation++; }
            current = null;
            return;
        }
        if (current == null || !same(current.device, found)) {
            invalidate("detected device identity changed");
            current = new Snapshot(epoch, ++generation, found);
        }
    }

    public synchronized Snapshot snapshot() {
        return !closed && serviceBound && devicesValid && stateValid && nativeState == 2 && activeMethod == 8
            ? current : null;
    }

    synchronized Ticket reserve(Snapshot snapshot, long now, long timeoutMillis) {
        tick(now);
        if (snapshot == null || snapshot != snapshot() || spent || pending != null // NOPMD CompareObjectsWithEquals - Require the exact observation capability.
            || timeoutMillis <= 0 || timeoutMillis > 10000 || now > Long.MAX_VALUE - timeoutMillis) {
            return null;
        }
        spent = true;
        pending = new Ticket(snapshot, now + timeoutMillis);
        lastOutcome = QUEUED; lastDetail = "owned ASL request queued, not dispatched";
        return pending;
    }

    synchronized boolean issue(Ticket ticket, long now) {
        tick(now);
        if (ticket == null || ticket != pending || ticket.issued || snapshot() != ticket.snapshot) { // NOPMD CompareObjectsWithEquals - Exact pending ticket and snapshot capabilities are required.
            if (ticket == pending) { // NOPMD CompareObjectsWithEquals - Retire only this exact pending ticket.
                invalidate("queued observation became stale");
            }
            return false;
        }
        ticket.issued = true;
        lastOutcome = PENDING; lastDetail = "typed invocation attempted; delivery and acceptance unproved";
        return true;
    }

    synchronized void dispatchFailed(Ticket ticket) {
        if (pending == ticket) { // NOPMD CompareObjectsWithEquals - A failed old ticket cannot retire a later request.
            lastOutcome = ticket.issued ? UNKNOWN : NOT_SUBMITTED;
            lastDetail = ticket.issued ? "typed invocation failed; delivery unknown" : "ASL enqueue failed; not dispatched";
            pending = null;
        }
    }

    private void invalidate(String reason) {
        if (pending != null) {
            lastOutcome = pending.issued ? UNKNOWN : NOT_SUBMITTED;
            lastDetail = reason; pending = null;
        }
    }

    synchronized void tick(long now) {
        if (lastClock != Long.MIN_VALUE && now < lastClock) {
            invalidate("clock moved backward; request outcome unknown");
            spent = true;
        }
        lastClock = now;
        if (pending != null && now >= pending.deadline) {
            lastOutcome = pending.issued ? UNKNOWN : NOT_SUBMITTED;
            lastDetail = pending.issued ? "observation deadline; no retry in this service epoch"
                : "ASL queue deadline; no invocation";
            pending = null;
        }
    }

    public synchronized String outcome(long now) { tick(now); return lastOutcome; }
    public synchronized String detail() { return lastDetail; }
}
