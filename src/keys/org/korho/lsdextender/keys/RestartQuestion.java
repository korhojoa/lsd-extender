/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.keys;

/** Pure authority for a question. This class cannot call a native restart. */
final class RestartQuestion {
    private static final int VALID_INPUT = 1;
    private static final int PRESS = 1;
    interface Clock { long now(); }
    interface Output { void open(long generation); void close(String reason); void log(String decision); }
    private final Clock clock;
    private final Output output;
    private long last = -1L, started = -1L, eligible = -1L, pressed, delivered, generation;
    private boolean live, held, offered, visible, touchConfirmed, stopped;
    private int stage;

    RestartQuestion(Clock time, Output sink) {
        if (time == null || sink == null) { throw new IllegalArgumentException("question dependencies required"); }
        clock = time; output = sink;
    }
    void begin() {
        long now = now();
        synchronized (this) {
            if (now < 0 || now > Long.MAX_VALUE - 60000L) { stopped = true; }
            else { started = now; eligible = now + 60000L; }
        }
        output.log("first-minute-input-ignored");
    }
    synchronized void warmup(int duration) {
        if (started >= 0) { eligible = started + (duration >= 0 && duration <= 60000 ? duration : 60000); }
    }
    void service(boolean available) {
        cancel("service-generation-changed");
        synchronized (this) { live = available && !stopped; }
    }
    boolean key(int keyboard, int key, int value, int validity) {
        long now = now();
        String decision;
        boolean stop = false;
        synchronized (this) {
            if (stopped || !live) { return false; }
            decision = rejection(keyboard, key, validity, now);
            if (decision != null) { stop = true; }
            else if (now < eligible) { return false; }
            else if (value == 0) {
                held = false; stage = 0;
                if (!offered) { decision = "release-seen"; }
            } else if (offered) { return false; }
            else if (value == PRESS) {
                held = true; stage = PRESS; pressed = now; delivered = now; decision = "hold-candidate-press";
            } else if (nextStage(value) && now - delivered >= 500L && now - delivered <= 2000L) {
                stage = value; delivered = now;
            } else { stop = true; decision = "out-of-order-key"; }
        }
        if (stop) { cancel(decision); }
        else if (decision != null) { output.log(decision); }
        tick();
        return false;
    }
    private boolean nextStage(int value) {
        return held && ((stage == PRESS && value == 3) || (stage == 3 && value == 4) || (stage == 4 && value == 5));
    }
    private String rejection(int keyboard, int key, int validity, long now) {
        if (now < 0 || eligible < 0) { return "clock-invalid"; }
        if (validity != VALID_INPUT) { return "invalid-key"; }
        return keyboard != 4 || key != 39 ? "other-key" : null;
    }
    /** A native timer may finish the hold only after ultralong was observed. */
    void tick() {
        long now = now(), ticket = -1L;
        boolean bad;
        synchronized (this) {
            if (stopped || !live) { return; }
            bad = now < 0 || (held && now < pressed);
            if (!bad && held && stage == 5 && !offered && now - pressed >= 3000L) {
                if (generation == Long.MAX_VALUE) { bad = true; }
                else { offered = true; visible = true; ticket = ++generation; }
            }
        }
        if (bad) { cancel("clock-invalid"); }
        else if (ticket >= 0) { output.open(ticket); output.log("question-requested-no-restart"); }
    }
    synchronized boolean current(long ticket) { return !stopped && live && visible && generation == ticket; }
    /** The view calls this only after its own fresh in-button touch sequence. */
    boolean consume(long ticket) {
        long now = now();
        synchronized (this) {
            if (now < 0 || !current(ticket)) { return false; }
            visible = false; touchConfirmed = true; return true;
        }
    }
    synchronized boolean confirmed(long ticket) { return !stopped && live && touchConfirmed && generation == ticket; }
    synchronized boolean dispatch(long ticket) {
        if (!confirmed(ticket)) { return false; }
        touchConfirmed = false; return true;
    }
    long remaining() {
        long now = now();
        synchronized (this) {
            return live && !stopped && held && stage == 5 && !offered && now >= pressed
                ? Math.max(0L, 3000L - (now - pressed)) : -1L;
        }
    }
    void cancel(String reason) {
        synchronized (this) {
            held = false; offered = false; visible = false; touchConfirmed = false; stage = 0;
            if (generation == Long.MAX_VALUE) { stopped = true; live = false; }
            else { generation++; }
        }
        output.close(reason); output.log(reason);
    }
    void shutdown() { synchronized (this) { stopped = true; live = false; } cancel("feature-retired"); }
    private long now() {
        try {
            long value = clock.now();
            synchronized (this) { if (value < 0 || value < last) { return -1L; } last = value; }
            return value;
        } catch (Throwable failure) { return -1L; }
    }
}
