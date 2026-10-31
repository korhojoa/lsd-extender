/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.ev;

import org.korho.lsdextender.LsdExtender;
import java.io.File;

final class PpsDirRetry {
    static final long FIRST_MS = 10000L;
    static final long MAX_MS = 120000L;
    private long missingSince = -1L;
    private int misses;
    private long retryAt;

    static long delayMs(int misses) {
        long delay = FIRST_MS;
        for (int i = 0; i < misses && delay < MAX_MS; i++) {
            delay *= 2L;
        }
        return Math.min(delay, MAX_MS);
    }

    int missCount() {
        return misses;
    }

    /** False while the directory is known missing and the next retry is not due. */
    boolean due(long now) {
        return missingSince < 0 || now >= retryAt;
    }

    /** After a failed write: true when the cause is the missing directory (handled here). */
    boolean failed(String path, long now) {
        File parent = new File(path).getParentFile();
        if (parent == null || parent.isDirectory()) {
            return false;
        }
        if (missingSince < 0) {
            missingSince = now;
            misses = 0;
            LsdExtender.trace(" ev: PPS directory " + parent + " missing; quiet retries "
                + FIRST_MS / 1000L + " s doubling to " + MAX_MS / 1000L + " s wall_ms=" + now);
        } else {
            misses++;
        }
        retryAt = now + delayMs(misses);
        return true;
    }

    /** After a successful write: one line if the directory had been missing. */
    void written(String path, long seq, long now) {
        if (missingSince < 0) {
            return;
        }
        LsdExtender.trace(" ev: PPS directory " + new File(path).getParent() + " present, first write ok seq="
            + seq + " after " + misses + " quiet retries, absent since wall_ms=" + missingSince + " wall_ms=" + now);
        missingSince = -1L;
        misses = 0;
    }
}
