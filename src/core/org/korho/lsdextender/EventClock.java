/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** The HMI timer clock, shared by input timestamps and lifecycle deadlines. */
public final class EventClock {
    private EventClock() {
    }

    public static long millis() {
        try {
            return de.vw.mib.widgets.internal.ServiceManager.timerManager.getSystemTimeMillis();
        } catch (Throwable unavailable) {
            return -1L;
        }
    }

    public static String stamp() {
        return "mono_ms=" + millis() + " wall_ms=" + System.currentTimeMillis();
    }
}
