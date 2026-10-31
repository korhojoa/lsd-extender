/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.kombisync;

import java.util.Properties;

/** Cumulative valid availability edges, independent of the PPS write cadence. */
final class SinkCycle {
    static final String COUNTER_PROPERTY = "org.korho.lsdextender.kombisync.observerCounter";
    private static final long MAX_COUNTER = 4294967295L;
    private static final String DELETE = "-gfx_cycle_schema\n-gfx_observer_epoch\n-gfx_cycle_serial\n";
    private String epoch;
    private long serial = 1L;
    private boolean armed;
    private boolean usable;

    SinkCycle(String epoch) {
        this.epoch = epoch;
        usable = validEpoch(epoch);
    }

    /** Opaque witness token, not a clock, boot ID or native service lifetime. */
    static String newEpoch(long wall, long event, int identity) {
        if (event < 0) {
            return null;
        }
        try {
            Properties properties = System.getProperties();
            synchronized (properties) {
                Object previous = properties.get(COUNTER_PROPERTY);
                long counter = 0L;
                if (previous != null) {
                    if (!(previous instanceof String)) {
                        return null;
                    }
                    String value = (String) previous;
                    counter = Long.parseLong(value);
                    if (counter < 1L || counter >= MAX_COUNTER || !Long.toString(counter).equals(value)) {
                        return null;
                    }
                }
                counter++;
                properties.put(COUNTER_PROPERTY, Long.toString(counter));
                return hex(counter) + hex(wall) + hex(event) + hex(identity);
            }
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static String hex(long value) {
        String text = Long.toHexString(value & MAX_COUNTER);
        StringBuffer out = new StringBuffer(8);
        for (int i = text.length(); i < 8; i++) {
            out.append('0');
        }
        return out.append(text).toString();
    }

    private static boolean validEpoch(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    void observe(int state, boolean valid) {
        if (!usable) {
            return;
        }
        if (!valid || (state != 0 && state != 1)) {
            invalidate(null);
        } else if (state == 0) {
            armed = true;
        } else if (armed) {
            armed = false;
            if (serial == Long.MAX_VALUE) {
                usable = false;
            } else {
                serial++;
            }
        }
    }

    /** A continuity loss cannot disappear inside a coalesced PPS write. */
    void invalidate(String nextEpoch) {
        armed = false;
        serial = 1L;
        if (!usable) {
            return;
        }
        usable = validEpoch(nextEpoch) && !epoch.equals(nextEpoch);
        epoch = nextEpoch;
    }

    String body() {
        if (!usable) {
            return DELETE;
        }
        return "gfx_cycle_schema=1\ngfx_observer_epoch=" + epoch + "\ngfx_cycle_serial=" + serial + "\n";
    }
}
