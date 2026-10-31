/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.IOException;
import java.io.InputStream;

/** Bounded pfctl execution. Both pipes drain concurrently to prevent deadlock. */
final class PfCommand {
    private static final long TIMEOUT_MS = 5000L;
    private static final int OUTPUT_LIMIT = 65536;

    private PfCommand() { }

    static String run(String[] argv) {
        Process child = null;
        try {
            child = Runtime.getRuntime().exec(argv);
            Capture out = new Capture(child.getInputStream());
            Capture err = new Capture(child.getErrorStream());
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!exited(child)) {
                if (System.currentTimeMillis() >= deadline) { throw new IOException("pfctl timeout"); }
                Thread.sleep(25L);
            }
            out.thread.join(TIMEOUT_MS);
            err.thread.join(TIMEOUT_MS);
            if (!out.done || !err.done || out.failed || err.failed) { throw new IOException("pfctl output incomplete"); }
            if (child.exitValue() != 0) {
                Slog.write(" pf: " + argv[1] + " exit " + child.exitValue() + ": " + err.text.toString().trim());
                return null;
            }
            return out.text.toString().trim();
        } catch (Exception e) {
            Slog.write(" pf: command failed: " + e);
            return null;
        } finally {
            if (child != null) {
                child.destroy();
                close(child.getInputStream());
                close(child.getErrorStream());
                try { child.getOutputStream().close(); } catch (IOException ignored) { }
            }
        }
    }

    private static boolean exited(Process process) {
        try { process.exitValue(); return true; }
        catch (IllegalThreadStateException running) { return false; }
    }

    private static void close(InputStream in) {
        try { in.close(); } catch (IOException ignored) { }
    }

    private static final class Capture implements Runnable {
        final InputStream input;
        final StringBuffer text = new StringBuffer();
        final Thread thread;
        volatile boolean done;
        volatile boolean failed;

        Capture(InputStream input) {
            this.input = input;
            thread = new Thread(this, "lsd-pfctl-output");
            thread.setDaemon(true);
            thread.start();
        }

        public void run() {
            try {
                byte[] bytes = new byte[1024];
                int n;
                while ((n = input.read(bytes)) >= 0) {
                    if (text.length() + n <= OUTPUT_LIMIT) { text.append(new String(bytes, 0, n)); }
                    else { failed = true; }
                }
            } catch (IOException e) { failed = true; }
            finally { close(input); done = true; }
        }
    }
}
