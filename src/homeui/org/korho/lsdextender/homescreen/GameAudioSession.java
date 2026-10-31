/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

import java.io.IOException;
import org.korho.lsdextender.NativeMediaBridge;
import org.korho.lsdextender.NativeMediaSession;
import org.korho.lsdextender.NativeMediaState;

/** One owned launch waits for media readiness and releases only its own lease. */
final class GameAudioSession {
    interface Connection { void release(); }
    interface Backend { Connection acquire() throws IOException, InterruptedException; }
    private final Backend backend;
    private Connection ownedConnection;
    private boolean acquiring, closed;

    GameAudioSession() {
        this(new Backend() {
            public Connection acquire() throws IOException, InterruptedException {
                NativeMediaSession.acquire();
                final NativeMediaState expected;
                synchronized (NativeMediaBridge.class) { expected = NativeMediaBridge.state; }
                if (expected == null || !expected.ready || expected.closed) {
                    throw new IOException("native media lease retired before game startup");
                }
                return connection(expected);
            }
        });
    }
    static Connection connection(final NativeMediaState expected) {
        return new Connection() {
            public void release() {
                // Publication and retirement in NativeMediaSession use
                // this same owned bridge lock. A late old game callback
                // can never close a subsequent game's lease.
                synchronized (NativeMediaBridge.class) {
                    if (NativeMediaBridge.state == expected) { NativeMediaBridge.release(); } // NOPMD CompareObjectsWithEquals - Exact retained lease.
                }
            }
        };
    }
    GameAudioSession(Backend backend) { this.backend = backend; }
    static void start() { NativeMediaSession.start(); }
    static void prepareReload() {
        if (NativeMediaBridge.changing()) { throw new IllegalStateException("game audio acquisition or restoration is active"); }
    }
    static void shutdown() { NativeMediaSession.shutdown(); }

    /** Launcher thread only; never wait on the native UI thread. */
    void acquire() throws IOException, InterruptedException {
        synchronized (this) {
            if (closed || acquiring || ownedConnection != null) { throw new IOException("game audio lease unavailable"); }
            acquiring = true;
        }
        Connection acquired = null;
        try {
            acquired = backend.acquire();
            if (acquired == null) { throw new IOException("native audio readiness missing"); }
            synchronized (this) {
                if (closed) { throw new IOException("game launch cancelled during audio acquisition"); }
                ownedConnection = acquired; acquired = null;
            }
        } finally {
            synchronized (this) { acquiring = false; }
            if (acquired != null) { acquired.release(); }
        }
    }
    void release() {
        Connection owned;
        synchronized (this) { closed = true; owned = ownedConnection; ownedConnection = null; }
        if (owned != null) { owned.release(); }
    }
}
