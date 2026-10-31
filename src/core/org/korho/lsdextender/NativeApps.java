/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.util.Vector;

/** Native sessions are RAM-only and owned by their feed connection. */
public final class NativeApps {
    public interface Host {
        void connected(NativeAppSession session);
        void disconnected(NativeAppSession session);
    }

    private static Host host;
    private static final Vector sessions = new Vector();

    private NativeApps() {
    }

    public static synchronized void setHost(Host value) {
        host = value;
    }

    public static synchronized NativeAppSession find(String name) {
        for (int i = 0; i < sessions.size(); i++) {
            NativeAppSession session = (NativeAppSession)sessions.elementAt(i);
            if (session.name.equals(name)) { return session; }
        }
        return null;
    }

    static NativeAppSession register(String spec, NativeAppSession.Output output) {
        NativeAppSession session = parseRegistration(spec, output);
        Host listener;
        synchronized (NativeApps.class) {
            listener = host;
            if (listener == null) { throw new IllegalStateException("HMI native app host not ready"); }
            if (sessions.size() >= 8 || find(session.name) != null) { throw new IllegalStateException("app already registered or full"); }
            for (int i = 0; i < sessions.size(); i++) {
                if (((NativeAppSession)sessions.elementAt(i)).displayable == session.displayable) {
                    throw new IllegalStateException("displayable already registered");
                }
            }
            sessions.addElement(session);
        }
        try {
            listener.connected(session);
        } catch (Throwable failed) {
            closed(session);
            throw new IllegalStateException("HMI native app registration failed: " + failed);
        }
        return session;
    }

    private static NativeAppSession parseRegistration(String spec, NativeAppSession.Output output) {
        java.util.StringTokenizer tokens = new java.util.StringTokenizer(spec);
        if (tokens.countTokens() != 5 || !"1".equals(tokens.nextToken())) {
            throw new IllegalArgumentException("app register 1 <name> <displayable> <width> <height>");
        }
        String name = tokens.nextToken();
        validateName(name);
        int displayable = Integer.parseInt(tokens.nextToken());
        int width = Integer.parseInt(tokens.nextToken());
        int height = Integer.parseInt(tokens.nextToken());
        // KD caption property 69 carries only two bytes; 200 becomes 20.
        // Use IDs above the named stock range, with a native collision check.
        if (displayable < 64 || displayable > 99 || width < 1 || width > 8192 || height < 1 || height > 8192) {
            throw new IllegalArgumentException("app geometry or displayable outside native range");
        }
        return new NativeAppSession(name, displayable, width, height, output);
    }

    private static void validateName(String name) {
        if (name.length() == 0 || name.length() > 48) { throw new IllegalArgumentException("app name"); }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' && c != '_') {
                throw new IllegalArgumentException("app name");
            }
        }
    }

    static void closed(NativeAppSession session) {
        if (session == null) { return; }
        session.disconnect();
        Host listener;
        synchronized (NativeApps.class) {
            if (!sessions.removeElement(session)) { return; }
            listener = host;
        }
        if (listener != null) { listener.disconnected(session); }
    }

    public static void contextVisible(int[] displayables) {
        NativeAppSession[] snapshot;
        synchronized (NativeApps.class) {
            snapshot = new NativeAppSession[sessions.size()];
            sessions.copyInto(snapshot);
        }
        for (int i = 0; i < snapshot.length; i++) {
            boolean visible = false;
            for (int j = 0; j < displayables.length; j++) {
                if (displayables[j] == snapshot[i].displayable) { visible = true; }
            }
            snapshot[i].displayVisible(visible);
        }
    }
}
