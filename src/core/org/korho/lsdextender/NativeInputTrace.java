/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

/** Opt-in private-stream timing, emitted by the socket writer after flush. */
final class NativeInputTrace {
    private NativeInputTrace() { }

    static void written(byte[] data, long queued, long start, long end, int client, long sequence) {
        String record = new String(data).trim();
        if (!record.startsWith("app touch ") && !record.startsWith("app focus ")) { return; }
        String identity = " client=" + client + " seq=" + sequence;
        // Separate bounded lines keep every timestamp and record intact in slog.
        Slog.write(" input: " + EventClock.stamp() + identity + " phase=write queued_ms=" + queued
            + " start_ms=" + start + " end_ms=" + end);
        Slog.write(" input: " + EventClock.stamp() + identity + " phase=record " + record);
    }
}
