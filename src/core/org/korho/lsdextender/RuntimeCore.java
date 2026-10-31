/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender;

import java.io.File;
import java.io.FileInputStream;
import java.util.Hashtable;

/** Current card label, accepted only for the source identity loaded in this JVM. */
public final class RuntimeCore {
    private static final int MAX_BYTES = 8192;
    private static final int STAMP_LENGTH = 20;
    private static final String UNKNOWN = "lsd-extender-core UNKNOWN metadata=";
    private static final String MALFORMED = UNKNOWN + "malformed";
    private static final String[] KEYS = {"feature", "version", "built", "git", "banner", "core_id"};
    private static volatile String current = UNKNOWN + "unread";

    private RuntimeCore() { }

    /** No I/O or configuration access, including during SPI/class initialization. */
    public static String banner() { return current; }

    /** Restore the admitted reload checkpoint without reading staged disk bytes. */
    static void restore(String previous) { current = previous; }

    /** Called only by admitted preparation/reload workers, never the startup SPI. */
    public static void refresh() {
        String next;
        try {
            String directory = Config.directory();
            next = directory == null ? UNKNOWN + "missing_directory" : read(new File(directory), Build.CORE_ID);
        } catch (Throwable failed) { next = UNKNOWN + "unavailable"; }
        current = next;
    }

    static String read(File directory, String identity) {
        FileInputStream input = null;
        try {
            File file = new File(directory, "core-build.properties");
            if (!file.isFile()) { return UNKNOWN + "missing"; }
            input = new FileInputStream(file);
            byte[] bytes = new byte[MAX_BYTES + 1];
            int used = 0;
            while (used < bytes.length) {
                int count = input.read(bytes, used, bytes.length - used);
                if (count < 0) { break; }
                if (count == 0) { return UNKNOWN + "unavailable"; }
                used += count;
            }
            if (used > MAX_BYTES) { return UNKNOWN + "oversized"; }
            for (int i = 0; i < used; i++) {
                int value = bytes[i] & 255;
                if (value != '\n' && value != '\r' && (value < 32 || value > 126)) {
                    return MALFORMED;
                }
            }
            return parse(new String(bytes, 0, used, "US-ASCII"), identity);
        } catch (Throwable failed) { return UNKNOWN + "unavailable"; }
        finally {
            if (input != null) {
                try { input.close(); } catch (Throwable ignored) { /* Read-only descriptor. */ }
            }
        }
    }

    private static String parse(String text, String identity) {
        Hashtable values = new Hashtable();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) { end = text.length(); }
            String line = text.substring(start, end).trim();
            start = end + 1;
            if (line.length() == 0 || line.charAt(0) == '#') { continue; }
            int equals = line.indexOf('=');
            if (equals <= 0) { return MALFORMED; }
            String key = line.substring(0, equals);
            if (!known(key) || values.containsKey(key)) { return MALFORMED; }
            values.put(key, line.substring(equals + 1));
        }
        if (values.size() != KEYS.length || !"core".equals(values.get("feature"))) {
            return MALFORMED;
        }
        if (!hex(identity, 64) || !identity.equals(values.get("core_id"))) {
            return UNKNOWN + "identity_mismatch";
        }
        String version = (String) values.get("version");
        String built = (String) values.get("built");
        String git = (String) values.get("git");
        String banner = (String) values.get("banner");
        if (!version(version) || !built(built) || !revision(git)) { return MALFORMED; }
        String expected = "lsd-extender-core " + version + " built=" + built + " git=" + git;
        if (!banner.equals(expected) && !unitSuffix(banner, expected)) { return MALFORMED; }
        return banner;
    }

    private static boolean known(String key) {
        for (int i = 0; i < KEYS.length; i++) { if (KEYS[i].equals(key)) { return true; } }
        return false;
    }

    private static boolean version(String value) {
        int plus = value.indexOf('+');
        if (plus < 0 || plus != value.lastIndexOf('+') || !number(value.substring(plus + 1), true)) { return false; }
        String semantic = value.substring(0, plus);
        int start = 0;
        for (int part = 0; part < 3; part++) {
            int end = semantic.indexOf('.', start);
            if (part == 2 ? end >= 0 : end < 0) { return false; }
            if (end < 0) { end = semantic.length(); }
            if (!number(semantic.substring(start, end), false)) { return false; }
            start = end + 1;
        }
        return true;
    }

    private static boolean number(String value, boolean leadingZeros) {
        if (value.length() == 0 || !leadingZeros && value.length() > 1 && value.charAt(0) == '0') { return false; }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') { return false; }
        }
        return true;
    }

    private static boolean built(String value) {
        if (value.length() != STAMP_LENGTH) { return false; }
        String shape = "0000-00-00T00:00:00Z";
        for (int i = 0; i < shape.length(); i++) {
            char expected = shape.charAt(i), actual = value.charAt(i);
            if (expected == '0' ? actual < '0' || actual > '9' : expected != actual) { return false; }
        }
        return true;
    }

    private static boolean revision(String value) {
        String raw = value.endsWith("+dirty") ? value.substring(0, value.length() - 6) : value;
        return raw.length() >= 7 && raw.length() <= 64 && hex(raw, raw.length());
    }

    private static boolean hex(String value, int length) {
        if (value.length() != length) { return false; }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) { return false; }
        }
        return true;
    }

    private static boolean unitSuffix(String banner, String expected) {
        String prefix = expected + " unit=";
        if (!banner.startsWith(prefix) || banner.length() == prefix.length()) { return false; }
        for (int i = prefix.length(); i < banner.length(); i++) {
            char c = banner.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-') { return false; }
        }
        return true;
    }
}
