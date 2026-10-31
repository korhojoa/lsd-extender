/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.Slog;
import java.io.File;

/**
 * Makes the settings card writable for the length of one save.
 *
 * The card mounts read-only, so the write fails with the file name as its whole
 * message. Only a failed write asks for this, and the read-only state is put
 * back immediately after, so the card spends a fraction of a second writable
 * per settings change instead of a whole session.
 *
 * The shell program is constant and the mount point is an argv element, so a
 * path can never become shell syntax. LD_PRELOAD is dropped for the child: the
 * HMI JVM runs with one, and the preloaded libraries are not meant for
 * /bin/sh child processes.
 */
final class CardMount {
    /** A mount call that does not finish in this time is abandoned. */
    private static final long TIMEOUT_MS = 5000L;

    private CardMount() { }

    /** True when the mount point now has the requested state. */
    static boolean remount(String mountpoint, boolean writable) {
        if (mountpoint == null) { return false; }
        String shell = new File("/bin/sh").exists() ? "/bin/sh" : "/armle/bin/sh";
        String[] argv = {
            shell, "-c",
            "unset LD_PRELOAD; exec mount -u" + (writable ? 'w' : 'r') + " \"$1\" >/dev/null 2>&1",
            "wireless-card", mountpoint,
        };
        try {
            Process child = Runtime.getRuntime().exec(argv);
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (true) {
                try {
                    boolean ok = child.exitValue() == 0;
                    Slog.write(" wireless: card " + mountpoint + (writable ? " read-write" : " read-only")
                        + (ok ? "" : " FAILED"));
                    return ok;
                } catch (IllegalThreadStateException running) {
                    if (System.currentTimeMillis() >= deadline) {
                        child.destroy();
                        Slog.write(" wireless: card " + mountpoint + " remount did not finish");
                        return false;
                    }
                    try { Thread.sleep(50L); } catch (InterruptedException interrupted) { return false; }
                }
            }
        } catch (Throwable t) {
            Slog.write(" wireless: card " + mountpoint + " remount failed: " + t);
            return false;
        }
    }
}
