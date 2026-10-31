/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

/** Used only by a non-ASL retirement worker; never waits on the UI queue itself. */
final class RestartPromptWait {
    private RestartPromptWait() { }
    static void released(RestartPrompt prompt, long limit) {
        long end = System.currentTimeMillis() + limit;
        int tries = 0;
        while (!prompt.released()) {
            if (System.currentTimeMillis() >= end || tries++ >= limit / 10L) {
                throw new IllegalStateException("owned question release unresolved; handle retained");
            }
            try { Thread.sleep(10L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("owned question release interrupted; handle retained");
            }
        }
    }
}
