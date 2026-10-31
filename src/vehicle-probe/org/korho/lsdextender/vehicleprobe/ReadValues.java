/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.vehicleprobe;

/** Eight bounded latest-value slots. No vehicle request surface. */
final class ReadValues {
    interface Sink { void log(String detail); }
    static final String[] KEYS = new String[]{"reverse", "standstill", "parking_brake",
        "outside_temp", "range", "odometer", "coming_home", "leaving_home"};
    private static final long INTERVAL_MS = 1000L;
    private final Sink sink;
    private final String[] observed = new String[KEYS.length];
    private final String[] emitted = new String[KEYS.length];
    private final long[] written = new long[KEYS.length];
    private boolean closed;

    ReadValues(Sink sink) {
        this.sink = sink;
        for (int i = 0; i < written.length; i++) { written[i] = -1; }
    }

    synchronized void update(int key, String raw, int valid) {
        if (closed) { return; }
        if (valid != 1 || raw == null) {
            // An invalid first callback is not a vehicle value. Remain silent.
            if (observed[key] == null) { return; }
            observed[key] = "unavailable valid=" + valid;
        } else {
            observed[key] = raw + " valid=1";
        }
    }

    synchronized void unavailable(int first, int end) {
        for (int i = first; i < end; i++) { update(i, null, 0); }
    }

    synchronized void flush(long now) {
        if (closed || now < 0) { return; }
        for (int i = 0; i < observed.length; i++) {
            String value = observed[i];
            if (value == null || value.equals(emitted[i])) { continue; }
            if (written[i] >= 0 && (now < written[i] || now - written[i] < INTERVAL_MS)) { continue; }
            sink.log("op=read key=" + KEYS[i] + " " + value);
            emitted[i] = value;
            written[i] = now;
        }
    }

    synchronized void close() { closed = true; }
}
