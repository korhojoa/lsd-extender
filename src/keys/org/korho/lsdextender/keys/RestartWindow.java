/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

/** Bounded confirmation lifetime, separate from native service authority. */
final class RestartWindow {
    private static final long TIMEOUT = 15000L;
    private long token = -1L, deadline = -1L;
    private boolean activated;
    synchronized boolean open(long ticket, long now) {
        if (now < 0 || now > Long.MAX_VALUE - TIMEOUT) { return false; }
        token = ticket; deadline = now + TIMEOUT; activated = false; return true;
    }
    synchronized boolean current(long ticket, long now) { return ticket == token && now >= 0 && now < deadline; }
    synchronized boolean activate(long ticket, long now) {
        if (activated || !current(ticket, now)) { return false; }
        activated = true; return true;
    }
    synchronized boolean active(long ticket, long now) { return activated && current(ticket, now); }
}
