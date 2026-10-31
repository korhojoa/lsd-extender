/* Copyright (C) 2026 korhojoa */
/* SPDX-License-Identifier: GPL-3.0-only */
package org.korho.lsdextender.wirelessaa;

import java.io.IOException;
import java.util.StringTokenizer;
import java.util.Vector;

/** Bounded, versioned preferences. Pairing never implicitly grants permission. */
final class WirelessPolicy {
    private static final String HEX_DIGITS = "0123456789ABCDEF";
    private static final int ENABLED_ROW = 1;
    static final int MAX_PHONES = 64;
    boolean enabled;
    final Vector phones = new Vector();

    static final class Phone {
        final String address;
        String name;
        boolean allowed;
        Phone(String address, String name) { this.address = address; this.name = name; }
    }

    static String address(String input) {
        if (input == null) { return null; }
        String s = input.trim().toUpperCase();
        StringBuffer b = new StringBuffer();
        if (s.length() != 12 && s.length() != 17) { return null; }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.length() == 17 && i % 3 == 2) {
                if (c != ':' && c != '-') { return null; }
            } else {
                if (HEX_DIGITS.indexOf(c) < 0) { return null; }
                if (b.length() > 0 && b.length() % 3 == 2) { b.append(':'); }
                b.append(c);
            }
        }
        return b.toString();
    }

    Phone find(String addr) {
        for (int i = 0; i < phones.size(); i++) {
            Phone p = (Phone)phones.elementAt(i);
            if (p.address.equals(addr)) { return p; }
        }
        return null;
    }

    Phone remember(String addr, String name) {
        addr = address(addr);
        if (addr == null) { return null; }
        Phone p = find(addr);
        if (p == null) {
            if (phones.size() == MAX_PHONES) { return null; }
            p = new Phone(addr, addr);
            phones.addElement(p);
        }
        if (name != null && name.trim().length() > 0) {
            StringBuffer clean = new StringBuffer();
            for (int i = 0; i < name.length() && clean.length() < 96; i++) {
                char c = name.charAt(i);
                clean.append(c < 32 || c == 127 ? ' ' : c);
            }
            p.name = clean.toString();
        }
        return p;
    }

    boolean permits(String addr) {
        Phone p = find(addr);
        return enabled && p != null && p.allowed;
    }

    WirelessPolicy copy() {
        WirelessPolicy c = new WirelessPolicy();
        c.enabled = enabled;
        for (int i = 0; i < phones.size(); i++) {
            Phone p = (Phone)phones.elementAt(i);
            c.remember(p.address, p.name).allowed = p.allowed;
        }
        return c;
    }

    String encode() {
        StringBuffer out = new StringBuffer("wireless-aa=1\nenabled=");
        out.append(enabled ? '1' : '0').append('\n');
        for (int i = 0; i < phones.size(); i++) {
            Phone p = (Phone)phones.elementAt(i);
            out.append("phone=").append(p.address).append(',').append(p.allowed ? '1' : '0')
                .append(',').append(hex(p.name)).append('\n');
        }
        return out.append("end=1\n").toString();
    }

    static WirelessPolicy decode(String text) throws IOException {
        if (text.length() > 65536 || !text.startsWith("wireless-aa=1\nenabled=")
                || !text.endsWith("\nend=1\n")) { throw new IOException("invalid settings file"); }
        StringTokenizer lines = new StringTokenizer(text, "\n", true);
        WirelessPolicy p = new WirelessPolicy();
        int row = 0;
        while (lines.hasMoreTokens()) {
            String line = readLine(lines);
            if (row == 0) {
                if (!line.equals("wireless-aa=1")) { throw new IOException("settings version"); }
            } else if (row == ENABLED_ROW) {
                if (!line.equals("enabled=0") && !line.equals("enabled=1")) { throw new IOException("enabled value"); }
                p.enabled = line.equals("enabled=1");
            } else if (line.equals("end=1") && !lines.hasMoreTokens()) {
                return p;
            } else {
                p.decodePhone(line);
            }
            row++;
        }
        throw new IOException("incomplete settings file");
    }

    private static String readLine(StringTokenizer lines) throws IOException {
        String line = lines.nextToken();
        if ("\n".equals(line) || !lines.hasMoreTokens() || !"\n".equals(lines.nextToken())) {
            throw new IOException("invalid settings line");
        }
        return line;
    }

    private void decodePhone(String line) throws IOException {
        if (!line.startsWith("phone=") || line.length() < 26 || line.charAt(23) != ',') {
            throw new IOException("phone record");
        }
        String a = line.substring(6, 23);
        if (!a.equals(address(a)) || line.charAt(25) != ',' || (line.charAt(24) != '0' && line.charAt(24) != '1')
                || find(a) != null || phones.size() == MAX_PHONES) { throw new IOException("phone record"); }
        Phone phone = remember(a, unhex(line.substring(26)));
        phone.allowed = line.charAt(24) == '1';
    }

    private static String hex(String s) {
        try {
            byte[] bytes = s.getBytes("UTF-8");
            StringBuffer b = new StringBuffer();
            for (int i = 0; i < bytes.length; i++) {
                int c = bytes[i] & 255;
                b.append(HEX_DIGITS.charAt(c >> 4)).append(HEX_DIGITS.charAt(c & 15));
            }
            return b.toString();
        } catch (IOException e) { throw new RuntimeException(e.toString()); }
    }

    private static String unhex(String s) throws IOException {
        if (s.length() == 0 || s.length() > 768 || s.length() % 2 != 0) { throw new IOException("phone name"); }
        byte[] bytes = new byte[s.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = HEX_DIGITS.indexOf(s.charAt(i * 2));
            int lo = HEX_DIGITS.indexOf(s.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) { throw new IOException("phone name encoding"); }
            bytes[i] = (byte)(hi * 16 + lo);
        }
        String result = new String(bytes, "UTF-8");
        if (!hex(result).equals(s)) { throw new IOException("phone name UTF-8"); }
        return result;
    }
}
