/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Hashtable;

/**
 * A QNX PPS object that carries state to a native reader. Each update has
 * {@code seq::<n>} (+1 per write) and
 * {@code updated_ms::<System.currentTimeMillis()>} first, then the caller's
 * attributes. Published with ONE write on a descriptor opened without
 * truncation (a truncating open deletes every attribute of a PPS object
 * first; no temp file, no rename: the unit's /dev/shmem has none), at most
 * every 500 ms (updates in between are coalesced into the next write), never
 * deleted. Never throws.
 *
 * The caller's lines are {@code key=value} (the state-file format the readers
 * had) and are written as {@code key::value}; a {@code -key} line deletes the
 * attribute, which a caller writes for every value it omits (PPS keeps an
 * attribute until it is deleted, where a rewritten file simply lacked it).
 */
public final class PpsObject {
    private static final long MIN_INTERVAL_MS = 500;
    /** A {@code -key} line deletes the attribute; it needs at least one key character. */
    private static final char DELETE_MARK = '-';
    private static final int MIN_DELETE_LINE = 2;

    private final String label;
    private String path;
    private String body;
    private boolean dirty;
    private long seq;
    private long lastWrite;
    private Thread writer;

    public PpsObject(String label, String path) {
        this.label = label;
        this.path = path;
    }

    public synchronized void setPath(String p) {
        path = p;
    }

    /** New content (without seq/updated_ms). Written by the writer thread. */
    public synchronized void publish(String keyValueLines) {
        body = keyValueLines;
        dirty = true;
        if (writer == null) {
            writer = new Thread(new Runnable() {
                public void run() {
                    loop();
                }
            }, "lsd-extender-" + label);
            writer.setDaemon(true);
            writer.start();
        }
        notifyAll();
    }

    private void loop() {
        while (true) {
            String content;
            String p;
            long n;
            synchronized (this) {
                while (!dirty) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                long since = System.currentTimeMillis() - lastWrite;
                if (since < MIN_INTERVAL_MS) {
                    try {
                        wait(MIN_INTERVAL_MS - since);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                dirty = false;
                seq++;
                n = seq;
                lastWrite = System.currentTimeMillis();
                content = render("seq=" + seq + "\nupdated_ms=" + lastWrite + "\n" + body);
                p = path;
            }
            write(p, content, n);
        }
    }

    private void write(String p, String content, long n) {
        try {
            writeObject(p, content);
            if (n == 1 || n % 20 == 0) {
                Slog.write(" " + label + ": wrote " + p + " " + content.replace('\n', ' ').trim());
            }
        } catch (Throwable t) {
            if (n == 1 || n % 20 == 0) {
                Slog.write(" " + label + ": write " + p + " failed: " + t);
            }
        }
    }

    /**
     * {@code key=value} lines to PPS attribute lines ({@code key::value});
     * {@code -key} lines pass through; anything else is dropped.
     */
    public static String render(String keyValueLines) {
        StringBuffer b = new StringBuffer(keyValueLines.length() + 16);
        int start = 0;
        while (start < keyValueLines.length()) {
            int nl = keyValueLines.indexOf('\n', start);
            if (nl < 0) {
                nl = keyValueLines.length();
            }
            String line = keyValueLines.substring(start, nl);
            start = nl + 1;
            if (line.length() == 0) {
                continue;
            }
            if (line.charAt(0) == DELETE_MARK) {
                if (line.length() >= MIN_DELETE_LINE) {
                    b.append(line).append('\n');
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || line.substring(0, eq).indexOf(':') >= 0) {
                continue;
            }
            b.append(line.substring(0, eq)).append("::").append(line.substring(eq + 1)).append('\n');
        }
        return b.toString();
    }

    /**
     * One write of the attribute lines to the object at {@code path}, the
     * directory created first, the descriptor opened without truncation.
     */
    public static void writeObject(String path, String attributeLines) throws IOException {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("PPS directory unavailable: " + parent);
        }
        RandomAccessFile out = new RandomAccessFile(path, "rw");
        try {
            out.write(attributeLines.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    /**
     * The attributes of an object's text ({@code name:encoding:value} lines;
     * the {@code @name} line, {@code -name} deletions and anything else are
     * skipped), as name to value.
     */
    public static Hashtable parse(String text) {
        Hashtable kv = new Hashtable();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                nl = text.length();
            }
            String line = text.substring(start, nl);
            start = nl + 1;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            int first = line.indexOf(':');
            if (first <= 0 || line.charAt(0) == '@' || line.charAt(0) == '-') {
                continue;
            }
            int second = line.indexOf(':', first + 1);
            if (second < 0) {
                continue;
            }
            kv.put(line.substring(0, first), line.substring(second + 1));
        }
        return kv;
    }

    /** The whole object text (at most {@code max} bytes), "" when unreadable. */
    public static String read(String path, int max) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] buf = new byte[max];
            int n = 0;
            int r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
            }
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }
}
