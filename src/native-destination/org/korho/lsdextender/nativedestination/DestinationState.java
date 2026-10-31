/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.nativedestination;

/** Session-bound destination policy; acceptance is distinct from observed guidance. */
public final class DestinationState {
    public static final String WAITING = "WAITING_FRESH_REPLY";
    public static final String CONFIRM = "NEEDS_CONFIRMATION";
    public static final String BLOCKED = "BLOCKED_NATIVE_STATE";
    public static final String ACCEPTED = "ACCEPTED_AWAITING_DRIVER";
    public static final String UNAVAILABLE = "DESTINATION_UNAVAILABLE";
    public static final String EXPIRED = "TIMED_OUT_NATIVE_WORK_NOT_CANCELLED";
    public static final String RETIRED = "RETIRED_NATIVE_WORK_NOT_CANCELLED";

    private static final int ACCEPTED_RESULT = 1;
    private final String producerEpoch;
    private final long sessionGeneration;
    private final long destinationGeneration;
    private final String challenge;
    private final long replyBaseline;
    private final String addressHex;
    private boolean fresh;
    private boolean terminal;
    private boolean nativeClear;
    private boolean accepted;
    private boolean attempted;
    private boolean matchingNativeDestination;
    private long lastReply;
    private String status = WAITING;

    /** Tokens and the pre-challenge reply baseline are supplied by an external owner. */
    public DestinationState(int schema, String epoch, long session, long destination,
            long offerSerial, boolean available, String address, String token, long baseline) {
        if (schema != 1 || !token(epoch) || !token(token) || session <= 0
                || destination <= 0 || offerSerial <= 0 || baseline < 0
                || (available ? !address(address) : address != null)) {
            throw new IllegalArgumentException("invalid destination snapshot");
        }
        producerEpoch = epoch;
        sessionGeneration = session;
        destinationGeneration = destination;
        challenge = token;
        replyBaseline = baseline;
        lastReply = baseline;
        addressHex = address;
        if (!available) { status = UNAVAILABLE; terminal = true; }
    }

    /** Returns false for stale, mismatched or malformed replies without changing policy. */
    public synchronized boolean reply(int schema, String epoch, long session, long destination,
            String token, long serial, boolean available, String address) {
        if (terminal || schema != 1 || !producerEpoch.equals(epoch)
                || session != sessionGeneration || destination != destinationGeneration
                || !challenge.equals(token) || serial <= replyBaseline || serial <= lastReply
                || (available ? !address(address) : address != null)) { return false; }
        if (available && !addressHex.equals(address)) { return false; }
        lastReply = serial;
        if (!available) {
            terminal = true;
            status = UNAVAILABLE;
            matchingNativeDestination = false;
            return true;
        }
        fresh = true;
        if (!accepted) { status = nativeClear ? CONFIRM : BLOCKED; }
        return true;
    }

    /** observationKnown covers every required active/calculation observation. */
    public synchronized void nativeSnapshot(boolean observationKnown, boolean available, boolean actualRgActive,
            boolean calculating, String copiedFinalAddressHex) {
        if (terminal) { return; }
        nativeClear = observationKnown && available && !actualRgActive && !calculating;
        matchingNativeDestination = fresh && observationKnown && available && actualRgActive
                && addressHex.equals(copiedFinalAddressHex);
        if (fresh && !accepted) { status = nativeClear ? CONFIRM : BLOCKED; }
    }

    /** Records an externally issued public call result; this model never issues that call. */
    public synchronized void externalStartResult(int result) {
        if (!terminal && fresh) {
            if (result == ACCEPTED_RESULT) { accepted = true; status = ACCEPTED; }
            else { terminal = true; status = "PUBLIC_START_REJECTED"; }
        }
    }

    public synchronized void timeout() {
        if (!terminal) { terminal = true; status = EXPIRED; matchingNativeDestination = false; }
    }

    public synchronized void retire() {
        terminal = true;
        status = RETIRED;
        matchingNativeDestination = false;
    }

    public synchronized String state() { return status; }
    public synchronized boolean matchingNativeDestinationObserved() { return matchingNativeDestination; }

    /** Consume before any external side effect, including a call that can throw. */
    synchronized boolean takeAttempt() {
        if (terminal || attempted || !fresh || !nativeClear) { return false; }
        attempted = true;
        return true;
    }

    synchronized void unknown() { attempted = true; terminal = true; status = "UNKNOWN_NO_RETRY"; }
    synchronized boolean observing() { return fresh && !terminal; }

    String addressText() {
        try { return new String(bytes(addressHex), "UTF-8"); }
        catch (java.io.UnsupportedEncodingException failed) { throw new IllegalStateException("UTF-8 unavailable"); }
    }

    static boolean token(String value) { return value != null && value.length() == 32 && hex(value); }

    private static boolean hex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) { return false; }
        }
        return true;
    }

    /** Decode/re-encode checks lossless UTF-8, including rejection of replacement on invalid input. */
    static boolean address(String value) {
        if (value == null || value.length() < 2 || value.length() > 1024
                || value.length() % 2 != 0 || !hex(value)) { return false; }
        byte[] bytes = bytes(value);
        try {
            String decoded = new String(bytes, "UTF-8");
            boolean content = false;
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (Character.isISOControl(c)) { return false; }
                if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) { content = true; }
            }
            if (!content) { return false; }
            byte[] encoded = decoded.getBytes("UTF-8");
            if (encoded.length != bytes.length) { return false; }
            for (int i = 0; i < bytes.length; i++) { if (bytes[i] != encoded[i]) { return false; } }
            return true;
        } catch (java.io.UnsupportedEncodingException unavailable) { return false; }
    }

    private static byte[] bytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
