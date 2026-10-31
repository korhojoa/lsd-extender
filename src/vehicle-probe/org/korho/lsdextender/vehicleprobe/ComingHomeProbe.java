/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.vehicleprobe;

/** One explicit, reversible setting experiment. Callbacks never transmit. */
final class ComingHomeProbe {
    interface Platform {
        long now();
        void enqueue(Runnable work);
        void send(int time, boolean state);
        void log(String detail);
    }

    private static final int VALID = 1;
    private static final int AVAILABLE = 2;
    private static final int MAX_TIME = 30;
    private static final long QUEUE_LIMIT_MS = 2000L;
    private final Platform platform;
    private final boolean enabled;
    private boolean closed;
    private boolean known;
    private boolean available;
    private int time;
    private boolean state;
    private int validity;
    private long revision;
    private boolean pending;
    private long operations;
    private boolean saved;
    private boolean changed;
    private int originalTime;
    private boolean originalState;
    private int sentTime;

    ComingHomeProbe(boolean enabled, Platform platform) {
        this.enabled = enabled;
        this.platform = platform;
    }

    synchronized void availability(int optionState, int valid) {
        if (!enabled || closed) { return; }
        boolean next = optionState == AVAILABLE && valid == VALID;
        if (next != available) { available = next; revision++; }
    }

    synchronized void value(int nextTime, boolean nextState, int nextValidity) {
        if (!enabled || closed) { return; }
        if (known && nextTime == time && nextState == state && nextValidity == validity) { return; }
        known = true;
        time = nextTime;
        state = nextState;
        validity = nextValidity;
        revision++;
        if (saved && nextValidity == VALID) {
            if (time == sentTime && state == originalState && time != originalTime) { changed = true; }
            if (changed && time == originalTime && state == originalState) { saved = false; }
        }
        platform.log("op=value time=" + time + " state=" + state + " valid=" + validity);
    }

    synchronized void unavailable() {
        known = false;
        available = false;
        revision++;
    }

    synchronized String status() {
        return "ok vehicle-probe enabled=" + enabled + " available=" + available
            + " known=" + known + " time=" + time + " state=" + state
            + " valid=" + validity + " pending=" + pending + " restore=" + saved;
    }

    synchronized String set(int requested, int expected) {
        if (saved) { return "err restore the original value before another test"; }
        return submit(requested, expected, false);
    }

    synchronized String restore() {
        if (!saved) { return "err no original value recorded"; }
        if (state != originalState) { return "err state changed; use the stock control"; }
        return submit(originalTime, sentTime, true);
    }

    private String admission(int requested, int expected) {
        if (!enabled) { return "disabled"; }
        if (closed) { return "closed"; }
        if (!known) { return "no-live-value"; }
        if (!available) { return "setting-unavailable"; }
        if (validity != VALID) { return "invalid-value"; }
        if (!duration(time) || !duration(requested) || !duration(expected)) { return "unsupported-duration"; }
        if (time != expected) { return "stale-value"; }
        if (requested == time) { return "unchanged-value"; }
        return null;
    }

    private String submit(final int requested, final int expected, final boolean restoring) {
        String refused = admission(requested, expected);
        if (refused != null) { return refuse(refused); }
        if (pending) { return refuse("busy"); }
        if (!restoring && Math.abs(requested - expected) != 5) { return refuse("one-step-only"); }
        final long at = platform.now();
        if (at < 0) { return refuse("clock-unavailable"); }
        final long ticket = revision;
        final boolean preserved = state;
        final long operation = ++operations;
        pending = true; // NOPMD UnusedAssignment - enqueue may immediately run the callback which reads this flag.
        try {
            platform.enqueue(new Runnable() {
                public void run() { transmit(requested, expected, preserved, restoring, ticket, at, operation); }
            });
        } catch (RuntimeException failed) {
            pending = false;
            return refuse("queue-unavailable");
        }
        return "ok queued; verify the value callback, not this reply";
    }

    private synchronized void transmit(int requested, int expected, boolean preserved,
        boolean restoring, long ticket, long at, long operation) {
        if (!pending || operation != operations) { return; }
        pending = false;
        long now = platform.now();
        String refused = admission(requested, expected);
        if (refused != null) { refuse(refused); return; }
        if (ticket != revision || state != preserved) { refuse("stale-value"); return; }
        if (now < at || now - at > QUEUE_LIMIT_MS) { refuse("expired"); return; }
        if (!restoring) {
            originalTime = time;
            originalState = state;
            saved = true;
            changed = false;
            sentTime = requested;
        }
        String result = "returned";
        try {
            platform.send(requested, preserved);
        } catch (Throwable failed) { // Native optional calls must fail open on the HMI queue.
            result = "failed";
        }
        platform.log("op=send time=" + requested + " state=" + preserved + " old=" + expected
            + " restore=" + restoring + " result=" + result);
    }

    private String refuse(String reason) {
        if (enabled && !closed) { platform.log("op=refused reason=" + reason); }
        return "err vehicle-probe " + reason;
    }

    synchronized void close() {
        closed = true;
        pending = false;
        known = false;
        available = false;
        revision++;
    }

    private static boolean duration(int value) { return value >= 0 && value <= MAX_TIME && value % 5 == 0; }
}
