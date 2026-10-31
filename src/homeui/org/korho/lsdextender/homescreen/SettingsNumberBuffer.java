/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.homescreen;

/** Keyboard edit state, independent of widgets and backend mutations. */
final class SettingsNumberBuffer {
    private static final int MAX_LENGTH = 11;
    private String text;
    private int cursor, start, end;
    private boolean negative;

    SettingsNumberBuffer(int initial) {
        text = String.valueOf(initial); cursor = text.length(); start = 0; end = cursor; negative = initial < 0;
    }
    String getText() { return text; }
    int getCursor() { return cursor; }
    int getStart() { return start; }
    int getEnd() { return end; }

    void position(int position, int selectionStart, int selectionEnd) {
        cursor = bounded(position);
        start = bounded(selectionStart); end = bounded(selectionEnd);
        if (start > end) { int old = start; start = end; end = old; }
    }

    private int bounded(int position) { return Math.max(0, Math.min(text.length(), position)); }

    void insert(String value) {
        for (int i = 0; i < value.length(); i++) {
            if ((value.charAt(i) < '0' || value.charAt(i) > '9') && value.charAt(i) != '-') { return; }
        }
        int left = start < end ? start : cursor;
        int right = start < end ? end : cursor;
        // Replacing or clearing the selected magnitude must keep the sign chosen on the value row.
        boolean sign = negative && left == 0 && right == text.length()
            && value.length() > 0 && value.charAt(0) != '-';
        String next = (sign ? "-" : "") + text.substring(0, left) + value + text.substring(right);
        if (next.length() > MAX_LENGTH || next.indexOf('-', 1) >= 0) { return; }
        text = next; cursor = left + value.length() + (sign ? 1 : 0); start = cursor; end = cursor;
        if (text.length() > 0) { negative = text.charAt(0) == '-'; }
    }

    void delete(boolean all) {
        if (all) { text = ""; cursor = 0; start = 0; end = 0; return; }
        if (start == end && cursor > 0) { start = cursor - 1; end = cursor; }
        if (start < end) { insert(""); }
    }

    int value(int minimum, int maximum, boolean dpi) {
        boolean signed = text.startsWith("-");
        long magnitude = Long.parseLong(signed ? text.substring(1) : text);
        long result = signed ? -magnitude : magnitude;
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE || result < minimum || result > maximum
                || dpi && result > 0 && result < 60) {
            throw new NumberFormatException("outside setting range");
        }
        return (int)result;
    }
}
