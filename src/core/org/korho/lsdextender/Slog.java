/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;

public final class Slog {
    static final int MAX = 200;
    static final String PREFIX = "lsd-extender";
    /** Message characters per chunk: room for PREFIX and a mark of up to " (999/999)+ ". */
    private static final int PIECE = MAX - PREFIX.length() - 12;
    private static final int[] HEADER = new int[]{13, 1058691, 1};

    static {
        String arch = System.getProperty("os.arch");
        if ("arm".equals(arch) || "sh4".equals(arch)) {
            for (int i = 0; i < HEADER.length; i++) {
                HEADER[i] = swap(HEADER[i]);
            }
        }
    }

    private Slog() {
    }

    private static int swap(int v) {
        return (v & 0xFF) << 24 | (v >> 8 & 0xFF) << 16 | (v >> 16 & 0xFF) << 8 | (v >> 24 & 0xFF);
    }

    /**
     * Long messages are split into numbered chunks rather than cut off, each at
     * most MAX characters. Every chunk starts with PREFIX, so a watcher on
     * "lsd-extender" sees all of them: the first is PREFIX + text + " (1/n)", the
     * next ones are PREFIX + " (k/n)+ " + text (the continuation mark). The
     * feed gets the whole line once.
     */
    public static void write(String msg) {
        String s = PREFIX + msg;
        try {
            Publisher.publish(s);
        } catch (Throwable ignored) {
            // the feed is best effort
        }
        // Dynamic subscriptions supply state updates to feed clients. Deliver the
        // complete line above without multiplying sample writes to /dev/slog.
        // Extra diagnostic slog output is opt-in; status and errors still log.
        if (msg.startsWith(" dsispy: = ")
                && !Config.getBool("lsdextender.dsispy.slog", false)) {
            return;
        }
        String[] lines = chunks(msg);
        for (int i = 0; i < lines.length; i++) {
            writeOne(lines[i]);
        }
    }

    /** The slog lines for one message (package access for the host test). */
    static String[] chunks(String msg) {
        String s = PREFIX + msg;
        if (s.length() <= MAX) {
            return new String[]{s};
        }
        int parts = (msg.length() + PIECE - 1) / PIECE;
        String[] out = new String[parts];
        for (int i = 0; i < parts; i++) {
            int from = i * PIECE;
            String text = msg.substring(from, Math.min(msg.length(), from + PIECE));
            String mark = " (" + (i + 1) + "/" + parts + ")";
            out[i] = i == 0 ? PREFIX + text + mark : PREFIX + mark + "+ " + text;
        }
        return out;
    }

    private static void writeOne(String s) {
        FileOutputStream out = null;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 + MAX);
            DataOutputStream d = new DataOutputStream(buf);
            d.writeInt(HEADER[0]);
            d.writeInt(HEADER[1]);
            d.writeInt(HEADER[2]);
            d.writeBytes(s);
            d.write(0);
            // never create the file: opening a missing path for append would silently
            // make a regular file (that is what happens in the off-car container)
            if (!new java.io.File("/dev/slog").exists()) {
                throw new java.io.IOException("no /dev/slog");
            }
            out = new FileOutputStream("/dev/slog", true);
            out.write(buf.toByteArray());
            out.flush();
        } catch (Throwable t) {
            // no /dev/slog (off-car harness) or write failure: say so on stderr instead
            try {
                System.err.println("[lsd-extender slog-fallback] " + s.substring("lsd-extender".length()));
            } catch (Throwable ignored) {
                // nothing left to try
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // nothing to do
                }
            }
        }
    }
}
