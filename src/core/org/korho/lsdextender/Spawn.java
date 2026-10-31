/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Vector;

/**
 * Runs commands from the HMI JVM. Configuration "spawn <command>" lines run once
 * during startup. Each helper controls its own background work.
 * The command channel also accepts "spawn <command>" to start a command
 * without restarting the HMI.
 *
 * Contract with the launched command: it must return
 * quickly and background its daemons itself, with their stdio redirected to a file, since
 * the JVM holds only the pipes of the direct child and reads them until it exits. A
 * command still running after lsdextender.spawn.timeout_s (30) is left alone: its
 * output threads keep draining, the slog line says "still running".
 *
 * argv is the command split on whitespace (no quoting). A first word without a slash
 * is looked up in /bin and /armle/bin, because J9's exec does not search PATH here.
 * Never throws; every outcome is one slog line " spawn: <cmd> rc=N in X ms [out]"
 */
public final class Spawn {
    private static final String LOG_PREFIX = " spawn: ";
    private static final String[] BIN_DIRS = new String[]{"/bin", "/armle/bin", "/usr/bin"};
    private static final int MAX_OUT = 300;
    private static final char SHELL_QUOTE = '\'';
    private static final char LINE_FEED = '\n';
    private static final int MAX_GAME_RECORD_CHARS = 80;
    private static boolean started;

    private Spawn() {
    }

    /** Runs every conf "spawn" line once, in order, on one daemon thread. */
    public static synchronized void startAll() {
        if (started) {
            return;
        }
        started = true;
        final String[] lines = Config.spawnLines();
        if (lines.length == 0) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < lines.length; i++) {
                    Slog.write(Spawn.run(lines[i]));
                }
            }
        }, "lsd-extender-spawn");
        t.setDaemon(true);
        t.start();
        Slog.write(LOG_PREFIX + lines.length + " command(s) from the conf, running on their own thread");
    }

    /** Runs one command, waits up to the timeout, returns the one-line report. */
    public static String run(String command) {
        return run(command, null);
    }

    /**
     * As run(String), and when the child outlives the timeout a daemon thread keeps
     * watching it and calls onExit once it ends (a game started from the launcher tile:
     * the audio source is given back then). onExit is not called for a child that ended
     * within the timeout; the report says so and the caller acts on it directly.
     */
    public static String run(String command, final Runnable onExit) {
        return run(command, null, onExit);
    }

    /** Add NAME=value entries to the inherited child environment before exec. */
    public static String run(String command, String[] environment, final Runnable onExit) {
        return run(LOG_PREFIX, command, split(command), environment, onExit, false, false);
    }

    /** Execute literal argv, including spaces and shell punctuation in file names. */
    public static String runArguments(String[] arguments, String[] environment, final Runnable onExit) {
        return runArguments(arguments, environment, onExit, false);
    }

    /** Game-only stdout and stderr diagnostics; never wait for a log-rate slot. */
    public static String runGameArguments(String[] arguments, String[] environment, final Runnable onExit) {
        return runArguments(arguments, environment, onExit, true);
    }

    private static String runArguments(String[] arguments, String[] environment, final Runnable onExit,
                                       boolean gameOutput) {
        String[] argv = new String[arguments.length];
        System.arraycopy(arguments, 0, argv, 0, argv.length);
        StringBuffer description = new StringBuffer();
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) { description.append(' '); }
            description.append('[').append(argv[i]).append(']');
        }
        return run(LOG_PREFIX, description.toString(), argv, environment, onExit, true, gameOutput);
    }

    private static String run(final String prefix, String command, String[] argv, String[] environment,
                              final Runnable onExit, boolean literal, boolean gameOutput) {
        if (argv.length == 0) {
            return prefix + "empty command";
        }
        argv[0] = resolve(argv[0]);
        // Children inherit the HMI process preload libraries.
        // Clear that variable so each child loads its own runtime and graphics library.
        // The shell clears it and execs the child with the other variables kept.
        boolean dropPreload = !Config.getBool("lsdextender.spawn.keep_preload", false);
        argv = childArguments(argv, environment, dropPreload, literal);
        final long t0 = System.currentTimeMillis();
        final StringBuffer out = new StringBuffer();
        final Process p;
        try {
            p = Runtime.getRuntime().exec(argv);
        } catch (Throwable t) {
            return prefix + command + " failed to start: " + t;
        }
        GameOutput lines = gameOutput ? new GameOutput() : null;
        Thread r1 = drain(p.getInputStream(), out, lines, 0);
        Thread r2 = drain(p.getErrorStream(), out, lines, 1);
        long timeout = (long) Config.getInt("lsdextender.spawn.timeout_s", 30) * 1000L;
        while (true) {
            try {
                int rc = p.exitValue();
                // the child is gone but its last output may still be in the pipes
                try {
                    r1.join(500L);
                    r2.join(500L);
                } catch (InterruptedException ignored) {
                    // report what we have
                }
                return prefix + command + " rc=" + rc + " in " + (System.currentTimeMillis() - t0) + " ms"
                    + (dropPreload ? " (LD_PRELOAD dropped)" : "") + tail(out);
            } catch (IllegalThreadStateException running) {
                if (System.currentTimeMillis() - t0 >= timeout) {
                    if (onExit != null) {
                        final String cmd = command;
                        Thread w = new Thread(new Runnable() {
                            public void run() {
                                int rc;
                                try {
                                    rc = p.waitFor();
                                } catch (Throwable t) {
                                    rc = -1;
                                }
                                Slog.write(prefix + cmd + " ended rc=" + rc + " after "
                                    + (System.currentTimeMillis() - t0) / 1000L + " s" + tail(out));
                                try {
                                    onExit.run();
                                } catch (Throwable t) {
                                    Slog.write(prefix + "exit handler failed: " + t);
                                }
                            }
                        }, "lsd-extender-spawn-watch");
                        w.setDaemon(true);
                        w.start();
                    }
                    return prefix + command + " still running after " + timeout / 1000L + " s, left alone"
                        + (onExit != null ? " (watched)" : "") + tail(out);
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    return prefix + command + " wait interrupted" + tail(out);
                }
            }
        }
    }

    private static String[] childArguments(String[] argv, String[] environment, boolean dropPreload, boolean literal) {
        boolean exports = environment != null && environment.length > 0;
        if (!exports && !dropPreload) { return argv; }
        StringBuffer joined = new StringBuffer();
        if (exports) {
            joined.append("export");
            for (int i = 0; i < environment.length; i++) {
                joined.append(" '");
                for (int j = 0; j < environment[i].length(); j++) {
                    char c = environment[i].charAt(j);
                    if (c == SHELL_QUOTE) { joined.append("'\\''"); }
                    else { joined.append(c); }
                }
                joined.append(SHELL_QUOTE);
            }
            joined.append(" || exit; ");
        }
        if (dropPreload) { joined.append("unset LD_PRELOAD; "); }
        if (literal) {
            joined.append("exec \"$@\"");
            String[] result = new String[argv.length + 4];
            result[0] = resolve("sh"); result[1] = "-c";
            result[2] = joined.toString(); result[3] = "lsd-extender";
            System.arraycopy(argv, 0, result, 4, argv.length);
            return result;
        }
        joined.append("exec");
        for (int i = 0; i < argv.length; i++) {
            joined.append(' ').append(argv[i]);
        }
        return new String[]{resolve("sh"), "-c", joined.toString()};
    }

    private static String resolve(String word) {
        if (word.indexOf('/') >= 0) {
            return word;
        }
        for (int i = 0; i < BIN_DIRS.length; i++) {
            if (new File(BIN_DIRS[i] + "/" + word).exists()) {
                return BIN_DIRS[i] + "/" + word;
            }
        }
        return word;
    }

    private static String[] split(String s) {
        Vector v = new Vector();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                v.addElement(s.substring(start, i));
            }
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    /**
     * Reads a child stream to the end on its own thread; keeps the LAST MAX_OUT chars,
     * so the exit line of a long-running child (a game that crashed after minutes)
     * carries its final stderr, the part that says why.
     */
    private static Thread drain(final InputStream in, final StringBuffer out, final GameOutput lines, final int stream) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                if (lines != null) { drainGame(in, out, lines, stream); return; }
                byte[] buf = new byte[256];
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        synchronized (out) {
                            out.append(new String(buf, 0, r));
                            if (out.length() > MAX_OUT) {
                                out.delete(0, out.length() - MAX_OUT);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // child gone
                }
            }
        }, "lsd-extender-spawn-out");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Bounded records, not a queue: two last records and two stream identities. */
    static final class GameOutput {
        private static final long RATE_WINDOW_MS = 1000L;
        private static final long NO_DROPS = 0L;
        private static final int SINGLE_PIECE = 1;
        interface Sink { long now(); void write(String text); }
        private final Sink sink;
        private final String[] last = new String[2];
        private final int[] lastLine = { -1, -1 };
        private final int[] lastPieces = new int[2];
        private final boolean[] first = new boolean[2];
        private long start, droppedRecords, droppedLines;
        private int emitted, open = 2;
        GameOutput() {
            this(new Sink() {
                public long now() {
                    long mono = EventClock.millis();
                    return mono >= 0L ? mono : System.currentTimeMillis();
                }
                public void write(String text) { Slog.write(text + " " + EventClock.stamp()); }
            });
            Thread timer = new Thread(new Runnable() {
                public void run() { flushLoop(); }
            }, "lsd-game-output-rate");
            timer.setDaemon(true);
            try { timer.start(); }
            catch (RuntimeException failure) { Slog.write(" game output periodic flush unavailable: " + failure); }
        }
        GameOutput(Sink sink) { this.sink = sink; start = sink.now(); }
        synchronized void line(int stream, int line, int part, String text) {
            tick();
            String entry = " game output stream=" + (stream == 0 ? "stdout" : "stderr")
                + " line=" + line + " part=" + part + " text=" + text;
            // The first record from each stream always survives, even after the cap.
            if (!first[stream] || emitted < 20) {
                first[stream] = true; emitted++; sink.write(entry);
            } else {
                droppedRecords++;
                if (lastLine[stream] != line) { droppedLines++; lastLine[stream] = line; lastPieces[stream] = 0; }
                lastPieces[stream]++;
                last[stream] = entry;
            }
        }
        synchronized void tick() {
            long now = sink.now();
            if (now - start < RATE_WINDOW_MS) { return; }
            flush(); start = now; emitted = 0;
            first[0] = false; first[1] = false;
        }
        private void flush() {
            if (droppedRecords != NO_DROPS) {
                // Keep each stream's last record, so later stdout cannot hide stderr.
                for (int i = 0; i < last.length; i++) {
                    if (last[i] != null) {
                        sink.write(last[i] + " burst_last=1"); droppedRecords--;
                        if (lastPieces[i] == SINGLE_PIECE) { droppedLines--; }
                    }
                    last[i] = null; lastLine[i] = -1; lastPieces[i] = 0;
                }
                sink.write(" game output dropped_lines=" + droppedLines + " dropped_records=" + droppedRecords);
                droppedLines = 0L; droppedRecords = 0L;
            }
        }
        synchronized void ended() {
            open--;
            if (open == 0) { flush(); notifyAll(); }
        }
        private synchronized void flushLoop() {
            while (open > 0) {
                try { wait(1000L); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
                if (open > 0) { tick(); }
            }
        }
    }

    private static void drainGame(InputStream input, StringBuffer out, GameOutput lines, int stream) {
        StringBuffer piece = new StringBuffer();
        int line = 1, part = 1;
        try {
            InputStreamReader reader = new InputStreamReader(input, "UTF-8");
            char[] chars = new char[256];
            int count;
            while ((count = reader.read(chars)) > 0) {
                synchronized (out) {
                    out.append(chars, 0, count);
                    if (out.length() > MAX_OUT) { out.delete(0, out.length() - MAX_OUT); }
                }
                for (int i = 0; i < count; i++) {
                    char ch = chars[i];
                    if (ch == LINE_FEED) {
                        if (piece.length() != 0 || part == 1) { lines.line(stream, line, part, piece.toString()); }
                        piece.setLength(0); line++; part = 1;
                    } else {
                        // Slog.writeBytes is eight bit; escape other characters without losing them.
                        String text = ch >= 32 && ch <= 126 ? String.valueOf(ch)
                            : "\\u" + ("0000" + Integer.toHexString(ch)).substring(Integer.toHexString(ch).length());
                        if (piece.length() + text.length() > MAX_GAME_RECORD_CHARS) {
                            lines.line(stream, line, part++, piece.toString()); piece.setLength(0);
                        }
                        piece.append(text);
                    }
                }
            }
            if (piece.length() != 0) { lines.line(stream, line, part, piece.toString()); }
        } catch (java.io.IOException failure) {
            lines.line(stream, line, part, "reader failed: " + failure);
        } finally { lines.ended(); }
    }

    private static String tail(StringBuffer out) {
        String s;
        synchronized (out) {
            s = out.toString().trim();
        }
        if (s.length() == 0) {
            return "";
        }
        if (s.length() > MAX_OUT) {
            s = s.substring(s.length() - MAX_OUT);
        }
        return " out=" + s.replace('\n', '|').replace('\r', ' ');
    }
}
