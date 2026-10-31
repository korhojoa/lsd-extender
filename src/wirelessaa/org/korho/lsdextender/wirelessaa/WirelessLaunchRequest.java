/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import org.korho.lsdextender.Slog;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Hashtable;

/** One queued intent. Access queue state only under the settings service lock. */
final class WirelessLaunchRequest {
    private String address;
    private long epoch;
    private long generation;
    private long started;
    private long observed;
    private Runnable continuation;
    private boolean sent;
    private boolean queued;
    private boolean tcpReady;
    private boolean cancelled;
    private static final long SETUP_MS = 30000L;
    private static final long LEASE_MS = 10000L;
    private static final long OBSERVATION_MS = 1000L;
    private static final long MAX_COUNTER = 4294967295L;

    static boolean eligible(boolean ready, boolean stopped, String selected, Hashtable active, WirelessPolicy policy) {
        return ready && !stopped && selected != null && active.containsKey(selected) && policy.permits(selected);
    }

    void queue(String peer, long generation) {
        queue(peer, generation, null, 0L);
    }

    void queue(String peer, long policyEpoch, Runnable action, long now) {
        if (action != null && continuation != null && peer.equals(address) && epoch == policyEpoch) { return; }
        cancel();
        cancelled = false;
        address = peer;
        epoch = policyEpoch;
        continuation = action;
        started = now;
        sent = false;
    }

    void cancel() {
        cancelled |= continuation != null && sent;
        generation++;
        address = null;
        continuation = null;
        queued = false;
        tcpReady = false;
    }

    boolean takeCancellation() {
        boolean value = cancelled;
        cancelled = false;
        return value;
    }

    boolean pending() { return address != null; }

    String take(String selected, long generation, boolean permitted) {
        String peer = address;
        if (peer == null) { return null; }
        if (!peer.equals(selected) || epoch != generation || !permitted) { cancel(); return null; }
        if (sent) { return null; }
        sent = true;
        if (continuation == null) { address = null; }
        return "prepare " + peer + " " + epoch + "\n";
    }

    void observe(String selected, long policyEpoch, boolean permitted, long revision,
                 String session, String usb, long now) {
        if (continuation == null) { return; }
        if (!matches(selected, policyEpoch, permitted, now)) { cancel(); return; }
        tcpReady = ready(session, usb, address, epoch, revision, started, now);
        observed = now;
    }

    long queueToken() {
        if (continuation == null || !sent || !tcpReady || queued) { return 0L; }
        queued = true;
        return generation;
    }

    Runnable takeContinuation(long token, String selected, long policyEpoch, boolean permitted, long now) {
        if (token != generation || !queued || !tcpReady || now < observed || now - observed > OBSERVATION_MS
                || !matches(selected, policyEpoch, permitted, now)) { return null; }
        Runnable action = continuation;
        continuation = null;
        address = null;
        tcpReady = false;
        generation++;
        return action;
    }

    private boolean matches(String selected, long policyEpoch, boolean permitted, long now) {
        return permitted && address != null && address.equals(selected) && epoch == policyEpoch
            && now >= started && now - started < SETUP_MS;
    }

    static boolean ready(String session, String usb, String peer, long epoch, long revision, long started, long now) {
        try {
            return peer != null && epoch > 0L && revision > 0L
                && sessionReady(session, peer, epoch, now) && streamReady(usb, revision, started, now);
        } catch (RuntimeException invalid) { return false; }
    }

    private static boolean fresh(String text, long now) {
        long updated = number(text, "updated_ms");
        return now >= updated && now - updated <= LEASE_MS;
    }

    private static boolean sessionReady(String text, String peer, long epoch, long now) {
        return text != null && text.startsWith("version=1\n") && text.endsWith("end=1\n")
            && fresh(text, now) && number(text, "epoch") == epoch
            && peer.equals(field(text, "peer")) && number(text, "session") > 0L;
    }

    private static boolean streamReady(String text, long revision, long started, long now) {
        if (text == null || !text.startsWith("revision=") || !text.endsWith("end=1\n")
                || !fresh(text, now) || number(text, "updated_ms") < started
                || number(text, "revision") != revision || number(text, "stream") != 1L) { return false; }
        String[] counters = { "held", "accepted", "nosession", "badpeer", "lost" };
        for (int i = 0; i < counters.length; i++) {
            if (number(text, counters[i]) > MAX_COUNTER) { return false; }
        }
        return true;
    }

    private static String field(String text, String key) {
        String prefix = key + "=";
        int from = text.startsWith(prefix) ? 0 : text.indexOf("\n" + prefix) + 1;
        if (from < 0 || !text.startsWith(prefix, from)) { throw new IllegalArgumentException("missing field"); }
        int end = text.indexOf('\n', from);
        if (end < 0 || text.indexOf("\n" + prefix, from) >= 0) { throw new IllegalArgumentException("invalid field"); }
        return text.substring(from + prefix.length(), end);
    }

    private static long number(String text, String key) {
        String value = field(text, key);
        if (value.length() == 0) { throw new IllegalArgumentException("empty field"); }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') { throw new IllegalArgumentException("invalid number"); }
        }
        return Long.parseLong(value);
    }

    /** Runs on the RAM worker after publishing policy, never on the ASL thread. */
    static void send(String request) throws Exception {
        if (request == null) { return; }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", 5289), 250);
            socket.setTcpNoDelay(true);
            socket.getOutputStream().write(request.getBytes("US-ASCII"));
            Slog.write(" wireless: early " + request.trim());
        } finally { socket.close(); }
    }
}
